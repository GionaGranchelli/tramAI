package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuationStatus
import java.time.Instant

/**
 * Epic 8.2b: pure, independent oracle for the ApprovalContinuation lifecycle.
 *
 * The model is deliberately NOT derived from production helpers: it encodes
 * the documented lifecycle contract (claim/cancel/expire/complete/
 * forceCancelClaimed/get) and its own version numbering, and the TCK replays
 * generated actions through it against the real stores. The key design
 * distinction versus the approval lifecycle model: a FAILED operation can
 * legitimately mutate durable state — a late claim/cancel normalizes an
 * elapsed PENDING row to EXPIRED before reporting its typed failure, so
 * [ApprovalContinuationLifecycleOutcome.Failure] carries the post-failure
 * model, not merely a failure kind.
 */
internal data class ApprovalContinuationLifecycleModel(
    val status: ApprovalContinuationStatus,
    val version: Long,
    val now: Instant,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val completedAt: Instant?,
    val recoveryResolvedBy: String?,
    val recoveryResolvedAt: Instant?,
    val recoveryReasonCode: String?,
    val argumentsAvailable: Boolean,
) {

    companion object {
        fun pending(now: Instant): ApprovalContinuationLifecycleModel =
            ApprovalContinuationLifecycleModel(
                status = ApprovalContinuationStatus.PENDING,
                version = 0L,
                now = now,
                claimedBy = null,
                claimedAt = null,
                completedAt = null,
                recoveryResolvedBy = null,
                recoveryResolvedAt = null,
                recoveryReasonCode = null,
                argumentsAvailable = true,
            )
    }

    /** Lazy-expiry normalization, mirroring the stores' `expireIfElapsed`. */
    fun normalized(expiresAt: Instant): ApprovalContinuationLifecycleModel =
        normalizedAt(now, expiresAt)

    /**
     * Lazy-expiry normalization evaluated at an explicit observation time.
     * The TCK observation `store.get()` runs at the clock instant set for
     * the executed action (the pre-action model time) — an `Advance*`
     * action changes [now] but not the store clock, so observation-time
     * normalization must use the observation time, not [now].
     */
    fun normalizedAt(observationTime: Instant, expiresAt: Instant): ApprovalContinuationLifecycleModel =
        if (status == ApprovalContinuationStatus.PENDING && observationTime >= expiresAt) {
            copy(
                status = ApprovalContinuationStatus.EXPIRED,
                version = version + 1,
                argumentsAvailable = false,
            )
        } else {
            this
        }

    fun apply(
        action: ApprovalContinuationLifecycleAction,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome = when (action) {
        is ApprovalContinuationLifecycleAction.AdvanceToBeforeExpiry ->
            ApprovalContinuationLifecycleOutcome.Success(copy(now = expiresAt.minusSeconds(1)))
        is ApprovalContinuationLifecycleAction.AdvanceToExactExpiry ->
            ApprovalContinuationLifecycleOutcome.Success(copy(now = expiresAt))
        is ApprovalContinuationLifecycleAction.AdvancePastExpiry ->
            ApprovalContinuationLifecycleOutcome.Success(copy(now = expiresAt.plusSeconds(1)))
        is ApprovalContinuationLifecycleAction.Get ->
            // get() can itself trigger lazy expiry — a real state transition.
            ApprovalContinuationLifecycleOutcome.Success(normalized(expiresAt))
        is ApprovalContinuationLifecycleAction.ClaimCurrentVersion ->
            applyClaim(action.worker, expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.ClaimWrongVersion ->
            applyClaim(action.worker, expectedVersion = version + 1, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.CancelCurrentVersion ->
            applyCancel(expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.CancelWrongVersion ->
            applyCancel(expectedVersion = version + 1, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.ExpireCurrentVersion ->
            applyExpire(expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.ExpireWrongVersion ->
            applyExpire(expectedVersion = version + 1, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.CompleteCurrentVersion ->
            applyComplete(action.worker, expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.CompleteWrongVersion ->
            applyComplete(action.worker, expectedVersion = version + 1, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.CompleteWrongActor ->
            applyComplete(action.intruder, expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion ->
            applyForceCancel(action.recoveryActor, action.reasonCode, expectedVersion = version, expiresAt = expiresAt)
        is ApprovalContinuationLifecycleAction.ForceCancelWrongVersion ->
            applyForceCancel(action.recoveryActor, action.reasonCode, expectedVersion = version + 1, expiresAt = expiresAt)
    }

    private fun applyClaim(
        worker: String,
        expectedVersion: Long,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome {
        val normalized = normalized(expiresAt)
        if (normalized.status == ApprovalContinuationStatus.EXPIRED) {
            // Late claim: the elapsed PENDING row is normalized to EXPIRED,
            // arguments are discarded, and the failure is reported AFTER the
            // durable mutation.
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE,
                next = normalized,
            )
        }
        if (normalized.version != expectedVersion) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = normalized,
            )
        }
        if (normalized.status != ApprovalContinuationStatus.PENDING) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE,
                next = normalized,
            )
        }
        return ApprovalContinuationLifecycleOutcome.Success(
            next = normalized.copy(
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = worker,
                claimedAt = normalized.now,
                version = normalized.version + 1,
                argumentsAvailable = false,
            ),
            releasedArguments = normalized.argumentsAvailable,
        )
    }

    private fun applyCancel(
        expectedVersion: Long,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome {
        val normalized = normalized(expiresAt)
        if (normalized.status == ApprovalContinuationStatus.EXPIRED) {
            // Late cancel: same normalization-first behavior as late claim,
            // but the typed failure is CONFLICT (per the stores).
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = normalized,
            )
        }
        if (normalized.version != expectedVersion) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = normalized,
            )
        }
        if (normalized.status != ApprovalContinuationStatus.PENDING) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = normalized,
            )
        }
        return ApprovalContinuationLifecycleOutcome.Success(
            next = normalized.copy(
                status = ApprovalContinuationStatus.CANCELLED,
                version = normalized.version + 1,
                argumentsAvailable = false,
            ),
        )
    }

    private fun applyExpire(
        expectedVersion: Long,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome {
        // Explicit expire checks the version BEFORE the expiry window, and
        // rejects an early (still-live) explicit expire with CONFLICT.
        if (version != expectedVersion) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = this,
            )
        }
        if (status != ApprovalContinuationStatus.PENDING) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = this,
            )
        }
        if (now < expiresAt) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = this,
            )
        }
        return ApprovalContinuationLifecycleOutcome.Success(
            next = copy(
                status = ApprovalContinuationStatus.EXPIRED,
                version = version + 1,
                argumentsAvailable = false,
            ),
        )
    }

    private fun applyComplete(
        worker: String,
        expectedVersion: Long,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome {
        if (version != expectedVersion) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = this,
            )
        }
        if (status != ApprovalContinuationStatus.CLAIMED || claimedBy == null || claimedAt == null) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.NOT_COMPLETABLE,
                next = this,
            )
        }
        if (claimedBy != worker) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.NOT_COMPLETABLE,
                next = this,
            )
        }
        return ApprovalContinuationLifecycleOutcome.Success(
            next = copy(
                status = ApprovalContinuationStatus.COMPLETED,
                completedAt = now,
                version = version + 1,
                argumentsAvailable = false,
            ),
        )
    }

    private fun applyForceCancel(
        recoveryActor: String,
        reasonCode: String,
        expectedVersion: Long,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleOutcome {
        if (version != expectedVersion) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.CONFLICT,
                next = this,
            )
        }
        if (status != ApprovalContinuationStatus.CLAIMED) {
            return ApprovalContinuationLifecycleOutcome.Failure(
                kind = ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE,
                next = this,
            )
        }
        return ApprovalContinuationLifecycleOutcome.Success(
            next = copy(
                status = ApprovalContinuationStatus.CANCELLED_UNCERTAIN,
                version = version + 1,
                recoveryResolvedBy = recoveryActor,
                recoveryResolvedAt = now,
                recoveryReasonCode = reasonCode,
                argumentsAvailable = false,
            ),
        )
    }
}

