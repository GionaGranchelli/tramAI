package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview REST approval control plane
 * and approval inbox endpoints.
 *
 * Creates:
 * - [ApprovalControlPlaneController] when `rest-control-plane-enabled=true`
 *   and required service-level beans are present
 * - [ApprovalInboxController] when `rest-control-plane-enabled=true`
 *   and an [ApprovalInboxQueryService] bean is present
 *
 * Disabled by default.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["org.springframework.web.bind.annotation.RestController"])
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
class ApprovalControlPlaneRestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalControlPlaneController::class)
    fun approvalControlPlaneController(
        decisionControlPlane: ApprovalDecisionControlPlane,
        resumeControlPlane: ApprovalResumeControlPlane,
        approvalStoreProvider: ObjectProvider<ApprovalStore>,
        approvalContinuationStoreProvider: ObjectProvider<ApprovalContinuationStore>,
    ): ApprovalControlPlaneController {
        val approvalStore = approvalStoreProvider.ifAvailable
            ?: throw IllegalStateException("tramai-sovereign-approval-rest-missing-approval-store")
        val approvalContinuationStore = approvalContinuationStoreProvider.ifAvailable
            ?: throw IllegalStateException("tramai-sovereign-approval-rest-missing-continuation-store")
        return ApprovalControlPlaneController(
            decisionControlPlane = decisionControlPlane,
            resumeControlPlane = resumeControlPlane,
            approvalStore = approvalStore,
            approvalContinuationStore = approvalContinuationStore,
        )
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalInboxController::class)
    @ConditionalOnBean(ApprovalInboxQueryService::class)
    fun approvalInboxController(
        queryService: ApprovalInboxQueryService,
    ): ApprovalInboxController =
        ApprovalInboxController(queryService)
}
