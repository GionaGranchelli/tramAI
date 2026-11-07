package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal data class AgentCliExecution(
    val exitCode: Int,
    val output: String,
    val stderr: String,
    val truncated: Boolean,
    val actualSizeBytes: Long,
    val durationMillis: Long,
)

internal class AgentCliTimeoutException(
    val timeoutSeconds: Long,
) : RuntimeException("timed out after ${timeoutSeconds}s")

/** Internal marker: the CLI process could not be started (before execution began). */
internal class AgentCliStartException(
    cause: Throwable,
) : RuntimeException("agent CLI process could not be started", cause)

internal data class AgentCliRequest(
    val workflowName: String,
    val stepName: String,
    val startedEvent: dev.tramai.core.observation.event.RuntimeEventDefinition,
    val completedEvent: dev.tramai.core.observation.event.RuntimeEventDefinition,
    val agentType: String,
    val processBuilder: ProcessBuilder,
    val timeoutSeconds: Long,
    val maxOutputBytes: Long,
    val promptLength: Int,
    val context: WorkflowContext,
    val observer: WorkflowObserver,
    internal val processStarter: (ProcessBuilder) -> Process = { it.start() },
)

internal suspend fun executeAgentCli(
    request: AgentCliRequest,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AgentCliExecution {
    currentCoroutineContext().ensureActive()
    val workflowName = request.workflowName
    val stepName = request.stepName
    val startedEvent = request.startedEvent
    val completedEvent = request.completedEvent
    val agentType = request.agentType
    val timeoutSeconds = request.timeoutSeconds
    val maxOutputBytes = request.maxOutputBytes
    val promptLength = request.promptLength
    val context = request.context
    val observer = request.observer

    // E5/E6: Lineage validation before admission
    val processAdmission = currentProcessAdmission()
    val evidenceStore = currentEffectEvidenceStore()
    val stepAttempt = currentStepAttempt()
    if (evidenceStore != null) {
        requireEvidenceLineageIfEnabled(
            workflowName,
            context.workflowId,
            stepName,
            processAdmission != null,
            stepAttempt,
        )
    }

    // A fresh durable grant for THIS dispatch, consumed once, immediately before acquisition
    val permit = processAdmission?.admitProcess(stepName, PROCESS_EFFECT_KIND)
    val evidenceKey =
        recordAdmittedEvidence(
            permit = permit,
            evidenceStore = evidenceStore,
            stepAttempt = stepAttempt,
            request = request,
        )

    permit?.tryConsume()
    val process = startAgentProcess(request, ioDispatcher, evidenceKey, evidenceStore)
    val lifecycle = CancellableProcessLifecycle(process)
    // Attach-then-active-check: parent cancellation requests process-tree termination
    // immediately, closing the pipes so the blocking readers below cannot delay
    // cancellation completion. The handle is disposed in the finally so a completed
    // step does not leave a handler attached to a long-lived workflow job.
    val registration = lifecycle.attachTo(currentCoroutineContext().job)

    val startedAtNanos = System.nanoTime()
    var primaryFailure: Throwable? = null
    var executedResult: AgentCliExecution? = null
    var cleanupResult: ProcessCleanupResult? = null
    try {
        process.outputStream.close()
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            context = context,
            event =
                RuntimeEvent.of(startedEvent) {
                    set(RuntimeAttributes.STEP_NAME, stepName)
                    set(RuntimeAttributes.AGENT_TYPE, agentType)
                    set(RuntimeAttributes.PROMPT_LENGTH, promptLength.toLong())
                },
        )
        executedResult =
            withTimeoutOrNull(timeoutSeconds.seconds) {
                val timeoutRegistration = lifecycle.attachTo(currentCoroutineContext().job)
                try {
                    updateEvidenceState(evidenceKey, evidenceStore, WorkflowEffectEvidenceState.DISPATCHED)

                    coroutineScope {
                        val stdoutDeferred =
                            async(ioDispatcher) {
                                process.inputStream.captureAgentOutput(maxOutputBytes, lifecycle)
                            }
                        val stderrDeferred =
                            async(ioDispatcher) {
                                process.errorStream.captureAgentOutput(maxOutputBytes, lifecycle)
                            }

                        lifecycle.awaitExit()
                        currentCoroutineContext().ensureActive()

                        val stdout = stdoutDeferred.await()
                        val stderr = stderrDeferred.await()
                        val renderedOutput = stdout.asTextWithFooter(maxOutputBytes)
                        val renderedStderr = stderr.asTextWithFooter(maxOutputBytes)
                        val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

                        observer.emitWorkflowEvent(
                            workflowName = workflowName,
                            context = context,
                            event =
                                RuntimeEvent.of(completedEvent) {
                                    set(RuntimeAttributes.STEP_NAME, stepName)
                                    set(RuntimeAttributes.AGENT_TYPE, agentType)
                                    set(RuntimeAttributes.PROMPT_LENGTH, promptLength.toLong())
                                    set(RuntimeAttributes.RESPONSE_LENGTH, renderedOutput.length.toLong())
                                    set(RuntimeAttributes.DURATION_MS, durationMillis)
                                    set(RuntimeAttributes.EXIT_CODE, process.exitValue().toLong())
                                },
                        )

                        AgentCliExecution(
                            exitCode = process.exitValue(),
                            output = renderedOutput,
                            stderr = renderedStderr,
                            truncated = stdout.truncated,
                            actualSizeBytes = stdout.actualSizeBytes,
                            durationMillis = durationMillis,
                        )
                    }
                } finally {
                    timeoutRegistration.dispose()
                }
            }
        if (executedResult == null) {
            currentCoroutineContext().ensureActive()
            lifecycle.requestTermination()
            throw AgentCliTimeoutException(timeoutSeconds)
        }
    } catch (error: CancellationException) {
        primaryFailure = error
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        primaryFailure = error
    } finally {
        registration.dispose()
        // NonCancellable + IO nesting lives inside terminateAndAwait; calling it
        // directly here keeps the primary failure (and its diagnostics) intact.
        cleanupResult = lifecycle.terminateAndAwait()
    }

    handleExecutionCleanupAndFailure(
        primaryFailure = primaryFailure,
        cleanup = cleanupResult,
        evidenceKey = evidenceKey,
        evidenceStore = evidenceStore,
    )

    val finalResult = executedResult!!
    updateEvidenceState(evidenceKey, evidenceStore, WorkflowEffectEvidenceState.COMPLETED)
    return finalResult
}

