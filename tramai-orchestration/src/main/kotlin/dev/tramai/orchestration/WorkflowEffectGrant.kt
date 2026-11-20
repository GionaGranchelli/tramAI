package dev.tramai.orchestration

/**
 * The one durable-admission routine every effect family shares: present the revision this worker holds, take the
 * store's conditional write, and refuse when the write did not take.
 *
 * It exists as one function because the rule must not exist twice. Five families (process, HTTP, MCP start, MCP tool,
 * external executor) and the approval-resume authority all reach the store through here, so a later fix to the
 * precondition lands once. Callers decide which refusal vocabulary their layer speaks; this routine speaks the
 * orchestration one and they map it.
 *
 * @throws WorkflowAdmissionRefusedException when there is no revision to present, or the store refused the write.
 */
@Suppress("LongParameterList")
internal suspend fun WorkflowCheckpointStore.admitEffect(
    workflowName: String,
    workflowId: String,
    currentRevision: Long?,
    currentGeneration: String?,
    stepName: String,
    effectKind: String,
): WorkflowAdmission {
    val revision =
        currentRevision ?: throw refuseProcessAdmission(workflowName, workflowId, stepName, effectKind)
    val admission =
        admit(
            WorkflowAdmissionRequest(
                workflowName = workflowName,
                workflowId = workflowId,
                expectedRevision = revision,
                expectedGeneration = currentGeneration,
            ),
        )
    if (!admission.admitted) {
        throw refuseProcessAdmission(workflowName, workflowId, stepName, effectKind, admission)
    }
    return admission
}
