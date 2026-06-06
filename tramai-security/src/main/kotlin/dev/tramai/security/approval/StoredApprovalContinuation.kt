package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.SensitiveToolArguments

/**
 * Internal storage envelope that pairs continuation metadata with
 * its sensitive payload. The payload is scrubbed (set to null) on
 * claim, expire, and cancel.
 */
internal data class StoredApprovalContinuation(
    val continuation: ApprovalContinuation,
    val arguments: SensitiveToolArguments?,
)
