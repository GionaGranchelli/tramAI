package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import dev.tramai.spring.sovereign.ops.rest.security.OperatorSecurityProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
 * Disabled by default, and **fail-closed when enabled without the operator security boundary**: a
 * reachable approve/deny endpoint with authentication and authorization switched off is the privilege
 * escalation 0.7.6e exists to remove, so that combination fails startup with a named reason instead of
 * serving requests. Enabling mutations without this controller is unaffected — infrastructure-only
 * deployments keep starting.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["org.springframework.web.bind.annotation.RestController"])
@EnableConfigurationProperties(OperatorSecurityProperties::class)
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["rest-control-plane-enabled"],
    havingValue = "true",
)
class ApprovalControlPlaneRestAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ApprovalControlPlaneController::class)
    fun approvalControlPlaneController(
        decisionControlPlaneProvider: ObjectProvider<ApprovalDecisionControlPlane>,
        resumeControlPlane: ApprovalResumeControlPlane,
        approvalStoreProvider: ObjectProvider<ApprovalStore>,
        approvalContinuationStoreProvider: ObjectProvider<ApprovalContinuationStore>,
        operatorSecurity: OperatorSecurityProperties,
    ): ApprovalControlPlaneController {
        // Startup matrix, in the order the failure must be reported: authentication first, because a
        // missing policy is a configuration gap while a missing boundary is an open door.
        if (!operatorSecurity.enabled) {
            throw IllegalStateException(
                "tramai-rest-control-plane-requires-operator-security: " +
                    "tramai.sovereign.ops.rest-control-plane-enabled=true while " +
                    "tramai.sovereign.ops.security.enabled=false; the approve/deny endpoints would be reachable " +
                    "without verified identity. Enable the operator security boundary or disable the control plane.",
            )
        }
        val decisionControlPlane =
            decisionControlPlaneProvider.ifAvailable
                ?: throw IllegalStateException(
                    "tramai-rest-control-plane-requires-approval-policy: no approval decision control plane is " +
                        "active, which means no OperatorApprovalPolicy is configured; configure " +
                        "tramai.sovereign.ops.security.approval-policies",
                )
        val approvalStore =
            approvalStoreProvider.ifAvailable
                ?: throw IllegalStateException("tramai-sovereign-approval-rest-missing-approval-store")
        val approvalContinuationStore =
            approvalContinuationStoreProvider.ifAvailable
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
    fun approvalInboxController(queryService: ApprovalInboxQueryService): ApprovalInboxController = ApprovalInboxController(queryService)

    @Bean
    @ConditionalOnMissingBean(ApprovalReviewerUiController::class)
    @ConditionalOnBean(ApprovalInboxQueryService::class, ApprovalDecisionControlPlane::class)
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops",
        name = ["reviewer-ui-enabled"],
        havingValue = "true",
    )
    fun approvalReviewerUiController(): ApprovalReviewerUiController = ApprovalReviewerUiController()
}
