package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import dev.tramai.core.exception.ApprovalContinuationStoreException
import dev.tramai.testing.persistence.approval.MutableClock
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
 * Epic 8.1b shared ApprovalContinuationStore compatibility contract.
 *
 * Every [ApprovalContinuationStore] implementation must satisfy exactly the
 * same externally observable continuation persistence contract, regardless of
 * storage technology (memory, encrypted files, JDBC): state-machine
 * agreement, optimistic-concurrency agreement, and exactly-once release of
 * raw sensitive arguments (the only path that exposes them).
 *
 * Each test gets a FRESH store from [harness] wired to a [MutableClock]
 * fixed at T0. Time advances only via [MutableClock.advance] — never real
 * sleeps.
 */
abstract class ApprovalContinuationStoreTck {

    abstract val harness: ApprovalContinuationStoreTckHarness

    protected lateinit var store: ApprovalContinuationStore
    protected lateinit var clock: MutableClock

    protected val t0: Instant = Instant.parse("2026-08-23T12:00:00Z")
    protected val expiry: Instant = t0.plusSeconds(300)

    @BeforeEach
    fun setUp() {
        clock = MutableClock(t0)
        store = harness.createStore(clock)
    }

    @AfterEach
    fun tearDown() = runBlocking<Unit> { harness.closeStore(store) }

    // ── Setup helpers ───────────────────────────────────────────────

    protected fun pending(
        id: String,
        createdAt: Instant = t0,
        expiresAt: Instant = expiry,
        rawArguments: String = ApprovalContinuationFixtures.DEFAULT_ARGUMENTS,
    ): Pair<ApprovalContinuation, dev.tramai.core.approval.SensitiveToolArguments> =
        ApprovalContinuationFixtures.continuation(id, createdAt, expiresAt, rawArguments) to
            ApprovalContinuationFixtures.arguments(rawArguments)

    protected suspend fun createPending(
        id: String,
        createdAt: Instant = t0,
        expiresAt: Instant = expiry,
        rawArguments: String = ApprovalContinuationFixtures.DEFAULT_ARGUMENTS,
    ): Pair<ApprovalContinuation, dev.tramai.core.approval.SensitiveToolArguments> {
        val (continuation, arguments) = pending(id, createdAt, expiresAt, rawArguments)
        store.create(continuation, arguments)
        return continuation to arguments
    }

    protected suspend fun setupClaimed(
        id: String,
        claimedBy: String = "worker-1",
        rawArguments: String = ApprovalContinuationFixtures.DEFAULT_ARGUMENTS,
    ): ClaimedApprovalContinuation {
        createPending(id, rawArguments = rawArguments)
        return store.claimForExecution(id, 0L, claimedBy)
    }

    protected suspend fun setupCompleted(id: String, completedBy: String = "worker-1"): ApprovalContinuation {
        setupClaimed(id, completedBy)
        return store.complete(id, 1L, completedBy)
    }

    protected suspend fun setupCancelled(id: String): ApprovalContinuation {
        createPending(id)
        return store.cancel(id, 0L)
    }

    /** Claims then force-cancels into CANCELLED_UNCERTAIN at T0. */
    protected suspend fun setupCancelledUncertain(id: String, cancelledBy: String = "recovery-1"): ApprovalContinuation {
        setupClaimed(id)
        return store.forceCancelClaimed(id, 1L, cancelledBy, "stale-claim")
    }

    // ── Creation / read ─────────────────────────────────────────────

