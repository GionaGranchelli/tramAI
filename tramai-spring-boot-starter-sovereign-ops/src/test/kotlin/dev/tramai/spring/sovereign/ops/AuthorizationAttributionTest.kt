package dev.tramai.spring.sovereign.ops

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
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Contract Test 12 (0.7.6e): attribution comes from the verified authority, at every sink.
 *
 * The fixture identities are deliberately distinctive: [VERIFIED_SUBJECT] and [VERIFIED_ISSUER] appear nowhere
 * else in the fixture, so a control plane that fell back to a literal, a default, a stale field or a
 * request-derived value cannot pass. Removing the command's `actorId`/`actorRole` did not make this test
 * redundant — it removed one *input*, and these assertions are about the *outputs*.
 *
 * Four sinks are asserted, because four different things consume the decider:
 * - the per-approval role requirement receives the verified approver roles (proved both ways: the operator
 *   holding the configured role is allowed, one holding a different verified role is refused);
 * - the approval record's `decidedBy`;
 * - the audit outbox record's `actor`;
 * - the authoritative mutation store's `actor` argument.
 */
class AuthorizationAttributionTest {
    private companion object {
        const val VERIFIED_SUBJECT = "verified-operator-9f2c"
        const val VERIFIED_ISSUER = "https://issuer-verified.example"
        const val APPROVAL_ID = "approval-attribution"
        const val POLICY_VERSION = "2024-06-medical-v3"
        val VERIFIED_ROLE = ApproverRole("verified-only-reviewer")
        val OTHER_ROLE = ApproverRole("finance-reviewer")
        val NOW: Instant = Instant.parse("2026-10-25T09:00:00Z")
    }

    private fun authority(roles: Set<ApproverRole>) =
        OperatorAuthority(
            OperatorPrincipal(
                subject = VERIFIED_SUBJECT,
                issuer = VERIFIED_ISSUER,
                authenticatedAt = NOW,
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = roles,
            ),
        )

