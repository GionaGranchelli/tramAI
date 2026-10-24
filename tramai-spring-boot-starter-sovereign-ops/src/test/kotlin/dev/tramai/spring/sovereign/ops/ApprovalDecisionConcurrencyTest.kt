package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stale-precondition and concurrency guarantees for approval decisions (0.7.6d).
 *
 * The F2 defect was a *state* defect, not a response defect: the control plane substituted the server's
 * current version for a version the caller never supplied. Every assertion below therefore inspects
 * authoritative state and outbox records, not only the returned type — a 409 alone would not prove that a
 * rejected command changed nothing.
 */
class ApprovalDecisionConcurrencyTest {
    private val now: Instant = Instant.parse("2026-06-25T10:15:30Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun digest(seed: String): Sha256Digest = Sha256Digest.of("sha256:${seed.repeat(64).take(64)}")

    private fun pending(version: Long = 0L): ApprovalRequest =
        ApprovalRequest(
            approvalId = "approval-1",
            binding =
                ApprovalBinding(
                    workflowRunId = "run-1",
                    toolName = "claim-triage",
                    argumentsDigest = digest("01"),
                    policyVersion = "v1",
                    workflowDigest = digest("02"),
                    approvalTokenDigest = digest("03"),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "requester",
            requestedAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = version,
        )

    private fun command(expectedVersion: Long): ApprovalDecisionCommand =
        ApprovalDecisionCommand(
            approvalId = ApprovalId("approval-1"),
            actorId = "reviewer-1",
            actorRole = ApproverRole("medical-reviewer"),
            expectedVersion = expectedVersion,
            comment = "review complete",
            correlationId = "corr-1",
        )

    private fun controlPlane(
        fixture: Fixture,
        mutations: SovereignOpsApprovalMutationStore = fixture.mutations,
    ) = SovereignOpsApprovalDecisionControlPlane(
        approvalStore = fixture.approvals,
        mutationStore = mutations,
        clock = clock,
    )

    @Test
    fun `a stale observed version is rejected without touching approval state or the outbox`() {
        runBlocking {
            val fixture = Fixture(pending(version = 0L))
            val decisions = controlPlane(fixture)
            assertThat(decisions.approve(command(0L)))
                .isInstanceOf(ApprovalDecisionResult.Approved::class.java)

            val stale = decisions.deny(command(0L))

            assertThat(stale).isEqualTo(
                ApprovalDecisionResult.Stale(
                    approvalId = ApprovalId("approval-1"),
                    expectedVersion = 0L,
                    currentVersion = 1L,
                ),
            )
            val authoritative = requireNotNull(fixture.approvals.get("approval-1"))
            assertThat(authoritative.version).isEqualTo(1L)
            assertThat(authoritative.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10))
                .describedAs("exactly one dispatchable audit fact: the winning decision")
                .hasSize(1)
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE, 10)).isEmpty()
        }
    }

    @Test
    fun `a stale version is reported even though the approval is already terminal`() {
        runBlocking {
            val fixture = Fixture(pending(version = 0L))
            val decisions = controlPlane(fixture)
            decisions.approve(command(0L))

            // A caller holding version 0 never made the earlier decision, so it must not be told
            // "already approved" as though its own command had succeeded.
            assertThat(decisions.approve(command(0L)))
                .isEqualTo(
                    ApprovalDecisionResult.Stale(
                        approvalId = ApprovalId("approval-1"),
                        expectedVersion = 0L,
                        currentVersion = 1L,
                    ),
                )
            assertThat(requireNotNull(fixture.approvals.get("approval-1")).version).isEqualTo(1L)
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(1)
        }
    }

    @Test
    fun `a caller holding the current version still observes the terminal state`() {
        runBlocking {
            val fixture = Fixture(pending(version = 0L))
            val decisions = controlPlane(fixture)
            decisions.approve(command(0L))

            assertThat(decisions.approve(command(1L)))
                .isInstanceOf(ApprovalDecisionResult.AlreadyApproved::class.java)
            assertThat(decisions.deny(command(1L)))
                .isInstanceOf(ApprovalDecisionResult.AlreadyApproved::class.java)
            assertThat(requireNotNull(fixture.approvals.get("approval-1")).version).isEqualTo(1L)
        }
    }

    @Test
    fun `a missing approval is NotFound and mutates nothing`() {
        runBlocking {
            val fixture = Fixture(pending())

            assertThat(controlPlane(fixture).approve(command(0L).copy(approvalId = ApprovalId("nonexistent"))))
                .isEqualTo(ApprovalDecisionResult.NotFound(ApprovalId("nonexistent")))
            assertThat(requireNotNull(fixture.approvals.get("approval-1")).version).isZero()
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
        }
    }

    @Test
    fun `an expired approval is Expired and mutates nothing`() {
        runBlocking {
            val expired = pending().copy(expiresAt = now.minusSeconds(1))
            val fixture = Fixture(expired)

            assertThat(controlPlane(fixture).approve(command(0L)))
                .isEqualTo(ApprovalDecisionResult.Expired(ApprovalId("approval-1"), expired.expiresAt))
            assertThat(requireNotNull(fixture.approvals.get("approval-1")).status)
                .isEqualTo(ApprovalStatus.PENDING)
            assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
        }
    }

    @Test
    fun `concurrent approve and deny on the same observed version commit exactly one decision`() {
        val fixture = Fixture(pending(version = 0L))
        // Both commands read version 0 before either mutates: the gate lives inside the store's read, so
        // this is a real interleaving rather than two sequential calls.
        fixture.approvals.barrier = CyclicBarrier(2)
        val decisions = controlPlane(fixture)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val approve = pool.submit(Callable { runBlocking { decisions.approve(command(0L)) } })
            val deny = pool.submit(Callable { runBlocking { decisions.deny(command(0L)) } })
            val results = listOf(approve.get(30, TimeUnit.SECONDS), deny.get(30, TimeUnit.SECONDS))

            val committed =
                results.filter {
                    it is ApprovalDecisionResult.Approved || it is ApprovalDecisionResult.Denied
                }
            val stale = results.filterIsInstance<ApprovalDecisionResult.Stale>()
            assertThat(committed).describedAs("exactly one winning decision").hasSize(1)
            assertThat(stale).describedAs("the loser is reported stale").hasSize(1)
            assertThat(stale.single().expectedVersion).isZero()
            assertThat(stale.single().currentVersion).isEqualTo(1L)

            runBlocking {
                val authoritative = requireNotNull(fixture.approvals.get("approval-1"))
                assertThat(authoritative.version).describedAs("one increment, no double commit").isEqualTo(1L)
                assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10))
                    .describedAs("one dispatchable audit fact for the winner only")
                    .hasSize(1)
                assertThat(fixture.outbox.listByStatus(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE, 10))
                    .isEmpty()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the mutation authority receives the caller's version verbatim`() {
        runBlocking {
            val fixture = Fixture(pending(version = 0L))
            val recording = VersionCapturingMutationStore(fixture.mutations)

            controlPlane(fixture, recording).approve(command(0L))

            assertThat(recording.expectedVersions).containsExactly(0L)
        }
    }

    @Test
    fun `a stale command never reaches the mutation authority at all`() {
        runBlocking {
            val fixture = Fixture(pending(version = 3L))
            val recording = VersionCapturingMutationStore(fixture.mutations)

            assertThat(controlPlane(fixture, recording).approve(command(0L)))
                .isInstanceOf(ApprovalDecisionResult.Stale::class.java)
            assertThat(recording.expectedVersions)
                .describedAs("no mutation attempt for a stale command")
                .isEmpty()
        }
    }

    @Test
    fun `a negative observed version cannot even become a command`() {
        val rejection =
            assertThrows<IllegalArgumentException> {
                ApprovalDecisionCommand(
                    approvalId = ApprovalId("approval-1"),
                    actorId = "reviewer-1",
                    actorRole = ApproverRole("medical-reviewer"),
                    expectedVersion = -1L,
                )
            }

        assertThat(rejection).hasMessageContaining("tramai-approval-invalid-expected-version")
    }

    @Test
    fun `a fake operator principal confers no decision authority`() {
        runBlocking {
            // 0.7.6c's OperatorPrincipal is data, not proof. Holding one changes nothing here: the command
            // carries caller claims only, and this test builds a forged principal to show there is no
            // parameter through which it could be presented as authentication. Authorization is 0.7.6e.
            val forgedPrincipal =
                OperatorPrincipal(
                    subject = "alice",
                    issuer = "https://idp.example/",
                    authenticatedAt = now,
                    capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                )
            val fixture = Fixture(pending(version = 0L))

            assertThat(controlPlane(fixture).approve(command(0L)))
                .isInstanceOf(ApprovalDecisionResult.Approved::class.java)
            assertThat(
                ApprovalDecisionCommand::class.java.constructors
                    .first()
                    .parameterTypes
                    .map { it.name },
            ).describedAs("no authentication or principal parameter exists to pass")
                .noneMatch { it.contains("OperatorPrincipal") || it.contains("Authentication") }
            assertThat(forgedPrincipal.subject).isEqualTo("alice")
        }
    }

    private class Fixture(
        seed: ApprovalRequest,
    ) {
        val approvals = CasApprovalStore(seed)
        val outbox = InMemorySovereignOpsAuditOutboxStore()
        val mutations = InMemorySovereignOpsApprovalMutationStore(approvals, outbox)
    }
}

