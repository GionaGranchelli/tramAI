package dev.tramai.testing.persistence.lease

/**
 * Pure lease-lifecycle oracle. Tokens are symbolic so this model never
 * depends on a store-generated UUID or calls production code.
 */
internal data class WorkflowLeaseLifecycleModel(
    val now: Long,
    val generation: Long,
    val current: ModeledLease?,
    val predecessors: List<ModeledLease>,
    val hasOlderSnapshot: Boolean,
) {

    companion object {
        fun absent(now: Long): WorkflowLeaseLifecycleModel = WorkflowLeaseLifecycleModel(
            now = now,
            generation = 0,
            current = null,
            predecessors = emptyList(),
            hasOlderSnapshot = false,
        )
    }

    fun withNow(now: Long): WorkflowLeaseLifecycleModel = copy(now = now).normalizeExpired()

    fun apply(
        action: WorkflowLeaseLifecycleAction,
        durationMillis: Long,
    ): WorkflowLeaseLifecycleOutcome {
        val normalized = normalizeExpired()
        return when (action) {
            is WorkflowLeaseLifecycleAction.Claim -> normalized.claim(action, durationMillis)
            is WorkflowLeaseLifecycleAction.RenewCurrent -> normalized.renew(action.checkpointRevision, durationMillis)
            is WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot ->
                normalized.renew(action.checkpointRevision, durationMillis)
            is WorkflowLeaseLifecycleAction.RenewStalePredecessor,
            is WorkflowLeaseLifecycleAction.RenewWrongOwner,
            is WorkflowLeaseLifecycleAction.RenewForgedToken,
            -> WorkflowLeaseLifecycleOutcome.Failure(WorkflowLeaseLifecycleFailureKind.CONFLICT)
            WorkflowLeaseLifecycleAction.ReleaseCurrent,
            WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot,
            -> normalized.releaseCurrent()
            is WorkflowLeaseLifecycleAction.ReleaseStalePredecessor,
            is WorkflowLeaseLifecycleAction.ReleaseWrongOwner,
            is WorkflowLeaseLifecycleAction.ReleaseForgedToken,
            -> if (normalized.current == null) {
                WorkflowLeaseLifecycleOutcome.NoOp(normalized)
            } else {
                WorkflowLeaseLifecycleOutcome.Failure(WorkflowLeaseLifecycleFailureKind.CONFLICT)
            }
            WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry -> normalized.advanceBeforeExpiry()
            WorkflowLeaseLifecycleAction.AdvanceToExactExpiry -> normalized.advanceToExactExpiry()
            WorkflowLeaseLifecycleAction.AdvancePastExpiry -> normalized.advancePastExpiry()
            WorkflowLeaseLifecycleAction.ObserveCurrent -> WorkflowLeaseLifecycleOutcome.Success(normalized)
        }
    }

    private fun claim(
        action: WorkflowLeaseLifecycleAction.Claim,
        durationMillis: Long,
    ): WorkflowLeaseLifecycleOutcome {
        if (current != null) return WorkflowLeaseLifecycleOutcome.Failure(WorkflowLeaseLifecycleFailureKind.CONFLICT)
        val nextGeneration = generation + 1
        return WorkflowLeaseLifecycleOutcome.Success(
            copy(
                generation = nextGeneration,
                current = ModeledLease(
                    generation = nextGeneration,
                    symbolicToken = "T$nextGeneration",
                    ownerId = action.ownerId,
                    checkpointRevision = action.checkpointRevision,
                    acquiredAtEpochMillis = now,
                    expiresAtEpochMillis = now + durationMillis,
                ),
                hasOlderSnapshot = false,
            ),
        )
    }

    private fun renew(
        checkpointRevision: Long?,
        durationMillis: Long,
    ): WorkflowLeaseLifecycleOutcome {
        val active = current
            ?: return WorkflowLeaseLifecycleOutcome.Failure(WorkflowLeaseLifecycleFailureKind.CONFLICT)
        val renewed = active.copy(
            checkpointRevision = checkpointRevision,
            expiresAtEpochMillis = now + durationMillis,
        )
        check(renewed.generation == active.generation)
        check(renewed.symbolicToken == active.symbolicToken)
        check(renewed.ownerId == active.ownerId)
        check(renewed.acquiredAtEpochMillis == active.acquiredAtEpochMillis)
        return WorkflowLeaseLifecycleOutcome.Success(copy(current = renewed, hasOlderSnapshot = true))
    }

    private fun releaseCurrent(): WorkflowLeaseLifecycleOutcome {
        val active = current ?: return WorkflowLeaseLifecycleOutcome.NoOp(this)
        return WorkflowLeaseLifecycleOutcome.Success(
            copy(
                current = null,
                predecessors = predecessors + active,
                hasOlderSnapshot = false,
            ),
        )
    }

    private fun advanceBeforeExpiry(): WorkflowLeaseLifecycleOutcome.Success {
        val active = current
        val target = if (active == null) now + 1 else maxOf(now, active.expiresAtEpochMillis - 1)
        return WorkflowLeaseLifecycleOutcome.Success(copy(now = target).normalizeExpired())
    }

    private fun advanceToExactExpiry(): WorkflowLeaseLifecycleOutcome.Success {
        val active = current
        val target = if (active == null) now + 1 else maxOf(now, active.expiresAtEpochMillis)
        return WorkflowLeaseLifecycleOutcome.Success(copy(now = target).normalizeExpired())
    }

    private fun advancePastExpiry(): WorkflowLeaseLifecycleOutcome.Success {
        val active = current
        val target = if (active == null) now + 1 else maxOf(now, active.expiresAtEpochMillis + 1)
        return WorkflowLeaseLifecycleOutcome.Success(copy(now = target).normalizeExpired())
    }

    private fun normalizeExpired(): WorkflowLeaseLifecycleModel {
        val active = current ?: return this
        if (now < active.expiresAtEpochMillis) return this
        return copy(
            current = null,
            predecessors = predecessors + active,
            hasOlderSnapshot = false,
        )
    }

    /** Returns every structural invariant violation; empty means legal. */
    fun invariants(): List<String> {
        val violations = ArrayList<String>()
        val leases = predecessors + listOfNotNull(current)
        if (generation < 0) violations += "generation $generation < 0"
        if (current != null && now >= current.expiresAtEpochMillis) {
            violations += "current ${current.symbolicToken} is expired at now=$now"
        }
        if (leases.any { it.generation > generation }) {
            violations += "lease generation exceeds claim count $generation"
        }
        if (current != null && current.generation != generation) {
            violations += "current generation ${current.generation} differs from claim count $generation"
        }
        val duplicateTokens = leases.groupBy { it.symbolicToken }.filterValues { it.size > 1 }.keys
        if (duplicateTokens.isNotEmpty()) violations += "duplicate tokens $duplicateTokens"
        val duplicateGenerations = leases.groupBy { it.generation }.filterValues { it.size > 1 }.keys
        if (duplicateGenerations.isNotEmpty()) violations += "duplicate generations $duplicateGenerations"
        if (current != null && predecessors.any { it.symbolicToken == current.symbolicToken }) {
            violations += "current token ${current.symbolicToken} is also a predecessor"
        }
        if (leases.any { it.symbolicToken != "T${it.generation}" }) {
            violations += "symbolic token does not match its generation"
        }
        if (hasOlderSnapshot && current == null) {
            violations += "older snapshot marker requires a renewed current lease"
        }
        return violations
    }

    fun describe(): String =
        "now=$now generation=$generation current=${current?.describe()} " +
            "predecessors=${predecessors.joinToString(prefix = "[", postfix = "]") { it.describe() }} " +
            "hasOlderSnapshot=$hasOlderSnapshot"
}

