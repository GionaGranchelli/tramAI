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
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SovereignOpsApprovalDecisionControlPlaneTest {
    private val now: Instant = Instant.parse("2026-06-25T10:15:30Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    /**
     * The authority the migrated tests decide under: authenticated, holding DECIDE_APPROVAL, and holding the
     * approver role the fixture approvals require. Every decision call passes it explicitly, because the
     * control plane no longer accepts identity from the command.
     */
    private val operatorAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "test-operator",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-10-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    /** Requirement for the fixture approvals, which are all recorded at `policyVersion = "v1"`. */
    private val testApprovalPolicy =
        OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer"))))

    @Test
    fun `approve pending approval returns Approved`() {
        runBlocking {
            val store = MutableApprovalStore(mutableMapOf("approval-1" to pendingApproval("approval-1")))
            val mutationStore = StubApprovalMutationStore(store)
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = mutationStore,
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand("approval-1"), operatorAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Approved::class.java)
            val approved = result as ApprovalDecisionResult.Approved
            assertThat(approved.approvalId.value).isEqualTo("approval-1")
            // The recorded decider is the verified authority subject, not any value the command carried:
            // the command has no actor field left to carry one.
            assertThat(approved.decidedBy).isEqualTo("test-operator")
            assertThat(approved.decidedAt).isEqualTo(now)
            assertThat(approved.version).isEqualTo(1L)
        }
    }

    @Test
    fun `deny pending approval returns Denied`() {
        runBlocking {
            val store = MutableApprovalStore(mutableMapOf("approval-1" to pendingApproval("approval-1")))
            val mutationStore = StubApprovalMutationStore(store)
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = mutationStore,
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-1"), operatorAuthority)

            assertThat(result).isInstanceOf(ApprovalDecisionResult.Denied::class.java)
            val denied = result as ApprovalDecisionResult.Denied
            assertThat(denied.approvalId.value).isEqualTo("approval-1")
            assertThat(denied.decidedBy).isEqualTo("test-operator")
            assertThat(denied.decidedAt).isEqualTo(now)
            assertThat(denied.version).isEqualTo(1L)
        }
    }

    @Test
    fun `already approved returns AlreadyApproved`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to approvedApproval("approval-1", "existing-reviewer", now.minusSeconds(60)),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyApproved(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `already denied returns AlreadyDenied`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to deniedApproval("approval-1", "existing-reviewer", now.minusSeconds(60)),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyDenied(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `expired approval returns Expired`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf("approval-1" to pendingApproval("approval-1", expiresAt = now)),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-1"), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Expired(
                    approvalId = ApprovalId("approval-1"),
                    expiredAt = now,
                ),
            )
        }
    }

    @Test
    fun `already approved expired approval returns AlreadyApproved`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to
                            approvedApproval(
                                approvalId = "approval-1",
                                decidedBy = "existing-reviewer",
                                decidedAt = now.minusSeconds(60),
                            ).copy(expiresAt = now),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyApproved(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `already denied expired approval returns AlreadyDenied`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to
                            deniedApproval(
                                approvalId = "approval-1",
                                decidedBy = "existing-reviewer",
                                decidedAt = now.minusSeconds(60),
                            ).copy(expiresAt = now),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyDenied(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `missing approval returns NotFound`() {
        runBlocking {
            val store = MutableApprovalStore()
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-404"), operatorAuthority)

            assertThat(result).isEqualTo(ApprovalDecisionResult.NotFound(ApprovalId("approval-404")))
        }
    }

    @Test
    fun `an operator without the required approver role is refused with a typed Unauthorized`() {
        runBlocking {
            val store = MutableApprovalStore(mutableMapOf("approval-1" to pendingApproval("approval-1")))
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    // A policy that accepts only a role this operator does not hold. The former
                    // permissive authorizer hook would have answered this with a Conflict; the refusal is
                    // now a typed Unauthorized naming the role requirement.
                    approvalPolicy = OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("finance-reviewer")))),
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand("approval-1"), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Unauthorized(
                    approvalId = ApprovalId("approval-1"),
                    reason = ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED,
                ),
            )
        }
    }

    @Test
    fun `approve on denied returns AlreadyDenied`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to deniedApproval("approval-1", "existing-reviewer", now.minusSeconds(60)),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.approve(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyDenied(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `deny on approved returns AlreadyApproved`() {
        runBlocking {
            val store =
                MutableApprovalStore(
                    mutableMapOf(
                        "approval-1" to approvedApproval("approval-1", "existing-reviewer", now.minusSeconds(60)),
                    ),
                )
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = StubApprovalMutationStore(store),
                    clock = clock,
                )

            val result = controlPlane.deny(decisionCommand("approval-1", expectedVersion = 1L), operatorAuthority)

            assertThat(result).isEqualTo(
                ApprovalDecisionResult.AlreadyApproved(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            )
        }
    }

    @Test
    fun `version conflict at mutation store returns Conflict`() {
        runBlocking {
            val store = MutableApprovalStore(mutableMapOf("approval-1" to pendingApproval("approval-1")))
            val mutationStore = StubApprovalMutationStore(store)
            val controlPlane =
                SovereignOpsApprovalDecisionControlPlane(
                    approvalPolicy = testApprovalPolicy,
                    approvalStore = store,
                    mutationStore = mutationStore,
                    clock = clock,
                )

            val result =
                controlPlane.approve(
                    decisionCommand("approval-1", expectedVersion = 99L),
                    operatorAuthority,
                )

            // F2 fix: a caller-observed version that does not match authoritative state is a stale
            // precondition, reported before the mutation store is consulted at all.
            assertThat(result).isEqualTo(
                ApprovalDecisionResult.Stale(
                    approvalId = ApprovalId("approval-1"),
                    expectedVersion = 99L,
                    currentVersion = 0L,
                ),
            )
            assertThat(store.get("approval-1")!!.version).isEqualTo(0L)
            assertThat(store.get("approval-1")!!.status).isEqualTo(ApprovalStatus.PENDING)
        }
    }

    private fun decisionCommand(
        approvalId: String,
        expectedVersion: Long = 0L,
    ): ApprovalDecisionCommand =
        ApprovalDecisionCommand(
            approvalId = ApprovalId(approvalId),
            expectedVersion = expectedVersion,
            comment = "review complete",
            correlationId = "corr-1",
        )

    private fun pendingApproval(
        approvalId: String,
        expiresAt: Instant = now.plusSeconds(600),
    ): ApprovalRequest =
        approval(
            approvalId = approvalId,
            status = ApprovalStatus.PENDING,
            expiresAt = expiresAt,
        )

    private fun approvedApproval(
        approvalId: String,
        decidedBy: String,
        decidedAt: Instant,
    ): ApprovalRequest =
        approval(
            approvalId = approvalId,
            status = ApprovalStatus.APPROVED,
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionComment = "approved",
            version = 1L,
        )

    private fun deniedApproval(
        approvalId: String,
        decidedBy: String,
        decidedAt: Instant,
    ): ApprovalRequest =
        approval(
            approvalId = approvalId,
            status = ApprovalStatus.DENIED,
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionComment = "denied",
            version = 1L,
        )

    private fun approval(
        approvalId: String,
        status: ApprovalStatus,
        expiresAt: Instant = now.plusSeconds(600),
        decidedBy: String? = null,
        decidedAt: Instant? = null,
        decisionComment: String? = null,
        version: Long = 0L,
    ): ApprovalRequest =
        ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = "wf-$approvalId",
                    toolName = "claim-triage",
                    argumentsDigest = digest("01"),
                    policyVersion = "v1",
                    workflowDigest = digest("02"),
                    approvalTokenDigest = digest("03"),
                ),
            status = status,
            requestedBy = "triage-system",
            requestedAt = now.minusSeconds(30),
            expiresAt = expiresAt,
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionComment = decisionComment,
            consumedBy = null,
            consumedAt = null,
            version = version,
        )

    private fun digest(suffix: String): Sha256Digest = Sha256Digest.of("sha256:${suffix.padStart(64, suffix.first())}")
}

