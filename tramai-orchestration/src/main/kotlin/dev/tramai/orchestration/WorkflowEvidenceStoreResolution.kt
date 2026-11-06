@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)

package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkflowCheckpointBindingScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Resolves the active [WorkflowEffectEvidenceRecordStore] from a [WorkflowCheckpointStore]
 * when evidence tracking has been explicitly configured.
 */
internal fun WorkflowCheckpointStore?.resolveEvidenceStore(): WorkflowEffectEvidenceRecordStore? =
    when (this) {
        is WorkflowEffectEvidenceRecordStore -> this
        is InMemoryWorkflowCheckpointStore -> this.evidenceStore
        is JdbcWorkflowCheckpointStore -> this.evidenceStore
        else -> null
    }

internal fun resolveWorkerEvidenceStore(
    binding: ErasedWorkflowBinding,
    checkpointStore: WorkflowCheckpointStore,
): WorkflowEffectEvidenceRecordStore? = binding.evidenceStore ?: checkpointStore.resolveEvidenceStore()

/**
 * Validates that when D7 evidence tracking is enabled ([evidenceStore] != null),
 * both an admission capability and authoritative step-attempt lineage are present
 * before an effect admission sequence is requested.
 */
internal fun requireEvidenceLineageIfEnabled(
    workflowName: String,
    workflowId: String,
    stepName: String,
    admissionAvailable: Boolean,
    stepAttempt: PersistedStepAttempt?,
) {
    if (!admissionAvailable || stepAttempt == null) {
        throw WorkflowEvidenceResolutionException(
            "workflow '$workflowName' run '$workflowId' cannot dispatch effect for step '$stepName': " +
                "per-effect evidence tracking is enabled, but authoritative step-attempt lineage or admission " +
                "authority is absent from the execution scope",
        )
    }
}

internal suspend fun resolveStepAttemptAuthority(frame: WorkflowExecutionFrame<*>): StepAttemptAuthority? =
    ((frame.observer as? FailureIsolatingWorkflowObserver)?.delegate as? StepAttemptAuthority)
        ?: (frame.observer as? StepAttemptAuthority)
        ?: currentCoroutineContext()[StepAttemptAuthorityScope]?.authority

internal fun governedStepContext(
    workflowName: String,
    frame: WorkflowExecutionFrame<*>,
): CoroutineContext {
    val identity = frame.governedRunIdentity ?: return EmptyCoroutineContext
    return GovernedRunScope(identity) +
        WorkflowCheckpointBindingScope(
            WorkflowCheckpointBinding(
                workflowName = workflowName,
                workflowId = frame.context.workflowId,
            ),
        )
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun recordUncertainFailure(
    evidenceKey: WorkflowEffectEvidenceKey?,
    evidenceStore: WorkflowEffectEvidenceRecordStore?,
    error: Throwable,
    timeoutMillis: Long = 5_000L,
) {
    if (evidenceKey == null || evidenceStore == null) return
    try {
        withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMillis) {
                val record = evidenceStore.getEvidence(evidenceKey)
                if (record != null && record.state != WorkflowEffectEvidenceState.UNCERTAIN) {
                    val preview = boundedWorkflowDetailPreview(error.message ?: error::class.java.name).text
                    evidenceStore.updateEvidence(
                        record.copy(
                            state = WorkflowEffectEvidenceState.UNCERTAIN,
                            diagnosticSummary = preview,
                        ),
                    )
                }
            }
        }
    } catch (suppressed: Throwable) {
        suppressed.rethrowIfCancellation()
        error.addSuppressed(suppressed)
    }
}
