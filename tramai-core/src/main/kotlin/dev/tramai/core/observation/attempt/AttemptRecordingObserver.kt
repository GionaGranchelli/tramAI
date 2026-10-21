package dev.tramai.core.observation.attempt

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.NoOpOperationObservation
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.observation.secondary.SecondaryEffectAuthority
import dev.tramai.core.observation.secondary.SecondaryFailureDiagnostic
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Decorating observer that persists exactly one content-free attempt record per provider attempt
 * (Epic 0.7.5b).
 *
 * [delegate] keeps its previous behaviour — pass the OpenTelemetry observer here and nothing about
 * existing observation changes. Persistence and delegation are isolated from each other and from the
 * governed operation: no failure in this observer can change an execution outcome.
 *
 * An attempt whose context carries no `invocationId` is not recorded. Two invocations must never
 * share an attempt identity, so an unidentified attempt is reported on the secondary-diagnostic
 * channel instead of being stored under a fabricated identity.
 */
@OptIn(ExperimentalTramaiInternalApi::class)
class AttemptRecordingObserver(
    private val store: AttemptObservationStore,
    /**
     * Identity of the invocation this attempt belongs to, supplied by the engine that created it.
     * Identity travels by construction because `OperationCallContext` is a stable API: extending it
     * would break `copy` and `componentN` for compiled consumers.
     */
    private val identity: AttemptIdentity = AttemptIdentity(),
    /**
     * The already-wrapped observation supplied by the composition wiring, as for every engine
     * coordinator: forwarded callbacks cross the failure-isolating boundary before they reach the
     * application's own observer. This class never wraps or invokes a raw observer itself.
     */
    private val delegate: OperationObserver = NoOpOperationObserver,
    private val clock: Clock = Clock.systemUTC(),
) : OperationObserver,
    AttemptIdentifyingObserver {
    /**
     * Same store, same delegate, this attempt's identity: the engine calls this once per attempt
     * and the returned observer records at most one row for it.
     */
    override fun forAttempt(identity: AttemptIdentity): OperationObserver {
        val bound = AttemptRecordingObserver(store, identity, delegate, clock)
        return bound
    }

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val startedAt = clock.instant()
        val delegateObservation = delegate.onCallStarted(context)
        val invocationId = identity.invocationId
        if (invocationId.isNullOrBlank()) {
            SecondaryFailureDiagnostic.report(
                extensionPoint = "attempt_observation",
                callback = "onCallStarted",
                errorType = "MissingInvocationIdentity",
                failurePolicy = "FAIL_OPEN",
                authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
            )
            return delegateObservation
        }
        return RecordingObservation(
            store = store,
            delegate = delegateObservation,
            context = context,
            identity = identity,
            startedAt = startedAt,
            clock = clock,
        )
    }

    @OptIn(ExperimentalTramaiInternalApi::class)
    private class RecordingObservation(
        private val store: AttemptObservationStore,
        private val delegate: OperationObservation,
        private val context: OperationCallContext,
        private val identity: AttemptIdentity,
        private val startedAt: java.time.Instant,
        private val clock: Clock,
    ) : OperationObservation {
        private var response: ModelResponse? = null
        private var failure: Throwable? = null
        private var cancelled = false
        private var written = false

        override fun onProviderResponse(response: ModelResponse) {
            this.response = response
            delegate.onProviderResponse(response)
        }

        override fun onProviderFailure(error: Throwable) {
            this.failure = error
            delegate.onProviderFailure(error)
        }

        override fun onStructuredParseFailure(
            rawResponse: String,
            errorSummary: String,
        ) {
            // Diagnostic material stays on the delegate's privileged channel; it is never recorded.
            delegate.onStructuredParseFailure(rawResponse, errorSummary)
        }

        override fun onEngineEvent(
            name: String,
            attributes: Map<String, Any?>,
        ) {
            delegate.onEngineEvent(name, attributes)
        }

        override fun onEngineEvent(event: RuntimeEvent) {
            delegate.onEngineEvent(event)
        }

        override fun onCallCompleted(parseSuccess: Boolean?) {
            delegate.onCallCompleted(parseSuccess)
            record()
        }

        override fun onCallCancelled() {
            cancelled = true
            delegate.onCallCancelled()
            record()
        }

        /**
         * Writes at most one record per attempt. The engine may emit several terminal callbacks
         * (failure followed by completion), and a repeated write must not become a second attempt.
         */
        private fun record() {
            if (written) return
            written = true
            val completedAt = clock.instant()
            val observation =
                try {
                    ModelAttemptObservation(
                        invocationId = identity.invocationId.orEmpty(),
                        attempt = context.attempt,
                        workflowRunId = identity.workflowRunId,
                        correlationId = identity.correlationId,
                        routeIndex = identity.routeIndex,
                        providerId = context.providerId,
                        requestedModel = context.requestedModel,
                        reportedModel = response?.modelUsed,
                        startedAt = startedAt,
                        completedAt = completedAt,
                        durationMillis =
                            java.time.Duration
                                .between(startedAt, completedAt)
                                .toMillis(),
                        outcome = outcomeOf(),
                        failureCategory = failure?.javaClass?.simpleName,
                        finishReason = response?.finishReason?.name,
                        // ModelResponse defaults finishReason to STOP; a default is not a provider report.
                        finishReasonReported = false,
                        inputTokens = response?.inputTokens,
                        outputTokens = response?.outputTokens,
                        thinkingTokens = response?.thinkingTokens,
                        provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
                    )
                } catch (error: IllegalArgumentException) {
                    contain("record", error)
                    return
                }
            runCatching { store.append(observation) }.onFailure { contain("persist", it) }
        }

        private fun outcomeOf(): AttemptOutcome =
            when {
                failure != null -> AttemptOutcome.FAILED
                cancelled -> AttemptOutcome.CANCELLED
                response != null -> AttemptOutcome.SUCCEEDED
                else -> AttemptOutcome.UNKNOWN
            }
    }
}

/**
 * Contains a secondary-effect failure; cancellation always escapes unchanged.
 */
@OptIn(ExperimentalTramaiInternalApi::class)
private fun contain(
    callback: String,
    error: Throwable,
) {
    if (error is CancellationException) throw error
    SecondaryFailureDiagnostic.report(
        extensionPoint = "attempt_observation",
        callback = callback,
        errorType = error.javaClass.simpleName,
        failurePolicy = "FAIL_OPEN",
        authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,
    )
}

private inline fun contain(
    callback: String,
    block: () -> Unit,
) = runCatching(block).onFailure { contain(callback, it) }
