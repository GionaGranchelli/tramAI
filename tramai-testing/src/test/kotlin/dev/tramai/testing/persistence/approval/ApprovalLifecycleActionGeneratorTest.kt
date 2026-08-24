package dev.tramai.testing.persistence.approval

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.2a coverage guard for the deterministic action corpus.
 *
 * Randomized-looking property suites are worthless if the fixed seed corpus
 * never reaches difficult states. This test executes the exact 32-seed
 * corpus and proves it contains every important lifecycle category, and that
 * the same seed always yields the same action trace — so future generator
 * edits cannot silently delete behavioral coverage.
 */
class ApprovalLifecycleActionGeneratorTest {

    private val t0: Instant = Instant.parse("2026-08-22T12:00:00Z")
    private val expiry: Instant = t0.plusSeconds(600)

    private data class RecordedEvent(
        val category: String,
        val seed: Long,
        val step: Int,
        val action: ApprovalLifecycleAction,
        val outcome: ApprovalLifecycleOutcome,
    )

    @Test
    fun `same seed produces exactly the same action trace`() {
        for (seed in 0L until ApprovalLifecycleActionGenerator.SEED_COUNT) {
            val first = ApprovalLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            val second = ApprovalLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            assertThat(first.map { it.describe() })
                .withFailMessage("seed $seed must be deterministic")
                .isEqualTo(second.map { it.describe() })
        }
    }

    @Test
    fun `fixed seed corpus covers every lifecycle category`() {
        val events = ArrayList<RecordedEvent>()
        for (seed in 0L until ApprovalLifecycleActionGenerator.SEED_COUNT) {
            var model = ApprovalLifecycleModel.pending(t0)
            val actions = ApprovalLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            actions.forEachIndexed { step, action ->
                val before = model
                val outcome = model.apply(action, expiry)
                record(events, seed, step, action, before, outcome)
                if (outcome is ApprovalLifecycleOutcome.Success) {
                    model = outcome.next
                }
            }
        }

        val categories = events.map { it.category }.toSet()
        val expectedCategories = listOf(
            "valid-approve",
            "valid-deny",
            "valid-timeout",
            "early-timeout-rejection",
            "approve-after-expiry-rejection",
            "deny-after-expiry-rejection",
            "wrong-version-conflict",
            "transition-wrong-version-while-pending",
            "post-terminal-rejection",
            "fresh-consume",
            "expired-fresh-consume-rejection",
            "wrong-token-rejection",
            "exact-replay",
            "exact-replay-after-expiry",
            "wrong-actor-replay-rejection",
            "wrong-version-replay-rejection",
            "fresh-consume-wrong-version-rejection",
            "exact-expiry-boundary",
        )
        val missing = expectedCategories.filterNot { it in categories }
        assertThat(missing)
            .withFailMessage {
                val available = categories.sorted().joinToString(", ")
                "corpus missing categories: $missing\navailable: $available\n" +
                    "total events: ${events.size}, seeds: ${ApprovalLifecycleActionGenerator.SEED_COUNT}"
            }
            .isEmpty()
    }

    @Test
    fun `corpus contains both terminal outcomes and the full decision lattice`() {
        val statuses = HashSet<dev.tramai.core.approval.ApprovalStatus>()
        for (seed in 0L until ApprovalLifecycleActionGenerator.SEED_COUNT) {
            var model = ApprovalLifecycleModel.pending(t0)
            val actions = ApprovalLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            actions.forEachIndexed { step, action ->
                val outcome = model.apply(action, expiry)
                if (outcome is ApprovalLifecycleOutcome.Success) {
                    model = outcome.next
                    statuses += outcome.next.status
                }
            }
        }
        assertThat(statuses)
            .withFailMessage("corpus must reach every lifecycle status; found=$statuses")
            .containsExactlyInAnyOrder(
                dev.tramai.core.approval.ApprovalStatus.PENDING,
                dev.tramai.core.approval.ApprovalStatus.APPROVED,
                dev.tramai.core.approval.ApprovalStatus.DENIED,
                dev.tramai.core.approval.ApprovalStatus.TIMED_OUT,
            )
    }

    // ── category recording ───────────────────────────────────────────

