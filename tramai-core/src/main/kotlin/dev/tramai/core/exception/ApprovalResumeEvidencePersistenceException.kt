package dev.tramai.core.exception

import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Raised when workflow admission committed a conditional grant for a resumed approval continuation (E9),
 * but writing the required child evidence record failed.
 *
 * This is an unresolved post-admission failure, NOT a pre-admission refusal: the durable checkpoint
 * admission sequence has committed and cannot be dispatched, but must not be treated as though admission
 * was denied or the continuation safely cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalResumeEvidencePersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    CopyableThrowable<ApprovalResumeEvidencePersistenceException> {
    override fun createCopy(): ApprovalResumeEvidencePersistenceException =
        ApprovalResumeEvidencePersistenceException(
            message ?: "Approval resume evidence persistence failed",
            cause ?: this,
        ).also { copy ->
            for (suppressedError in suppressed) {
                copy.addSuppressed(suppressedError)
            }
        }
}
