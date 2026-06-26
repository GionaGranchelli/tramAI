package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview REST approval control plane.
 *
 * Creates an [ApprovalControlPlaneController] when the service-level control
 * plane beans are present.
 *
 * Disabled by default - set `tramai.sovereign.ops.rest-control-plane-enabled=true` to enable.
 *
 * @see ApprovalControlPlaneController
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
class ApprovalControlPlaneRestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalControlPlaneController::class)
    @ConditionalOnBean(
        value = [
            ApprovalDecisionControlPlane::class,
            ApprovalResumeControlPlane::class,
            ApprovalStore::class,
            ApprovalContinuationStore::class,
        ],
    )
    fun approvalControlPlaneController(
        decisionControlPlane: ApprovalDecisionControlPlane,
        resumeControlPlane: ApprovalResumeControlPlane,
        approvalStore: ApprovalStore,
        approvalContinuationStore: ApprovalContinuationStore,
    ): ApprovalControlPlaneController = ApprovalControlPlaneController(
        decisionControlPlane = decisionControlPlane,
        resumeControlPlane = resumeControlPlane,
        approvalStore = approvalStore,
        approvalContinuationStore = approvalContinuationStore,
    )
}