private suspend fun recordAdmittedEvidence(
    permit: WorkflowProcessPermit?,
    evidenceStore: WorkflowEffectEvidenceRecordStore?,
    stepAttempt: PersistedStepAttempt?,
    request: AgentCliRequest,
): WorkflowEffectEvidenceKey? {
    if (permit == null || evidenceStore == null || stepAttempt == null) return null
    val key =
        WorkflowEffectEvidenceKey(
            workflowName = request.workflowName,
            workflowId = request.context.workflowId,
            checkpointGeneration = permit.checkpointGeneration,
            admissionSeq = permit.admissionSequence,
        )
    evidenceStore.recordEvidence(
        WorkflowEffectEvidenceRecord(
            key = key,
            stepName = request.stepName,
            stepAttemptId = stepAttempt.attemptId,
            effectKind = PROCESS_EFFECT_KIND,
            state = WorkflowEffectEvidenceState.ADMITTED,
            createdAtEpochMillis = currentEffectEvidenceClockMillis() ?: stepAttempt.startedAtEpochMillis,
        ),
    )
    return key
}

private suspend fun startAgentProcess(
    request: AgentCliRequest,
    ioDispatcher: CoroutineDispatcher,
    evidenceKey: WorkflowEffectEvidenceKey?,
    evidenceStore: WorkflowEffectEvidenceRecordStore?,
): Process =
    try {
        startOwnedProcess(ioDispatcher) {
            request.processStarter(
                request.processBuilder.redirectErrorStream(false),
            )
        }
    } catch (cancelled: CancellationException) {
        recordUncertainFailure(evidenceKey, evidenceStore, cancelled)
        throw cancelled
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        recordUncertainFailure(evidenceKey, evidenceStore, error)
        throw AgentCliStartException(error)
    }