/**
 * Minimal authoritative store with real compare-and-set semantics: a transition applies only when the
 * stored version matches, otherwise it reports a conflict — the same signal the JDBC store emits.
 */
private class CasApprovalStore(
    seed: ApprovalRequest,
) : ApprovalStore {
    private val approvals = ConcurrentHashMap<String, ApprovalRequest>().apply { put(seed.approvalId, seed) }
    private val barrierReads = AtomicInteger()

    /** When set, the first two reads block until both racing callers observed the pre-mutation state. */
    @Volatile
    var barrier: CyclicBarrier? = null

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        approvals[request.approvalId] = request
        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? {
        val snapshot = approvals[approvalId]
        barrier?.let { gate ->
            // Only the two racing decision reads rendezvous; later reads (the mutation store's re-read
            // under its own lock, and post-race assertions) pass through so the gate cannot deadlock.
            if (barrierReads.incrementAndGet() <= 2) {
                gate.await(5, TimeUnit.SECONDS)
            }
        }
        return snapshot
    }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest =
        synchronized(this) {
            val current = approvals[approvalId] ?: throw ApprovalStoreConflictException(approvalId)
            if (current.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)
            val decidedAt = Instant.parse("2026-06-25T10:15:30Z")
            val updated =
                when (transition) {
                    is ApprovalTransition.Approve -> {
                        current.copy(
                            status = ApprovalStatus.APPROVED,
                            version = current.version + 1,
                            decidedBy = transition.decidedBy,
                            decidedAt = decidedAt,
                            decisionComment = transition.comment,
                        )
                    }

                    is ApprovalTransition.Deny -> {
                        current.copy(
                            status = ApprovalStatus.DENIED,
                            version = current.version + 1,
                            decidedBy = transition.decidedBy,
                            decidedAt = decidedAt,
                            decisionComment = transition.comment,
                        )
                    }

                    is ApprovalTransition.Timeout -> {
                        current.copy(status = ApprovalStatus.TIMED_OUT, version = current.version + 1)
                    }
                }
            approvals[approvalId] = updated
            updated
        }

    /** Not part of the decision path; these tests never consume an approval. */
    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt = throw UnsupportedOperationException("not exercised by decision tests")
}

/** Records the expected version the mutation authority was actually asked to compare against. */
private class VersionCapturingMutationStore(
    private val delegate: SovereignOpsApprovalMutationStore,
) : SovereignOpsApprovalMutationStore {
    val expectedVersions: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun approveApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        expectedVersions.add(expectedVersion)
        return delegate.approveApprovalWithAuditIntent(approvalId, expectedVersion, actor, reason, auditIntent)
    }

    override suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        expectedVersions.add(expectedVersion)
        return delegate.denyApprovalWithAuditIntent(approvalId, expectedVersion, actor, reason, auditIntent)
    }
}
