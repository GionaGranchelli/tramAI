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
        // takeIf matters: a raw `false` is a non-null value, which would sail through a null check and leave
        // the gate a no-op. The disabled case must reach the helper as absence.
        requireConfiguration(
            operatorSecurity.enabled.takeIf { it },
            "tramai-rest-control-plane-requires-operator-security",
        )
        val decisionControlPlane =
            requireConfiguration(
                decisionControlPlaneProvider.ifAvailable,
                "tramai-rest-control-plane-requires-approval-policy",
            )
        val approvalStore =
            requireConfiguration(
                approvalStoreProvider.ifAvailable,
                "tramai-sovereign-approval-rest-missing-approval-store",
            )
        val approvalContinuationStore =
            requireConfiguration(
                approvalContinuationStoreProvider.ifAvailable,
                "tramai-sovereign-approval-rest-missing-continuation-store",
            )
        return ApprovalControlPlaneController(
            decisionControlPlane = decisionControlPlane,
            resumeControlPlane = resumeControlPlane,
            approvalStore = approvalStore,
            approvalContinuationStore = approvalContinuationStore,
        )
    }

    /**
     * Fails startup with a named reason instead of letting a missing collaborator surface as a Spring
     * no-such-bean message that names neither the configuration key nor why it matters.
     */
    private fun <T : Any> requireConfiguration(
        value: T?,
        reason: String,
    ): T =
        value ?: throw IllegalStateException(
            "$reason: see tramai.sovereign.ops.rest-control-plane-enabled and " +
                "tramai.sovereign.ops.security.enabled; an approval endpoint cannot be published without both the " +
                "operator security boundary and an active approval policy",
        )

    @Bean
    @ConditionalOnMissingBean(ApprovalInboxController::class)
    @ConditionalOnBean(ApprovalInboxQueryService::class)
    fun approvalInboxController(queryService: ApprovalInboxQueryService): ApprovalInboxController {
        val controller = ApprovalInboxController(queryService)
        return controller
    }

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
