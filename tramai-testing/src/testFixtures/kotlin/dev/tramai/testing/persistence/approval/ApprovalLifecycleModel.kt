package dev.tramai.testing.persistence.approval

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import java.time.Instant

/**
 * Epic 8.2a: PURE, independent lifecycle oracle for the ApprovalStore
 * compatibility contract.
 *
 * This is deliberately NOT derived from the production stores or their
 * helpers — it encodes the documented lifecycle (ApprovalRequest KDoc +
 * ApprovalStore SPI KDoc + the #267 contract) as a standalone state machine,
 * so the property suite proves "the store agrees with the documented model"
 * rather than "the store agrees with itself".
 *
 * State: status/version/now plus every mutable decision and consumption
 * field. The immutable request fields (approvalId, binding, requestedBy,
 * requestedAt, expiresAt) are pinned separately by the caller and merged via
 * [toRequest] for whole-record comparison.
 *
 * Expiry boundary (documented and implemented everywhere):
 *   now < expiresAt  → decision legal, fresh consume legal
 *   now >= expiresAt → timeout legal (while PENDING), decision illegal,
 *                      fresh consume illegal, exact replay still legal
 *
 * Invariants enforced structurally by the transition rules and re-checked
 * explicitly via [invariants] after every generated action.
 */
internal data class ApprovalLifecycleModel(
    val status: ApprovalStatus,
    val version: Long,
    val now: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionComment: String?,
    val consumedBy: String?,
    val consumedAt: Instant?,
) {

    companion object {
        fun from(request: ApprovalRequest, now: Instant): ApprovalLifecycleModel = ApprovalLifecycleModel(
            status = request.status,
            version = request.version,
            now = now,
            decidedBy = request.decidedBy,
            decidedAt = request.decidedAt,
            decisionComment = request.decisionComment,
            consumedBy = request.consumedBy,
            consumedAt = request.consumedAt,
        )

        fun pending(now: Instant): ApprovalLifecycleModel = ApprovalLifecycleModel(
            status = ApprovalStatus.PENDING,
            version = 0L,
            now = now,
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
        )
    }

    /** Reconstructs the complete expected durable record from a base request. */
    fun toRequest(base: ApprovalRequest): ApprovalRequest = base.copy(
        status = status,
        version = version,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionComment = decisionComment,
        consumedBy = consumedBy,
        consumedAt = consumedAt,
    )

    /**
     * Applies [action] to the model and returns the predicted outcome. A
     * [ApprovalLifecycleOutcome.Success] carries the next model state; a
     * [ApprovalLifecycleOutcome.Failure] leaves the model unchanged. The
     * model never throws production exceptions.
     */
    fun apply(action: ApprovalLifecycleAction, expiresAt: Instant): ApprovalLifecycleOutcome = when (action) {
        is ApprovalLifecycleAction.AdvanceToBeforeExpiry -> success(now = expiresAt.minusSeconds(1))
        is ApprovalLifecycleAction.AdvanceToExactExpiry -> success(now = expiresAt)
        is ApprovalLifecycleAction.AdvancePastExpiry -> success(now = expiresAt.plusSeconds(1))
        is ApprovalLifecycleAction.ApproveCurrentVersion -> applyDecision(action.actor, action.comment, ApprovalStatus.APPROVED, expiresAt)
        is ApprovalLifecycleAction.ApproveWrongVersion -> ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.CONFLICT)
        is ApprovalLifecycleAction.DenyCurrentVersion -> applyDecision(action.actor, action.comment, ApprovalStatus.DENIED, expiresAt)
        is ApprovalLifecycleAction.DenyWrongVersion -> ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.CONFLICT)
        is ApprovalLifecycleAction.TimeoutCurrentVersion -> applyTimeout(expiresAt)
        is ApprovalLifecycleAction.TimeoutWrongVersion -> ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.CONFLICT)
        is ApprovalLifecycleAction.ConsumeValid -> applyConsumeValid(action.worker, expiresAt)
        is ApprovalLifecycleAction.ConsumeWrongVersion -> applyConsumeWrongVersion(expiresAt)
        is ApprovalLifecycleAction.ConsumeWrongToken -> applyConsumeWrongToken(action.worker, expiresAt)
    }

    // ── decision path ────────────────────────────────────────────────

    private fun applyDecision(
        actor: String,
        comment: String?,
        target: ApprovalStatus,
        expiresAt: Instant,
    ): ApprovalLifecycleOutcome {
        if (status != ApprovalStatus.PENDING) return illegalTransition()
        if (now >= expiresAt) return illegalTransition()
        return success(
            status = target,
            version = version + 1,
            decidedBy = actor,
            decidedAt = now,
            decisionComment = comment,
        )
    }

    private fun applyTimeout(expiresAt: Instant): ApprovalLifecycleOutcome {
        if (status != ApprovalStatus.PENDING) return illegalTransition()
        if (now < expiresAt) return illegalTransition()
        return success(
            status = ApprovalStatus.TIMED_OUT,
            version = version + 1,
            decidedBy = null,
            decidedAt = now,
            decisionComment = null,
        )
    }

    // ── consumption path ─────────────────────────────────────────────

    private fun applyConsumeValid(worker: String, expiresAt: Instant): ApprovalLifecycleOutcome {
        if (status != ApprovalStatus.APPROVED) return notConsumable()
        if (consumedAt == null) {
            // Fresh path: unexpired, version/token match (the action is the
            // "valid" shape), unconsumed → fresh consumption.
            if (now >= expiresAt) return notConsumable()
            return success(consumedBy = worker, consumedAt = now, version = version + 1)
        }
        // Consumed path: exact replay requires the same actor; replay stays
        // legal after expiry and never changes the durable record.
        return if (consumedBy == worker) {
            ApprovalLifecycleOutcome.Success(next = this, replayed = true)
        } else {
            notConsumable()
        }
    }

    private fun applyConsumeWrongVersion(expiresAt: Instant): ApprovalLifecycleOutcome {
        if (status != ApprovalStatus.APPROVED) return notConsumable()
        return ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.CONFLICT)
    }

    private fun applyConsumeWrongToken(worker: String, expiresAt: Instant): ApprovalLifecycleOutcome {
        if (status != ApprovalStatus.APPROVED) return notConsumable()
        // Token check is unconditional on an APPROVED record (checked before
        // the actor/replay logic and before the expiry window — uniform across
        // InMemory/File/JDBC; the SPI KDoc does not pin check order): a wrong
        // token is TOKEN_REJECTED even for a consumed-by-other record and even
        // when expired.
        return ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.TOKEN_REJECTED)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun success(
        status: ApprovalStatus = this.status,
        version: Long = this.version,
        now: Instant = this.now,
        decidedBy: String? = this.decidedBy,
        decidedAt: Instant? = this.decidedAt,
        decisionComment: String? = this.decisionComment,
        consumedBy: String? = this.consumedBy,
        consumedAt: Instant? = this.consumedAt,
    ): ApprovalLifecycleOutcome.Success = ApprovalLifecycleOutcome.Success(
        next = copy(
            status = status,
            version = version,
            now = now,
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionComment = decisionComment,
            consumedBy = consumedBy,
            consumedAt = consumedAt,
        ),
        replayed = false,
    )

    private fun illegalTransition(): ApprovalLifecycleOutcome.Failure =
        ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.ILLEGAL_TRANSITION)

    private fun notConsumable(): ApprovalLifecycleOutcome.Failure =
        ApprovalLifecycleOutcome.Failure(ApprovalLifecycleFailureKind.NOT_CONSUMABLE)

    /**
     * Explicit per-step invariant audit (the table from the Epic 8.2a brief).
     * Returns every violated invariant; empty means the model state is legal.
     */
    fun invariants(): List<String> {
        val violations = ArrayList<String>()
        if (version < 0) violations += "version $version < 0"
        if (status == ApprovalStatus.PENDING) {
            if (decidedBy != null || decidedAt != null || decisionComment != null) {
                violations += "PENDING must not carry decision fields"
            }
            if (consumedBy != null || consumedAt != null) {
                violations += "PENDING must not carry consumption fields"
            }
        }
        if (status == ApprovalStatus.DENIED || status == ApprovalStatus.TIMED_OUT) {
            if (consumedBy != null || consumedAt != null) {
                violations += "$status must never acquire consumption fields"
            }
        }
        if (status == ApprovalStatus.APPROVED) {
            if (decidedBy == null || decidedAt == null) violations += "APPROVED must carry decision fields"
            if (consumedBy == null != (consumedAt == null)) {
                violations += "APPROVED consumption fields must be both null or both set"
            }
        }
        if (status == ApprovalStatus.DENIED && (decidedBy == null || decidedAt == null)) {
            violations += "DENIED must carry decision fields"
        }
        if (status == ApprovalStatus.TIMED_OUT && (decidedBy != null || decisionComment != null)) {
            violations += "TIMED_OUT must not carry decidedBy or decisionComment"
        }
        if (consumedBy != null && consumedAt == null) violations += "consumedBy set without consumedAt"
        return violations
    }

    fun describe(): String =
        "$status(v=$version, decidedBy=$decidedBy, decidedAt=$decidedAt, comment=$decisionComment, consumedBy=$consumedBy, consumedAt=$consumedAt)"
}

