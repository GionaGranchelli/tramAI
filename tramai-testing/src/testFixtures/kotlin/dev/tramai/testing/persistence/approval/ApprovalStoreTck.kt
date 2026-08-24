package dev.tramai.testing.persistence.approval

import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Epic 8.1a shared ApprovalStore compatibility contract.
 *
 * Every [ApprovalStore] implementation must satisfy exactly the same
 * externally observable approval persistence contract, regardless of storage
 * technology (memory, encrypted files, JDBC). This TCK tests the SPI, not
 * implementation internals.
 *
 * Each test gets a FRESH store from [harness] wired to a [MutableClock] fixed
 * at T0. Time advances only via [MutableClock.advance] — never real sleeps.
 */
abstract class ApprovalStoreTck {

    abstract val harness: ApprovalStoreTckHarness

    protected lateinit var store: ApprovalStore
    protected lateinit var clock: MutableClock

    protected val t0: Instant = Instant.parse("2026-08-22T12:00:00Z")
    protected val expiry: Instant = t0.plusSeconds(600)

    @BeforeEach
    fun setUp() {
        clock = MutableClock(t0)
        store = harness.createStore(clock)
    }

    @AfterEach
    fun tearDown() = runBlocking<Unit> { harness.closeStore(store) }

    // ── Creation / read ─────────────────────────────────────────────