    @Test
    fun `create pending continuation round-trips exactly`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("create-1")
        val stored = store.create(continuation, arguments)
        assertThat(stored).isEqualTo(continuation)
        assertThat(store.get("create-1")).isEqualTo(continuation)
    }

    @Test
    fun `get missing id returns null`() = runBlocking<Unit> {
        assertThat(store.get("does-not-exist")).isNull()
    }

    @Test
    fun `duplicate create throws conflict`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("dup-1")
        store.create(continuation, arguments)
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `non-zero initial version rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("bad-version").let { (c, a) -> c.copy(version = 1L) to a }
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-pending initial status rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("bad-status")
            .let { (c, a) -> c.copy(status = ApprovalContinuationStatus.CLAIMED) to a }
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pre-populated claimed fields rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("bad-claimed")
            .let { (c, a) -> c.copy(claimedBy = "worker-1", claimedAt = t0) to a }
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pre-populated completion and recovery fields rejected`() = runBlocking<Unit> {
        val withCompletion = pending("bad-completed").let { (c, a) -> c.copy(completedAt = t0) to a }
        assertThat(runCatching { store.create(withCompletion.first, withCompletion.second) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)

        val withRecovery = pending("bad-recovery").let { (c, a) ->
            c.copy(recoveryResolvedBy = "recovery-1", recoveryResolvedAt = t0, recoveryReasonCode = "stale-claim") to a
        }
        assertThat(runCatching { store.create(withRecovery.first, withRecovery.second) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank approval id rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("  ")
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `future createdAt rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("future-created", createdAt = t0.plusSeconds(60), expiresAt = expiry.plusSeconds(60))
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-future expiry rejected`() = runBlocking<Unit> {
        // expiresAt == now fails the in-future check.
        val atNow = pending("expiry-now", expiresAt = t0)
        assertThat(runCatching { store.create(atNow.first, atNow.second) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)

        // expiresAt before createdAt (both in the past) fails the ordering check.
        val inPast = pending("expiry-past", createdAt = t0.minusSeconds(120), expiresAt = t0.minusSeconds(60))
        assertThat(runCatching { store.create(inPast.first, inPast.second) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ttl beyond bound rejected`() = runBlocking<Unit> {
        // Stores cap the creation TTL at 15 minutes by default.
        val (continuation, arguments) = pending("ttl-1", expiresAt = t0.plus(Duration.ofMinutes(16)))
        assertThat(runCatching { store.create(continuation, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `arguments digest mismatch rejected`() = runBlocking<Unit> {
        val (continuation, arguments) = pending("bad-digest")
        val tampered = continuation.copy(
            argumentsDigest = ApprovalContinuationFixtures.digest("c".repeat(64)),
        )
        assertThat(runCatching { store.create(tampered, arguments) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── Claim ───────────────────────────────────────────────────────

    @Test
    fun `claim transitions to claimed and increments version`() = runBlocking<Unit> {
        createPending("claim-1")

        val claimed = store.claimForExecution("claim-1", 0L, "worker-1")

        assertThat(claimed.continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(claimed.continuation.version).isEqualTo(1L)
        assertThat(claimed.continuation.claimedBy).isEqualTo("worker-1")
        assertThat(claimed.continuation.claimedAt).isEqualTo(t0)

        val persisted = store.get("claim-1")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(persisted.version).isEqualTo(1L)
        assertThat(persisted.claimedBy).isEqualTo("worker-1")
    }

    @Test
    fun `claim releases the exact raw arguments`() = runBlocking<Unit> {
        val raw = "{\"secret\":\"hunter2\",\"args\":[1,2,3]}"
        createPending("claim-args", rawArguments = raw)

        val claimed = store.claimForExecution("claim-args", 0L, "worker-1")

        assertThat(claimed.arguments.reveal()).isEqualTo(raw)
    }

    @Test
    fun `claim on missing approval throws not found`() = runBlocking<Unit> {
        assertThat(runCatching { store.claimForExecution("missing-claim", 0L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    @Test
    fun `claim with stale version throws conflict`() = runBlocking<Unit> {
        createPending("stale-claim")
        store.claimForExecution("stale-claim", 0L, "worker-1")

        assertThat(runCatching { store.claimForExecution("stale-claim", 0L, "worker-2") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `claim on non-claimable terminal statuses rejected`() = runBlocking<Unit> {
        setupCompleted("claim-completed")
        setupCancelled("claim-cancelled")
        setupCancelledUncertain("claim-uncertain")

        listOf(
            "claim-completed" to 2L,
            "claim-cancelled" to 1L,
            "claim-uncertain" to 2L,
        ).forEach { (id, version) ->
            assertThat(
                runCatching { store.claimForExecution(id, version, "worker-1") }.exceptionOrNull(),
            ).isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        }
    }

    // ── Exactly-once release ────────────────────────────────────────

    @Test
    fun `second claim can never retrieve arguments`() = runBlocking<Unit> {
        createPending("once-1")
        val first = store.claimForExecution("once-1", 0L, "worker-1")
        assertThat(first.arguments.reveal()).isEqualTo(ApprovalContinuationFixtures.DEFAULT_ARGUMENTS)

        // Stale version: typed conflict.
        assertThat(runCatching { store.claimForExecution("once-1", 0L, "worker-2") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        // Correct version: no longer claimable.
        assertThat(runCatching { store.claimForExecution("once-1", 1L, "worker-2") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        // Persisted metadata stays queryable.
        val persisted = store.get("once-1")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(persisted.claimedBy).isEqualTo("worker-1")
    }

    @Test
    fun `arguments are cleared from storage on claim`() = runBlocking<Unit> {
        // The store contract: after a claim, the only remaining record is
        // metadata; a second claim must fail before any arguments are
        // exposed. Proving it via the claim path (the only exposing API).
        createPending("cleared-1")
        store.claimForExecution("cleared-1", 0L, "worker-1")

        val second = runCatching { store.claimForExecution("cleared-1", 1L, "worker-2") }.exceptionOrNull()
        assertThat(second).isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        assertThat(second).isNotInstanceOf(ClaimedApprovalContinuation::class.java)
    }

    // ── Expiry ──────────────────────────────────────────────────────

    @Test
    fun `explicit expire after deadline`() = runBlocking<Unit> {
        createPending("expire-1")
        clock.advance(Duration.ofSeconds(301))

        val expired = store.expire("expire-1", 0L)

        assertThat(expired.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(expired.version).isEqualTo(1L)
        val persisted = store.get("expire-1")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(persisted.version).isEqualTo(1L)
    }

    @Test
    fun `explicit expire before deadline rejected`() = runBlocking<Unit> {
        createPending("early-expire")
        assertThat(runCatching { store.expire("early-expire", 0L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `late claim persists expired and fails not claimable`() = runBlocking<Unit> {
        createPending("late-claim")
        clock.advance(Duration.ofSeconds(301))

        assertThat(runCatching { store.claimForExecution("late-claim", 0L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        val persisted = store.get("late-claim")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(persisted.version).isEqualTo(1L)
    }

    @Test
    fun `late cancel persists expired and fails conflict`() = runBlocking<Unit> {
        createPending("late-cancel")
        clock.advance(Duration.ofSeconds(301))

        assertThat(runCatching { store.cancel("late-cancel", 0L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        val persisted = store.get("late-cancel")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(persisted.version).isEqualTo(1L)
        assertThat(runCatching { store.claimForExecution("late-cancel", 1L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `lazy get expires elapsed pending exactly once`() = runBlocking<Unit> {
        createPending("lazy-expire")
        clock.advance(Duration.ofSeconds(301))

        val first = store.get("lazy-expire")!!
        assertThat(first.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(first.version).isEqualTo(1L)

        val second = store.get("lazy-expire")!!
        assertThat(second.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(second.version).isEqualTo(1L)
    }

    @Test
    fun `claimed never lazy expires`() = runBlocking<Unit> {
        setupClaimed("claimed-stays")
        clock.advance(Duration.ofHours(12))

        val persisted = store.get("claimed-stays")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(persisted.version).isEqualTo(1L)
        assertThat(store.sweepExpired()).isZero()
    }

    @Test
    fun `expire on non-pending statuses rejected`() = runBlocking<Unit> {
        setupClaimed("expire-claimed")
        setupCompleted("expire-completed")
        setupCancelled("expire-cancelled")

        listOf("expire-claimed" to 1L, "expire-completed" to 2L, "expire-cancelled" to 1L)
            .forEach { (id, version) ->
                assertThat(runCatching { store.expire(id, version) }.exceptionOrNull())
                    .isInstanceOf(ApprovalContinuationConflictException::class.java)
            }
    }

    @Test
    fun `expire on already expired rejected`() = runBlocking<Unit> {
        createPending("expire-twice")
        clock.advance(Duration.ofSeconds(301))
        store.expire("expire-twice", 0L)

        assertThat(runCatching { store.expire("expire-twice", 1L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    // ── Cancellation ────────────────────────────────────────────────

    @Test
    fun `cancel pending`() = runBlocking<Unit> {
        createPending("cancel-1")

        val cancelled = store.cancel("cancel-1", 0L)

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(cancelled.version).isEqualTo(1L)
        val persisted = store.get("cancel-1")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        // Arguments are gone: a claim can no longer succeed.
        assertThat(runCatching { store.claimForExecution("cancel-1", 1L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `cancel on non-cancellable statuses rejected`() = runBlocking<Unit> {
        setupClaimed("cancel-claimed")
        setupCompleted("cancel-completed")
        setupCancelledUncertain("cancel-uncertain")

        listOf("cancel-claimed" to 1L, "cancel-completed" to 2L, "cancel-uncertain" to 2L)
            .forEach { (id, version) ->
                assertThat(runCatching { store.cancel(id, version) }.exceptionOrNull())
                    .isInstanceOf(ApprovalContinuationConflictException::class.java)
            }
    }

    @Test
    fun `cancel with stale version throws conflict`() = runBlocking<Unit> {
        createPending("stale-cancel")
        store.cancel("stale-cancel", 0L)
        assertThat(runCatching { store.cancel("stale-cancel", 0L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `cancel on missing approval throws not found`() = runBlocking<Unit> {
        assertThat(runCatching { store.cancel("missing-cancel", 0L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    // ── Completion ──────────────────────────────────────────────────

    @Test
    fun `complete claimed by claimant`() = runBlocking<Unit> {
        setupClaimed("complete-1", claimedBy = "worker-1")

        val completed = store.complete("complete-1", 1L, "worker-1")

        assertThat(completed.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(completed.version).isEqualTo(2L)
        assertThat(completed.completedAt).isEqualTo(t0)
        val persisted = store.get("complete-1")!!
        assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(persisted.completedAt).isEqualTo(t0)
    }

    @Test
    fun `complete by wrong actor rejected`() = runBlocking<Unit> {
        setupClaimed("complete-wrong", claimedBy = "worker-1")
        assertThat(runCatching { store.complete("complete-wrong", 1L, "intruder") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `complete with stale version throws conflict`() = runBlocking<Unit> {
        setupClaimed("complete-stale", claimedBy = "worker-1")
        assertThat(runCatching { store.complete("complete-stale", 0L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `complete on non-completable statuses rejected`() = runBlocking<Unit> {
        createPending("complete-pending")
        setupCancelled("complete-cancelled")
        createPending("complete-expired")
        clock.advance(Duration.ofSeconds(301))
        store.expire("complete-expired", 0L)

        listOf(
            "complete-pending" to 0L,
            "complete-cancelled" to 1L,
            "complete-expired" to 1L,
        ).forEach { (id, version) ->
            assertThat(runCatching { store.complete(id, version, "worker-1") }.exceptionOrNull())
                .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
        }
    }

    @Test
    fun `complete on missing approval throws not found`() = runBlocking<Unit> {
        assertThat(runCatching { store.complete("missing-complete", 0L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    // ── Recovery ────────────────────────────────────────────────────

    @Test
    fun `findStaleClaimed includes boundary claimedAt and excludes others`() = runBlocking<Unit> {
        setupClaimed("stale-boundary", claimedBy = "worker-1") // claimedAt == t0
        clock.advance(Duration.ofSeconds(10))
        setupClaimed("stale-fresh", claimedBy = "worker-1") // claimedAt == t0+10s — newer
        setupCompleted("stale-completed")
        setupCancelled("stale-cancelled")
        createPending("stale-pending")

        val stale = store.findStaleClaimed(t0, 100)

        assertThat(stale.map { it.approvalId }).containsExactly("stale-boundary")
    }

    @Test
    fun `findStaleClaimed orders by claimedAt then approvalId`() = runBlocking<Unit> {
        // All three claimed at the SAME instant with deliberately shuffled
        // ids: the secondary (approvalId) ordering is what makes the result
        // stable and discriminating.
        setupClaimed("stale-z", claimedBy = "worker-1")
        setupClaimed("stale-a", claimedBy = "worker-1")
        setupClaimed("stale-m", claimedBy = "worker-1")

        val stale = store.findStaleClaimed(t0, 100)

        // Ascending approvalId: stale-a < stale-m < stale-z.
        assertThat(stale.map { it.approvalId }).containsExactly("stale-a", "stale-m", "stale-z")
    }

    @Test
    fun `findStaleClaimed enforces limit`() = runBlocking<Unit> {
        setupClaimed("limit-1", claimedBy = "worker-1")
        setupClaimed("limit-2", claimedBy = "worker-1")
        setupClaimed("limit-3", claimedBy = "worker-1")

        val stale = store.findStaleClaimed(t0.plusSeconds(1), 2)

        assertThat(stale).hasSize(2)
    }

    @Test
    fun `findStaleClaimed invalid limit rejected`() = runBlocking<Unit> {
        assertThat(runCatching { store.findStaleClaimed(t0, 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.findStaleClaimed(t0, 101) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `forceCancelClaimed transitions to cancelled uncertain with recovery metadata`() = runBlocking<Unit> {
        setupClaimed("recover-1", claimedBy = "worker-1")

        val recovered = store.forceCancelClaimed("recover-1", 1L, "recovery-1", "stale-claim")

        assertThat(recovered.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
        assertThat(recovered.version).isEqualTo(2L)
        assertThat(recovered.recoveryResolvedBy).isEqualTo("recovery-1")
        assertThat(recovered.recoveryResolvedAt).isEqualTo(t0)
        assertThat(recovered.recoveryReasonCode).isEqualTo("stale-claim")
        assertThat(recovered.claimedBy).isEqualTo("worker-1")
    }

    @Test
    fun `forceCancelClaimed on non-claimed statuses rejected`() = runBlocking<Unit> {
        createPending("recover-pending")
        setupCompleted("recover-completed")
        setupCancelled("recover-cancelled")

        listOf(
            "recover-pending" to 0L,
            "recover-completed" to 2L,
            "recover-cancelled" to 1L,
        ).forEach { (id, version) ->
            assertThat(
                runCatching { store.forceCancelClaimed(id, version, "recovery-1", "stale-claim") }.exceptionOrNull(),
            ).isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        }
    }

    @Test
    fun `forceCancelClaimed rejects invalid reason codes`() = runBlocking<Unit> {
        setupClaimed("recover-reason", claimedBy = "worker-1")
        assertThat(
            runCatching { store.forceCancelClaimed("recover-reason", 1L, "recovery-1", "UPPERCASE") }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { store.forceCancelClaimed("recover-reason", 1L, "recovery-1", "has space") }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `forceCancelClaimed with stale version throws conflict`() = runBlocking<Unit> {
        setupClaimed("recover-stale", claimedBy = "worker-1")
        assertThat(
            runCatching { store.forceCancelClaimed("recover-stale", 0L, "recovery-1", "stale-claim") }.exceptionOrNull(),
        ).isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    // ── Sweep ───────────────────────────────────────────────────────

    @Test
    fun `sweep expires only elapsed pending rows and reports exact count`() = runBlocking<Unit> {
        createPending("sweep-a")
        createPending("sweep-b")
        clock.advance(Duration.ofSeconds(301))
        // Created after the deadline — must NOT be swept.
        createPending("sweep-fresh", createdAt = t0.plusSeconds(301), expiresAt = t0.plusSeconds(601))

        val count = store.sweepExpired()

        assertThat(count).isEqualTo(2)
        assertThat(store.get("sweep-a")!!.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(store.get("sweep-b")!!.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(store.get("sweep-fresh")!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
    }

    @Test
    fun `second sweep returns zero`() = runBlocking<Unit> {
        createPending("sweep-idem")
        clock.advance(Duration.ofSeconds(301))
        store.sweepExpired()

        assertThat(store.sweepExpired()).isZero()
    }

    @Test
    fun `sweep never touches claimed or terminal rows`() = runBlocking<Unit> {
        setupClaimed("sweep-claimed", claimedBy = "worker-1")
        setupCompleted("sweep-completed")
        setupCancelled("sweep-cancelled")
        createPending("sweep-expired")
        clock.advance(Duration.ofHours(2))
        store.sweepExpired()

        assertThat(store.sweepExpired()).isZero()
        assertThat(store.get("sweep-claimed")!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(store.get("sweep-completed")!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(store.get("sweep-cancelled")!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(store.get("sweep-expired")!!.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
    }

    // ── Concurrency ─────────────────────────────────────────────────

    /**
     * Runs [contenders] on parallel workers, releasing them only once every
     * contender is ready, so the operations genuinely overlap instead of
     * serializing on the caller's single-threaded event loop.
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
    fun `concurrent claims - exactly one winner releases arguments`() = runBlocking<Unit> {
        repeat(5) { iteration ->
            val id = "claim-race-$iteration"
            createPending(id)

            val outcomes = runInParallel(
                { runCatching { store.claimForExecution(id, 0L, "worker-a") } },
                { runCatching { store.claimForExecution(id, 0L, "worker-b") } },
            )

            val successes = outcomes.filter { it.isSuccess }
            val losers = outcomes.filter { it.isFailure }

            assertThat(successes).hasSize(1)
            assertThat(losers).hasSize(1)
            assertThat(losers.single().exceptionOrNull())
                .isInstanceOf(ApprovalContinuationStoreException::class.java)

            val winner = successes.single().getOrThrow()
            assertThat(winner.arguments.reveal()).isEqualTo(ApprovalContinuationFixtures.DEFAULT_ARGUMENTS)

            val persisted = store.get(id)!!
            assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
            assertThat(persisted.version).isEqualTo(1L)
            // Loser must not be able to retrieve arguments either.
            assertThat(runCatching { store.claimForExecution(id, 1L, "worker-c") }.exceptionOrNull())
                .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        }
    }

    @Test
    fun `concurrent claim vs cancel - exactly one legal transition`() = runBlocking<Unit> {
        repeat(5) { iteration ->
            val id = "claim-cancel-race-$iteration"
            createPending(id)

            val outcomes = runInParallel(
                { runCatching { store.claimForExecution(id, 0L, "worker-a") } },
                { runCatching { store.cancel(id, 0L) } },
            )

            val successes = outcomes.count { it.isSuccess }
            val losers = outcomes.count { it.isFailure }
            assertThat(successes).isEqualTo(1)
            assertThat(losers).isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.single().exceptionOrNull())
                .isInstanceOf(ApprovalContinuationStoreException::class.java)

            val persisted = store.get(id)!!
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(persisted.status).isIn(ApprovalContinuationStatus.CLAIMED, ApprovalContinuationStatus.CANCELLED)
        }
    }

    @Test
    fun `competing same-version cancels cannot both succeed`() = runBlocking<Unit> {
        repeat(5) { iteration ->
            val id = "cancel-race-$iteration"
            createPending(id)

            val outcomes = runInParallel(
                { runCatching { store.cancel(id, 0L) } },
                { runCatching { store.cancel(id, 0L) } },
            )

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.count { it.exceptionOrNull() is ApprovalContinuationConflictException }).isEqualTo(1)

            val persisted = store.get(id)!!
            assertThat(persisted.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
            assertThat(persisted.version).isEqualTo(1L)
        }
    }
}
