package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
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
 * A [OperatorApprovalPolicy] bean is **required**. The former optional `ApprovalDecisionAuthorizer` hook
 * is gone: an arbitrary policy object could answer `true` for every approval while every startup check
 * still passed, so the per-approval requirement is now deployment configuration keyed by the approval's
 * persisted `policyVersion` and cannot be replaced by a permissive bean.
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
            SovereignOpsApprovalMutationStore::class,
            ApprovalStore::class,
        ],
    )
    fun sovereignOpsApprovalDecisionControlPlane(
        approvalStore: ApprovalStore,
        mutationStore: SovereignOpsApprovalMutationStore,
        approvalPolicy: ObjectProvider<OperatorApprovalPolicy>,
    ): ApprovalDecisionControlPlane =
        SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            approvalPolicy =
                requireNotNull(approvalPolicy.ifAvailable) {
                    "tramai-approval-policy-missing: no OperatorApprovalPolicy is configured, so no privileged " +
                        "decision could be authorized; configure the approval-policies requirement"
                },
        )
}