    @Test
    fun `create pending round-trips exactly`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("create-1", t0, expiry)
        val stored = store.create(request)
        assertThat(stored).isEqualTo(request)
        assertThat(store.get("create-1")).isEqualTo(request)
    }

    @Test
    fun `get missing id returns null`() = runBlocking<Unit> {
        assertThat(store.get("does-not-exist")).isNull()
    }

    @Test
    fun `duplicate create throws conflict`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("dup-1", t0, expiry)
        store.create(request)
        assertThat(
            runCatching { store.create(request) }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreConflictException::class.java)
    }

    @Test
    fun `non-zero initial version rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("bad-version", t0, expiry).copy(version = 1L)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-pending initial state rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("bad-status", t0, expiry).copy(status = ApprovalStatus.APPROVED)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pre-populated decision fields rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("bad-decided", t0, expiry)
            .copy(decidedBy = "someone", decidedAt = t0)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pre-populated consumption fields rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("bad-consumed", t0, expiry)
            .copy(consumedBy = "someone", consumedAt = t0)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank approval id rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("  ", t0, expiry)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `requestedAt in future rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("future-req", t0.plusSeconds(60), expiry)
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `expiresAt before requestedAt rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("bad-expiry", t0, t0.minusSeconds(60))
        assertThat(runCatching { store.create(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── State transitions ───────────────────────────────────────────

    @Test
    fun `pending to approved sets decision fields and increments version`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("approve-1", t0, expiry)
        store.create(request)

        val updated = store.transition("approve-1", 0L, ApprovalTransition.Approve("approver-1", "looks good"))

        assertThat(updated.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isEqualTo("approver-1")
        assertThat(updated.decidedAt).isEqualTo(t0)
        assertThat(updated.decisionComment).isEqualTo("looks good")
    }

    @Test
    fun `pending to denied sets decision fields and increments version`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("deny-1", t0, expiry)
        store.create(request)

        val updated = store.transition("deny-1", 0L, ApprovalTransition.Deny("denier-1"))

        assertThat(updated.status).isEqualTo(ApprovalStatus.DENIED)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isEqualTo("denier-1")
        assertThat(updated.decidedAt).isEqualTo(t0)
    }

    @Test
    fun `expired pending can time out`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("timeout-1", t0, expiry)
        store.create(request)
        clock.advance(Duration.ofSeconds(601))

        val updated = store.transition("timeout-1", 0L, ApprovalTransition.Timeout)

        assertThat(updated.status).isEqualTo(ApprovalStatus.TIMED_OUT)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedAt).isEqualTo(t0.plusSeconds(601))
    }

    @Test
    fun `timeout before expiry rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("early-timeout", t0, expiry)
        store.create(request)

        assertThat(runCatching { store.transition("early-timeout", 0L, ApprovalTransition.Timeout) }.exceptionOrNull())
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
    }

    @Test
    fun `approve after expiry rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("late-approve", t0, expiry)
        store.create(request)
        clock.advance(Duration.ofSeconds(601))

        assertThat(runCatching { store.transition("late-approve", 0L, ApprovalTransition.Approve("approver-1")) }.exceptionOrNull())
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
    }

    @Test
    fun `deny after expiry rejected`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("late-deny", t0, expiry)
        store.create(request)
        clock.advance(Duration.ofSeconds(601))

        assertThat(runCatching { store.transition("late-deny", 0L, ApprovalTransition.Deny("denier-1")) }.exceptionOrNull())
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
    }

    @Test
    fun `terminal states reject any further transition`() = runBlocking<Unit> {
        // APPROVED is terminal
        val approved = ApprovalStoreFixtures.pending("terminal-approved", t0, expiry)
        store.create(approved)
        store.transition("terminal-approved", 0L, ApprovalTransition.Approve("approver-1"))
        assertThat(
            runCatching { store.transition("terminal-approved", 1L, ApprovalTransition.Deny("denier-1")) }.exceptionOrNull(),
        ).isInstanceOf(IllegalApprovalTransitionException::class.java)

        // DENIED is terminal
        val denied = ApprovalStoreFixtures.pending("terminal-denied", t0, expiry)
        store.create(denied)
        store.transition("terminal-denied", 0L, ApprovalTransition.Deny("denier-1"))
        assertThat(
            runCatching { store.transition("terminal-denied", 1L, ApprovalTransition.Approve("approver-1")) }.exceptionOrNull(),
        ).isInstanceOf(IllegalApprovalTransitionException::class.java)

        // TIMED_OUT is terminal
        val timedOut = ApprovalStoreFixtures.pending("terminal-timedout", t0, expiry)
        store.create(timedOut)
        clock.advance(Duration.ofSeconds(601))
        store.transition("terminal-timedout", 0L, ApprovalTransition.Timeout)
        assertThat(
            runCatching { store.transition("terminal-timedout", 1L, ApprovalTransition.Approve("approver-1")) }.exceptionOrNull(),
        ).isInstanceOf(IllegalApprovalTransitionException::class.java)
    }

    @Test
    fun `stale expected version throws conflict`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("stale-1", t0, expiry)
        store.create(request)
        store.transition("stale-1", 0L, ApprovalTransition.Approve("approver-1"))

        assertThat(runCatching { store.transition("stale-1", 0L, ApprovalTransition.Deny("denier-1")) }.exceptionOrNull())
            .isInstanceOf(ApprovalStoreConflictException::class.java)
    }

    @Test
    fun `transition on missing approval throws not found`() = runBlocking<Unit> {
        assertThat(runCatching { store.transition("missing-1", 0L, ApprovalTransition.Approve("approver-1")) }.exceptionOrNull())
            .isInstanceOf(ApprovalStoreNotFoundException::class.java)
    }

    @Test
    fun `every successful transition increments version exactly once`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("incr-1", t0, expiry)
        store.create(request)

        val first = store.transition("incr-1", 0L, ApprovalTransition.Approve("approver-1"))
        assertThat(first.version).isEqualTo(1L)
        val persisted = store.get("incr-1")
        assertThat(persisted?.version).isEqualTo(1L)
    }

    // ── Consumption / exact replay ──────────────────────────────────

    private suspend fun approveAndConsumeSetup(id: String) {
        val request = ApprovalStoreFixtures.pending(id, t0, expiry)
        store.create(request)
        store.transition(id, 0L, ApprovalTransition.Approve("approver-1"))
    }

    @Test
    fun `fresh consumption succeeds`() = runBlocking<Unit> {
        approveAndConsumeSetup("consume-1")

        val receipt = store.consumeApprovedOrReplay(
            "consume-1", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1",
        )

        assertThat(receipt.replayed).isFalse()
        assertThat(receipt.request.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(receipt.request.consumedBy).isEqualTo("worker-1")
        assertThat(receipt.request.consumedAt).isEqualTo(t0)
        assertThat(receipt.request.version).isEqualTo(2L)
    }

    @Test
    fun `wrong token rejected`() = runBlocking<Unit> {
        approveAndConsumeSetup("bad-token")

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("bad-token", 1L, ApprovalStoreFixtures.wrongTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreTokenRejectedException::class.java)
    }

    @Test
    fun `wrong consumedBy on replay rejected`() = runBlocking<Unit> {
        approveAndConsumeSetup("bad-replayer")
        store.consumeApprovedOrReplay("bad-replayer", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("bad-replayer", 1L, ApprovalStoreFixtures.validTokenDigest(), "intruder")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotConsumableException::class.java)
    }

    @Test
    fun `stale expected version on consume rejected`() = runBlocking<Unit> {
        approveAndConsumeSetup("stale-consume")

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("stale-consume", 0L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreConflictException::class.java)
    }

    @Test
    fun `pending cannot be consumed`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("pending-consume", t0, expiry)
        store.create(request)

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("pending-consume", 0L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotConsumableException::class.java)
    }

    @Test
    fun `denied cannot be consumed`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("denied-consume", t0, expiry)
        store.create(request)
        store.transition("denied-consume", 0L, ApprovalTransition.Deny("denier-1"))

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("denied-consume", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotConsumableException::class.java)
    }

    @Test
    fun `timed out cannot be consumed`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("timeout-consume", t0, expiry)
        store.create(request)
        clock.advance(Duration.ofSeconds(601))
        store.transition("timeout-consume", 0L, ApprovalTransition.Timeout)

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("timeout-consume", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotConsumableException::class.java)
    }

    @Test
    fun `expired unconsumed approval cannot be consumed`() = runBlocking<Unit> {
        approveAndConsumeSetup("expired-consume")
        clock.advance(Duration.ofSeconds(601))

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("expired-consume", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotConsumableException::class.java)
    }

    @Test
    fun `exact replay returns same durable record without writing`() = runBlocking<Unit> {
        approveAndConsumeSetup("replay-1")
        val first = store.consumeApprovedOrReplay("replay-1", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        val replay = store.consumeApprovedOrReplay("replay-1", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        assertThat(replay.replayed).isTrue()
        assertThat(replay.request).isEqualTo(first.request)
        assertThat(replay.request.consumedAt).isEqualTo(first.request.consumedAt)
        assertThat(replay.request.version).isEqualTo(2L)
        assertThat(store.get("replay-1")?.version).isEqualTo(2L)
    }

    @Test
    fun `exact replay remains valid after expiry`() = runBlocking<Unit> {
        approveAndConsumeSetup("replay-expired")
        store.consumeApprovedOrReplay("replay-expired", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
        clock.advance(Duration.ofHours(1))

        val replay = store.consumeApprovedOrReplay("replay-expired", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        assertThat(replay.replayed).isTrue()
        assertThat(replay.request.consumedAt).isEqualTo(t0)
    }

    @Test
    fun `rejected consumption leaves stored record unchanged`() = runBlocking<Unit> {
        approveAndConsumeSetup("non-mutating")
        val before = store.get("non-mutating")!!

        runCatching {
            store.consumeApprovedOrReplay("non-mutating", 1L, ApprovalStoreFixtures.wrongTokenDigest(), "worker-1")
        }

        assertThat(store.get("non-mutating")).isEqualTo(before)
        assertThat(store.get("non-mutating")?.consumedBy).isNull()
        assertThat(store.get("non-mutating")?.version).isEqualTo(1L)
    }

    @Test
    fun `replay with stale expected version rejected`() = runBlocking<Unit> {
        approveAndConsumeSetup("replay-stale")
        store.consumeApprovedOrReplay("replay-stale", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("replay-stale", 0L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreConflictException::class.java)
    }

    @Test
    fun `wrong token on replay rejected`() = runBlocking<Unit> {
        approveAndConsumeSetup("replay-bad-token")
        store.consumeApprovedOrReplay("replay-bad-token", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")

        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("replay-bad-token", 1L, ApprovalStoreFixtures.wrongTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreTokenRejectedException::class.java)
    }

    @Test
    fun `consume on missing approval throws not found`() = runBlocking<Unit> {
        assertThat(
            runCatching {
                store.consumeApprovedOrReplay("missing-consume", 0L, ApprovalStoreFixtures.validTokenDigest(), "worker-1")
            }.exceptionOrNull(),
        ).isInstanceOf(ApprovalStoreNotFoundException::class.java)
    }

    @Test
    fun `fresh consumption records the advanced clock instant`() = runBlocking<Unit> {
        val request = ApprovalStoreFixtures.pending("consume-advanced", t0, expiry)
        store.create(request)
        store.transition("consume-advanced", 0L, ApprovalTransition.Approve("approver-1"))
        clock.advance(Duration.ofSeconds(10))

        val receipt = store.consumeApprovedOrReplay(
            "consume-advanced", 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1",
        )

        assertThat(receipt.request.consumedAt).isEqualTo(t0.plusSeconds(10))
    }

    // ── Concurrency ─────────────────────────────────────────────────

    /**
     * Runs [contenders] on parallel workers, releasing them only once every
     * contender is ready, so the operations genuinely overlap instead of
     * serializing on the caller's single-threaded event loop. A non-atomic
     * store (check-then-act without a lock/row-lock) must fail the race
     * assertions below.
     */
    private suspend fun <T> runInParallel(vararg contenders: suspend () -> T): List<T> = coroutineScope {
        val ready = Channel<Unit>(capacity = contenders.size)
        val release = CompletableDeferred<Unit>()
        val results = contenders.map { op ->
            async(Dispatchers.Default) {
                ready.send(Unit)
                release.await()
                op()
            }
        }
        repeat(contenders.size) { ready.receive() }
        release.complete(Unit)
        results.map { it.await() }
    }

    @Test
    fun `concurrent transition race - exactly one wins`() = runBlocking<Unit> {
        // Several scheduling opportunities: one run per id, each on a fresh
        // approval, so a store that only wins by luck once still fails.
        repeat(5) { iteration ->
            val id = "race-$iteration"
            val request = ApprovalStoreFixtures.pending(id, t0, expiry)
            store.create(request)

            val outcomes = runInParallel(
                { runCatching { store.transition(id, 0L, ApprovalTransition.Approve("a")) } },
                { runCatching { store.transition(id, 0L, ApprovalTransition.Deny("b")) } },
            )

            val successes = outcomes.count { it.isSuccess }
            val conflicts = outcomes.count { it.exceptionOrNull() is ApprovalStoreConflictException }
            assertThat(successes).isEqualTo(1)
            assertThat(conflicts).isEqualTo(1)

            val persisted = store.get(id)!!
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(persisted.status).isIn(ApprovalStatus.APPROVED, ApprovalStatus.DENIED)
            val winner = outcomes.first { it.isSuccess }.getOrThrow()
            assertThat(persisted.status).isEqualTo(winner.status)
        }
    }

    @Test
    fun `concurrent identical consumption - one fresh one replay, same durable record`() = runBlocking<Unit> {
        repeat(5) { iteration ->
            val id = "consume-race-$iteration"
            approveAndConsumeSetup(id)

            val receipts = runInParallel(
                { store.consumeApprovedOrReplay(id, 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1") },
                { store.consumeApprovedOrReplay(id, 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-1") },
            )

            assertThat(receipts.count { !it.replayed }).isEqualTo(1)
            assertThat(receipts.count { it.replayed }).isEqualTo(1)
            assertThat(receipts[0].request).isEqualTo(receipts[1].request)
            assertThat(receipts[0].request.consumedAt).isEqualTo(receipts[1].request.consumedAt)
            assertThat(receipts[0].request.version).isEqualTo(2L)
            assertThat(store.get(id)?.version).isEqualTo(2L)
        }
    }

    // ── Epic 8.2a: model-based lifecycle properties ────────────────
    //
    // The properties below replace "specific scenario → expected
    // result" with "model state → generated action → predicted transition →
    // execute real store → compare → assert all invariants → repeat". The
    // oracle is ApprovalLifecycleModel — a PURE, independent encoding of the
    // documented lifecycle, deliberately not derived from the production
    // stores or their helpers.

    @Test
    fun `generated approval lifecycle sequences match the independent model after every action`() = runBlocking<Unit> {
        for (seed in 0L until ApprovalLifecycleActionGenerator.SEED_COUNT) {
            clock.set(t0)
            val id = "state-machine-$seed"
            val initial = ApprovalStoreFixtures.pending(id, t0, expiry)
            store.create(initial)
            var model = ApprovalLifecycleModel.from(initial, t0)
            val actions = ApprovalLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)

            actions.forEachIndexed { step, action ->
                clock.set(model.now)
                val before = store.get(id)!!
                val expected = model.apply(action, expiry)
                val actual = executeLifecycleAction(action, model, store, id)

                assertLifecycleOutcome(expected, actual, seed, step, action, actions, model)

                model = when (expected) {
                    is ApprovalLifecycleOutcome.Success -> expected.next
                    is ApprovalLifecycleOutcome.Failure -> model
                }
                clock.set(model.now)

                val modelViolations = model.invariants()
                assertThat(modelViolations).withFailMessage {
                    "seed=$seed step=$step action=${action.describe()}: model invariants violated: $modelViolations"
                }.isEmpty()

                val expectedRecord = model.toRequest(initial)
                val persisted = store.get(id)!!
                assertThat(persisted).withFailMessage {
                    lifecycleFailureMessage(seed, step, action, actions, model, expected, actual)
                }.isEqualTo(expectedRecord)

                if (expected is ApprovalLifecycleOutcome.Failure) {
                    assertThat(persisted).withFailMessage {
                        "seed=$seed step=$step action=${action.describe()}: rejected action must not mutate the durable record"
                    }.isEqualTo(before)
                }
            }
        }
    }

    @Test
    fun `wrong-version decisions always conflict without changing durable state`() = runBlocking<Unit> {
        // Pins optimistic versioning on DECISION transitions, including
        // failure precedence: version is checked before the expiry window,
        // so a stale-version decision at/after expiry is a CONFLICT, not an
        // IllegalApprovalTransition. Same model machinery as the generated
        // property — nothing duplicated by hand.
        val transitions = listOf(
            ApprovalLifecycleAction.ApproveWrongVersion("approver"),
            ApprovalLifecycleAction.DenyWrongVersion("denier"),
            ApprovalLifecycleAction.TimeoutWrongVersion,
        )
        val times = listOf(t0, expiry, expiry.plusSeconds(1))
        times.forEachIndexed { timeIndex, now ->
            transitions.forEachIndexed { transitionIndex, action ->
                val id = "wrong-version-decision-$timeIndex-$transitionIndex"
                clock.set(t0)
                val initial = ApprovalStoreFixtures.pending(id, t0, expiry)
                store.create(initial)
                val model = ApprovalLifecycleModel.from(initial, t0).copy(now = now)
                clock.set(now)
                val before = store.get(id)!!

                val expected = model.apply(action, expiry)
                val actual = executeLifecycleAction(action, model, store, id)

                assertLifecycleOutcome(expected, actual, 0L, 0, action, listOf(action), model)
                assertThat(store.get(id)).withFailMessage {
                    "wrong-version $action at $now must not mutate the durable record"
                }.isEqualTo(before)
            }
        }
    }

    @Test
    fun `duplicate concurrent decisions - exactly one winner and seven conflicts`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "decision-race-$iteration"
            val request = ApprovalStoreFixtures.pending(id, t0, expiry)
            store.create(request)

            val contenders = Array<suspend () -> Result<ApprovalRequest>>(8) { index ->
                val approve = index % 2 == 0
                {
                    runCatching {
                        if (approve) {
                            store.transition(id, 0L, ApprovalTransition.Approve("approver-$index"))
                        } else {
                            store.transition(id, 0L, ApprovalTransition.Deny("denier-$index"))
                        }
                    }
                }
            }
            val outcomes = runInParallel(*contenders)

            assertThat(outcomes.count { it.isSuccess })
                .withFailMessage("iteration $iteration: exactly one decision must win")
                .isEqualTo(1)
            assertThat(outcomes.count { it.exceptionOrNull() is ApprovalStoreConflictException })
                .withFailMessage("iteration $iteration: the seven losers must all conflict")
                .isEqualTo(7)

            val winner = outcomes.indexOfFirst { it.isSuccess }
            val persisted = store.get(id)!!
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(persisted.status)
                .isEqualTo(if (winner % 2 == 0) ApprovalStatus.APPROVED else ApprovalStatus.DENIED)
            assertThat(persisted.decidedBy)
                .isEqualTo(if (winner % 2 == 0) "approver-$winner" else "denier-$winner")
        }
    }

    @Test
    fun `eight identical consumers - one fresh receipt and seven exact replays of the same durable record`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "identical-consume-$iteration"
            approveAndConsumeSetup(id)

            val contenders = Array<suspend () -> ApprovalConsumptionReceipt>(8) {
                { store.consumeApprovedOrReplay(id, 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-a") }
            }
            val receipts = runInParallel(*contenders)

            assertThat(receipts.count { !it.replayed })
                .withFailMessage("iteration $iteration: exactly one fresh receipt expected")
                .isEqualTo(1)
            assertThat(receipts.count { it.replayed })
                .withFailMessage("iteration $iteration: exactly seven exact replays expected")
                .isEqualTo(7)

            val durable = store.get(id)!!
            assertThat(durable.version).isEqualTo(2L)
            assertThat(durable.consumedBy).isEqualTo("worker-a")
            receipts.forEach { receipt ->
                assertThat(receipt.request).isEqualTo(durable)
            }
        }
    }

    @Test
    fun `eight competing consumers - exactly one durable consumer identity`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "competing-consume-$iteration"
            approveAndConsumeSetup(id)

            val contenders = Array<suspend () -> Result<ApprovalConsumptionReceipt>>(8) { index ->
                { runCatching { store.consumeApprovedOrReplay(id, 1L, ApprovalStoreFixtures.validTokenDigest(), "worker-$index") } }
            }
            val outcomes = runInParallel(*contenders)

            val freshWinners = outcomes.filter { it.isSuccess && !it.getOrThrow().replayed }
            assertThat(freshWinners.size)
                .withFailMessage("iteration $iteration: exactly one fresh consumption winner expected")
                .isEqualTo(1)
            // No loser may ever obtain a successful receipt: replay requires
            // the durable consumedBy to equal the caller, and the winner is a
            // different actor than every loser.
            assertThat(outcomes.filter { it.isSuccess && it.getOrThrow().replayed }).isEmpty()

            val durable = store.get(id)!!
            assertThat(durable.version).isEqualTo(2L)
            val winnerIndex = outcomes.indexOfFirst { it.isSuccess && it.getOrThrow().replayed == false }
            assertThat(durable.consumedBy).isEqualTo("worker-$winnerIndex")

            // After the winner commits, every backend's whole-consume atomicity
            // (InMemory CAS, File per-record lock, JDBC FOR UPDATE row-lock)
            // serializes each loser AFTER the winner: the loser reads the
            // consumed record, dispatches to the replay path with the valid
            // predecessor version (1) and matching token, and fails only on
            // the actor check. Conflict or TokenRejected are NOT legal
            // serialized loser outcomes — a loser leaking Conflict would mean
            // an implementation that lost a race without re-reading the
            // winning state (exactly the false confidence Epic 8.2 removes).
            val loserFailures = outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }
            assertThat(loserFailures.size).isEqualTo(7)
            assertThat(loserFailures.map { it?.javaClass }).allMatch {
                it == ApprovalStoreNotConsumableException::class.java
            }
        }
    }

    // ── Epic 8.2a helpers ───────────────────────────────────────────

    private sealed interface LifecycleExecutionResult {
        data class Success(
            val replayed: Boolean,
        ) : LifecycleExecutionResult

        data class Failure(
            val exceptionClass: Class<out Exception>,
        ) : LifecycleExecutionResult

        fun describe(): String = when (this) {
            is Success -> "Success(replayed=$replayed)"
            is Failure -> "Failure(${exceptionClass.simpleName})"
        }
    }

    private suspend fun executeLifecycleAction(
        action: ApprovalLifecycleAction,
        model: ApprovalLifecycleModel,
        store: ApprovalStore,
        id: String,
    ): LifecycleExecutionResult {
        if (action.isAdvance) return LifecycleExecutionResult.Success(replayed = false)

        val expectedVersion = when (action) {
            is ApprovalLifecycleAction.ApproveCurrentVersion,
            is ApprovalLifecycleAction.DenyCurrentVersion,
            ApprovalLifecycleAction.TimeoutCurrentVersion,
            -> model.version
            is ApprovalLifecycleAction.ApproveWrongVersion,
            is ApprovalLifecycleAction.DenyWrongVersion,
            ApprovalLifecycleAction.TimeoutWrongVersion,
            -> model.version + 1
            is ApprovalLifecycleAction.ConsumeWrongVersion ->
                // The dangerous wrong expected-version is the one ADJACENT to
                // the valid expected version, not durable+1: fresh consume
                // valid expected = durable (v1) -> wrong = v2; replay valid
                // expected = durable-1 (v1) -> wrong = v2 (an implementation
                // accepting expectedVersion == durableVersion would pass a
                // durable+1 probe but must fail here).
                if (model.consumedAt == null) model.version + 1 else model.version
            is ApprovalLifecycleAction.ConsumeValid,
            is ApprovalLifecycleAction.ConsumeWrongToken,
            -> if (model.consumedAt == null) model.version else model.version - 1
            else -> model.version
        }

        return when (action) {
            is ApprovalLifecycleAction.ApproveCurrentVersion -> runCatching {
                store.transition(id, expectedVersion, ApprovalTransition.Approve(action.actor, action.comment))
            }.toLifecycleResult()
            is ApprovalLifecycleAction.ApproveWrongVersion -> runCatching {
                store.transition(id, expectedVersion, ApprovalTransition.Approve(action.actor, null))
            }.toLifecycleResult()
            is ApprovalLifecycleAction.DenyCurrentVersion -> runCatching {
                store.transition(id, expectedVersion, ApprovalTransition.Deny(action.actor, action.comment))
            }.toLifecycleResult()
            is ApprovalLifecycleAction.DenyWrongVersion -> runCatching {
                store.transition(id, expectedVersion, ApprovalTransition.Deny(action.actor, null))
            }.toLifecycleResult()
            ApprovalLifecycleAction.TimeoutCurrentVersion,
            ApprovalLifecycleAction.TimeoutWrongVersion,
            -> runCatching {
                store.transition(id, expectedVersion, ApprovalTransition.Timeout)
            }.toLifecycleResult()
            is ApprovalLifecycleAction.ConsumeValid -> runCatching {
                store.consumeApprovedOrReplay(id, expectedVersion, ApprovalStoreFixtures.validTokenDigest(), action.worker)
            }.let { result ->
                result.fold(
                    onSuccess = { LifecycleExecutionResult.Success(replayed = it.replayed) },
                    onFailure = { LifecycleExecutionResult.Failure(it::class.java as Class<out Exception>) },
                )
            }
            is ApprovalLifecycleAction.ConsumeWrongVersion -> runCatching {
                store.consumeApprovedOrReplay(id, expectedVersion, ApprovalStoreFixtures.validTokenDigest(), action.worker)
            }.let { result ->
                result.fold(
                    onSuccess = { LifecycleExecutionResult.Success(replayed = it.replayed) },
                    onFailure = { LifecycleExecutionResult.Failure(it::class.java as Class<out Exception>) },
                )
            }
            is ApprovalLifecycleAction.ConsumeWrongToken -> runCatching {
                store.consumeApprovedOrReplay(id, expectedVersion, ApprovalStoreFixtures.wrongTokenDigest(), action.worker)
            }.let { result ->
                result.fold(
                    onSuccess = { LifecycleExecutionResult.Success(replayed = it.replayed) },
                    onFailure = { LifecycleExecutionResult.Failure(it::class.java as Class<out Exception>) },
                )
            }
            else -> LifecycleExecutionResult.Success(replayed = false) // advances handled above
        }
    }

    private fun Result<*>.toLifecycleResult(): LifecycleExecutionResult = fold(
        onSuccess = { LifecycleExecutionResult.Success(replayed = false) },
        onFailure = { LifecycleExecutionResult.Failure(it::class.java as Class<out Exception>) },
    )

    private fun assertLifecycleOutcome(
        expected: ApprovalLifecycleOutcome,
        actual: LifecycleExecutionResult,
        seed: Long,
        step: Int,
        action: ApprovalLifecycleAction,
        actions: List<ApprovalLifecycleAction>,
        model: ApprovalLifecycleModel,
    ) {
        when (expected) {
            is ApprovalLifecycleOutcome.Success -> {
                assertThat(actual).withFailMessage {
                    lifecycleFailureMessage(seed, step, action, actions, model, expected, actual)
                }.isInstanceOf(LifecycleExecutionResult.Success::class.java)
                val success = actual as LifecycleExecutionResult.Success
                assertThat(success.replayed).withFailMessage {
                    lifecycleFailureMessage(seed, step, action, actions, model, expected, actual)
                }.isEqualTo(expected.replayed)
            }
            is ApprovalLifecycleOutcome.Failure -> {
                assertThat(actual).withFailMessage {
                    lifecycleFailureMessage(seed, step, action, actions, model, expected, actual)
                }.isInstanceOf(LifecycleExecutionResult.Failure::class.java)
                val failure = actual as LifecycleExecutionResult.Failure
                assertThat(failure.exceptionClass).withFailMessage {
                    lifecycleFailureMessage(seed, step, action, actions, model, expected, actual)
                }.isEqualTo(expected.kind.exceptionClass())
            }
        }
    }

    private fun lifecycleFailureMessage(
        seed: Long,
        step: Int,
        action: ApprovalLifecycleAction,
        actions: List<ApprovalLifecycleAction>,
        model: ApprovalLifecycleModel,
        expected: ApprovalLifecycleOutcome,
        actual: LifecycleExecutionResult,
    ): String = buildString {
        appendLine("Approval lifecycle property failed")
        appendLine("seed=$seed")
        appendLine("step=$step")
        appendLine("action=${action.describe()}")
        appendLine("prefix:")
        actions.take(step).forEachIndexed { index, prior -> appendLine("  $index ${prior.describe()}") }
        appendLine("modelBefore=${model.describe()}")
        appendLine("expected=$expected")
        appendLine("actual=${actual.describe()}")
    }
}
