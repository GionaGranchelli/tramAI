package dev.tramai.orchestration

import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkflowCheckpointBindingScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Resolves the underlying [WorkflowEffectEvidenceRecordStore] from a [WorkflowCheckpointStore],
 * unwrapping lease fences or delegates if necessary.
 */
internal fun WorkflowCheckpointStore?.resolveEvidenceStore(): WorkflowEffectEvidenceRecordStore? {
    var current: Any? = this
    var resolved: WorkflowEffectEvidenceRecordStore? = null
    while (current != null && resolved == null) {
        when (current) {
            is WorkflowEffectEvidenceRecordStore -> resolved = current
            is InMemoryWorkflowCheckpointStore -> resolved = current.evidenceStore
            is JdbcWorkflowCheckpointStore -> resolved = current.evidenceStore
            is LeaseFencedCheckpointStore -> current = current.delegate
            else -> current = null
        }
    }
    return resolved
}

internal suspend fun resolveStepAttemptAuthority(frame: WorkflowExecutionFrame<*>): StepAttemptAuthority? =
    ((frame.observer as? FailureIsolatingWorkflowObserver)?.delegate as? StepAttemptAuthority)
        ?: (frame.observer as? StepAttemptAuthority)
        ?: currentCoroutineContext()[StepAttemptAuthorityScope]?.authority

internal suspend fun <S> executeGovernedStep(
    workflowName: String,
    stepExecutor: WorkflowStepExecutor<S>,
    step: InternalWorkflowStep<S>,
    request: WorkflowStepExecutionRequest<S>,
    frame: WorkflowExecutionFrame<S>,
): WorkflowStepExecutionResult<S> {
    val identity = frame.governedRunIdentity ?: return stepExecutor.executeStep(step, request)
    return withContext(
        GovernedRunScope(identity) +
            WorkflowCheckpointBindingScope(
                WorkflowCheckpointBinding(
                    workflowName = workflowName,
                    workflowId = frame.context.workflowId,
                ),
            ),
    ) {
        stepExecutor.executeStep(step, request)
    }
}