private suspend fun updateEvidenceState(
    evidenceKey: WorkflowEffectEvidenceKey?,
    evidenceStore: WorkflowEffectEvidenceRecordStore?,
    targetState: WorkflowEffectEvidenceState,
) {
    if (evidenceKey == null || evidenceStore == null) return
    val record = evidenceStore.getEvidence(evidenceKey) ?: return
    val completedAt =
        if (targetState == WorkflowEffectEvidenceState.COMPLETED) {
            currentEffectEvidenceClockMillis() ?: record.createdAtEpochMillis
        } else {
            record.completedAtEpochMillis
        }
    evidenceStore.updateEvidence(
        record.copy(
            state = targetState,
            completedAtEpochMillis = completedAt,
        ),
    )
}

private suspend fun handleExecutionCleanupAndFailure(
    primaryFailure: Throwable?,
    cleanup: ProcessCleanupResult,
    evidenceKey: WorkflowEffectEvidenceKey?,
    evidenceStore: WorkflowEffectEvidenceRecordStore?,
) {
    var cleanupFailure: Throwable? = null
    try {
        surfaceProcessCleanup(primaryFailure, cleanup)
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        if (primaryFailure == null) {
            recordUncertainFailure(evidenceKey, evidenceStore, error)
        }
        cleanupFailure = error
    }

    val terminalFailure = primaryFailure ?: cleanupFailure
    if (terminalFailure != null) {
        if (terminalFailure === primaryFailure) {
            recordUncertainFailure(evidenceKey, evidenceStore, primaryFailure)
        }
        throw terminalFailure
    }
}

private data class AgentStreamCapture(
    val bytes: ByteArray,
    val actualSizeBytes: Long,
    val truncated: Boolean,
) {
    fun asTextWithFooter(
        maxOutputBytes: Long,
        charset: Charset = Charsets.UTF_8,
    ): String {
        val text = bytes.toString(charset)
        if (!truncated) {
            return text
        }
        val footer =
            buildString {
                appendLine()
                append("[truncated output: captured ")
                append(maxOutputBytes)
                append(" bytes of ")
                append(actualSizeBytes)
                append(" total bytes]")
            }
        return text + footer
    }
}

private fun InputStream.captureAgentOutput(
    maxOutputBytes: Long,
    lifecycle: CancellableProcessLifecycle,
): AgentStreamCapture =
    use { stream ->
        val maxCapturedBytes = maxOutputBytes.toInt()
        val capturedBytes =
            ByteArrayOutputStream(
                min(maxCapturedBytes.coerceAtLeast(AGENT_CLI_MINIMUM_BUFFER_SIZE), AGENT_CLI_STREAM_CHUNK_SIZE),
            )
        val buffer = ByteArray(AGENT_CLI_STREAM_CHUNK_SIZE)
        var actualSizeBytes = 0L
        var truncated = false
        while (true) {
            val bytesRead = lifecycle.readStreamChunk(stream, buffer) ?: break
            if (bytesRead < 0) {
                break
            }
            actualSizeBytes += bytesRead
            val remaining = maxCapturedBytes - capturedBytes.size()
            if (remaining > 0) {
                val bytesToWrite = min(bytesRead, remaining)
                capturedBytes.write(buffer, 0, bytesToWrite)
                if (bytesToWrite < bytesRead) {
                    truncated = true
                }
            } else {
                truncated = true
            }
        }
        AgentStreamCapture(
            bytes = capturedBytes.toByteArray(),
            actualSizeBytes = actualSizeBytes,
            truncated = truncated,
        )
    }

internal fun AgentCliExecution.describeNonZeroExit(): String {
    val stderrSummary = stderr.trim()
    if (stderrSummary.isEmpty()) {
        return "failed with exit code $exitCode"
    }
    return buildString {
        append("failed with exit code ")
        append(exitCode)
        append("; stderr: ")
        if (stderrSummary.length <= AGENT_CLI_FAILURE_MESSAGE_MAX_CHARS) {
            append(stderrSummary)
        } else {
            append(stderrSummary.take(AGENT_CLI_FAILURE_MESSAGE_MAX_CHARS))
            append("... [truncated stderr]")
        }
    }
}

internal fun Throwable.rethrowAgentCancellation() {
    rethrowIfCancellation()
}

private const val AGENT_CLI_STREAM_CHUNK_SIZE = 8_192
private const val AGENT_CLI_MINIMUM_BUFFER_SIZE = 16
private const val AGENT_CLI_FAILURE_MESSAGE_MAX_CHARS = 4_096
