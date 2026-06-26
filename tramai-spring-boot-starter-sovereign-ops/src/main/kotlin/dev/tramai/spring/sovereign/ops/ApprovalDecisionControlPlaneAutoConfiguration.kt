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
 * An optional [ApprovalDecisionAuthorizer] bean can be provided to customise
 * decision authorization; otherwise [AllowAllApprovalDecisionAuthorizer] is used.
 *
 * @see SovereignOpsApprovalDecisionControlPlane
 * @see ApprovalDecisionAuthorizer
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
        authorizer: ObjectProvider<ApprovalDecisionAuthorizer>,
    ): ApprovalDecisionControlPlane =
        SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            authorizer = authorizer.ifAvailable ?: AllowAllApprovalDecisionAuthorizer,
        )
}
