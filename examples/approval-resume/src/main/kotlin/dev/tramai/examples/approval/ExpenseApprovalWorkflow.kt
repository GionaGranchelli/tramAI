package dev.tramai.examples.approval

import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.workflow.SovereignWorkflowResult
import dev.tramai.core.workflow.toWorkflowResult
import java.util.UUID

/**
 * Expense approval workflow that suspends for manager approval
 * when the expense exceeds the [APPROVAL_THRESHOLD].
 *
 * The workflow uses [ApprovalGateway] to create a suspension point
 * and [SovereignWorkflowResult.toWorkflowResult] to map each outcome.
 */
class ExpenseApprovalWorkflow(
    private val approvalGateway: ApprovalGateway,
    private val ledger: InMemoryExpenseLedger,
) {
    /**
     * Process an expense claim.
     *
     * @return [SovereignWorkflowResult] indicating the workflow outcome.
     */
    suspend fun process(claim: ExpenseClaim): SovereignWorkflowResult<String> {
        // Low-value expenses complete without approval
        if (claim.amount < APPROVAL_THRESHOLD) {
            ledger.reimburse(claim.expenseId)
            return SovereignWorkflowResult.Completed("EXPENSE_REIMBURSED")
        }

        // High-value expenses require manager approval
        val runId = "expense-${claim.expenseId}-${UUID.randomUUID()}"
        return approvalGateway.requestApproval(
            subject = ApprovalSubject(claim.expenseId),
            recommendation = ApprovalRecommendation(
                type = "expense-approval",
                summary = "Expense of €${claim.amount} requires manager approval",
                payload = mapOf(
                    "employeeId" to claim.employeeId,
                    "amount" to claim.amount.toString(),
                    "reason" to claim.reason,
                ),
            ),
            requiredRole = ApproverRole("manager"),
            workflowRunId = WorkflowRunId(runId),
        ).toWorkflowResult { _ ->
            // Only invoked when AlreadyApproved — lazy
            ledger.reimburse(claim.expenseId)
            "EXPENSE_REIMBURSED"
        }
    }
}
