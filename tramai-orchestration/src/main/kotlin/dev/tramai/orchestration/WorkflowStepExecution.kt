package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a workflow step may checkpoint the whole workflow run.
 *
 * [NONE] steps never suspend; [TOP_LEVEL_CHECKPOINT] steps may persist a
 * checkpoint and suspend the run at a top-level step boundary.
 */
internal enum class WorkflowStepSuspensionMode {
    NONE,
    TOP_LEVEL_CHECKPOINT,
}

/**
 * Contract-level result of executing one workflow step.
 *
 * A step returns [Completed] with the next state, or [Suspended] to
 * checkpoint and suspend the workflow run. Suspension is modelled as a value,
 * never as an exception thrown from inside a step; the workflow runner
 * converts [Suspended] into the outer [WorkflowSuspendedException] at the
 * existing boundary.
 */
internal sealed interface WorkflowStepExecutionResult<out S> {
    data class Completed<S>(
        val state: S,
    ) : WorkflowStepExecutionResult<S>

    data object Suspended : WorkflowStepExecutionResult<Nothing>
}

/**
 * Runtime-only collaborators handed to a step's execute implementation.
 *
 * This is the deliberate boundary that keeps steps from reaching into the
 * whole [Workflow] object: a step may use these services and nothing else.
 */
internal data class WorkflowStepExecutionServices(
    val clock: Clock,
    val httpTransport: HttpTransport,
    val outboundNetworkPolicy: OutboundNetworkPolicy,
    val externalStepExecutorResolver: ExternalStepExecutorResolver,
    val failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    /** E11 admission capability, when the run claims durable cancellation authority. */
    val stepAdmission: WorkflowStepAdmission? = null,
    /** Synchronous step attempt authority, when the run claims authoritative attempt lineage. */
    val stepAttemptAuthority: StepAttemptAuthority? = null,
)

/**
 * Everything a step needs to execute, without the step itself: the receiver
 * of [InternalWorkflowStep.execute] is the step.
 *
 * [executeNestedSteps] routes nested steps through the same common step
 * wrapper (step counting, observation, cancellation, sanitisation) instead of
 * letting a step call child [InternalWorkflowStep.execute] directly.
 */
internal data class WorkflowStepExecutionRequest<S>(
    val workflowName: String,
    val state: S,
    val context: WorkflowContext,
    val observer: WorkflowObserver,
    val stepCounter: StepCounter,
    val persistenceSession: WorkflowPersistenceSession<S>?,
    val topLevelStepIndex: Int?,
    val resumedCheckpointMetadata: Map<String, String>?,
    val services: WorkflowStepExecutionServices,
    val executeNestedSteps: suspend (
        steps: List<InternalWorkflowStep<S>>,
        state: S,
    ) -> S,
)

/**
 * Internal runtime execution contract for every built-in workflow step.
 *
 * The workflow runner sequences steps through this single boundary; it no
 * longer knows how any concrete step type executes. [suspensionMode] makes
 * the topology constraint part of the model: only [WorkflowStepSuspensionMode.TOP_LEVEL_CHECKPOINT]
 * steps may suspend, and only at top level.
 */
internal sealed interface InternalWorkflowStep<S> {
    val name: String

    val suspensionMode: WorkflowStepSuspensionMode
        get() = WorkflowStepSuspensionMode.NONE

    suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S>
}

// ─── Built-in step implementations (simple steps) ───────────────────────────

