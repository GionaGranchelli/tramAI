package dev.tramai.core.approval.gateway

/**
 * Front-door contract for non-blocking human approval in sovereign workflows.
 *
 * Implementations persist durable suspension state and return immediately;
 * they must not block a thread waiting for a human decision.
 *
 * This SPI is [Preview] — the API shape is usable but may evolve.
 * See [docs/architecture/sovereign-api-stability-boundary.md](../../../../../../docs/architecture/sovereign-api-stability-boundary.md).
 */
interface ApprovalGateway {

    /**
     * Request human approval for a workflow step.
     *
     * @param subject  the business object requiring approval (e.g. claim ID)
     * @param recommendation  the recommendation to be reviewed
     * @param requiredRole  the role permitted to approve or deny
     * @param workflowRunId  optional workflow run identifier for idempotency
     * @return  [ApprovalRequestResult] indicating suspension or pre-existing decision
     */
    suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId? = null,
    ): ApprovalRequestResult
}
