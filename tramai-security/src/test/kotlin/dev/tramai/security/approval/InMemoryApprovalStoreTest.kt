package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.IllegalApprovalTransitionException
import dev.tramai.core.approval.Sha256Digest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A test clock whose instant can be advanced programmatically.
 * Used to test time-dependent behavior without reflection.
 */
class MutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    override fun getZone(): ZoneId = zone

    fun advance(amount: java.time.Duration) { now = now.plus(amount) }
    fun set(newNow: Instant) { now = newNow }
}

class InMemoryApprovalStoreTest {

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-06-04T10:00:00Z"),
        ZoneId.of("UTC"),
    )

    private lateinit var store: InMemoryApprovalStore

    @BeforeEach
    fun setUp() {
        store = InMemoryApprovalStore(clock = fixedClock)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun aPendingRequest(
        approvalId: String = "req-1",
        workflowRunId: String = "wf-run-1",
        toolName: String = "search-tool",
        argumentsDigest: Sha256Digest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
        policyVersion: String = "v1",
        workflowDigest: Sha256Digest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111"),
        approvalTokenDigest: Sha256Digest = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
        requestedBy: String = "user-1",
        requestedAt: Instant = fixedClock.instant(),
        expiresAt: Instant = fixedClock.instant().plusSeconds(3600),
        version: Long = 0L,
    ) = ApprovalRequest(
        approvalId = approvalId,
        binding = ApprovalBinding(
            workflowRunId = workflowRunId,
            toolName = toolName,
            argumentsDigest = argumentsDigest,
            policyVersion = policyVersion,
            workflowDigest = workflowDigest,
            approvalTokenDigest = approvalTokenDigest,
        ),
        status = ApprovalStatus.PENDING,
        requestedBy = requestedBy,
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        consumedBy = null,
        consumedAt = null,
        version = version,
    )

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    fun `create stores a pending approval`() : Unit = runBlocking {
        val request = aPendingRequest()
        val created = store.create(request)

        assertThat(created).isEqualTo(request)
        assertThat(created.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `get returns the stored approval`() : Unit = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val retrieved = store.get("req-1")
        assertThat(retrieved).isNotNull
        assertThat(retrieved!!.approvalId).isEqualTo("req-1")
        assertThat(retrieved.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `get returns null for nonexistent approval`() : Unit = runBlocking {
        val retrieved = store.get("does-not-exist")
        assertThat(retrieved).isNull()
    }

    @Test
    fun `approve a pending request transitions to APPROVED`() : Unit = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val updated = store.transition("req-1", 0L, ApprovalTransition.Approve("user-2", "Looks good"))

        assertThat(updated.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isEqualTo("user-2")
        assertThat(updated.decisionComment).isEqualTo("Looks good")
        assertThat(updated.decidedAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `deny a pending request transitions to DENIED`() : Unit = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val updated = store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", "Not appropriate"))

        assertThat(updated.status).isEqualTo(ApprovalStatus.DENIED)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isEqualTo("user-2")
        assertThat(updated.decisionComment).isEqualTo("Not appropriate")
    }

    @Test
    fun `timeout an expired pending request transitions to TIMED_OUT`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T08:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        val request = aPendingRequest(
            approvalId = "expired-to",
            requestedAt = Instant.parse("2026-06-04T08:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        )
        store2.create(request)

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        val updated = store2.transition("expired-to", 0L, ApprovalTransition.Timeout)

        assertThat(updated.status).isEqualTo(ApprovalStatus.TIMED_OUT)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isNull()
        assertThat(updated.decisionComment).isNull()
        assertThat(updated.decidedAt).isEqualTo(mutableClock.instant())
    }

    // -----------------------------------------------------------------------
    // Illegal transitions from terminal states
    // -----------------------------------------------------------------------

    @Test
    fun `approve an already approved request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already granted")
    }

    @Test
    fun `deny a denied request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Deny("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already denied")
    }

    @Test
    fun `approve a denied request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already denied")
    }

    @Test
    fun `approve a timed-out request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T08:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "timed-out",
            requestedAt = Instant.parse("2026-06-04T08:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        store2.transition("timed-out", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store2.transition("timed-out", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already timed out")
    }

    @Test
    fun `timeout a timed-out request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T08:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "timed-out-2",
            requestedAt = Instant.parse("2026-06-04T08:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        store2.transition("timed-out-2", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store2.transition("timed-out-2", 1L, ApprovalTransition.Timeout) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already timed out")
    }

    // -----------------------------------------------------------------------
    // Expiry
    // -----------------------------------------------------------------------

    @Test
    fun `approve an expired pending request throws IllegalApprovalTransitionException mentioning expired`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T09:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "expired-req",
            requestedAt = Instant.parse("2026-06-04T09:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Not expired yet - approve should work
        store2.transition("expired-req", 0L, ApprovalTransition.Approve("user-2", null))

        // Create another request
        store2.create(aPendingRequest(
            approvalId = "expired-req-2",
            requestedAt = Instant.parse("2026-06-04T09:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        assertThatThrownBy {
            runBlocking { store2.transition("expired-req-2", 0L, ApprovalTransition.Approve("user-2", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("expired")
    }

    @Test
    fun `timeout on an expired pending request succeeds`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T09:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "expired-req",
            requestedAt = Instant.parse("2026-06-04T09:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        val updated = store2.transition("expired-req", 0L, ApprovalTransition.Timeout)

        assertThat(updated.status).isEqualTo(ApprovalStatus.TIMED_OUT)
    }

    // -----------------------------------------------------------------------
    // Timeout before expiry is rejected
    // -----------------------------------------------------------------------

    @Test
    fun `timeout on non-expired pending request throws IllegalApprovalTransitionException`() : Unit = runBlocking {
        store.create(aPendingRequest(approvalId = "not-yet-expired"))

        assertThatThrownBy {
            runBlocking { store.transition("not-yet-expired", 0L, ApprovalTransition.Timeout) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("Cannot time out approval before expiry")
    }

    // -----------------------------------------------------------------------
    // Duplicate and validation
    // -----------------------------------------------------------------------

    @Test
    fun `duplicate approval ID throws IllegalArgumentException mentioning already exists`() : Unit = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(aPendingRequest()) } }
            .withMessageContaining("already exists")
    }

    @Test
    fun `stale expected version throws IllegalArgumentException mentioning version mismatch`() : Unit = runBlocking {
        store.create(aPendingRequest())
        // First transition advances version to 1
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2", null))

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.transition("req-1", 0L, ApprovalTransition.Timeout) } }
            .withMessageContaining("version mismatch")
    }

    @Test
    fun `transition on nonexistent approval throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.transition("nope", 0L, ApprovalTransition.Approve("u", null)) } }
            .withMessageContaining("not found")
    }

    // -----------------------------------------------------------------------
    // Validation: blanks
    // -----------------------------------------------------------------------

    @Test
    fun `blank approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(approvalId = "  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank workflowRunId throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(workflowRunId = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank toolName throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(toolName = "   ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank requestedBy throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(requestedBy = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `transition with blank approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.transition("", 0L, ApprovalTransition.Timeout)
                }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `initial request with decisionComment set throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest().copy(decisionComment = "should not be set")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have decisionComment set")
    }

    @Test
    fun `get with newline in approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.get("req\n-1") } }
            .withMessageContaining("control characters")
    }

    @Test
    fun `transition with newline in approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.transition("req\n-1", 0L, ApprovalTransition.Timeout)
                }
            }
            .withMessageContaining("control characters")
    }

    @Test
    fun `consumeApproved with newline in approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "req\n-1", 0L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("control characters")
    }

    // -----------------------------------------------------------------------
    // Validation: oversized
    // -----------------------------------------------------------------------

    @Test
    fun `transition with oversized approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.transition("a".repeat(257), 0L, ApprovalTransition.Timeout)
                }
            }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `consumeApproved with oversized approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "a".repeat(257), 0L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("exceeds maximum length")
    }

    // -----------------------------------------------------------------------
    // Validation: oversized values
    // -----------------------------------------------------------------------

    @Test
    fun `oversized approvalId throws IllegalArgumentException mentioning exceeds maximum length`() : Unit = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(approvalId = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized workflowRunId throws IllegalArgumentException mentioning exceeds maximum length`() : Unit = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(workflowRunId = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized toolName throws IllegalArgumentException mentioning exceeds maximum length`() : Unit = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(toolName = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized policyVersion throws IllegalArgumentException mentioning exceeds maximum length`() : Unit = runBlocking {
        val longVersion = "a".repeat(257)
        val request = aPendingRequest(
            approvalId = "req-pv",
            policyVersion = longVersion,
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    // -----------------------------------------------------------------------
    // Initial status validation
    // -----------------------------------------------------------------------

    @Test
    fun `non-PENDING initial status throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest().copy(status = ApprovalStatus.APPROVED)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must be PENDING")
    }

    // -----------------------------------------------------------------------
    // Concurrency: CAS race between approve and deny
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent approve and deny results in exactly one winning transition`() : Unit = runBlocking {
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)
        concurrencyStore.create(aPendingRequest(approvalId = "concurrent-req"))

        val results = mutableListOf<Result<ApprovalRequest>>()
        val barrier = CountDownLatch(2)

        val job1 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.transition(
                    "concurrent-req", 0L,
                    ApprovalTransition.Approve("user-a"),
                )
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        val job2 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.transition(
                    "concurrent-req", 0L,
                    ApprovalTransition.Deny("user-b"),
                )
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        job1.join()
        job2.join()

        val successes = results.filter { it.isSuccess }
        val failures = results.filter { it.isFailure }

        assertThat(successes).hasSize(1)
        assertThat(failures).hasSize(1)

        val winner = successes.single().getOrThrow()
        assertThat(winner.status).isIn(ApprovalStatus.APPROVED, ApprovalStatus.DENIED)
        assertThat(winner.version).isEqualTo(1L)

        val loser = failures.single().exceptionOrNull()
        assertThat(loser).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(loser!!.message).contains("version mismatch")
    }

    // -----------------------------------------------------------------------
    // Concurrency: CAS race on create with duplicate ID
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent create with duplicate ID results in exactly one success`() : Unit = runBlocking {
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)

        val results = mutableListOf<Result<ApprovalRequest>>()
        val barrier = CountDownLatch(2)

        val job1 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.create(aPendingRequest(approvalId = "dup-create"))
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        val job2 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.create(aPendingRequest(approvalId = "dup-create"))
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        job1.join()
        job2.join()

        // Exactly one should succeed, one should fail with "already exists"
        val successes = results.filter { it.isSuccess }
        val failures = results.filter { it.isFailure }

        assertThat(successes).hasSize(1)
        assertThat(failures).hasSize(1)

        val failureException = failures.single().exceptionOrNull()
        assertThat(failureException).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failureException!!.message).contains("already exists")
    }

    // -----------------------------------------------------------------------
    // Comment length enforcement
    // -----------------------------------------------------------------------

    @Test
    fun `oversized comment on approve throws IllegalArgumentException`() : Unit = runBlocking {
        val strictStore = InMemoryApprovalStore(clock = fixedClock, maxCommentLength = 10)
        strictStore.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    strictStore.transition("req-1", 0L, ApprovalTransition.Approve("user-2", "a".repeat(11)))
                }
            }
            .withMessageContaining("Comment exceeds maximum length")
    }

    @Test
    fun `oversized comment on deny throws IllegalArgumentException`() : Unit = runBlocking {
        val strictStore = InMemoryApprovalStore(clock = fixedClock, maxCommentLength = 10)
        strictStore.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    strictStore.transition("req-1", 0L, ApprovalTransition.Deny("user-2", "a".repeat(11)))
                }
            }
            .withMessageContaining("Comment exceeds maximum length")
    }

    @Test
    fun `comment at max length on approve succeeds`() : Unit = runBlocking {
        val strictStore = InMemoryApprovalStore(clock = fixedClock, maxCommentLength = 10)
        strictStore.create(aPendingRequest())

        val updated = strictStore.transition("req-1", 0L, ApprovalTransition.Approve("user-2", "a".repeat(10)))

        assertThat(updated.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(updated.decisionComment).isEqualTo("a".repeat(10))
    }

    // -----------------------------------------------------------------------
    // Initial version and decision field validation
    // -----------------------------------------------------------------------

    @Test
    fun `non-zero initial version throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(version = 1L)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Initial approval version must be 0")
    }

    @Test
    fun `initial request with decidedBy set throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest().copy(decidedBy = "someone")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have decidedBy set")
    }

    @Test
    fun `initial request with decidedAt set throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest().copy(decidedAt = fixedClock.instant())
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have decidedAt set")
    }

    // -----------------------------------------------------------------------
    // Expires at validation on create
    // -----------------------------------------------------------------------

    @Test
    fun `expiresAt in the past throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(expiresAt = Instant.parse("2026-06-04T09:00:00Z"))
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("expiresAt must be in the future")
    }

    @Test
    fun `expiresAt before requestedAt throws IllegalArgumentException`() : Unit = runBlocking {
        // When requestedAt and expiresAt are both before now, expiresAt > now fires first
        val request = aPendingRequest().copy(
            requestedAt = Instant.parse("2026-06-04T11:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T10:00:00Z"),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("expiresAt must be in the future")
    }

    // -----------------------------------------------------------------------
    // Whitespace trimming validation
    // -----------------------------------------------------------------------

    @Test
    fun `approvalId with surrounding whitespace throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(approvalId = "  req-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `requestedBy with surrounding whitespace throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(requestedBy = "  user-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `workflowRunId with surrounding whitespace throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(workflowRunId = "  wf-run-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `toolName with surrounding whitespace throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(toolName = "  tool  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `policyVersion with surrounding whitespace throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(policyVersion = "  v1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    // -----------------------------------------------------------------------
    // decidedBy validation on transitions
    // -----------------------------------------------------------------------

    @Test
    fun `approve with blank decidedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Approve("  ")) }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `deny with blank decidedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Deny("  ")) }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `approve with oversize decidedBy throws IllegalArgumentException`() : Unit = runBlocking {
        val longName = "a".repeat(257)
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Approve(longName)) }
            }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `deny with oversize decidedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest())
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.transition("req-1", 0L, ApprovalTransition.Deny("a".repeat(257)))
                }
            }
            .withMessageContaining("decidedBy exceeds maximum length")
    }

    // -----------------------------------------------------------------------
    // Consumption
    // -----------------------------------------------------------------------

    @Test
    fun `approved approval with valid token digest succeeds`() : Unit = runBlocking {
        val request = aPendingRequest()
        store.create(request)
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2"))

        val consumed = store.consumeApproved(
            "req-1", 1L,
            Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
            "consumer-1",
        )

        assertThat(consumed.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(consumed.consumedBy).isEqualTo("consumer-1")
        assertThat(consumed.consumedAt).isEqualTo(fixedClock.instant())
        assertThat(consumed.version).isEqualTo(2L)
    }

    @Test
    fun `second consume of same approval fails`() : Unit = runBlocking {
        val request = aPendingRequest()
        store.create(request)
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2"))
        store.consumeApproved(
            "req-1", 1L,
            Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
            "consumer-1",
        )

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "req-1", 2L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-2",
                    )
                }
            }
            .withMessageContaining("already been consumed")
    }

    @Test
    fun `pending approval consume fails`() : Unit = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "req-1", 0L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("cannot be consumed")
            .withMessageContaining("PENDING")
    }

    @Test
    fun `denied approval consume fails`() : Unit = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Deny("user-2"))

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "req-1", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("cannot be consumed")
            .withMessageContaining("DENIED")
    }

    @Test
    fun `timed-out approval consume fails`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T08:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "timed-out-consumable",
            requestedAt = Instant.parse("2026-06-04T08:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        store2.transition("timed-out-consumable", 0L, ApprovalTransition.Timeout)

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store2.consumeApproved(
                        "timed-out-consumable", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("cannot be consumed")
            .withMessageContaining("TIMED_OUT")
    }

    @Test
    fun `expired approved approval consume fails`() : Unit = runBlocking {
        val mutableClock = MutableClock(Instant.parse("2026-06-04T08:00:00Z"))
        val store2 = InMemoryApprovalStore(clock = mutableClock)
        store2.create(aPendingRequest(
            approvalId = "expired-consumable",
            requestedAt = Instant.parse("2026-06-04T08:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T09:00:00Z"),
        ))
        store2.transition("expired-consumable", 0L, ApprovalTransition.Approve("user-2"))

        // Advance clock past expiry
        mutableClock.advance(Duration.ofHours(2))

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store2.consumeApproved(
                        "expired-consumable", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("expired")
    }

    @Test
    fun `wrong token digest for consume fails`() : Unit = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2"))

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "req-1", 1L,
                        Sha256Digest.of("sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("token digest does not match")
    }

    @Test
    fun `concurrent consume calls exactly one succeeds`() : Unit = runBlocking {
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)
        val request = aPendingRequest(approvalId = "concurrent-consume")
        concurrencyStore.create(request)
        concurrencyStore.transition("concurrent-consume", 0L, ApprovalTransition.Approve("user-2"))

        val results = mutableListOf<Result<ApprovalRequest>>()
        val barrier = CountDownLatch(2)

        val job1 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.consumeApproved(
                    "concurrent-consume", 1L,
                    Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                    "consumer-a",
                )
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        val job2 = launch(kotlinx.coroutines.Dispatchers.Default) {
            barrier.countDown()
            barrier.await()

            try {
                val r = concurrencyStore.consumeApproved(
                    "concurrent-consume", 1L,
                    Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                    "consumer-b",
                )
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        job1.join()
        job2.join()

        val successes = results.filter { it.isSuccess }
        val failures = results.filter { it.isFailure }

        assertThat(successes).hasSize(1)
        assertThat(failures).hasSize(1)

        val winner = successes.single().getOrThrow()
        assertThat(winner.consumedBy).isIn("consumer-a", "consumer-b")
        assertThat(winner.version).isEqualTo(2L)

        val loser = failures.single().exceptionOrNull()
        assertThat(loser).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(loser!!.message).contains("version mismatch")
    }

    // -----------------------------------------------------------------------
    // consumeApproved validation
    // -----------------------------------------------------------------------

    @Test
    fun `consumeApproved with blank consumedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest(approvalId = "consume-blank"))
        store.transition("consume-blank", 0L, ApprovalTransition.Approve("user-2"))
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "consume-blank", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "  ",
                    )
                }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `consumeApproved with oversized consumedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest(approvalId = "consume-oversized"))
        store.transition("consume-oversized", 0L, ApprovalTransition.Approve("user-2"))
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "consume-oversized", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "a".repeat(257),
                    )
                }
            }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `consumeApproved with control characters in consumedBy throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest(approvalId = "consume-ctrl"))
        store.transition("consume-ctrl", 0L, ApprovalTransition.Approve("user-2"))
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "consume-ctrl", 1L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer\n-1",
                    )
                }
            }
            .withMessageContaining("control characters")
    }

    @Test
    fun `consumeApproved with nonexistent approvalId throws IllegalArgumentException`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "does-not-exist", 0L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("not found")
    }

    @Test
    fun `consumeApproved with version mismatch throws IllegalArgumentException`() : Unit = runBlocking {
        store.create(aPendingRequest(approvalId = "consume-version"))
        store.transition("consume-version", 0L, ApprovalTransition.Approve("user-2"))
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.consumeApproved(
                        "consume-version", 0L,
                        Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                        "consumer-1",
                    )
                }
            }
            .withMessageContaining("version mismatch")
    }

    // -----------------------------------------------------------------------
    // Create validation: consumption fields and future requestedAt
    // -----------------------------------------------------------------------

    @Test
    fun `create rejects pre-set consumedBy`() : Unit = runBlocking {
        val request = aPendingRequest().copy(consumedBy = "someone")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have consumedBy set")
    }

    @Test
    fun `create rejects pre-set consumedAt`() : Unit = runBlocking {
        val request = aPendingRequest().copy(consumedAt = fixedClock.instant())
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have consumedAt set")
    }

    @Test
    fun `create rejects requestedAt in the future`() : Unit = runBlocking {
        val request = aPendingRequest().copy(
            requestedAt = fixedClock.instant().plusSeconds(60),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be in the future")
    }

    // -----------------------------------------------------------------------
    // Validation: control characters
    // -----------------------------------------------------------------------

    @Test
    fun `approvalId with control character throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(approvalId = "req-\n1")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain control characters")
    }

    @Test
    fun `toolName with control character throws IllegalArgumentException`() : Unit = runBlocking {
        val request = aPendingRequest(toolName = "search\tool")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain control characters")
    }
}
