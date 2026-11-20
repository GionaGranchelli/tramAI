package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * The activation boundary between mutation infrastructure and the operator-facing decision service
 * (0.7.6e, startup matrix rows 3 and 4).
 *
 * `mutations-enabled=true` means "approvals can be mutated here". It does not mean "an authenticated
 * operator decides approvals here", and an application that logs approvals for an internal workflow must keep
 * starting without operator authentication. What must never happen is a decision control plane that is
 * published *and* permissive — so the plane appears only next to an [OperatorApprovalPolicy], and its absence
 * is a plain absent bean rather than an allow-all fallback or an unconditional startup failure.
 */
class ApprovalDecisionControlPlaneActivationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalDecisionControlPlaneAutoConfiguration::class.java),
            ).withUserConfiguration(InfrastructureConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.mutations-enabled=true",
                "tramai.sovereign.ops.security.enabled=false",
            )

    @Test
    fun `mutation infrastructure starts without operator authentication and publishes no decision service`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ApprovalDecisionControlPlane::class.java)
        }
    }

    @Test
    fun `a configured approval policy activates the decision service`() {
        contextRunner
            .withUserConfiguration(ApprovalPolicyConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ApprovalDecisionControlPlane::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    class ApprovalPolicyConfig {
        @Bean
        fun operatorApprovalPolicy(): OperatorApprovalPolicy =
            OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer"))))
    }

    @Configuration(proxyBeanMethods = false)
    class InfrastructureConfig {
        @Bean
        fun approvalStore(): ApprovalStore = TestApprovalStore()

        // 0.7.6f: the decision plane writes refusal evidence before returning a refusal, so a deployed
        // plane needs a sink. Without one the plane fails closed on every refusal.
        @Bean
        fun refusalRecorder(): SovereignOpsRefusalRecorder = RecordingRefusalRecorder()

        @Bean
        fun outboxStore(): SovereignOpsAuditOutboxStore = InMemorySovereignOpsAuditOutboxStore()

        @Bean
        fun mutationStore(
            approvalStore: ApprovalStore,
            outboxStore: SovereignOpsAuditOutboxStore,
        ): SovereignOpsApprovalMutationStore = InMemorySovereignOpsApprovalMutationStore(approvalStore, outboxStore)
    }

    /**
     * A host bean marked `@Primary` must not silently become the requirement the control plane enforces.
     *
     * Without an explicit qualifier Spring resolves the injection point to the primary candidate and the
     * deployment quietly runs on a host-supplied policy — the exact substitution this task exists to remove.
     * The control plane therefore names the policy bean it was built with, and a hostile primary fails.
     */
    @Test
    fun `a primary host policy does not replace the configured requirement`() {
        contextRunner
            .withUserConfiguration(ApprovalPolicyConfig::class.java, PrimaryHostPolicyConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                // Observable that distinguishes the two candidate policies: the approval below sits at
                // "hostile-v1", which ONLY the host policy has an entry for. If the host policy were the one
                // enforced, the decision would be Approved; enforcing the configured policy leaves that
                // version unknown and refuses it.
                val store = context.getBean(ApprovalStore::class.java)
                runBlocking {
                    store.create(hostileVersionApproval())
                }
                val decision =
                    runBlocking {
                        context.getBean(ApprovalDecisionControlPlane::class.java).approve(
                            ApprovalDecisionCommand(ApprovalId("hostile-approval"), 0L),
                            authorityHolding(ApproverRole("anyone")),
                        )
                    }

                assertThat(decision).isEqualTo(
                    ApprovalDecisionResult.Unauthorized(
                        ApprovalId("hostile-approval"),
                        ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE,
                    ),
                )
            }
    }

    private fun hostileVersionApproval() =
        dev.tramai.core.approval.ApprovalRequest(
            approvalId = "hostile-approval",
            binding =
                dev.tramai.core.approval.ApprovalBinding(
                    workflowRunId = "wf-hostile",
                    toolName = "tool",
                    argumentsDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:${"a".repeat(64)}"),
                    policyVersion = "hostile-v1",
                    workflowDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:${"b".repeat(64)}"),
                    approvalTokenDigest =
                        dev.tramai.core.approval.Sha256Digest
                            .of("sha256:${"c".repeat(64)}"),
                ),
            status = dev.tramai.core.approval.ApprovalStatus.PENDING,
            requestedBy = "requester",
            requestedAt = Instant.parse("2026-10-25T08:00:00Z"),
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

    private fun authorityHolding(role: ApproverRole) =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "hostile-probe-operator",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-10-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(role),
            ),
        )

    @Configuration(proxyBeanMethods = false)
    class PrimaryHostPolicyConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        fun hostilePrimaryPolicy(): OperatorApprovalPolicy {
            val policy = OperatorApprovalPolicy(mapOf("hostile-v1" to setOf(ApproverRole("anyone"))))
            return policy
        }
    }
}
