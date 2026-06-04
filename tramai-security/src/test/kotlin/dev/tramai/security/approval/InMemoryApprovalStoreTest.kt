package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.IllegalApprovalTransitionException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        argumentsDigest: String = "sha256:abc123",
        requestedBy: String? = "user-1",
        expiresAt: Instant? = null,
        version: Long = 0L,
    ) = ApprovalRequest(
        approvalId = approvalId,
        binding = ApprovalBinding(
            workflowRunId = workflowRunId,
            toolName = toolName,
            argumentsDigest = argumentsDigest,
            policyVersion = null,
            workflowDigest = null,
        ),
        status = ApprovalStatus.PENDING,
        requestedBy = requestedBy,
        requestedAt = fixedClock.instant(),
        expiresAt = expiresAt,
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        version = version,
    )

    private fun fixedClock(iso: String): Clock = Clock.fixed(
        Instant.parse(iso),
        ZoneId.of("UTC"),
    )

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    fun `create stores a pending approval`() = runBlocking {
        val request = aPendingRequest()
        val created = store.create(request)

        assertThat(created).isEqualTo(request)
        assertThat(created.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `get returns the stored approval`() = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val retrieved = store.get("req-1")
        assertThat(retrieved).isNotNull
        assertThat(retrieved!!.approvalId).isEqualTo("req-1")
        assertThat(retrieved.status).isEqualTo(ApprovalStatus.PENDING)
    }

    @Test
    fun `get returns null for nonexistent approval`() = runBlocking {
        val retrieved = store.get("does-not-exist")
        assertThat(retrieved).isNull()
    }

    @Test
    fun `approve a pending request transitions to APPROVED`() = runBlocking {
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
    fun `deny a pending request transitions to DENIED`() = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val updated = store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", "Not appropriate"))

        assertThat(updated.status).isEqualTo(ApprovalStatus.DENIED)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isEqualTo("user-2")
        assertThat(updated.decisionComment).isEqualTo("Not appropriate")
    }

    @Test
    fun `timeout a pending request transitions to TIMED_OUT`() = runBlocking {
        val request = aPendingRequest()
        store.create(request)

        val updated = store.transition("req-1", 0L, ApprovalTransition.Timeout)

        assertThat(updated.status).isEqualTo(ApprovalStatus.TIMED_OUT)
        assertThat(updated.version).isEqualTo(1L)
        assertThat(updated.decidedBy).isNull()
        assertThat(updated.decisionComment).isNull()
        assertThat(updated.decidedAt).isEqualTo(fixedClock.instant())
    }

    // -----------------------------------------------------------------------
    // Illegal transitions from terminal states
    // -----------------------------------------------------------------------

    @Test
    fun `approve an already approved request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already granted")
    }

    @Test
    fun `deny a denied request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Deny("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already denied")
    }

    @Test
    fun `approve a denied request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Deny("user-2", null))

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already denied")
    }

    @Test
    fun `approve a timed-out request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already timed out")
    }

    @Test
    fun `timeout a timed-out request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest())
        store.transition("req-1", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store.transition("req-1", 1L, ApprovalTransition.Timeout) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already timed out")
    }

    // -----------------------------------------------------------------------
    // Expiry
    // -----------------------------------------------------------------------

    @Test
    fun `approve an expired pending request throws IllegalApprovalTransitionException mentioning expired`() = runBlocking {
        val past = fixedClock("2026-06-04T09:00:00Z")
        val storeWithPastClock = InMemoryApprovalStore(clock = past)
        val request = aPendingRequest(
            approvalId = "expired-req",
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        )
        storeWithPastClock.create(request)

        // clock is now at 09:00, expiry is 09:30, this should work
        storeWithPastClock.transition("expired-req", 0L, ApprovalTransition.Approve("user-2", null))

        // Now test actual expiry: create a new request with expiry in the past
        val laterClock = fixedClock("2026-06-04T11:00:00Z")
        val storeWithLaterClock = InMemoryApprovalStore(clock = laterClock)
        val expiredRequest = aPendingRequest(
            approvalId = "expired-req-2",
            expiresAt = Instant.parse("2026-06-04T10:30:00Z"),
        )
        storeWithLaterClock.create(expiredRequest)

        assertThatThrownBy {
            runBlocking { storeWithLaterClock.transition("expired-req-2", 0L, ApprovalTransition.Approve("user-2", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("expired")
    }

    @Test
    fun `timeout on an expired pending request succeeds`() = runBlocking {
        val clock = fixedClock("2026-06-04T11:00:00Z")
        val expiredStore = InMemoryApprovalStore(clock = clock)
        val request = aPendingRequest(
            approvalId = "expired-req",
            expiresAt = Instant.parse("2026-06-04T10:30:00Z"),
        )
        expiredStore.create(request)

        val updated = expiredStore.transition("expired-req", 0L, ApprovalTransition.Timeout)

        assertThat(updated.status).isEqualTo(ApprovalStatus.TIMED_OUT)
    }

    // -----------------------------------------------------------------------
    // Duplicate and validation
    // -----------------------------------------------------------------------

    @Test
    fun `duplicate approval ID throws IllegalArgumentException mentioning already exists`() = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(aPendingRequest()) } }
            .withMessageContaining("already exists")
    }

    @Test
    fun `stale expected version throws IllegalArgumentException mentioning version mismatch`() = runBlocking {
        store.create(aPendingRequest())
        // First transition advances version to 1
        store.transition("req-1", 0L, ApprovalTransition.Approve("user-2", null))

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.transition("req-1", 0L, ApprovalTransition.Timeout) } }
            .withMessageContaining("version mismatch")
    }

    @Test
    fun `transition on nonexistent approval throws IllegalArgumentException`() = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.transition("nope", 0L, ApprovalTransition.Approve("u", null)) } }
            .withMessageContaining("not found")
    }

    // -----------------------------------------------------------------------
    // Validation: blanks
    // -----------------------------------------------------------------------

    @Test
    fun `blank approvalId throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(approvalId = "  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank workflowRunId throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(workflowRunId = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank toolName throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(toolName = "   ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `blank argumentsDigest throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(argumentsDigest = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    // -----------------------------------------------------------------------
    // Validation: oversized values
    // -----------------------------------------------------------------------

    @Test
    fun `oversized approvalId throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(approvalId = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized workflowRunId throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(workflowRunId = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized toolName throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longId = "a".repeat(257)
        val request = aPendingRequest(toolName = longId)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized argumentsDigest throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longDigest = "a".repeat(1025)
        val request = aPendingRequest(argumentsDigest = longDigest)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized policyVersion throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longVersion = "a".repeat(257)
        val request = aPendingRequest().copy(
            approvalId = "req-pv",
            binding = aPendingRequest().binding.copy(policyVersion = longVersion),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    @Test
    fun `oversized workflowDigest throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
        val longDigest = "a".repeat(1025)
        val request = aPendingRequest().copy(
            approvalId = "req-wd",
            binding = aPendingRequest().binding.copy(workflowDigest = longDigest),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("exceeds maximum length")
    }

    // -----------------------------------------------------------------------
    // Initial status validation
    // -----------------------------------------------------------------------

    @Test
    fun `non-PENDING initial status throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest().copy(status = ApprovalStatus.APPROVED)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must be PENDING")
    }

    // -----------------------------------------------------------------------
    // Concurrency: CAS race between approve and deny
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent approve and deny results in exactly one winning transition`() = runBlocking {
        // Use a single-threaded context so we control interleaving
        val ctx = newSingleThreadContext("concurrency-test")
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)
        val request = aPendingRequest(approvalId = "concurrent-req")
        concurrencyStore.create(request)

        val results = mutableListOf<Result<ApprovalRequest>>()

        try {
            // Launch competing transitions with the same expectedVersion = 0
            val job1 = launch(ctx) {
                try {
                    val r = concurrencyStore.transition(
                        "concurrent-req", 0L,
                        ApprovalTransition.Approve("user-a", "approve"),
                    )
                    synchronized(results) { results.add(Result.success(r)) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(Result.failure(e)) }
                }
            }

            val job2 = launch(ctx) {
                try {
                    val r = concurrencyStore.transition(
                        "concurrent-req", 0L,
                        ApprovalTransition.Deny("user-b", "deny"),
                    )
                    synchronized(results) { results.add(Result.success(r)) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(Result.failure(e)) }
                }
            }

            job1.join()
            job2.join()
        } finally {
            ctx.close()
        }

        // Exactly one should succeed, one should fail with version mismatch
        val successes = results.filter { it.isSuccess }
        val failures = results.filter { it.isFailure }

        assertThat(successes).hasSize(1)
        assertThat(failures).hasSize(1)

        val winningStatus = successes.single().getOrThrow().status
        assertThat(winningStatus).isIn(ApprovalStatus.APPROVED, ApprovalStatus.DENIED)

        val failureException = failures.single().exceptionOrNull()
        assertThat(failureException).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failureException!!.message).contains("version mismatch")
    }

    // -----------------------------------------------------------------------
    // Concurrency: CAS race on create with duplicate ID
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent create with duplicate ID results in exactly one success`() = runBlocking {
        val ctx = newSingleThreadContext("concurrency-create-test")
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)

        val results = mutableListOf<Result<ApprovalRequest>>()

        try {
            val job1 = launch(ctx) {
                try {
                    val r = concurrencyStore.create(aPendingRequest(approvalId = "dup-create"))
                    synchronized(results) { results.add(Result.success(r)) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(Result.failure(e)) }
                }
            }

            val job2 = launch(ctx) {
                try {
                    val r = concurrencyStore.create(aPendingRequest(approvalId = "dup-create"))
                    synchronized(results) { results.add(Result.success(r)) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(Result.failure(e)) }
                }
            }

            job1.join()
            job2.join()
        } finally {
            ctx.close()
        }

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
    fun `oversized comment on approve throws IllegalArgumentException`() = runBlocking {
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
    fun `oversized comment on deny throws IllegalArgumentException`() = runBlocking {
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
    fun `comment at max length on approve succeeds`() = runBlocking {
        val strictStore = InMemoryApprovalStore(clock = fixedClock, maxCommentLength = 10)
        strictStore.create(aPendingRequest())

        val updated = strictStore.transition("req-1", 0L, ApprovalTransition.Approve("user-2", "a".repeat(10)))

        assertThat(updated.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(updated.decisionComment).isEqualTo("a".repeat(10))
    }

    // -----------------------------------------------------------------------
    // CAS retry budget exhaustion
    // -----------------------------------------------------------------------

    @Test
    fun `CAS retry budget exhaustion throws IllegalStateException`() = runBlocking {
        // Use a store with 0 retries to force immediate exhaustion
        val zeroRetryStore = InMemoryApprovalStore(clock = fixedClock, maxCasRetries = 0)
        zeroRetryStore.create(aPendingRequest())

        // We need a concurrent write to force CAS failure
        val ctx = newSingleThreadContext("cas-exhaust-test")
        try {
            // Grab the reference so we know replace will fail
            val job = launch(ctx) {
                // Do a concurrent transition to advance version and force CAS failure
                zeroRetryStore.transition("req-1", 0L, ApprovalTransition.Approve("user-2", null))
            }
            job.join()
        } finally {
            ctx.close()
        }

        // Now version is 1, expected 0 will CAS-fail and retry budget will exhaust
        assertThatThrownBy {
            runBlocking { zeroRetryStore.transition("req-1", 0L, ApprovalTransition.Deny("user-3", null)) }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("CAS retry budget exhausted")
    }

    // -----------------------------------------------------------------------
    // Version overflow guard
    // -----------------------------------------------------------------------

    @Test
    fun `version overflow guard throws IllegalStateException`() = runBlocking {
        val store = InMemoryApprovalStore(clock = fixedClock)
        val request = aPendingRequest(version = Long.MAX_VALUE)
        store.create(request)

        assertThatThrownBy {
            runBlocking { store.transition("req-1", Long.MAX_VALUE, ApprovalTransition.Approve("user-2", null)) }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("version overflow")
    }
}