    private val policy =
        OperatorApprovalPolicy(mapOf(POLICY_VERSION to setOf(VERIFIED_ROLE)))

    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private fun pendingApproval() =
        ApprovalRequest(
            approvalId = APPROVAL_ID,
            binding =
                ApprovalBinding(
                    workflowRunId = "wf-attribution",
                    toolName = "expense-reimbursement",
                    argumentsDigest = Sha256Digest.of("sha256:${"a".repeat(64)}"),
                    policyVersion = POLICY_VERSION,
                    workflowDigest = Sha256Digest.of("sha256:${"b".repeat(64)}"),
                    approvalTokenDigest = Sha256Digest.of("sha256:${"c".repeat(64)}"),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "requester-1",
            requestedAt = NOW.minusSeconds(300),
            expiresAt = NOW.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

    @Test
    fun `every attribution sink records the verified subject and the policy sees the verified roles`() {
        val store = AttributionApprovalStore(pendingApproval())
        val mutationStore = AttributionMutationStore(store)
        val controlPlane =
            SovereignOpsApprovalDecisionControlPlane(
                approvalStore = store,
                mutationStore = mutationStore,
                approvalPolicy = policy,
                clock = clock,
            )

        val result =
            runBlocking {
                controlPlane.approve(
                    ApprovalDecisionCommand(
                        approvalId = ApprovalId(APPROVAL_ID),
                        expectedVersion = 0L,
                        comment = "attribution check",
                        correlationId = "corr-attribution",
                    ),
                    authority(setOf(VERIFIED_ROLE)),
                )
            }

        // Sink 1 — the approval record's decider.
        assertThat(result).isInstanceOf(ApprovalDecisionResult.Approved::class.java)
        assertThat((result as ApprovalDecisionResult.Approved).decidedBy).isEqualTo(VERIFIED_SUBJECT)
        assertThat(store.stored()?.decidedBy).isEqualTo(VERIFIED_SUBJECT)

        // Sink 2 — the audit outbox record's actor.
        assertThat(mutationStore.recordedAuditActor).isEqualTo(VERIFIED_SUBJECT)
        assertThat(mutationStore.recordedAuditRecord?.actor).isEqualTo(VERIFIED_SUBJECT)

        // Sink 3 — the authoritative mutation store's actor argument.
        assertThat(mutationStore.recordedActor).isEqualTo(VERIFIED_SUBJECT)

        // None of the sinks may carry a value that is not the verified subject.
        assertThat(listOf(store.stored()?.decidedBy, mutationStore.recordedActor, mutationStore.recordedAuditActor))
            .allMatch { it == VERIFIED_SUBJECT }
    }

    @Test
    fun `the role requirement is evaluated against the verified approver roles, not the identity`() {
        val store = AttributionApprovalStore(pendingApproval())
        val controlPlane =
            SovereignOpsApprovalDecisionControlPlane(
                approvalStore = store,
                mutationStore = AttributionMutationStore(store),
                approvalPolicy = policy,
                clock = clock,
            )

        // Same verified identity, same capability, but a verified approver role the policy does not accept:
        // the refusal proves the policy received the authority's roles rather than a default.
        val refused =
            runBlocking {
                controlPlane.approve(
                    ApprovalDecisionCommand(ApprovalId(APPROVAL_ID), 0L),
                    authority(setOf(OTHER_ROLE)),
                )
            }

        assertThat(refused).isEqualTo(
            ApprovalDecisionResult.Unauthorized(ApprovalId(APPROVAL_ID), ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED),
        )
        assertThat(store.stored()?.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(store.stored()?.decidedBy).isNull()
    }

    @Test
    fun `two verified operators are attributed separately through the same control plane`() {
        val store = AttributionApprovalStore(pendingApproval())
        val mutationStore = AttributionMutationStore(store)
        val controlPlane =
            SovereignOpsApprovalDecisionControlPlane(
                approvalStore = store,
                mutationStore = mutationStore,
                approvalPolicy = policy,
                clock = clock,
            )
        val secondOperator =
            OperatorAuthority(
                OperatorPrincipal(
                    subject = "second-verified-operator-3b7e",
                    issuer = VERIFIED_ISSUER,
                    authenticatedAt = NOW,
                    capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                    approverRoles = setOf(VERIFIED_ROLE),
                ),
            )

        runBlocking {
            controlPlane.approve(ApprovalDecisionCommand(ApprovalId(APPROVAL_ID), 0L), secondOperator)
        }

        assertThat(store.stored()?.decidedBy).isEqualTo("second-verified-operator-3b7e")
        assertThat(mutationStore.recordedActor).isEqualTo("second-verified-operator-3b7e")
    }
}

/** Minimal in-memory approval store: enough to read, transition and inspect what was written. */
private class AttributionApprovalStore(
    private var request: ApprovalRequest,
) : ApprovalStore {
    fun stored(): ApprovalRequest? = request

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        this.request = request
        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? = request.takeIf { it.approvalId == approvalId }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: dev.tramai.core.approval.ApprovalTransition,
    ): ApprovalRequest {
        val current = requireNotNull(get(approvalId)) { "missing approval" }
        check(current.version == expectedVersion) { "tramai-sovereign-ops-approval-version-conflict" }
        val updated =
            current.copy(
                status =
                    when (transition) {
                        is dev.tramai.core.approval.ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                        is dev.tramai.core.approval.ApprovalTransition.Deny -> ApprovalStatus.DENIED
                        else -> current.status
                    },
                decidedBy =
                    when (transition) {
                        is dev.tramai.core.approval.ApprovalTransition.Approve -> transition.decidedBy
                        is dev.tramai.core.approval.ApprovalTransition.Deny -> transition.decidedBy
                        else -> current.decidedBy
                    },
                version = current.version + 1,
            )
        request = updated
        return updated
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt = throw UnsupportedOperationException("attribution fixture does not consume")
}

/**
 * Records the actor the control plane passes to the authoritative mutation, and the audit actor it builds.
 *
 * Recording rather than asserting inside the store keeps the assertion in the test, where a failure names the
 * sink that broke.
 */
private class AttributionMutationStore(
    private val store: AttributionApprovalStore,
) : SovereignOpsApprovalMutationStore {
    var recordedActor: String? = null
    var recordedAuditActor: String? = null
    var recordedAuditRecord: SovereignOpsAuditOutboxRecord? = null

    override suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult =
        mutate(
            approvalId,
            expectedVersion,
            actor,
            auditIntent,
            dev.tramai.core.approval.ApprovalTransition
                .Deny(actor, reason),
        )

    override suspend fun approveApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult =
        mutate(
            approvalId,
            expectedVersion,
            actor,
            auditIntent,
            dev.tramai.core.approval.ApprovalTransition
                .Approve(actor, reason),
        )

    private suspend fun mutate(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
        transition: dev.tramai.core.approval.ApprovalTransition,
    ): SovereignOpsApprovalMutationResult {
        recordedActor = actor
        recordedAuditActor = auditIntent.actor
        recordedAuditRecord = auditIntent
        val updated = store.transition(approvalId, expectedVersion, transition)
        return SovereignOpsApprovalMutationResult(approval = updated, auditOutboxRecord = auditIntent)
    }
}
