package dev.tramai.core.approval

/**
 * Lifecycle status of an approval request.
 *
 * PENDING -> {APPROVED, DENIED, TIMED_OUT}
 * All statuses except PENDING are terminal — no further transitions are allowed.
 */
enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    TIMED_OUT,
}
