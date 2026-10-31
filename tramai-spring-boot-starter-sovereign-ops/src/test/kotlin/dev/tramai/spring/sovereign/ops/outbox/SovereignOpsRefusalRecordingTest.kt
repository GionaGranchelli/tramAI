package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalUnauthorizedReason
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.RecordingRefusalRecorder
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.SovereignOpsProperties
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 0.7.6f §5.2/§5.3 discriminators for refusal recording.
 *
 * The point of each test is a failure mode that is silent in production: two records for one logical
 * refusal, one record standing in for two different refusals, an approval row touched by an operation that
 * decided nothing, and a refusal returned as recorded when nothing was recorded.
 */
class SovereignOpsRefusalRecordingTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `recording the same refusal twice writes one record and returns the original`() =
        runBlocking<Unit> {
            val store = InMemorySovereignOpsAuditOutboxStore()
            val operations = operations(store)

            val first = operations.recordRefusal(evidence())
            val second = operations.recordRefusal(evidence())

            assertThat(first).isInstanceOf(RefusalRecording.Recorded::class.java)
            assertThat(second).isInstanceOf(RefusalRecording.Duplicate::class.java)
            // Idempotency means the *same* record, not merely "no second insert".
            val recordedId = (first as RefusalRecording.Recorded).record.outboxId
            assertThat((second as RefusalRecording.Duplicate).record.outboxId).isEqualTo(recordedId)
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(1)
        }

    @Test
    fun `a retry after restart dedupes onto the stored record`() =
        runBlocking<Unit> {
            val store = InMemorySovereignOpsAuditOutboxStore()
            operations(store).recordRefusal(evidence())

            // A fresh operations instance sees only what is durable, exactly like a restarted process.
            val afterRestart = operations(store).recordRefusal(evidence())

            assertThat(afterRestart).isInstanceOf(RefusalRecording.Duplicate::class.java)
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(1)
        }

    @Test
    fun `a different attempt is a different record, not a dedupe`() =
        runBlocking<Unit> {
            val store = InMemorySovereignOpsAuditOutboxStore()
            val operations = operations(store)

            operations.recordRefusal(evidence(expectedVersion = 7))
            val other = operations.recordRefusal(evidence(expectedVersion = 8))

            assertThat(other).isInstanceOf(RefusalRecording.Recorded::class.java)
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(2)
        }

    @Test
    fun `a key match with different content fails closed`() =
        runBlocking<Unit> {
            val store = InMemorySovereignOpsAuditOutboxStore()
            val operations = operations(store)
            val wanted = evidence()
            // Same event key, different logical fact: the shape a bug or a forged row would take.
            val impostor =
                wanted
                    .toOutboxRecord(clock.instant(), outboxId = "impostor")
                    .copy(refusalReason = "role-not-permitted")
            store.appendDispatchable(impostor)

            assertThatThrownBy { runBlocking { operations.recordRefusal(wanted) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("tramai-outbox-event-key-collision")
        }

    @Test
    fun `the recorded refusal is dispatchable and claims no approval state`() =
        runBlocking<Unit> {
            val store = InMemorySovereignOpsAuditOutboxStore()
            val recording = operations(store).recordRefusal(evidence()) as RefusalRecording.Recorded
            val stored = store.get(recording.record.outboxId)

            assertThat(stored).isNotNull
            assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(stored.evidenceKind).isEqualTo(SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL)
            assertThat(stored.refusalReason).isEqualTo("missing-capability")
            // Key inputs persisted as values, not only as a hash.
            assertThat(stored.refusalPolicyVersion).isNull()
            assertThat(stored.refusalExpectedVersion).isEqualTo(7L)
            // A refusal decided nothing, and it read nothing, so it claims no approval state at all --
            // not even a previous one, which would have to name a status it never saw.
            assertThat(stored.approvalStatus).isNull()
            assertThat(stored.approvalVersion).isNull()
            assertThat(stored.previousApprovalStatus).isNull()
            assertThat(stored.previousApprovalVersion).isNull()
            assertThat(stored.eventKey).startsWith("refusal-sha256:")
            assertThat(stored.actorIdentityDigest).isEqualTo(evidence().actorIdentityDigest)
        }

    @Test
    fun `a capability refusal records evidence while reading nothing`() =
        runBlocking<Unit> {
            val approvals = CountingApprovalStore()
            val mutations = CountingMutationStore()
            val recorder = RecordingRefusalRecorder()
            val plane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvals,
                    mutationStore = mutations,
                    approvalPolicy = OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer")))),
                    clock = clock,
                    refusalRecorder = recorder,
                )

            val result = plane.approve(decisionCommand(), authorityWithoutCapability())

            assertThat(result)
                .isEqualTo(
                    ApprovalDecisionResult.Unauthorized(
                        ApprovalId(APPROVAL_ID),
                        ApprovalUnauthorizedReason.MISSING_CAPABILITY,
                    ),
                )
            // The primary invariant: existence-blind, so no approval is read and nothing is mutated.
            assertThat(approvals.reads).isZero()
            assertThat(mutations.calls).isZero()
            val recorded = recorder.single()
            assertThat(recorded.refusalReason).isEqualTo("missing-capability")
            // Nothing was consulted, so the key must not claim a policy version.
            assertThat(recorded.policyVersion).isNull()
            assertThat(recorded.expectedVersion).isEqualTo(3L)
        }

    @Test
    fun `a role refusal records the consulted policy version`() =
        runBlocking<Unit> {
            val approvals = CountingApprovalStore()
            val recorder = RecordingRefusalRecorder()
            val plane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = approvals,
                    mutationStore = CountingMutationStore(),
                    approvalPolicy = OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer")))),
                    clock = clock,
                    refusalRecorder = recorder,
                )

            plane.approve(decisionCommand(), authorityWithCapability(setOf(ApproverRole("finance-reviewer"))))

            assertThat(approvals.reads).isEqualTo(1)
            val recorded = recorder.single()
            assertThat(recorded.refusalReason).isEqualTo("role-not-permitted")
            assertThat(recorded.policyVersion).isEqualTo("v1")
        }

    @Test
    fun `a recording failure never becomes an allowed decision`() =
        runBlocking<Unit> {
            val mutations = CountingMutationStore()
            val plane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = CountingApprovalStore(),
                    mutationStore = mutations,
                    approvalPolicy = OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer")))),
                    clock = clock,
                    refusalRecorder = RecordingRefusalRecorder(failure = IllegalStateException("store down")),
                )

            assertThatThrownBy {
                runBlocking { plane.approve(decisionCommand(), authorityWithoutCapability()) }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("store down")
            // No mutation either way: the answer is a server-side failure, not a refusal and not an approval.
            assertThat(mutations.calls).isZero()
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun operations(store: SovereignOpsAuditOutboxStore): SovereignOpsAuditOutboxOperations =
        DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = store,
            outboxDispatcher = null,
            properties = SovereignOpsProperties(),
            // Recovery is irrelevant to recording; the resolver is never consulted on this path.
            recoveryResolver = { SovereignOpsPreparedRecoveryDecision.UNKNOWN },
            clock = clock,
        )

    private fun evidence(
        expectedVersion: Long = 7,
        policyVersion: String? = null,
    ) = RefusalEvidence(
        operation = RefusalOperation.APPROVE,
        approvalId = APPROVAL_ID,
        issuer = ISSUER,
        subject = SUBJECT,
        refusalReason = "missing-capability",
        policyVersion = policyVersion,
        expectedVersion = expectedVersion,
    )

    private fun decisionCommand() = ApprovalDecisionCommand(ApprovalId(APPROVAL_ID), EXPECTED_VERSION)

    private fun authorityWithoutCapability() =
        OperatorAuthority(
            OperatorPrincipal(
                subject = SUBJECT,
                issuer = ISSUER,
                authenticatedAt = clock.instant(),
                capabilities = emptySet(),
                approverRoles = emptySet(),
            ),
        )

    private fun authorityWithCapability(roles: Set<ApproverRole>) =
        OperatorAuthority(
            OperatorPrincipal(
                subject = SUBJECT,
                issuer = ISSUER,
                authenticatedAt = clock.instant(),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = roles,
            ),
        )

    /** Counts reads so "zero lookup" is a fact about the run, not a claim about the code. */
    private class CountingApprovalStore : ApprovalStore {
        var reads: Int = 0
            private set

        override suspend fun get(approvalId: String): ApprovalRequest? {
            reads++
            return pendingApproval()
        }

        override suspend fun create(request: ApprovalRequest): ApprovalRequest = request

        override suspend fun transition(
            approvalId: String,
            expectedVersion: Long,
            transition: dev.tramai.core.approval.ApprovalTransition,
        ): ApprovalRequest = error("a refusal must never transition an approval")

        override suspend fun consumeApprovedOrReplay(
            approvalId: String,
            expectedVersion: Long,
            presentedTokenDigest: Sha256Digest,
            consumedBy: String,
        ): ApprovalConsumptionReceipt = error("a refusal must never consume an approval")
    }

    private class CountingMutationStore : SovereignOpsApprovalMutationStore {
        var calls: Int = 0
            private set

        override suspend fun approveApprovalWithAuditIntent(
            approvalId: String,
            expectedVersion: Long,
            actor: String,
            reason: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsApprovalMutationResult {
            calls++
            error("a refusal must never reach the mutation store")
        }

        override suspend fun denyApprovalWithAuditIntent(
            approvalId: String,
            expectedVersion: Long,
            actor: String,
            reason: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsApprovalMutationResult {
            calls++
            error("a refusal must never reach the mutation store")
        }
    }

    private companion object {
        private const val APPROVAL_ID = "approval-refusal-1"
        private const val ISSUER = "https://issuer.example"
        private const val SUBJECT = "operator-1"
        private const val EXPECTED_VERSION = 3L

        private fun digest(suffix: String): Sha256Digest = Sha256Digest.of("sha256:" + suffix.repeat(64).take(64))

        private fun pendingApproval() =
            ApprovalRequest(
                approvalId = APPROVAL_ID,
                binding =
                    ApprovalBinding(
                        workflowRunId = "wf-1",
                        toolName = "claim-triage",
                        argumentsDigest = digest("01"),
                        policyVersion = "v1",
                        workflowDigest = digest("02"),
                        approvalTokenDigest = digest("03"),
                    ),
                status = ApprovalStatus.PENDING,
                requestedBy = SUBJECT,
                requestedAt = Instant.parse("2026-10-31T08:00:00Z"),
                expiresAt = Instant.parse("2026-10-31T10:00:00Z"),
                decidedBy = null,
                decidedAt = null,
                decisionComment = null,
                consumedBy = null,
                consumedAt = null,
                version = EXPECTED_VERSION,
            )
    }
}