internal data class LocalWorkflowStep<S>(
    override val name: String,
    val transform: suspend (S, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    override suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S> =
        WorkflowStepExecutionResult.Completed(
            transform(request.state, request.context),
        )
}

internal data class AiWorkflowStep<S, I, O>(
    override val name: String,
    val replayDescriptor: (S, WorkflowContext) -> WorkflowStepReplayDescriptor,
    val input: (S, WorkflowContext) -> I,
    val invoke: suspend (I, WorkflowContext) -> O,
    val merge: (S, O, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        state: S,
        context: WorkflowContext,
    ): S = merge(state, invoke(input(state, context), context), context)

    override suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S> =
        WorkflowStepExecutionResult.Completed(
            execute(request.state, request.context),
        )
}

internal data class GateWorkflowStep<S>(
    override val name: String,
    val decide: suspend (S, WorkflowContext) -> GateDecision,
) : InternalWorkflowStep<S> {
    override suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S> {
        val decision = decide(request.state, request.context)
        if (!decision.allowed) {
            throw WorkflowGateRejectedException(
                "Workflow gate '$name' rejected execution: ${decision.reason}",
            )
        }
        return WorkflowStepExecutionResult.Completed(request.state)
    }
}

internal data class PluginWorkflowStep<S>(
    override val name: String,
    val type: String,
    val config: Map<String, Any?>,
    val merge: suspend (S, Map<String, Any?>, WorkflowContext) -> S,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        executorResolver: ExternalStepExecutorResolver,
    ): S {
        require(type.isNotBlank()) {
            "Workflow '$workflowName' plugin step '$name' type must not be blank"
        }
        val externalAdmission = currentExternalAdmission()
        val evidenceStore = currentEffectEvidenceStore()
        val stepAttempt = currentStepAttempt()
        if (evidenceStore != null) {
            requireEvidenceLineageIfEnabled(
                workflowName = workflowName,
                workflowId = context.workflowId,
                stepName = name,
                admissionAvailable = externalAdmission != null,
                stepAttempt = stepAttempt,
            )
        }

        // No resolver callback runs before admission. `ExternalStepExecutorResolver` is a public interface, so
        // `isRegistered` would be third-party code executing on a *denied* invocation, including after
        // cancellation, and it would race the built-in registry's mutable map between the check and the
        // factory. Admission therefore comes first and the resolver is consulted exactly once, afterwards.
        //
        // E8: one durable grant for one call into the executor, taken immediately before it and consumed at most once.
        // It authorizes this invocation and nothing the executor does inside its own code -- `ExternalStepExecutor` is
        // a third-party extension point, so its internal effects stay outside the enforcement claim. A refused
        // invocation reaches no resolver code at all, so it cannot instantiate the executor either.
        val permit = externalAdmission?.admitExternalStep(name, type)
        val evidenceContext =
            ExternalEvidenceContext(
                workflowName = workflowName,
                workflowId = context.workflowId,
                stepName = name,
                typeId = type,
                evidenceStore = evidenceStore,
                stepAttempt = stepAttempt,
            )
        val evidenceKey = ExternalEvidenceOps.recordAdmitted(permit, evidenceContext)

        permit?.tryConsume()

        val executor = ExternalEvidenceOps.resolveExecutor(executorResolver, type, evidenceKey, evidenceStore)
        val result = ExternalEvidenceOps.executeSafely(executor, config, evidenceKey, evidenceStore)

        ExternalEvidenceOps.updateState(
            evidenceKey = evidenceKey,
            evidenceStore = evidenceStore,
            targetState = WorkflowEffectEvidenceState.DISPATCHED,
        )
        ExternalEvidenceOps.updateState(
            evidenceKey = evidenceKey,
            evidenceStore = evidenceStore,
            targetState = WorkflowEffectEvidenceState.COMPLETED,
        )

        return merge(state, result, context)
    }

    override suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S> =
        WorkflowStepExecutionResult.Completed(
            execute(
                workflowName = request.workflowName,
                state = request.state,
                context = request.context,
                executorResolver = request.services.externalStepExecutorResolver,
            ),
        )
}

internal data class ExternalEvidenceContext(
    val workflowName: String,
    val workflowId: String,
    val stepName: String,
    val typeId: String,
    val evidenceStore: WorkflowEffectEvidenceRecordStore?,
    val stepAttempt: PersistedStepAttempt?,
)

