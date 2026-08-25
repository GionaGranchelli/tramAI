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
    fun `pre-populated claimed fields rejected independently`() = runBlocking<Unit> {
        // One forbidden field at a time — a store that stops validating a
        // single field must go RED, not hide behind another populated field.
        val cases = listOf(
            "bad-claimed-by" to { c: ApprovalContinuation -> c.copy(claimedBy = "worker-1") },
            "bad-claimed-at" to { c: ApprovalContinuation -> c.copy(claimedAt = t0) },
        )
        cases.forEach { (id, tamper) ->
            val (continuation, arguments) = pending(id)
            assertThat(runCatching { store.create(tamper(continuation), arguments) }.exceptionOrNull())
                .withFailMessage("create must reject pre-populated $id")
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `pre-populated completion and recovery fields rejected independently`() = runBlocking<Unit> {
        val cases = listOf(
            "bad-completed-at" to { c: ApprovalContinuation -> c.copy(completedAt = t0) },
            "bad-recovery-by" to { c: ApprovalContinuation -> c.copy(recoveryResolvedBy = "recovery-1") },
            "bad-recovery-at" to { c: ApprovalContinuation -> c.copy(recoveryResolvedAt = t0) },
            "bad-recovery-reason" to { c: ApprovalContinuation -> c.copy(recoveryReasonCode = "stale-claim") },
        )
        cases.forEach { (id, tamper) ->
            val (continuation, arguments) = pending(id)
            assertThat(runCatching { store.create(tamper(continuation), arguments) }.exceptionOrNull())
                .withFailMessage("create must reject pre-populated $id")
                .isInstanceOf(IllegalArgumentException::class.java)
        }
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
    fun `second claim cannot expose released arguments`() = runBlocking<Unit> {
        // Externally observable exactly-once release: after a claim, the
        // arguments can never be exposed through the only API that reveals
        // them — not even to the claimant again. Physical scrubbing of the
        // encrypted payload is the implementation-specific suites' concern
        // (JDBC asserts encrypted_arguments becomes NULL directly).
        createPending("cleared-1")
        store.claimForExecution("cleared-1", 0L, "worker-1")

        val second = runCatching { store.claimForExecution("cleared-1", 1L, "worker-2") }.exceptionOrNull()
        assertThat(second).isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
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
        setupCancelled("cancel-cancelled")
        setupCancelledUncertain("cancel-uncertain")
        createPending("cancel-expired")
        clock.advance(Duration.ofSeconds(301))
        store.expire("cancel-expired", 0L)

        listOf(
            "cancel-claimed" to 1L,
            "cancel-completed" to 2L,
            "cancel-cancelled" to 1L,
            "cancel-uncertain" to 2L,
            "cancel-expired" to 1L,
        ).forEach { (id, version) ->
            assertThat(runCatching { store.cancel(id, version) }.exceptionOrNull())
                .withFailMessage("cancel must reject $id")
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
        setupCompleted("complete-completed")
        setupCancelledUncertain("complete-uncertain")
        createPending("complete-expired")
        clock.advance(Duration.ofSeconds(301))
        store.expire("complete-expired", 0L)

        listOf(
            "complete-pending" to 0L,
            "complete-cancelled" to 1L,
            "complete-completed" to 2L,
            "complete-uncertain" to 2L,
            "complete-expired" to 1L,
        ).forEach { (id, version) ->
            assertThat(runCatching { store.complete(id, version, "worker-1") }.exceptionOrNull())
                .withFailMessage("complete must reject $id")
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
        setupCancelledUncertain("recover-uncertain")
        createPending("recover-expired")
        clock.advance(Duration.ofSeconds(301))
        store.expire("recover-expired", 0L)

        listOf(
            "recover-pending" to 0L,
            "recover-completed" to 2L,
            "recover-cancelled" to 1L,
            "recover-uncertain" to 2L,
            "recover-expired" to 1L,
        ).forEach { (id, version) ->
            assertThat(
                runCatching { store.forceCancelClaimed(id, version, "recovery-1", "stale-claim") }.exceptionOrNull(),
            ).withFailMessage("forceCancelClaimed must reject $id")
                .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
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
            // A claim that loses observes a stale version (1 vs expected 0)
            // and must report Conflict — not a broad store exception.
            assertThat(losers.single().exceptionOrNull())
                .isInstanceOf(ApprovalContinuationConflictException::class.java)

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
            // Whichever operation loses sees the winner's version (1) against
            // its own expected 0, so it must report Conflict — the same typed
            // outcome in every implementation.
            assertThat(outcomes.filter { it.isFailure }.single().exceptionOrNull())
                .isInstanceOf(ApprovalContinuationConflictException::class.java)

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

    // ── Epic 8.2b: model-based lifecycle properties ─────────────────

    /**
     * Reconstructs the expected durable continuation from the model's
     * predicted post-action state and the immutable identity fields of the
     * originally created continuation.
     */
    private fun reconstruct(
        immutable: ApprovalContinuation,
        m: ApprovalContinuationLifecycleModel,
    ): ApprovalContinuation =
        immutable.copy(
            status = m.status,
            version = m.version,
            claimedBy = m.claimedBy,
            claimedAt = m.claimedAt,
            completedAt = m.completedAt,
            recoveryResolvedBy = m.recoveryResolvedBy,
            recoveryResolvedAt = m.recoveryResolvedAt,
            recoveryReasonCode = m.recoveryReasonCode,
        )

    private fun assertLifecycleInvariants(
        m: ApprovalContinuationLifecycleModel,
        prev: ApprovalContinuationLifecycleModel?,
    ) {
        if (prev != null) {
            assertThat(m.version).isGreaterThanOrEqualTo(prev.version)
        }
        assertThat(m.version).isLessThanOrEqualTo(2L)
        when (m.status) {
            ApprovalContinuationStatus.PENDING -> {
                assertThat(m.version).isEqualTo(0L)
                assertThat(m.claimedBy).isNull()
                assertThat(m.claimedAt).isNull()
                assertThat(m.completedAt).isNull()
                assertThat(m.recoveryResolvedBy).isNull()
                assertThat(m.recoveryResolvedAt).isNull()
                assertThat(m.recoveryReasonCode).isNull()
            }
            ApprovalContinuationStatus.CLAIMED -> {
                assertThat(m.version).isEqualTo(1L)
                assertThat(m.claimedBy).isNotNull()
                assertThat(m.claimedAt).isNotNull()
                assertThat(m.completedAt).isNull()
                assertThat(m.recoveryResolvedBy).isNull()
            }
            ApprovalContinuationStatus.COMPLETED -> {
                assertThat(m.version).isEqualTo(2L)
                assertThat(m.claimedBy).isNotNull()
                assertThat(m.claimedAt).isNotNull()
                assertThat(m.completedAt).isNotNull()
                assertThat(m.recoveryResolvedBy).isNull()
            }
            ApprovalContinuationStatus.CANCELLED,
            ApprovalContinuationStatus.EXPIRED,
            -> {
                assertThat(m.version).isEqualTo(1L)
                assertThat(m.claimedBy).isNull()
                assertThat(m.claimedAt).isNull()
                assertThat(m.completedAt).isNull()
                assertThat(m.recoveryResolvedBy).isNull()
            }
            ApprovalContinuationStatus.CANCELLED_UNCERTAIN -> {
                assertThat(m.version).isEqualTo(2L)
                assertThat(m.claimedBy).isNotNull()
                assertThat(m.claimedAt).isNotNull()
                assertThat(m.completedAt).isNull()
                assertThat(m.recoveryResolvedBy).isNotNull()
                assertThat(m.recoveryResolvedAt).isNotNull()
                assertThat(m.recoveryReasonCode).isNotNull()
            }
        }
        if (m.argumentsAvailable) {
            assertThat(m.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        }
    }

    private sealed interface ExecutedContinuationAction {
        data class Claimed(val result: ClaimedApprovalContinuation) : ExecutedContinuationAction
        data class Plain(val result: ApprovalContinuation) : ExecutedContinuationAction
        data class Read(val result: ApprovalContinuation?) : ExecutedContinuationAction
        data class Failed(val exception: Throwable) : ExecutedContinuationAction
    }

    private suspend fun executeContinuationAction(
        id: String,
        action: ApprovalContinuationLifecycleAction,
        model: ApprovalContinuationLifecycleModel,
    ): ExecutedContinuationAction = when (action) {
        is ApprovalContinuationLifecycleAction.AdvanceToBeforeExpiry,
        is ApprovalContinuationLifecycleAction.AdvanceToExactExpiry,
        is ApprovalContinuationLifecycleAction.AdvancePastExpiry,
        -> ExecutedContinuationAction.Read(store.get(id))

        ApprovalContinuationLifecycleAction.Get -> ExecutedContinuationAction.Read(store.get(id))
        is ApprovalContinuationLifecycleAction.ClaimCurrentVersion ->
            runCatching { store.claimForExecution(id, model.version, action.worker) }
                .fold(
                    { ExecutedContinuationAction.Claimed(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.ClaimWrongVersion ->
            runCatching { store.claimForExecution(id, model.version + 1, action.worker) }
                .fold(
                    { ExecutedContinuationAction.Claimed(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        ApprovalContinuationLifecycleAction.CancelCurrentVersion ->
            runCatching { store.cancel(id, model.version) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        ApprovalContinuationLifecycleAction.CancelWrongVersion ->
            runCatching { store.cancel(id, model.version + 1) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        ApprovalContinuationLifecycleAction.ExpireCurrentVersion ->
            runCatching { store.expire(id, model.version) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        ApprovalContinuationLifecycleAction.ExpireWrongVersion ->
            runCatching { store.expire(id, model.version + 1) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.CompleteCurrentVersion ->
            runCatching { store.complete(id, model.version, action.worker) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.CompleteWrongVersion ->
            runCatching { store.complete(id, model.version + 1, action.worker) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.CompleteWrongActor ->
            runCatching { store.complete(id, model.version, action.intruder) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion ->
            runCatching { store.forceCancelClaimed(id, model.version, action.recoveryActor, action.reasonCode) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
        is ApprovalContinuationLifecycleAction.ForceCancelWrongVersion ->
            runCatching { store.forceCancelClaimed(id, model.version + 1, action.recoveryActor, action.reasonCode) }
                .fold(
                    { ExecutedContinuationAction.Plain(it) },
                    { ExecutedContinuationAction.Failed(it) },
                )
    }

    private fun expectedException(kind: ApprovalContinuationLifecycleFailureKind): Class<*> = when (kind) {
        ApprovalContinuationLifecycleFailureKind.CONFLICT -> ApprovalContinuationConflictException::class.java
        ApprovalContinuationLifecycleFailureKind.NOT_CLAIMABLE -> ApprovalContinuationNotClaimableException::class.java
        ApprovalContinuationLifecycleFailureKind.NOT_COMPLETABLE -> ApprovalContinuationNotCompletableException::class.java
    }

    @Test
    fun `generated continuation lifecycle sequences match the independent model after every action`() = runBlocking<Unit> {
        for (seed in 0L until ApprovalContinuationLifecycleActionGenerator.SEED_COUNT) {
            val id = "generated-$seed"
            clock.set(t0)
            val (original, _) = createPending(id)
            val rawArguments = ApprovalContinuationFixtures.DEFAULT_ARGUMENTS
            var model = ApprovalContinuationLifecycleModel.pending(t0)
            val actions = ApprovalContinuationLifecycleActionGenerator.generate(seed, initialNow = t0, expiresAt = expiry)
            var previous: ApprovalContinuationLifecycleModel? = null
            var released = false

            actions.forEachIndexed { step, action ->
                val observationTime = model.now
                clock.set(observationTime)
                val predicted = model.apply(action, expiry)
                val executed = executeContinuationAction(id, action, model)

                when (predicted) {
                    is ApprovalContinuationLifecycleOutcome.Success -> {
                        val message = "seed=$seed step=$step action=${action.describe()} prefix=${actions.take(step).joinToString(",") { it.describe() }}\n" +
                            "modelBefore=$model\nmodelAfter=${predicted.next}\nexpected=$predicted\nactual=$executed"
                        when (val actual = executed) {
                            is ExecutedContinuationAction.Claimed -> {
                                assertThat(actual.result.continuation).withFailMessage(message).isEqualTo(reconstruct(original, predicted.next))
                                if (predicted.releasedArguments) {
                                    assertThat(released).withFailMessage("seed=$seed step=$step: arguments released twice").isFalse()
                                    released = true
                                    assertThat(actual.result.arguments.reveal()).withFailMessage(message).isEqualTo(rawArguments)
                                } else {
                                    assertThat(released).withFailMessage(message).isTrue()
                                }
                            }
                            is ExecutedContinuationAction.Plain -> {
                                assertThat(actual.result).withFailMessage(message).isEqualTo(reconstruct(original, predicted.next))
                            }
                            is ExecutedContinuationAction.Read -> {
                                assertThat(actual.result).withFailMessage(message).isEqualTo(reconstruct(original, predicted.next))
                            }
                            is ExecutedContinuationAction.Failed -> {
                                assertThat(actual.exception).withFailMessage(message).isNull()
                            }
                        }
                    }
                    is ApprovalContinuationLifecycleOutcome.Failure -> {
                        val message = "seed=$seed step=$step action=${action.describe()} prefix=${actions.take(step).joinToString(",") { it.describe() }}\n" +
                            "modelBefore=$model\nmodelAfter=${predicted.next}\nexpected=${predicted.kind}\nactual=$executed"
                        val actual = executed as? ExecutedContinuationAction.Failed
                        assertThat(actual).withFailMessage("$message\nexpected typed failure, got success").isNotNull()
                        assertThat(actual!!.exception).withFailMessage(message).isInstanceOf(expectedException(predicted.kind))
                    }
                }

                model = when (predicted) {
                    is ApprovalContinuationLifecycleOutcome.Success -> predicted.next
                    is ApprovalContinuationLifecycleOutcome.Failure -> predicted.next
                }

                // Observation-time comparison: store.get() runs at the same
                // clock instant as the executed action, and get() itself can
                // trigger lazy expiry of a still-PENDING record — so the
                // model normalizes BEFORE the observation. normalized() is
                // idempotent, so an observation of an already-EXPIRED record
                // never re-increments, and the observed transition is a real
                // durable one (the model follows the store).
                val observationModel = model.normalizedAt(observationTime, expiry)
                assertLifecycleInvariants(observationModel, previous)
                val observed = store.get(id)!!
                val expected = reconstruct(original, observationModel)
                assertThat(observed)
                    .withFailMessage("seed=$seed step=$step observation mismatch\nexpected=$expected\nactual=$observed")
                    .isEqualTo(expected)

                previous = observationModel
                model = observationModel
            }
        }
    }

    @Test
    fun `failed late claim persists expired state before reporting`() = runBlocking<Unit> {
        // A failed operation can legitimately mutate durable state: a late
        // claim (PENDING, clock past expiry) must persist EXPIRED@v1 and
        // discard arguments BEFORE throwing NotClaimable. The clock is
        // rewound to t0 before the read so this assertion's own get() cannot
        // lazily expire a still-PENDING record and mask the missing
        // transition — the failed operation itself must have normalized.
        val id = "late-claim-persists"
        clock.set(t0)
        createPending(id)
        clock.set(expiry.plusSeconds(1))
        assertThat(runCatching { store.claimForExecution(id, 0L, "worker-1") }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        clock.set(t0)
        val durable = store.get(id)!!
        assertThat(durable.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(durable.version).isEqualTo(1L)
    }

    @Test
    fun `failed late cancel persists expired state before reporting`() = runBlocking<Unit> {
        // Same contract for cancel: a late cancel must persist EXPIRED@v1
        // before throwing Conflict; the rewind makes the assertion read
        // unable to repair a missing normalization via lazy expiry.
        val id = "late-cancel-persists"
        clock.set(t0)
        createPending(id)
        clock.set(expiry.plusSeconds(1))
        assertThat(runCatching { store.cancel(id, 0L) }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
        clock.set(t0)
        val durable = store.get(id)!!
        assertThat(durable.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(durable.version).isEqualTo(1L)
    }

    @Test
    fun `wrong-version continuation operations always conflict without changing durable state`() = runBlocking<Unit> {
        // PENDING before expiry: every wrong-version probe conflicts
        // (version checked first on the non-expiry path) and leaves the
        // durable record value-identical.
        val pendingId = "matrix-pending"
        val (pendingOriginal, pendingArgs) = createPending(pendingId)
        listOf<suspend () -> Any>(
            { store.claimForExecution(pendingId, 1L, "worker-1") },
            { store.cancel(pendingId, 1L) },
            { store.expire(pendingId, 1L) },
        ).forEach { op ->
            assertThat(runCatching { op() }.exceptionOrNull())
                .isInstanceOf(ApprovalContinuationConflictException::class.java)
            assertThat(store.get(pendingId)).isEqualTo(pendingOriginal)
        }
        assertThat(store.claimForExecution(pendingId, 0L, "worker-1").arguments.reveal())
            .isEqualTo(pendingArgs.reveal())

        // CLAIMED@1: complete/forceCancel/cancel with a stale expected
        // version conflict and leave the durable record value-identical.
        val claimedId = "matrix-claimed"
        setupClaimed(claimedId, claimedBy = "worker-1")
        val claimedOriginal = store.get(claimedId)!!
        listOf<suspend () -> Any>(
            { store.complete(claimedId, 0L, "worker-1") },
            { store.forceCancelClaimed(claimedId, 0L, "recovery-1", "stale-claim") },
            { store.cancel(claimedId, 0L) },
        ).forEach { op ->
            assertThat(runCatching { op() }.exceptionOrNull())
                .isInstanceOf(ApprovalContinuationConflictException::class.java)
            assertThat(store.get(claimedId)).isEqualTo(claimedOriginal)
        }
    }

    @Test
    fun `eight concurrent claims - exactly one winner releases arguments once`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "claim-race-$iteration"
            createPending(id)

            val contenders = Array<suspend () -> Result<ClaimedApprovalContinuation>>(8) { index ->
                { runCatching { store.claimForExecution(id, 0L, "worker-$index") } }
            }
            val outcomes = runInParallel(*contenders)

            val successes = outcomes.filter { it.isSuccess }
            assertThat(successes.size)
                .withFailMessage("iteration $iteration: exactly one fresh claim winner expected")
                .isEqualTo(1)
            // Whole-consume atomicity (InMemory CAS, File per-record lock,
            // JDBC FOR UPDATE row-lock) serializes every loser AFTER the
            // winner: the loser reads CLAIMED@1 against its expected 0 and
            // must report Conflict — the only legal serialized loser outcome.
            val loserFailures = outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }
            assertThat(loserFailures.size).isEqualTo(7)
            assertThat(loserFailures.map { it?.javaClass }).allMatch {
                it == ApprovalContinuationConflictException::class.java
            }

            val winnerIndex = outcomes.indexOfFirst { it.isSuccess }
            val winner = successes.single().getOrThrow()
            assertThat(winner.arguments.reveal()).isEqualTo(ApprovalContinuationFixtures.DEFAULT_ARGUMENTS)

            val durable = store.get(id)!!
            assertThat(durable.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
            assertThat(durable.version).isEqualTo(1L)
            assertThat(durable.claimedBy).isEqualTo("worker-$winnerIndex")
            assertThat(durable.claimedAt).isEqualTo(t0)

            // No second release through the only API that reveals arguments.
            assertThat(runCatching { store.claimForExecution(id, 1L, "worker-other") }.exceptionOrNull())
                .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        }
    }

    @Test
    fun `mixed claim and cancel race - one legal transition, no double release`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "claim-cancel-race-$iteration"
            createPending(id)

            val contenders = Array<suspend () -> Result<Any?>>(8) { index ->
                if (index % 2 == 0) {
                    { runCatching<Any?> { store.claimForExecution(id, 0L, "worker-$index") } }
                } else {
                    { runCatching<Any?> { store.cancel(id, 0L) } }
                }
            }
            val outcomes = runInParallel(*contenders)

            val successes = outcomes.filter { it.isSuccess }
            assertThat(successes.size)
                .withFailMessage("iteration $iteration: exactly one success expected")
                .isEqualTo(1)
            val losers = outcomes.filter { it.isFailure }
            assertThat(losers.size).isEqualTo(7)
            assertThat(losers.map { it.exceptionOrNull()?.javaClass }).allMatch {
                it == ApprovalContinuationConflictException::class.java
            }

            val durable = store.get(id)!!
            assertThat(durable.version).isEqualTo(1L)
            when (durable.status) {
                ApprovalContinuationStatus.CLAIMED -> {
                    val winner = successes.single().getOrThrow() as ClaimedApprovalContinuation
                    assertThat(winner.arguments.reveal()).isEqualTo(ApprovalContinuationFixtures.DEFAULT_ARGUMENTS)
                    assertThat(durable.claimedBy).isEqualTo(winner.continuation.claimedBy)
                }
                ApprovalContinuationStatus.CANCELLED -> {
                    // The claim losers conflicted before any release: zero
                    // raw-argument exposure on the cancel path.
                    assertThat(successes.single().getOrThrow()).isInstanceOf(ApprovalContinuation::class.java)
                }
                else -> throw AssertionError("iteration $iteration: unexpected final status ${durable.status}")
            }
        }
    }

    @Test
    fun `claimed resolution race - complete and recovery linearize to one winner`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "resolve-race-$iteration"
            setupClaimed(id, claimedBy = "worker-a")

            val contenders = Array<suspend () -> Result<Any?>>(8) { index ->
                if (index % 2 == 0) {
                    { runCatching<Any?> { store.complete(id, 1L, "worker-a") } }
                } else {
                    { runCatching<Any?> { store.forceCancelClaimed(id, 1L, "recovery-$index", "stale-claim") } }
                }
            }
            val outcomes = runInParallel(*contenders)

            assertThat(outcomes.count { it.isSuccess })
                .withFailMessage("iteration $iteration: exactly one resolution winner expected")
                .isEqualTo(1)
            val losers = outcomes.filter { it.isFailure }
            assertThat(losers.size).isEqualTo(7)
            // Both operations check the version before anything else, so a
            // loser observes v2 against its expected 1: Conflict is the only
            // legal serialized loser outcome.
            assertThat(losers.map { it.exceptionOrNull()?.javaClass }).allMatch {
                it == ApprovalContinuationConflictException::class.java
            }

            val durable = store.get(id)!!
            assertThat(durable.version).isEqualTo(2L)
            when (durable.status) {
                ApprovalContinuationStatus.COMPLETED -> {
                    assertThat(durable.completedAt).isNotNull()
                    assertThat(durable.recoveryResolvedBy).isNull()
                    assertThat(durable.recoveryResolvedAt).isNull()
                    assertThat(durable.recoveryReasonCode).isNull()
                }
                ApprovalContinuationStatus.CANCELLED_UNCERTAIN -> {
                    assertThat(durable.completedAt).isNull()
                    assertThat(durable.recoveryResolvedBy).isNotNull()
                    assertThat(durable.recoveryResolvedAt).isNotNull()
                    assertThat(durable.recoveryReasonCode).isEqualTo("stale-claim")
                }
                else -> throw AssertionError("iteration $iteration: unexpected final status ${durable.status}")
            }
        }
    }

    @Test
    fun `concurrent lazy expiry is one legal transition under concurrent observers`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val id = "lazy-race-$iteration"
            clock.set(t0)
            createPending(id)
            clock.set(expiry) // exactly the boundary
            val outcomes = runInParallel(*Array(8) { { store.get(id) } })

            outcomes.forEach { observed ->
                assertThat(observed).isNotNull()
                assertThat(observed!!.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
                assertThat(observed.version).isEqualTo(1L)
            }
            val durable = store.get(id)!!
            assertThat(durable.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
            assertThat(durable.version).isEqualTo(1L)
        }
    }

    @Test
    fun `generated sweep model - only elapsed pending rows transition exactly once`() = runBlocking<Unit> {
        for (seed in 0L until 16L) {
            val rng = kotlin.random.Random(seed)
            clock.set(t0)
            val swept = ArrayList<String>()
            val livePending = ArrayList<String>()
            // 27 deterministic records: 12 PENDING with expiry chosen by the
            // seed from {past, exact-boundary, live}, plus 3 each of CLAIMED,
            // COMPLETED, CANCELLED, CANCELLED_UNCERTAIN and already-EXPIRED
            // rows (12 + 15 = 27).
            val pendingExpiries = List(12) { i ->
                when (rng.nextInt(3)) {
                    0 -> expiry.minusSeconds(120) // past
                    1 -> expiry // exact boundary
                    else -> expiry.plusSeconds(300) // live
                }
            }
            pendingExpiries.forEachIndexed { i, expiresAt ->
                val id = "sweep-$seed-p$i"
                createPending(id, createdAt = t0, expiresAt = expiresAt)
                if (expiresAt <= expiry) swept.add(id) else livePending.add(id)
            }
            val claimedIds = ArrayList<String>()
            repeat(3) { i ->
                val id = "sweep-$seed-c$i"
                claimedIds.add(id)
                setupClaimed(id)
            }
            val completedIds = ArrayList<String>()
            repeat(3) { i ->
                val id = "sweep-$seed-d$i"
                completedIds.add(id)
                setupCompleted(id)
            }
            val cancelledIds = ArrayList<String>()
            repeat(3) { i ->
                val id = "sweep-$seed-x$i"
                cancelledIds.add(id)
                setupCancelled(id)
            }
            val uncertainIds = ArrayList<String>()
            repeat(3) { i ->
                val id = "sweep-$seed-u$i"
                uncertainIds.add(id)
                setupCancelledUncertain(id)
            }
            val expiredIds = ArrayList<String>()
            repeat(3) { i ->
                val id = "sweep-$seed-e$i"
                expiredIds.add(id)
                clock.set(t0)
                createPending(id)
                clock.set(expiry.plusSeconds(1))
                store.expire(id, 0L)
            }
            // Capture every row the sweep must NOT touch (non-PENDING rows and
            // live PENDING rows) before the sweep, then assert value-identity
            // after: sweep must leave CLAIMED and terminal rows untouched.
            clock.set(t0)
            val untouchedIds = claimedIds + completedIds + cancelledIds + uncertainIds + expiredIds + livePending
            val preSweep = untouchedIds.associateWith { store.get(it)!! }
            clock.set(expiry)

            val count = store.sweepExpired()

            assertThat(count)
                .withFailMessage("seed $seed: sweep must transition exactly the elapsed pending rows")
                .isEqualTo(swept.size)
            swept.forEach { id ->
                val durable = store.get(id)!!
                assertThat(durable.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
                assertThat(durable.version).isEqualTo(1L)
            }
            untouchedIds.forEach { id ->
                assertThat(store.get(id))
                    .withFailMessage("seed $seed: sweep mutated untouched record $id")
                    .isEqualTo(preSweep[id])
            }
            assertThat(store.sweepExpired()).isZero()
        }
    }

    @Test
    fun `generated stale-claim query model - boundary ordering and limit`() = runBlocking<Unit> {
        // The store accumulates records across seeds within this test, so
        // the model accumulates too: each seed's query is checked against
        // every claimed record created so far (deterministic — ids and
        // claimedAt offsets are seed-fixed).
        val claimed = HashMap<String, Instant?>()
        for (seed in 0L until 16L) {
            val rng = kotlin.random.Random(seed)
            val boundary = t0.plusSeconds(15)
            val ids = (0 until 20).map { "stale-$seed-$it" }.shuffled(rng)

            ids.forEachIndexed { i, id ->
                val claimedAt = when (i % 5) {
                    0, 1 -> t0 // before boundary
                    2 -> t0.plusSeconds(10) // before boundary
                    3 -> t0.plusSeconds(15) // exactly at the boundary — inclusive contract
                    else -> t0.plusSeconds(20) // after boundary
                }
                clock.set(claimedAt)
                createPending(id)
                store.claimForExecution(id, 0L, "worker-1")
                claimed[id] = claimedAt
            }
            repeat(4) { i ->
                val id = "stale-$seed-nc$i"
                clock.set(t0)
                createPending(id)
                claimed[id] = null
            }
            clock.set(boundary)

            val limit = 1 + rng.nextInt(14) // 1..14
            val actual = store.findStaleClaimed(boundary, limit)
            val expectedIds = claimed
                .filterValues { it != null && !it.isAfter(boundary) }
                .toList()
                .sortedWith(compareBy<Pair<String, Instant?>> { it.second!! }.thenBy { it.first })
                .take(limit)
                .map { it.first }

            assertThat(actual.map { it.approvalId })
                .withFailMessage("seed $seed limit=$limit\nmodel=$expectedIds\nactual=${actual.map { it.approvalId }}")
                .containsExactlyElementsOf(expectedIds)
        }
    }
}
