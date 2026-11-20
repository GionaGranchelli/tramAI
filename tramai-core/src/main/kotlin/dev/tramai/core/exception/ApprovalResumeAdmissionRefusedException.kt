package dev.tramai.core.exception

/**
 * Raised when the owning workflow's durable authority refuses admission for a resumed approval invocation (E9).
 *
 * An approval decision authorizes the tool call; it does not authorize continuing work in a workflow that has been
 * authoritatively cancelled, or whose checkpoint revision/generation the resumer no longer holds. So this is not a
 * transient failure and not a tool failure: the resumed effect must not be dispatched, and the caller must not retry
 * the same attempt.
 *
 * Kept distinct from every authorization and persistence failure on purpose — a refusal is the authority saying no,
 * and rewriting it into an execution error, an uncertain outcome, or a token rejection would misreport who said no and
 * why.
 */
class ApprovalResumeAdmissionRefusedException(
    message: String,
) : IllegalStateException(message)