private class MutableApprovalStore(
    private val approvals: MutableMap<String, ApprovalRequest> = mutableMapOf(),
) : ApprovalStore {
    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        approvals[request.approvalId] = request
        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? = approvals[approvalId]

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest = throw UnsupportedOperationException("transition should not be called directly in this test")

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt = throw UnsupportedOperationException("stub")

    fun put(request: ApprovalRequest) {
        approvals[request.approvalId] = request
    }
}

private class StubApprovalMutationStore(
    private val approvalStore: MutableApprovalStore,
) : SovereignOpsApprovalMutationStore {
    override suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult =
        mutate(
            approvalId = approvalId,
            expectedVersion = expectedVersion,
            actor = actor,
            reason = reason,
            targetStatus = ApprovalStatus.DENIED,
            auditIntent = auditIntent,
        )

    override suspend fun approveApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult =
        mutate(
            approvalId = approvalId,
            expectedVersion = expectedVersion,
            actor = actor,
            reason = reason,
            targetStatus = ApprovalStatus.APPROVED,
            auditIntent = auditIntent,
        )

    private suspend fun mutate(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        targetStatus: ApprovalStatus,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        val current = requireNotNull(approvalStore.get(approvalId)) { "missing approval" }
        check(current.version == expectedVersion) { "tramai-sovereign-ops-approval-version-conflict" }
        val decidedAt = Instant.parse("2026-06-25T10:15:30Z")
        val updated =
            current.copy(
                status = targetStatus,
                decidedBy = actor,
                decidedAt = decidedAt,
                decisionComment = reason,
                version = current.version + 1,
            )
        approvalStore.put(updated)
        return SovereignOpsApprovalMutationResult(
            approval = updated,
            auditOutboxRecord = auditIntent,
        )
    }
}
