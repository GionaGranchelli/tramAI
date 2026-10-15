package dev.tramai.security.governance

import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.InMemoryAuditStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test

/**
 * 0.7.4c2 — the production path, end to end, with no substitute for the audit boundary.
 *
 * The chain under test is the real one: a decided policy outcome goes through the existing emitter
 * to `AuditStore.appendNext` in a real store implementation, is read back by
 * [AuditBackedGovernanceDecisionFactSource] through the `AuditStore` SPI, and is projected by
 * [GovernanceProjectionReader] against the real [WorkloadRegistrationAuthority] for the
 * registration half. Nothing between the decision and the projection is a stub.
 */
class AuditBackedGovernanceProjectionIntegrationTest {
    private val identity = WorkloadRegistrationFixtures.identity()
    private val runId = "run-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
    private val store = InMemoryAuditStore()
    private val engine = AuditEngine(store, clock = clock)
    private val emitter = AuditEnginePolicyDecisionAuditEmitter(engine)

    private fun context(
        enforcementPoint: EnforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
        toolName: String? = null,
    ) = PolicyContext(
        enforcementPoint = enforcementPoint,
        workflowId = "claims",
        workflowRunId = runId,
        correlationId = "corr-1",
        actorId = "system.anonymous",
        providerId = "ollama",
        modelName = "mistral",
        toolName = toolName,
        policyVersion = "v17",
    )

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            identity,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

    private fun declared() =
        DeclaredRunIdentity(
            workloadId = "claims",
            configurationId = "claims-prod",
            configurationVersion = "17",
            environmentId = "production",
            deploymentId = "eu-west-amsterdam-01",
            workflowRunId = runId,
        )

    private fun projection(
        authority: WorkloadRegistrationAuthority,
        source: GovernanceDecisionFactSource = AuditBackedGovernanceDecisionFactSource(store),
    ): GovernanceProjection = runBlocking { GovernanceProjectionReader(authority, source).read(identity, declared(), runId) }

    private suspend fun emitPolicy(decision: PolicyDecision) =
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), decision)

    private suspend fun emitTool(
        toolName: String,
        decision: PolicyDecision,
    ) = emitter.emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, context(toolName = toolName), decision)

    @Test
    fun `a persisted policy DENY reaches the projection as a policy decision at its source position`() =
        runBlocking<Unit> {
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            emitPolicy(PolicyDecision.Allow)

            val projected = projection(registeredAuthority())

            val policy = projected.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.latest.value?.decision).isEqualTo("ALLOW")
            assertThat(policy.latest.value?.sourceSequence).isEqualTo(2L)
            assertThat(policy.latest.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
            assertThat(policy.completeness).isEqualTo(Completeness.COMPLETE)
            assertThat(policy.coverage).isEqualTo(1L..2L)
        }

    @Test
    fun `a qualifying tool permission decision lands in the tool family and not the policy family`() =
        runBlocking<Unit> {
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            emitTool("pricing_lookup", PolicyDecision.Allow)

            val projected = projection(registeredAuthority())

            val tool = projected.decisions.single { it.kind == DecisionKind.TOOL_PERMISSION }
            assertThat(tool.latest.value?.decision).isEqualTo("ALLOW")
            assertThat(tool.latest.value?.sourceSequence).isEqualTo(2L)
            assertThat(
                tool.latest.value
                    ?.boundary
                    ?.id,
            ).isEqualTo("BEFORE_TOOL_EXECUTION")
            assertThat(tool.coverage).isEqualTo(1L..2L)

            val policy = projected.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.latest.value?.sourceSequence).isEqualTo(1L)
            assertThat(policy.latest.value?.decision).isEqualTo("DENY")
        }

    @Test
    fun `an ALLOW record stays an authorization record and never becomes runtime state`() =
        runBlocking<Unit> {
            emitPolicy(PolicyDecision.Allow)

            val projected = projection(registeredAuthority())
            val policy = projected.decisions.single { it.kind == DecisionKind.POLICY_DECISION }

            assertThat(policy.latest.authority).isNotEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
            assertThat(policy.latest.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
            assertThat(policy.latest.value?.decision).isEqualTo("ALLOW")
            // No completion, outcome or routing family can be produced by an audit read: the decision
            // families are the closed set, the tool family observed nothing here, and the routing
            // family still has no producer at all.
            assertThat(projected.decisions.map { it.kind })
                .containsExactly(
                    DecisionKind.POLICY_DECISION,
                    DecisionKind.TOOL_PERMISSION,
                    DecisionKind.PROVIDER_ROUTE,
                )
            assertThat(
                projected.decisions
                    .single { it.kind == DecisionKind.TOOL_PERMISSION }
                    .latest.value,
            ).isNull()
            assertThat(
                projected.decisions
                    .single { it.kind == DecisionKind.PROVIDER_ROUTE }
                    .latest.authority,
            ).isEqualTo(FactAuthority.NOT_PRODUCED)
            assertThat(projected.telemetry.value.orEmpty()).isEmpty()
        }

    @Test
    fun `decisions are historical records and stay independent of current registration state`() =
        runBlocking<Unit> {
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            val authority = registeredAuthority()
            val before = projection(authority)

            // Move authoritative registration state forward; the decision record must not move with it.
            authority.updateMetadata(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review, second pass"),
            )
            val after = projection(authority)

            assertThat(after.registration.authority).isEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
            assertThat(after.registration.observedVersion?.value)
                .isGreaterThan(before.registration.observedVersion?.value ?: 0L)
            val decisionBefore = before.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            val decisionAfter = after.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(decisionAfter.latest.value).isEqualTo(decisionBefore.latest.value)
            assertThat(decisionAfter.latest.authority)
                .isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
            assertThat(decisionAfter.completeness).isEqualTo(Completeness.COMPLETE)
        }

    @Test
    fun `completeness never exceeds what the source attested`() =
        runBlocking<Unit> {
            emitPolicy(PolicyDecision.Allow)
            emitPolicy(PolicyDecision.Allow)

            // A bounded read verifies a prefix and attests nothing about the rest.
            val bounded =
                projection(registeredAuthority(), AuditBackedGovernanceDecisionFactSource(store, pageSize = 1, maxEventsPerRead = 1))

            val policy = bounded.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(policy.coverage).isNull()
            assertThat(policy.latest.value?.sourceSequence).isEqualTo(1L)
        }

    @Test
    fun `a run with no audit records reports an unobserved family, not an absent producer`() =
        runBlocking<Unit> {
            val projected = projection(registeredAuthority())

            val policy = projected.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.latest.value).isNull()
            assertThat(policy.latest.authority).isEqualTo(FactAuthority.NOT_OBSERVED)
            assertThat(policy.latest.authority).isNotEqualTo(FactAuthority.NOT_PRODUCED)
        }
}
