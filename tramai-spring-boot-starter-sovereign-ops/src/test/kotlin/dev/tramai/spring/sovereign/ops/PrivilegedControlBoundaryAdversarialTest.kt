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
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.7.6g2 Adversarial qualification suite for privileged approval control boundaries.
 *
 * Verifies that approval mutations strictly enforce authentication, capability (L2),
 * role policy (L3), preconditions, outbox atomicity, and refusal evidence recording.
 */
class PrivilegedControlBoundaryAdversarialTest {
    private val now: Instant = Instant.parse("2026-06-25T10:15:30Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val authorizedAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "authorized-medical-reviewer",
                issuer = "https://issuer.example",
                authenticatedAt = now,
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    private val unauthorizedRoleAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "unauthorized-finance-reviewer",
                issuer = "https://issuer.example",
                authenticatedAt = now,
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("finance-reviewer")),
            ),
        )

    private val missingCapabilityAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "no-capability-operator",
                issuer = "https://issuer.example",
                authenticatedAt = now,
                capabilities = emptySet(),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    private val testApprovalPolicy =
        OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer"))))

    private fun digest(seed: String): Sha256Digest = Sha256Digest.of("sha256:${seed.repeat(64).take(64)}")

    private fun pendingApproval(
        approvalId: String = "approval-adv-1",
        version: Long = 0L,
        policyVersion: String = "v1",
    ): ApprovalRequest =
        ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = "run-adv-1",
                    toolName = "claim-triage",
                    argumentsDigest = digest("01"),
                    policyVersion = policyVersion,
                    workflowDigest = digest("02"),
                    approvalTokenDigest = digest("03"),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "test-requester",
            requestedAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = version,
        )

    private fun decisionCommand(
        approvalId: String = "approval-adv-1",
        expectedVersion: Long = 0L,
    ): ApprovalDecisionCommand =
        ApprovalDecisionCommand(
            approvalId = ApprovalId(approvalId),
            expectedVersion = expectedVersion,
            comment = "adversarial qualification test",
            correlationId = "corr-adv-1",
        )

    // ── G2-02: Missing L2 Capability Check & Positive Control ──

    @Test
    fun `G2-02 negative - operator without DECIDE_APPROVAL capability is refused existence-blindly`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(), missingCapabilityAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Unauthorized(
                    ApprovalId("approval-adv-1"),
                    ApprovalUnauthorizedReason.MISSING_CAPABILITY,
                ),
            )
            // L2 is existence-blind: store is never queried
            assertThat(store.getLookupCount).describedAs("store lookup count must be zero").isZero()
            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(persisted.version).isZero()

            // Refusal is recorded before returning, with consultedPolicyVersion = null (existence-blind)
            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.single()
            assertThat(refusal.operation).isEqualTo(RefusalOperation.APPROVE)
            assertThat(refusal.approvalId).isEqualTo("approval-adv-1")
            assertThat(refusal.refusalReason).isEqualTo(ApprovalUnauthorizedReason.MISSING_CAPABILITY.wireName)
            assertThat(refusal.policyVersion).isNull()
            assertThat(refusal.subject).isEqualTo(missingCapabilityAuthority.subject)
            assertThat(refusal.issuer).isEqualTo(missingCapabilityAuthority.issuer)

            // No outbox records created
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)).isEmpty()
        }
    }

    @Test
    fun `G2-02 positive control - operator with DECIDE_APPROVAL capability and correct role succeeds`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(), authorizedAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Approved::class.java)
            val approved = result as ApprovalDecisionResult.Approved
            assertThat(approved.decidedBy).isEqualTo(authorizedAuthority.subject)
            assertThat(approved.version).isEqualTo(1L)

            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(refusalRecorder.recorded).isEmpty()

            val pendingOutbox = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
            assertThat(pendingOutbox).hasSize(1)
            assertThat(pendingOutbox.single().evidenceKind).isEqualTo(SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED)
        }
    }

    // ── G2-03: Wrong L3 Approver Role & Positive Control ──

    @Test
    fun `G2-03 negative - operator with DECIDE_APPROVAL but wrong approver role is refused with refusal recorded`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(), unauthorizedRoleAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Unauthorized(
                    ApprovalId("approval-adv-1"),
                    ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED,
                ),
            )
            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(persisted.version).isZero()

            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.single()
            assertThat(refusal.operation).isEqualTo(RefusalOperation.APPROVE)
            assertThat(refusal.refusalReason).isEqualTo(ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED.wireName)
            assertThat(refusal.policyVersion).isEqualTo("v1")
            assertThat(refusal.subject).isEqualTo(unauthorizedRoleAuthority.subject)

            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
        }
    }

    @Test
    fun `G2-03 positive control - denial with correct approver role succeeds and records APPROVAL_DENIED evidence`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.deny(decisionCommand(), authorizedAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Denied::class.java)
            val denied = result as ApprovalDecisionResult.Denied
            assertThat(denied.decidedBy).isEqualTo(authorizedAuthority.subject)
            assertThat(denied.version).isEqualTo(1L)

            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.DENIED)
            assertThat(persisted.version).isEqualTo(1L)

            val pendingOutbox = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
            assertThat(pendingOutbox).hasSize(1)
            assertThat(pendingOutbox.single().evidenceKind).isEqualTo(SovereignOpsAuditEvidenceKind.APPROVAL_DENIED)
            assertThat(refusalRecorder.recorded).isEmpty()
        }
    }

    // ── G2-06 & G2-11: Fail Closed on Unconfigured Policy Version ──

    @Test
    fun `G2-11 negative - approval with unconfigured policy version fails closed and records refusal`() {
        runBlocking {
            // Approval requires policy version "v2", but configured policy only has "v1"
            val store = TestTrackingApprovalStore(pendingApproval(policyVersion = "v2"))
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(), authorizedAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Unauthorized(
                    ApprovalId("approval-adv-1"),
                    ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE,
                ),
            )
            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(persisted.version).isZero()

            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.single()
            assertThat(refusal.refusalReason).isEqualTo(ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE.wireName)
            assertThat(refusal.policyVersion).isEqualTo("v2")
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
        }
    }

    @Test
    fun `G2-11 positive control - when policy version v2 is configured, decision is authorized`() {
        runBlocking {
            val multiPolicy =
                OperatorApprovalPolicy(
                    mapOf(
                        "v1" to setOf(ApproverRole("medical-reviewer")),
                        "v2" to setOf(ApproverRole("medical-reviewer")),
                    ),
                )
            val store = TestTrackingApprovalStore(pendingApproval(policyVersion = "v2"))
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = multiPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(), authorizedAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Approved::class.java)
            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.APPROVED)
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(refusalRecorder.recorded).isEmpty()
        }
    }

    // ── G2-07: Stale Expected Version Rejected ──

    @Test
    fun `G2-07 negative - stale expectedVersion returns Stale and mutates nothing`() {
        runBlocking {
            // Store is at version 1L, caller provides expectedVersion 0L
            val store = TestTrackingApprovalStore(pendingApproval(version = 1L))
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val rawMutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            var mutationStoreCalls = 0
            val trackingMutationStore =
                object : SovereignOpsApprovalMutationStore by rawMutationStore {
                    override suspend fun approveApprovalWithAuditIntent(
                        approvalId: String,
                        expectedVersion: Long,
                        actor: String,
                        reason: String,
                        auditIntent: SovereignOpsAuditOutboxRecord,
                    ): SovereignOpsApprovalMutationResult {
                        mutationStoreCalls++
                        return rawMutationStore.approveApprovalWithAuditIntent(
                            approvalId = approvalId,
                            expectedVersion = expectedVersion,
                            actor = actor,
                            reason = reason,
                            auditIntent = auditIntent,
                        )
                    }
                }
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = trackingMutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val result = controlPlane.approve(decisionCommand(expectedVersion = 0L), authorizedAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Stale(
                    approvalId = ApprovalId("approval-adv-1"),
                    expectedVersion = 0L,
                    currentVersion = 1L,
                ),
            )
            assertThat(mutationStoreCalls).describedAs("a stale command must never reach the mutation store").isZero()
            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.version).isEqualTo(1L)
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
            assertThat(refusalRecorder.recorded).isEmpty()
        }
    }

    // ── G2-12: Outbox Atomicity / Control Plane Mutation Failure Handling ──
    // Note: Full database transaction rollback is qualified against real PostgreSQL
    // in PrivilegedControlBoundaryConcurrencyQualificationTest. This unit test verifies
    // that control-plane failure handling correctly captures mutation-store exceptions
    // and returns Conflict without mutating approval state.

    @Test
    fun `G2-12 - failed evidence persistence aborts approval mutation and leaves state untouched`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val failingMutationStore =
                object : SovereignOpsApprovalMutationStore {
                    override suspend fun approveApprovalWithAuditIntent(
                        approvalId: String,
                        expectedVersion: Long,
                        actor: String,
                        reason: String,
                        auditIntent: SovereignOpsAuditOutboxRecord,
                    ): SovereignOpsApprovalMutationResult = error("outbox-storage-disk-failure")

                    override suspend fun denyApprovalWithAuditIntent(
                        approvalId: String,
                        expectedVersion: Long,
                        actor: String,
                        reason: String,
                        auditIntent: SovereignOpsAuditOutboxRecord,
                    ): SovereignOpsApprovalMutationResult = error("outbox-storage-disk-failure")
                }

            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = failingMutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand(), authorizedAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Conflict::class.java)
            val conflict = result as ApprovalDecisionResult.Conflict
            assertThat(conflict.reason).contains("outbox-storage-disk-failure")

            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(persisted.version).isZero()
        }
    }

    // ── G2-13: Refusal Replay / Idempotent Isolation ──
    // Note: Durable deduplication of retried refusal event keys is authoritative in JDBC persistence
    // and verified by JdbcRefusalRecordingRestartTest. This unit test verifies that control-plane replays
    // never mutate approval state and never generate pending outbox records.

    @Test
    fun `G2-13 - replaying refused commands leaves approval state unmutated and outbox isolated`() {
        runBlocking {
            val store = TestTrackingApprovalStore(pendingApproval())
            val outboxStore = InMemorySovereignOpsAuditOutboxStore()
            val mutationStore = InMemorySovereignOpsApprovalMutationStore(store, outboxStore)
            val refusalRecorder = RecordingRefusalRecorder()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = mutationStore,
                    approvalPolicy = testApprovalPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            repeat(5) {
                val result = controlPlane.approve(decisionCommand(), unauthorizedRoleAuthority)
                assertThat(result).isEqualTo(
                    ApprovalDecisionResult.Unauthorized(
                        ApprovalId("approval-adv-1"),
                        ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED,
                    ),
                )
            }

            val persisted = requireNotNull(store.get("approval-adv-1"))
            assertThat(persisted.status).isEqualTo(ApprovalStatus.PENDING)
            assertThat(persisted.version).isZero()
            assertThat(outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).isEmpty()
        }
    }

    // ── Test helpers ──

    private class TestTrackingApprovalStore(
        initial: ApprovalRequest,
    ) : ApprovalStore {
        private val storage = ConcurrentHashMap<String, ApprovalRequest>().apply { put(initial.approvalId, initial) }
        var getLookupCount: Int = 0
            private set

        override suspend fun create(request: ApprovalRequest): ApprovalRequest {
            storage[request.approvalId] = request
            return request
        }

        override suspend fun get(approvalId: String): ApprovalRequest? {
            getLookupCount++
            return storage[approvalId]
        }

        override suspend fun transition(
            approvalId: String,
            expectedVersion: Long,
            transition: ApprovalTransition,
        ): ApprovalRequest {
            val current = storage[approvalId] ?: error("not found")
            if (current.version != expectedVersion) {
                val msg = "version conflict: expected $expectedVersion was ${current.version}"
                throw ApprovalStoreConflictException(msg)
            }
            val updated =
                when (transition) {
                    is ApprovalTransition.Approve -> {
                        current.copy(
                            status = ApprovalStatus.APPROVED,
                            decidedBy = transition.decidedBy,
                            decidedAt = Instant.now(),
                            decisionComment = transition.comment,
                            version = current.version + 1,
                        )
                    }

                    is ApprovalTransition.Deny -> {
                        current.copy(
                            status = ApprovalStatus.DENIED,
                            decidedBy = transition.decidedBy,
                            decidedAt = Instant.now(),
                            decisionComment = transition.comment,
                            version = current.version + 1,
                        )
                    }

                    else -> {
                        current
                    }
                }
            storage[approvalId] = updated
            return updated
        }

        override suspend fun consumeApprovedOrReplay(
            approvalId: String,
            expectedVersion: Long,
            presentedTokenDigest: Sha256Digest,
            consumedBy: String,
        ): ApprovalConsumptionReceipt = throw UnsupportedOperationException("test double")
    }
}
