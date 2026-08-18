package dev.tramai.orchestration

import java.time.Instant
import java.util.concurrent.TimeUnit

private const val WORKFLOW_DELAY_STEP_METADATA_KEY: String =
    "tramai.workflow.delay.step"
private const val WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY: String =
    "tramai.workflow.delay.resume_at_epoch_millis"

/**
 * Delay-specific checkpoint/suspension mechanics.
 *
 * A suspending delay receives onStepStarted (via the shared wrapper) but does
 * not receive onStepCompleted until it eventually completes after resume. The
 * build-time rule from Epic 4.1 is preserved: [WorkflowStepSuspensionMode.TOP_LEVEL_CHECKPOINT]
 * suspension inside a nested branch is rejected during workflow build, so this
 * step is only ever executed as a top-level step.
 */
internal data class DelayWorkflowStep<S>(
    override val name: String,
    val duration: Long,
    val unit: TimeUnit,
) : InternalWorkflowStep<S> {
    override val suspensionMode: WorkflowStepSuspensionMode
        get() = WorkflowStepSuspensionMode.TOP_LEVEL_CHECKPOINT

    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> {
        val workflowName = request.workflowName
        val context = request.context
        val observer = request.observer
        val clock = request.services.clock
        val now = clock.instant()
        val resumedResumeAt = request.resumedCheckpointMetadata?.delayResumeAt(workflowName, context.workflowId, name)
        val resumeAt = resumedResumeAt ?: now.plusMillis(unit.toMillis(duration))
        if (!resumeAt.isAfter(now)) {
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.delay.resumed",
                attributes = delayAttributes(context.workflowId, name, resumeAt),
                context = context,
            )
            return WorkflowStepExecutionResult.Completed(request.state)
        }
        val session = request.persistenceSession
            ?: throw WorkflowResumeException(
                "Workflow '$workflowName' delay step '$name' requires WorkflowPersistence to checkpoint the delayed run",
            )
        val stepIndex = request.topLevelStepIndex
            ?: throw WorkflowResumeException(
                "Workflow '$workflowName' delay step '$name' must be a top-level step because checkpoints resume at top-level step boundaries",
            )
        session.saveCheckpoint(
            state = request.state,
            nextStepIndex = stepIndex,
            lastCompletedStepName = null,
            stepExecutions = request.stepCounter.stepExecutions,
            extraMetadata = delayMetadata(name, resumeAt),
        )
        session.scheduleDelayWakeup(
            stepName = name,
            resumeAt = resumeAt,
        )
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = if (resumedResumeAt == null) {
                "tramai.workflow.delay.started"
            } else {
                "tramai.workflow.delay.waiting"
            },
            attributes = delayAttributes(context.workflowId, name, resumeAt),
            context = context,
        )
        return WorkflowStepExecutionResult.Suspended
    }
}

private fun delayMetadata(
    stepName: String,
    resumeAt: Instant,
): Map<String, String> = mapOf(
    WORKFLOW_DELAY_STEP_METADATA_KEY to stepName,
    WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY to resumeAt.toEpochMilli().toString(),
)

private fun delayAttributes(
    workflowId: String,
    stepName: String,
    resumeAt: Instant,
): Map<String, Any?> = mapOf(
    "workflow_id" to workflowId,
    "step_name" to stepName,
    "resume_at_epoch_millis" to resumeAt.toEpochMilli(),
)

private fun Map<String, String>.delayResumeAt(
    workflowName: String,
    workflowId: String,
    stepName: String,
): Instant? {
    val persistedStepName = this[WORKFLOW_DELAY_STEP_METADATA_KEY] ?: return null
    if (persistedStepName != stepName) {
        throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' contains delay metadata for step '$persistedStepName', but resume reached step '$stepName'",
        )
    }
    val rawResumeAt = this[WORKFLOW_DELAY_RESUME_AT_EPOCH_MILLIS_METADATA_KEY]
        ?: throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is missing delay resume-at metadata for step '$stepName'",
        )
    val epochMillis = rawResumeAt.toLongOrNull()
        ?: throw WorkflowResumeException(
            "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' has invalid delay resume-at metadata '$rawResumeAt' for step '$stepName'",
        )
    return Instant.ofEpochMilli(epochMillis)
}
