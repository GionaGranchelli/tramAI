package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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

        @Bean
        fun outboxStore(): SovereignOpsAuditOutboxStore = InMemorySovereignOpsAuditOutboxStore()

        @Bean
        fun mutationStore(
            approvalStore: ApprovalStore,
            outboxStore: SovereignOpsAuditOutboxStore,
        ): SovereignOpsApprovalMutationStore = InMemorySovereignOpsApprovalMutationStore(approvalStore, outboxStore)
    }
}
