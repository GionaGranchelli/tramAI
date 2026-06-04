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
        argumentsDigest: String = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
        policyVersion: String = "v1",
        workflowDigest: String = "sha256:1111111111111111111111111111111111111111111111111111111111111111",
        approvalTokenDigest: String = "sha256:2222222222222222222222222222222222222222222222222222222222222222",
        requestedBy: String = "user-1",
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
    fun `timeout an expired pending request transitions to TIMED_OUT`() = runBlocking {
        val request = aPendingRequest(
            approvalId = "expired-to",
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        )
        store.create(request)

        val updated = store.transition("expired-to", 0L, ApprovalTransition.Timeout)

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
        store.create(aPendingRequest(
            approvalId = "timed-out",
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))
        store.transition("timed-out", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store.transition("timed-out", 1L, ApprovalTransition.Approve("user-3", null)) }
        }
            .isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("approval already timed out")
    }

    @Test
    fun `timeout a timed-out request throws IllegalApprovalTransitionException`() = runBlocking {
        store.create(aPendingRequest(
            approvalId = "timed-out-2",
            expiresAt = Instant.parse("2026-06-04T09:30:00Z"),
        ))
        store.transition("timed-out-2", 0L, ApprovalTransition.Timeout)

        assertThatThrownBy {
            runBlocking { store.transition("timed-out-2", 1L, ApprovalTransition.Timeout) }
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
    // Timeout before expiry is rejected
    // -----------------------------------------------------------------------

    @Test
    fun `timeout on non-expired pending request throws IllegalApprovalTransitionException`() = runBlocking {
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
    fun `blank argumentsDigest throws IllegalArgumentException mentioning invalid digest`() = runBlocking {
        val request = aPendingRequest(argumentsDigest = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Invalid digest format")
    }

    // -----------------------------------------------------------------------
    // Validation: SHA-256 digest format
    // -----------------------------------------------------------------------

    @Test
    fun `invalid argumentsDigest format throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(argumentsDigest = "sha256:xyz")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Invalid digest format")
    }

    @Test
    fun `invalid workflowDigest format throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(workflowDigest = "sha256:xyz")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Invalid digest format")
    }

    @Test
    fun `invalid approvalTokenDigest format throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(approvalTokenDigest = "sha256:xyz")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Invalid digest format")
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
    fun `oversized policyVersion throws IllegalArgumentException mentioning exceeds maximum length`() = runBlocking {
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
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)
        concurrencyStore.create(aPendingRequest(approvalId = "concurrent-req"))

        val results = mutableListOf<Result<ApprovalRequest>>()
        var ready = 0

        val job1 = launch(kotlinx.coroutines.Dispatchers.Default) {
            // Signal ready and wait for partner
            synchronized(this@InMemoryApprovalStoreTest) { ready++ }
            while (ready < 2) { kotlinx.coroutines.yield() }

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
            synchronized(this@InMemoryApprovalStoreTest) { ready++ }
            while (ready < 2) { kotlinx.coroutines.yield() }

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
    fun `concurrent create with duplicate ID results in exactly one success`() = runBlocking {
        val concurrencyStore = InMemoryApprovalStore(clock = fixedClock)

        val results = mutableListOf<Result<ApprovalRequest>>()
        var ready = 0

        val job1 = launch(kotlinx.coroutines.Dispatchers.Default) {
            synchronized(this@InMemoryApprovalStoreTest) { ready++ }
            while (ready < 2) { kotlinx.coroutines.yield() }

            try {
                val r = concurrencyStore.create(aPendingRequest(approvalId = "dup-create"))
                synchronized(results) { results.add(Result.success(r)) }
            } catch (e: Exception) {
                synchronized(results) { results.add(Result.failure(e)) }
            }
        }

        val job2 = launch(kotlinx.coroutines.Dispatchers.Default) {
            synchronized(this@InMemoryApprovalStoreTest) { ready++ }
            while (ready < 2) { kotlinx.coroutines.yield() }

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
    // Initial version and decision field validation
    // -----------------------------------------------------------------------

    @Test
    fun `non-zero initial version throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(version = 1L)
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("Initial approval version must be 0")
    }

    @Test
    fun `initial request with decidedBy set throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest().copy(decidedBy = "someone")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have decidedBy set")
    }

    @Test
    fun `initial request with decidedAt set throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest().copy(decidedAt = fixedClock.instant())
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not have decidedAt set")
    }

    // -----------------------------------------------------------------------
    // Expires at validation on create
    // -----------------------------------------------------------------------

    @Test
    fun `expiresAt in the past throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(expiresAt = Instant.parse("2026-06-04T09:00:00Z"))
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("expiresAt must be in the future")
    }

    @Test
    fun `expiresAt before requestedAt throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest().copy(
            requestedAt = Instant.parse("2026-06-04T11:00:00Z"),
            expiresAt = Instant.parse("2026-06-04T10:00:00Z"),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("expiresAt must be after requestedAt")
    }

    // -----------------------------------------------------------------------
    // Whitespace trimming validation
    // -----------------------------------------------------------------------

    @Test
    fun `approvalId with surrounding whitespace throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(approvalId = "  req-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `requestedBy with surrounding whitespace throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(requestedBy = "  user-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `workflowRunId with surrounding whitespace throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(workflowRunId = "  wf-run-1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `toolName with surrounding whitespace throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(toolName = "  tool  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    @Test
    fun `policyVersion with surrounding whitespace throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(policyVersion = "  v1  ")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not contain surrounding whitespace")
    }

    // -----------------------------------------------------------------------
    // Blank requestedBy
    // -----------------------------------------------------------------------

    @Test
    fun `blank requestedBy throws IllegalArgumentException`() = runBlocking {
        val request = aPendingRequest(requestedBy = "")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.create(request) } }
            .withMessageContaining("must not be blank")
    }

    // -----------------------------------------------------------------------
    // decidedBy validation on transitions
    // -----------------------------------------------------------------------

    @Test
    fun `approve with blank decidedBy throws IllegalArgumentException`() = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Approve("  ")) }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `deny with blank decidedBy throws IllegalArgumentException`() = runBlocking {
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Deny("  ")) }
            }
            .withMessageContaining("must not be blank")
    }

    @Test
    fun `approve with oversize decidedBy throws IllegalArgumentException`() = runBlocking {
        val longName = "a".repeat(257)
        store.create(aPendingRequest())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.transition("req-1", 0L, ApprovalTransition.Approve(longName)) }
            }
            .withMessageContaining("exceeds maximum length")
    }
}
