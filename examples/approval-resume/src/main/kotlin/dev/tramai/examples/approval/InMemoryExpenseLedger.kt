package dev.tramai.examples.approval

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory expense ledger that tracks reimbursements for idempotency verification.
 *
 * The [executionCount] proves that a side effect executes exactly once,
 * even when the workflow is resumed after approval.
 */
class InMemoryExpenseLedger {

    private val reimbursed = ConcurrentHashMap.newKeySet<String>()
    private val _executionCount = AtomicInteger(0)

    /** Number of times [reimburse] has been called. */
    val executionCount: Int get() = _executionCount.get()

    /** Whether this expense has already been reimbursed. */
    fun isReimbursed(expenseId: String): Boolean = expenseId in reimbursed

    /**
     * Reimburse the expense. Idempotent — subsequent calls for the same
     * expenseId are no-ops and do not increment the execution count.
     */
    fun reimburse(expenseId: String) {
        if (reimbursed.add(expenseId)) {
            _executionCount.incrementAndGet()
        }
    }
}