internal sealed interface ApprovalContinuationLifecycleAction {
    data object AdvanceToBeforeExpiry : ApprovalContinuationLifecycleAction
    data object AdvanceToExactExpiry : ApprovalContinuationLifecycleAction
    data object AdvancePastExpiry : ApprovalContinuationLifecycleAction
    data object Get : ApprovalContinuationLifecycleAction
    data class ClaimCurrentVersion(val worker: String) : ApprovalContinuationLifecycleAction
    data class ClaimWrongVersion(val worker: String) : ApprovalContinuationLifecycleAction
    data object CancelCurrentVersion : ApprovalContinuationLifecycleAction
    data object CancelWrongVersion : ApprovalContinuationLifecycleAction
    data object ExpireCurrentVersion : ApprovalContinuationLifecycleAction
    data object ExpireWrongVersion : ApprovalContinuationLifecycleAction
    data class CompleteCurrentVersion(val worker: String) : ApprovalContinuationLifecycleAction
    data class CompleteWrongVersion(val worker: String) : ApprovalContinuationLifecycleAction
    data class CompleteWrongActor(val intruder: String) : ApprovalContinuationLifecycleAction
    data class ForceCancelCurrentVersion(val recoveryActor: String, val reasonCode: String) : ApprovalContinuationLifecycleAction
    data class ForceCancelWrongVersion(val recoveryActor: String, val reasonCode: String) : ApprovalContinuationLifecycleAction

    fun describe(): String = when (this) {
        AdvanceToBeforeExpiry -> "advance-before-expiry"
        AdvanceToExactExpiry -> "advance-exact-expiry"
        AdvancePastExpiry -> "advance-past-expiry"
        Get -> "get"
        is ClaimCurrentVersion -> "claim-current($worker)"
        is ClaimWrongVersion -> "claim-wrong($worker)"
        CancelCurrentVersion -> "cancel-current"
        CancelWrongVersion -> "cancel-wrong"
        ExpireCurrentVersion -> "expire-current"
        ExpireWrongVersion -> "expire-wrong"
        is CompleteCurrentVersion -> "complete-current($worker)"
        is CompleteWrongVersion -> "complete-wrong($worker)"
        is CompleteWrongActor -> "complete-wrong-actor($intruder)"
        is ForceCancelCurrentVersion -> "force-cancel-current($recoveryActor,$reasonCode)"
        is ForceCancelWrongVersion -> "force-cancel-wrong($recoveryActor,$reasonCode)"
    }
}

internal sealed interface ApprovalContinuationLifecycleOutcome {
    data class Success(
        val next: ApprovalContinuationLifecycleModel,
        val releasedArguments: Boolean = false,
    ) : ApprovalContinuationLifecycleOutcome

    data class Failure(
        val kind: ApprovalContinuationLifecycleFailureKind,
        val next: ApprovalContinuationLifecycleModel,
    ) : ApprovalContinuationLifecycleOutcome
}

internal enum class ApprovalContinuationLifecycleFailureKind {
    CONFLICT,
    NOT_CLAIMABLE,
    NOT_COMPLETABLE,
}
