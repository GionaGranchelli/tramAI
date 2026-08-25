package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Epic 8.2b coverage guard: pins the fixed 32-seed corpus so a future
 * generator edit cannot silently delete a behavioral discriminator.
 *
 * Categories are recorded from the MODEL WALK (reachable pre-state +
 * action + outcome semantics), not from action-enum presence — e.g.
 * `late-claim-persists-expired` requires a PENDING pre-state at/after the
 * deadline whose claim failure NORMALIZES the record to EXPIRED, and
 * `exact-expiry-boundary` requires `now == expiresAt`, not merely
 * `now > expiresAt`.
 */
class ApprovalContinuationLifecycleActionGeneratorTest {

    private val t0: Instant = Instant.parse("2026-08-23T12:00:00Z")
    private val expiry: Instant = t0.plusSeconds(300)

    @Test
    fun `same seed produces exactly the same action trace`() {
        for (seed in 0L until ApprovalContinuationLifecycleActionGenerator.SEED_COUNT) {
            val first = ApprovalContinuationLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            val second = ApprovalContinuationLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            assertThat(first.map { it.describe() })
                .withFailMessage("seed $seed must be deterministic")
                .isEqualTo(second.map { it.describe() })
        }
    }

    @Test
    fun `fixed seed corpus covers every lifecycle category`() {
        val events = ArrayList<RecordedEvent>()
        for (seed in 0L until ApprovalContinuationLifecycleActionGenerator.SEED_COUNT) {
            var model = ApprovalContinuationLifecycleModel.pending(t0)
            val actions = ApprovalContinuationLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            actions.forEachIndexed { step, action ->
                val before = model
                val outcome = model.apply(action, expiry)
                record(events, seed, step, action, before, outcome)
                when (outcome) {
                    is ApprovalContinuationLifecycleOutcome.Success -> model = outcome.next
                    is ApprovalContinuationLifecycleOutcome.Failure -> model = outcome.next
                }
            }
        }

        val categories = events.map { it.category }.toSet()
        val expectedCategories = listOf(
            "valid-claim",
            "claim-wrong-version-while-pending",
            "second-claim-current-version-not-claimable",
            "second-claim-stale-version-conflict",
            "valid-cancel",
            "cancel-wrong-version-while-pending",
            "valid-expire",
            "exact-expiry-boundary",
            "early-expire-conflict",
            "get-lazy-expiry",
            "late-claim-persists-expired",
            "late-cancel-persists-expired",
            "valid-complete",
            "complete-wrong-version",
            "complete-wrong-actor",
            "complete-non-claimed-rejection",
            "valid-force-cancel",
            "force-cancel-wrong-version",
            "force-cancel-non-claimed-rejection",
            "completed-terminal-stable",
            "cancelled-terminal-stable",
            "expired-terminal-stable",
            "cancelled-uncertain-terminal-stable",
            "expire-wrong-version-while-pending",
            "expire-wrong-version-after-expiry",
        )
        val missing = expectedCategories.filterNot { it in categories }
        assertThat(missing)
            .withFailMessage {
                val available = categories.sorted().joinToString(", ")
                "corpus missing categories: $missing\navailable: $available\n" +
                    "total events: ${events.size}, seeds: ${ApprovalContinuationLifecycleActionGenerator.SEED_COUNT}"
            }
            .isEmpty()

        // Exactly-once argument release: within every seed the model may
        // release the raw arguments at most once, and the forced claim-path
        // archetypes (seed % 4 == 0) release exactly once each.
        val releasesPerSeed = events.filter { it.category == "argument-release" }.groupingBy { it.seed }.eachCount()
        assertThat(releasesPerSeed.values.all { it == 1 })
            .withFailMessage("a seed must never release arguments twice: $releasesPerSeed")
            .isTrue()
        assertThat(releasesPerSeed.size)
            .withFailMessage("every claim-archetype seed must release arguments exactly once")
            .isEqualTo((0L until ApprovalContinuationLifecycleActionGenerator.SEED_COUNT).count { it % 4L == 0L })
    }

    @Test
    fun `corpus reaches every lifecycle status`() {
        val statuses = HashSet<ApprovalContinuationStatus>()
        for (seed in 0L until ApprovalContinuationLifecycleActionGenerator.SEED_COUNT) {
            var model = ApprovalContinuationLifecycleModel.pending(t0)
            val actions = ApprovalContinuationLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            actions.forEach { action ->
                val outcome = model.apply(action, expiry)
                when (outcome) {
                    is ApprovalContinuationLifecycleOutcome.Success -> model = outcome.next
                    is ApprovalContinuationLifecycleOutcome.Failure -> model = outcome.next
                }
                statuses += model.status
            }
        }
        assertThat(statuses)
            .withFailMessage("corpus must reach every lifecycle status; found=$statuses")
            .containsExactlyInAnyOrder(
                ApprovalContinuationStatus.PENDING,
                ApprovalContinuationStatus.CLAIMED,
                ApprovalContinuationStatus.COMPLETED,
                ApprovalContinuationStatus.EXPIRED,
                ApprovalContinuationStatus.CANCELLED,
                ApprovalContinuationStatus.CANCELLED_UNCERTAIN,
            )
    }

    // ── category recording ───────────────────────────────────────────