/**
 * Test-side failure categories, mapped to the public typed exceptions ONLY
 * when comparing with the real store. The model itself never throws.
 */
internal enum class ApprovalLifecycleFailureKind {
    CONFLICT,
    ILLEGAL_TRANSITION,
    NOT_CONSUMABLE,
    TOKEN_REJECTED,
    ;

    fun exceptionClass(): Class<out Exception> = when (this) {
        CONFLICT -> dev.tramai.core.exception.ApprovalStoreConflictException::class.java
        ILLEGAL_TRANSITION -> dev.tramai.core.exception.IllegalApprovalTransitionException::class.java
        NOT_CONSUMABLE -> dev.tramai.core.exception.ApprovalStoreNotConsumableException::class.java
        TOKEN_REJECTED -> dev.tramai.core.exception.ApprovalStoreTokenRejectedException::class.java
    }
}

internal sealed interface ApprovalLifecycleOutcome {
    data class Success(
        val next: ApprovalLifecycleModel,
        val replayed: Boolean,
    ) : ApprovalLifecycleOutcome

    data class Failure(
        val kind: ApprovalLifecycleFailureKind,
    ) : ApprovalLifecycleOutcome

    val nextModelOrCurrent: ApprovalLifecycleModel
        get() = if (this is Success) next else error("no next model for a failure")
}

