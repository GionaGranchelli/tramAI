package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import dev.tramai.spring.sovereign.ops.outbox.UnconfiguredRefusalRecorder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview [ApprovalDecisionControlPlane].
 *
 * Creates a [SovereignOpsApprovalDecisionControlPlane] bean when
 * [SovereignOpsApprovalMutationStore] and [ApprovalStore] are available.
 *
 * **Activation boundary (0.7.6e).** `mutations-enabled` turns on mutation *infrastructure*; it does not
 * mean an operator-facing decision service is configured, and an application that uses approvals without
 * operator authentication must keep starting. So this control plane is created only when a
 * [OperatorApprovalPolicy] bean exists, and simply is not published otherwise — no permissive default, no
 * startup failure for infrastructure-only deployments. A component that injects
 * [ApprovalDecisionControlPlane] then fails to start on the missing bean, which is the correct place to
 * fail: "the decision service is active" means somebody asked for it.
 *
 * A [OperatorApprovalPolicy] is required whenever the control plane *is* published. The former optional
 * `ApprovalDecisionAuthorizer` hook is gone: an arbitrary policy object could answer `true` for every
 * approval while every startup check still passed, so the per-approval requirement is now deployment
 * configuration keyed by the approval's persisted `policyVersion` and cannot be replaced by a permissive
 * bean.
 *
 * @see SovereignOpsApprovalDecisionControlPlane
 * @see OperatorApprovalPolicy
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["mutations-enabled"],
    havingValue = "true",
)
class ApprovalDecisionControlPlaneAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ApprovalDecisionControlPlane::class)
    @ConditionalOnBean(
        value = [
            OperatorApprovalPolicy::class,
            SovereignOpsApprovalMutationStore::class,
            ApprovalStore::class,
        ],
    )
    fun sovereignOpsApprovalDecisionControlPlane(
        approvalStore: ApprovalStore,
        mutationStore: SovereignOpsApprovalMutationStore,
        @org.springframework.beans.factory.annotation.Qualifier("operatorApprovalPolicy")
        approvalPolicy: OperatorApprovalPolicy,
        refusalRecorder: ObjectProvider<SovereignOpsRefusalRecorder>,
    ): ApprovalDecisionControlPlane =
        SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            approvalPolicy = approvalPolicy,
            // 0.7.6f §5.3: refusal evidence is written before the refusal is returned. Without a configured
            // sink the plane keeps its fail-closed default, so an unrecorded refusal cannot be returned as
            // if it had been recorded.
            refusalRecorder = refusalRecorder.getIfAvailable() ?: UnconfiguredRefusalRecorder,
        )
}
