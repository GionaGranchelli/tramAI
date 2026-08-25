package dev.tramai.testing.persistence.lease

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowLeaseLifecycleActionGeneratorTest {

    private val initialNow = 1_800_000_000_000L

    @Test
    fun `same seed produces exactly the same lease action trace`() {
        for (seed in 0L until WorkflowLeaseLifecycleActionGenerator.SEED_COUNT) {
            val first = WorkflowLeaseLifecycleActionGenerator.generate(seed, initialNow = initialNow)
            val second = WorkflowLeaseLifecycleActionGenerator.generate(seed, initialNow = initialNow)
            assertThat(first.map { it.describe() })
                .withFailMessage("seed $seed must be deterministic")
                .isEqualTo(second.map { it.describe() })
        }
    }

    @Test
    fun `fixed seed corpus covers every lease lifecycle category`() {
        val coverage = recordCorpus()
        val expected = setOf(
            "fresh-claim",
            "active-competing-claim-different-owner",
            "active-competing-claim-same-owner",
            "renew-current",
            "multiple-renewals-same-generation",
            "release-current",
            "claim-after-release",
            "before-expiry-active",
            "exact-expiry-transition",
            "past-expiry-transition",
            "different-owner-takeover",
            "same-owner-takeover",
            "stale-predecessor-renew",
            "stale-predecessor-release",
            "old-same-token-snapshot-renew",
            "old-same-token-snapshot-release",
            "wrong-owner-renew",
            "wrong-owner-release",
            "forged-token-renew",
            "forged-token-release",
            "checkpoint-revision-null-to-non-null",
            "checkpoint-revision-non-null-to-null",
            "three-plus-generations",
        )
        val missing = expected - coverage.categories
        assertThat(missing)
            .withFailMessage {
                "corpus missing categories: ${missing.sorted()}\n" +
                    "available: ${coverage.categories.sorted()}\n" +
                    "events: ${coverage.eventCount}, seeds: ${WorkflowLeaseLifecycleActionGenerator.SEED_COUNT}"
            }
            .isEmpty()
    }

    @Test
    fun `corpus reaches three generations revision reversals and both takeover owner kinds`() {
        val coverage = recordCorpus()
        assertThat(coverage.maxGeneration)
            .withFailMessage("corpus must reach at least three lease generations")
            .isGreaterThanOrEqualTo(3)
        assertThat(coverage.revisionDirections)
            .containsExactlyInAnyOrder("null-to-non-null", "non-null-to-null")
        assertThat(coverage.takeoverOwnerKinds)
            .containsExactlyInAnyOrder("same-owner", "different-owner")
    }

    private fun recordCorpus(): Coverage {
        val coverage = Coverage()
        for (seed in 0L until WorkflowLeaseLifecycleActionGenerator.SEED_COUNT) {
            var model = WorkflowLeaseLifecycleModel.absent(initialNow)
            var vacated: VacatedLease? = null
            val actions = WorkflowLeaseLifecycleActionGenerator.generate(seed, initialNow = initialNow)
            actions.forEachIndexed { step, action ->
                val before = model
                val outcome = model.apply(action, DURATION_MILLIS)
                vacated = record(coverage, seed, step, action, before, outcome, vacated)
                model = when (outcome) {
                    is WorkflowLeaseLifecycleOutcome.Success -> outcome.next
                    is WorkflowLeaseLifecycleOutcome.NoOp -> outcome.next
                    is WorkflowLeaseLifecycleOutcome.Failure -> model
                }
                coverage.maxGeneration = maxOf(coverage.maxGeneration, model.generation)
                if (model.generation >= 3) coverage.categories += "three-plus-generations"
            }
        }
        return coverage
    }

    private fun record(
        coverage: Coverage,
        seed: Long,
        step: Int,
        action: WorkflowLeaseLifecycleAction,
        before: WorkflowLeaseLifecycleModel,
        outcome: WorkflowLeaseLifecycleOutcome,
        previousVacated: VacatedLease?,
    ): VacatedLease? {
        coverage.eventCount++
        fun add(category: String) {
            coverage.categories += category
        }

        var vacated = previousVacated
        when (action) {
            is WorkflowLeaseLifecycleAction.Claim -> when (outcome) {
                is WorkflowLeaseLifecycleOutcome.Success -> {
                    if (before.generation == 0L) add("fresh-claim")
                    when (vacated?.kind) {
                        VacatedKind.RELEASED -> add("claim-after-release")
                        VacatedKind.EXPIRED -> {
                            val kind = if (vacated.ownerId == action.ownerId) "same-owner" else "different-owner"
                            coverage.takeoverOwnerKinds += kind
                            add("$kind-takeover")
                        }
                        null -> Unit
                    }
                    vacated = null
                }
                is WorkflowLeaseLifecycleOutcome.Failure -> {
                    check(before.current != null) { "seed $seed step $step active claim must have current lease" }
                    add(
                        if (before.current.ownerId == action.ownerId) {
                            "active-competing-claim-same-owner"
                        } else {
                            "active-competing-claim-different-owner"
                        },
                    )
                }
                is WorkflowLeaseLifecycleOutcome.NoOp -> error("claim cannot be a no-op")
            }
            is WorkflowLeaseLifecycleAction.RenewCurrent -> {
                if (outcome is WorkflowLeaseLifecycleOutcome.Success) {
                    add("renew-current")
                    if (before.hasOlderSnapshot) add("multiple-renewals-same-generation")
                    recordRevisionDirection(coverage, before.current?.checkpointRevision, action.checkpointRevision)
                }
            }
            is WorkflowLeaseLifecycleAction.RenewCurrentOldSnapshot -> {
                if (outcome is WorkflowLeaseLifecycleOutcome.Success) {
                    add("old-same-token-snapshot-renew")
                    if (before.hasOlderSnapshot) add("multiple-renewals-same-generation")
                    recordRevisionDirection(coverage, before.current?.checkpointRevision, action.checkpointRevision)
                }
            }
            is WorkflowLeaseLifecycleAction.RenewStalePredecessor -> add("stale-predecessor-renew")
            is WorkflowLeaseLifecycleAction.RenewWrongOwner -> add("wrong-owner-renew")
            is WorkflowLeaseLifecycleAction.RenewForgedToken -> add("forged-token-renew")
            WorkflowLeaseLifecycleAction.ReleaseCurrent -> if (outcome is WorkflowLeaseLifecycleOutcome.Success) {
                add("release-current")
                vacated = VacatedLease(requireNotNull(before.current).ownerId, VacatedKind.RELEASED)
            }
            WorkflowLeaseLifecycleAction.ReleaseCurrentOldSnapshot ->
                if (outcome is WorkflowLeaseLifecycleOutcome.Success) {
                    add("release-current")
                    add("old-same-token-snapshot-release")
                    vacated = VacatedLease(requireNotNull(before.current).ownerId, VacatedKind.RELEASED)
                }
            is WorkflowLeaseLifecycleAction.ReleaseStalePredecessor -> add("stale-predecessor-release")
            is WorkflowLeaseLifecycleAction.ReleaseWrongOwner -> add("wrong-owner-release")
            is WorkflowLeaseLifecycleAction.ReleaseForgedToken -> add("forged-token-release")
            WorkflowLeaseLifecycleAction.AdvanceBeforeExpiry -> {
                val next = (outcome as WorkflowLeaseLifecycleOutcome.Success).next
                if (before.current != null && next.current != null && next.now < next.current.expiresAtEpochMillis) {
                    add("before-expiry-active")
                }
            }
            WorkflowLeaseLifecycleAction.AdvanceToExactExpiry -> {
                val active = before.current
                val next = (outcome as WorkflowLeaseLifecycleOutcome.Success).next
                if (active != null && next.now == active.expiresAtEpochMillis && next.current == null) {
                    add("exact-expiry-transition")
                    vacated = VacatedLease(active.ownerId, VacatedKind.EXPIRED)
                }
            }
            WorkflowLeaseLifecycleAction.AdvancePastExpiry -> {
                val active = before.current
                val next = (outcome as WorkflowLeaseLifecycleOutcome.Success).next
                if (active != null && next.now > active.expiresAtEpochMillis && next.current == null) {
                    add("past-expiry-transition")
                    vacated = VacatedLease(active.ownerId, VacatedKind.EXPIRED)
                }
            }
            WorkflowLeaseLifecycleAction.ObserveCurrent -> Unit
        }
        return vacated
    }

    private fun recordRevisionDirection(
        coverage: Coverage,
        before: Long?,
        after: Long?,
    ) {
        when {
            before == null && after != null -> {
                coverage.revisionDirections += "null-to-non-null"
                coverage.categories += "checkpoint-revision-null-to-non-null"
            }
            before != null && after == null -> {
                coverage.revisionDirections += "non-null-to-null"
                coverage.categories += "checkpoint-revision-non-null-to-null"
            }
        }
    }

    private class Coverage(
        val categories: MutableSet<String> = linkedSetOf(),
        val revisionDirections: MutableSet<String> = linkedSetOf(),
        val takeoverOwnerKinds: MutableSet<String> = linkedSetOf(),
        var maxGeneration: Long = 0,
        var eventCount: Int = 0,
    )

    private data class VacatedLease(val ownerId: String, val kind: VacatedKind)

    private enum class VacatedKind {
        EXPIRED,
        RELEASED,
    }

    private companion object {
        const val DURATION_MILLIS: Long = 1_000
    }
}