/**
 * State-aware action alphabet for the generated lifecycle corpus. Each action
 * carries everything needed to EXECUTE it against the real store; the model
 * decides whether it is legal for the current state.
 */
internal sealed interface ApprovalLifecycleAction {

    data object AdvanceToBeforeExpiry : ApprovalLifecycleAction
    data object AdvanceToExactExpiry : ApprovalLifecycleAction
    data object AdvancePastExpiry : ApprovalLifecycleAction

    data class ApproveCurrentVersion(val actor: String, val comment: String? = null) : ApprovalLifecycleAction
    data class ApproveWrongVersion(val actor: String) : ApprovalLifecycleAction

    data class DenyCurrentVersion(val actor: String, val comment: String? = null) : ApprovalLifecycleAction
    data class DenyWrongVersion(val actor: String) : ApprovalLifecycleAction

    data object TimeoutCurrentVersion : ApprovalLifecycleAction
    data object TimeoutWrongVersion : ApprovalLifecycleAction
    // NOTE: TimeoutWrongVersion is never emitted by the generator — it is
    // exercised only by the wrong-version decision matrix in ApprovalStoreTck.

    data class ConsumeValid(val worker: String) : ApprovalLifecycleAction
    data class ConsumeWrongVersion(val worker: String) : ApprovalLifecycleAction
    data class ConsumeWrongToken(val worker: String) : ApprovalLifecycleAction

    val isAdvance: Boolean
        get() = this is AdvanceToBeforeExpiry || this is AdvanceToExactExpiry || this is AdvancePastExpiry

    fun describe(): String = when (this) {
        AdvanceToBeforeExpiry -> "AdvanceToBeforeExpiry"
        AdvanceToExactExpiry -> "AdvanceToExactExpiry"
        AdvancePastExpiry -> "AdvancePastExpiry"
        is ApproveCurrentVersion -> "ApproveCurrentVersion($actor)"
        is ApproveWrongVersion -> "ApproveWrongVersion($actor)"
        is DenyCurrentVersion -> "DenyCurrentVersion($actor)"
        is DenyWrongVersion -> "DenyWrongVersion($actor)"
        TimeoutCurrentVersion -> "TimeoutCurrentVersion"
        TimeoutWrongVersion -> "TimeoutWrongVersion"
        is ConsumeValid -> "ConsumeValid($worker)"
        is ConsumeWrongVersion -> "ConsumeWrongVersion($worker)"
        is ConsumeWrongToken -> "ConsumeWrongToken($worker)"
    }
}