    private fun record(
        events: MutableList<RecordedEvent>,
        seed: Long,
        step: Int,
        action: ApprovalLifecycleAction,
        before: ApprovalLifecycleModel,
        outcome: ApprovalLifecycleOutcome,
    ) {
        fun add(category: String) = events.add(RecordedEvent(category, seed, step, action, outcome))
        when (action) {
            is ApprovalLifecycleAction.ApproveCurrentVersion -> when (outcome) {
                is ApprovalLifecycleOutcome.Success -> {
                    add("valid-approve")
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now == expiry) {
                        add("exact-expiry-boundary")
                    }
                }
                is ApprovalLifecycleOutcome.Failure -> {
                    // Genuine after-expiry rejection requires the PENDING
                    // pre-state: a decision on a terminal status is a
                    // post-terminal rejection, not an expiry rejection.
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now >= expiry) {
                        add("approve-after-expiry-rejection")
                    }
                    // Exact-equality (now == expiresAt) must be reached, not
                    // just now > expiresAt — a future generator edit that
                    // silently loses exact-equality would make the M2
                    // boundary mutation weak again.
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now == expiry) {
                        add("exact-expiry-boundary")
                    }
                    if (before.status != dev.tramai.core.approval.ApprovalStatus.PENDING) add("post-terminal-rejection")
                }
            }
            is ApprovalLifecycleAction.DenyCurrentVersion -> when (outcome) {
                is ApprovalLifecycleOutcome.Success -> {
                    add("valid-deny")
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now == expiry) {
                        add("exact-expiry-boundary")
                    }
                }
                is ApprovalLifecycleOutcome.Failure -> {
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now >= expiry) {
                        add("deny-after-expiry-rejection")
                    }
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now == expiry) {
                        add("exact-expiry-boundary")
                    }
                    if (before.status != dev.tramai.core.approval.ApprovalStatus.PENDING) add("post-terminal-rejection")
                }
            }
            is ApprovalLifecycleAction.TimeoutCurrentVersion -> when (outcome) {
                is ApprovalLifecycleOutcome.Success -> {
                    add("valid-timeout")
                    // Timeout is legal at exact expiry (now == expiresAt) —
                    // the boundary where the M2 `>=` vs `>` mutation differs.
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now == expiry) {
                        add("exact-expiry-boundary")
                    }
                }
                is ApprovalLifecycleOutcome.Failure -> {
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING && before.now < expiry) {
                        add("early-timeout-rejection")
                    }
                    if (before.status != dev.tramai.core.approval.ApprovalStatus.PENDING) add("post-terminal-rejection")
                }
            }
            is ApprovalLifecycleAction.ApproveWrongVersion,
            is ApprovalLifecycleAction.DenyWrongVersion,
            is ApprovalLifecycleAction.TimeoutWrongVersion,
            -> {
                if (outcome is ApprovalLifecycleOutcome.Failure && outcome.kind == ApprovalLifecycleFailureKind.CONFLICT) {
                    add("wrong-version-conflict")
                }
                if (before.status == dev.tramai.core.approval.ApprovalStatus.PENDING) {
                    // Semantic category: a wrong-version DECISION actually
                    // evaluated against the PENDING pre-state — the only
                    // state where the transition version guard is the
                    // discriminator. Syntactic presence of the action enum is
                    // not sufficient evidence.
                    add("transition-wrong-version-while-pending")
                }
            }
            is ApprovalLifecycleAction.ConsumeValid -> when (outcome) {
                is ApprovalLifecycleOutcome.Success -> {
                    if (outcome.replayed) {
                        add("exact-replay")
                        if (before.now >= expiry) add("exact-replay-after-expiry")
                    } else {
                        add("fresh-consume")
                    }
                }
                is ApprovalLifecycleOutcome.Failure -> {
                    if (before.consumedAt != null && before.consumedBy != action.worker) add("wrong-actor-replay-rejection")
                    // Genuine expired-fresh rejection requires the APPROVED
                    // pre-state: a consume on TIMED_OUT/DENIED is a status
                    // rejection, not an expiry rejection.
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.APPROVED &&
                        before.consumedAt == null && before.now >= expiry
                    ) {
                        add("expired-fresh-consume-rejection")
                    }
                }
            }
            is ApprovalLifecycleAction.ConsumeWrongVersion -> when (outcome) {
                is ApprovalLifecycleOutcome.Failure -> {
                    add("wrong-version-conflict")
                    if (before.consumedAt != null) add("wrong-version-replay-rejection")
                    // Fresh-consumption version guard (M10): APPROVED +
                    // unconsumed + wrong expected version -> CONFLICT. Not
                    // implied by wrong-version-conflict (decisions) nor by
                    // wrong-version-replay-rejection (consumed state).
                    if (before.status == dev.tramai.core.approval.ApprovalStatus.APPROVED && before.consumedAt == null) {
                        add("fresh-consume-wrong-version-rejection")
                    }
                }
                is ApprovalLifecycleOutcome.Success -> Unit
            }
            is ApprovalLifecycleAction.ConsumeWrongToken -> if (outcome is ApprovalLifecycleOutcome.Failure &&
                outcome.kind == ApprovalLifecycleFailureKind.TOKEN_REJECTED
            ) {
                add("wrong-token-rejection")
            }
            else -> Unit // advances
        }
    }
}