internal object ExternalEvidenceOps {
    @Suppress("TooGenericExceptionCaught")
    suspend fun recordAdmitted(
        permit: WorkflowExternalPermit?,
        context: ExternalEvidenceContext,
    ): WorkflowEffectEvidenceKey? {
        val store = context.evidenceStore
        val attempt = context.stepAttempt
        if (permit == null || store == null || attempt == null) {
            return null
        }
        val key =
            WorkflowEffectEvidenceKey(
                workflowName = context.workflowName,
                workflowId = context.workflowId,
                checkpointGeneration = permit.checkpointGeneration,
                admissionSeq = permit.admissionSequence,
            )
        try {
            store.recordEvidence(
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = context.stepName,
                    stepAttemptId = attempt.attemptId,
                    effectKind = externalEffectKind(context.typeId),
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis =
                        currentEffectEvidenceClockMillis() ?: attempt.startedAtEpochMillis,
                ),
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            error.rethrowIfAuthoritativeRefusal()
            throw ExternalEvidencePersistenceException(error)
        }
        return key
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun resolveExecutor(
        resolver: ExternalStepExecutorResolver,
        type: String,
        evidenceKey: WorkflowEffectEvidenceKey?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
    ): ExternalStepExecutor =
        try {
            resolver.create(type)
        } catch (cancelled: CancellationException) {
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = cancelled,
                diagnosticSummary = EXTERNAL_CANCELLED_DIAGNOSTIC,
            )
            throw cancelled
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            error.rethrowIfAuthoritativeRefusal()
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = error,
                diagnosticSummary = EXTERNAL_RESOLUTION_FAILED_DIAGNOSTIC,
            )
            throw error
        }

    @Suppress("TooGenericExceptionCaught")
    suspend fun executeSafely(
        executor: ExternalStepExecutor,
        config: Map<String, Any?>,
        evidenceKey: WorkflowEffectEvidenceKey?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
    ): Map<String, Any?> =
        try {
            executor.execute(config)
        } catch (cancelled: CancellationException) {
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = cancelled,
                diagnosticSummary = EXTERNAL_CANCELLED_DIAGNOSTIC,
            )
            throw cancelled
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            error.rethrowIfAuthoritativeRefusal()
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = error,
                diagnosticSummary = EXTERNAL_EXECUTION_FAILED_DIAGNOSTIC,
            )
            throw error
        }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun updateState(
        evidenceKey: WorkflowEffectEvidenceKey?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
        targetState: WorkflowEffectEvidenceState,
    ) {
        if (evidenceKey == null || evidenceStore == null) return
        val record =
            try {
                evidenceStore.getEvidence(evidenceKey)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                error.rethrowIfAuthoritativeRefusal()
                val persistenceError = ExternalEvidencePersistenceException(error)
                recordUncertainFailure(
                    evidenceKey = evidenceKey,
                    evidenceStore = evidenceStore,
                    error = persistenceError,
                    diagnosticSummary = EXTERNAL_EVIDENCE_FAILED_DIAGNOSTIC,
                )
                throw persistenceError
            } ?: throw WorkflowEvidenceResolutionException(
                "Missing expected evidence record for key: $evidenceKey (target state: $targetState)",
            )

        val completedAt =
            if (targetState == WorkflowEffectEvidenceState.COMPLETED) {
                currentEffectEvidenceClockMillis() ?: record.createdAtEpochMillis
            } else {
                record.completedAtEpochMillis
            }

        try {
            evidenceStore.updateEvidence(
                record.copy(
                    state = targetState,
                    completedAtEpochMillis = completedAt,
                ),
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            error.rethrowIfAuthoritativeRefusal()
            val persistenceError = ExternalEvidencePersistenceException(error)
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = persistenceError,
                diagnosticSummary = EXTERNAL_EVIDENCE_FAILED_DIAGNOSTIC,
            )
            throw persistenceError
        }
    }
}

// ─── External step executor support (plugin steps) ──────────────────────────

interface ExternalStepExecutorFactory {
    val typeId: String

    fun create(): ExternalStepExecutor
}

fun interface ExternalStepExecutor {
    suspend fun execute(spec: Map<String, Any?>): Map<String, Any?>
}

class ExternalStepExecutorNotRegisteredException(
    typeId: String,
) : RuntimeException("No external step executor is registered for plugin step type '$typeId'")

interface ExternalStepExecutorResolver {
    fun isRegistered(typeId: String): Boolean

    fun registeredTypeIds(): Set<String>

    fun create(typeId: String): ExternalStepExecutor
}

class ExternalStepExecutorRegistry : ExternalStepExecutorResolver {
    private val factories = ConcurrentHashMap<String, ExternalStepExecutorFactory>()

    fun register(factory: ExternalStepExecutorFactory) {
        require(factory.typeId.isNotBlank()) { "External step executor typeId must not be blank" }
        factories[factory.typeId] = factory
    }

    fun unregister(typeId: String) {
        factories.remove(typeId)
    }

    fun clear() {
        factories.clear()
    }

    fun replaceAll(factories: Collection<ExternalStepExecutorFactory>) {
        val replacements = linkedMapOf<String, ExternalStepExecutorFactory>()
        factories.forEach { factory ->
            require(factory.typeId.isNotBlank()) { "External step executor typeId must not be blank" }
            replacements[factory.typeId] = factory
        }
        this.factories.clear()
        this.factories.putAll(replacements)
    }

    override fun isRegistered(typeId: String): Boolean = factories.containsKey(typeId)

    override fun registeredTypeIds(): Set<String> = factories.keys.toSortedSet()

    fun typeIds(): Set<String> = registeredTypeIds()

    override fun create(typeId: String): ExternalStepExecutor =
        factories[typeId]?.create() ?: throw ExternalStepExecutorNotRegisteredException(typeId)
}

object NoOpExternalStepExecutorResolver : ExternalStepExecutorResolver {
    override fun isRegistered(typeId: String): Boolean = false

    override fun registeredTypeIds(): Set<String> = emptySet()

    override fun create(typeId: String): ExternalStepExecutor = throw ExternalStepExecutorNotRegisteredException(typeId)
}
