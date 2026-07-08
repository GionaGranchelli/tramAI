package dev.tramai.examples.approval

/**
 * Input for an expense claim.
 */
data class ExpenseClaim(
    val expenseId: String,
    val employeeId: String,
    val amount: Int,
    val reason: String,
)

/**
 * Outcome of an expense approval workflow.
 */
data class ExpenseResult(
    val expenseId: String,
    val status: String,
    val approvalId: String? = null,
) {
    companion object {
        const val APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
        const val REIMBURSED = "REIMBURSED"
        const val DENIED = "DENIED"
        const val APPROVED = "APPROVED"
    }
}

/**
 * Threshold above which manager approval is required.
 */
const val APPROVAL_THRESHOLD = 1000