internal data class ModeledLease(
    val generation: Long,
    val symbolicToken: String,
    val ownerId: String,
    val checkpointRevision: Long?,
    val acquiredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    fun describe(): String =
        "$symbolicToken(owner=$ownerId, revision=$checkpointRevision, acquired=$acquiredAtEpochMillis, " +
            "expires=$expiresAtEpochMillis)"
}

internal enum class WorkflowLeaseLifecycleFailureKind {
    CONFLICT,
}

internal sealed interface WorkflowLeaseLifecycleOutcome {
    data class Success(val next: WorkflowLeaseLifecycleModel) : WorkflowLeaseLifecycleOutcome
    data class NoOp(val next: WorkflowLeaseLifecycleModel) : WorkflowLeaseLifecycleOutcome
    data class Failure(val kind: WorkflowLeaseLifecycleFailureKind) : WorkflowLeaseLifecycleOutcome
}

internal sealed interface WorkflowLeaseLifecycleAction {
    data class Claim(val ownerId: String, val checkpointRevision: Long?) : WorkflowLeaseLifecycleAction
    data class RenewCurrent(val checkpointRevision: Long?) : WorkflowLeaseLifecycleAction
    data class RenewCurrentOldSnapshot(val checkpointRevision: Long?) : WorkflowLeaseLifecycleAction
    data class RenewStalePredecessor(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data class RenewWrongOwner(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data class RenewForgedToken(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data object ReleaseCurrent : WorkflowLeaseLifecycleAction
    data object ReleaseCurrentOldSnapshot : WorkflowLeaseLifecycleAction
    data class ReleaseStalePredecessor(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data class ReleaseWrongOwner(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data class ReleaseForgedToken(val targetGeneration: Long) : WorkflowLeaseLifecycleAction
    data object AdvanceBeforeExpiry : WorkflowLeaseLifecycleAction
    data object AdvanceToExactExpiry : WorkflowLeaseLifecycleAction
    data object AdvancePastExpiry : WorkflowLeaseLifecycleAction
    data object ObserveCurrent : WorkflowLeaseLifecycleAction

    fun describe(): String = when (this) {
        is Claim -> "Claim(owner=$ownerId, revision=$checkpointRevision)"
        is RenewCurrent -> "RenewCurrent(revision=$checkpointRevision)"
        is RenewCurrentOldSnapshot -> "RenewCurrentOldSnapshot(revision=$checkpointRevision)"
        is RenewStalePredecessor -> "RenewStalePredecessor(generation=$targetGeneration)"
        is RenewWrongOwner -> "RenewWrongOwner(generation=$targetGeneration)"
        is RenewForgedToken -> "RenewForgedToken(generation=$targetGeneration)"
        ReleaseCurrent -> "ReleaseCurrent"
        ReleaseCurrentOldSnapshot -> "ReleaseCurrentOldSnapshot"
        is ReleaseStalePredecessor -> "ReleaseStalePredecessor(generation=$targetGeneration)"
        is ReleaseWrongOwner -> "ReleaseWrongOwner(generation=$targetGeneration)"
        is ReleaseForgedToken -> "ReleaseForgedToken(generation=$targetGeneration)"
        AdvanceBeforeExpiry -> "AdvanceBeforeExpiry"
        AdvanceToExactExpiry -> "AdvanceToExactExpiry"
        AdvancePastExpiry -> "AdvancePastExpiry"
        ObserveCurrent -> "ObserveCurrent"
    }
}