    private fun record(
        events: MutableList<RecordedEvent>,
        seed: Long,
        step: Int,
        action: ApprovalContinuationLifecycleAction,
        before: ApprovalContinuationLifecycleModel,
        outcome: ApprovalContinuationLifecycleOutcome,
    ) {
        fun add(category: String) = events.add(RecordedEvent(category, seed, step, action, outcome))

        fun terminalStable() {
            when (before.status) {
                ApprovalContinuationStatus.COMPLETED -> add("completed-terminal-stable")
                ApprovalContinuationStatus.CANCELLED -> add("cancelled-terminal-stable")
                ApprovalContinuationStatus.EXPIRED -> add("expired-terminal-stable")
                ApprovalContinuationStatus.CANCELLED_UNCERTAIN -> add("cancelled-uncertain-terminal-stable")
                else -> Unit
            }
        }

        fun exactBoundary() {
            if (before.status == ApprovalContinuationStatus.PENDING && before.now == expiry) {
                add("exact-expiry-boundary")
            }
        }

        val failed = outcome as? ApprovalContinuationLifecycleOutcome.Failure
        val succeeded = outcome as? ApprovalContinuationLifecycleOutcome.Success

        when (action) {
            is ApprovalContinuationLifecycleAction.AdvanceToBeforeExpiry,
            is ApprovalContinuationLifecycleAction.AdvanceToExactExpiry,
            is ApprovalContinuationLifecycleAction.AdvancePastExpiry,
            -> Unit

            is ApprovalContinuationLifecycleAction.Get -> {
                if (before.status == ApprovalContinuationStatus.PENDING && before.now >= expiry) {
                    add("get-lazy-expiry")
                    exactBoundary()
                }
            }

            is ApprovalContinuationLifecycleAction.ClaimCurrentVersion -> when {
                succeeded != null -> {
                    add("valid-claim")
                    if (succeeded.releasedArguments) add("argument-release")
                    exactBoundary()
                }
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE -> when (before.status) {
                    ApprovalContinuationStatus.PENDING -> {
                        if (before.now >= expiry) {
                            add("late-claim-persists-expired")
                            exactBoundary()
                        }
                    }
                    ApprovalContinuationStatus.CLAIMED -> add("second-claim-current-version-not-claimable")
                    else -> terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.ClaimWrongVersion -> when {
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT -> when (before.status) {
                    ApprovalContinuationStatus.PENDING -> if (before.now < expiry) add("claim-wrong-version-while-pending")
                    ApprovalContinuationStatus.CLAIMED -> add("second-claim-stale-version-conflict")
                    else -> terminalStable()
                }
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE -> when (before.status) {
                    ApprovalContinuationStatus.PENDING -> {
                        add("late-claim-persists-expired")
                        exactBoundary()
                    }
                    else -> terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.CancelCurrentVersion -> when {
                succeeded != null -> add("valid-cancel")
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT -> when (before.status) {
                    ApprovalContinuationStatus.PENDING -> {
                        if (before.now >= expiry) {
                            add("late-cancel-persists-expired")
                            exactBoundary()
                        }
                    }
                    else -> terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.CancelWrongVersion -> if (failed != null &&
                failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT
            ) {
                when (before.status) {
                    ApprovalContinuationStatus.PENDING -> if (before.now < expiry) add("cancel-wrong-version-while-pending")
                    else -> terminalStable()
                }
            }

            is ApprovalContinuationLifecycleAction.ExpireCurrentVersion -> when {
                succeeded != null -> {
                    add("valid-expire")
                    exactBoundary()
                }
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT -> when (before.status) {
                    ApprovalContinuationStatus.PENDING -> if (before.now < expiry) add("early-expire-conflict")
                    else -> terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.ExpireWrongVersion -> if (failed != null &&
                failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT
            ) {
                when (before.status) {
                    ApprovalContinuationStatus.PENDING -> {
                        if (before.now < expiry) add("expire-wrong-version-while-pending")
                        if (before.now >= expiry) add("expire-wrong-version-after-expiry")
                    }
                    else -> terminalStable()
                }
            }

            is ApprovalContinuationLifecycleAction.CompleteCurrentVersion -> when {
                succeeded != null -> add("valid-complete")
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.NOT_COMPLETABLE -> {
                    if (before.status != ApprovalContinuationStatus.CLAIMED) add("complete-non-claimed-rejection")
                    terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.CompleteWrongVersion -> if (failed != null &&
                failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT
            ) {
                when (before.status) {
                    ApprovalContinuationStatus.CLAIMED -> add("complete-wrong-version")
                    else -> terminalStable()
                }
            }

            is ApprovalContinuationLifecycleAction.CompleteWrongActor -> if (failed != null &&
                failed.kind == ApprovalContinuationLifecycleFailureKind.NOT_COMPLETABLE
            ) {
                if (before.status == ApprovalContinuationStatus.CLAIMED) add("complete-wrong-actor")
                if (before.status != ApprovalContinuationStatus.CLAIMED) add("complete-non-claimed-rejection")
                terminalStable()
            }

            is ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion -> when {
                succeeded != null -> add("valid-force-cancel")
                failed != null && failed.kind == ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE -> {
                    if (before.status != ApprovalContinuationStatus.CLAIMED) add("force-cancel-non-claimed-rejection")
                    terminalStable()
                }
                else -> Unit
            }

            is ApprovalContinuationLifecycleAction.ForceCancelWrongVersion -> if (failed != null &&
                failed.kind == ApprovalContinuationLifecycleFailureKind.CONFLICT
            ) {
                when (before.status) {
                    ApprovalContinuationStatus.CLAIMED -> add("force-cancel-wrong-version")
                    else -> terminalStable()
                }
            }
        }
    }
}

private data class RecordedEvent(
    val category: String,
    val seed: Long,
    val step: Int,
    val action: ApprovalContinuationLifecycleAction,
    val outcome: ApprovalContinuationLifecycleOutcome,
)
