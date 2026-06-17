package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for TramAI sovereign operations services.
 *
 * Activates when `tramai.sovereign.ops.enabled=true` (default).
 * Adds internal service beans for safe operational inspection of:
 * - Approvals
 * - Suspended invocations
 * - Audit streams
 * - Runtime/store status
 *
 * This module exposes **service beans only** — no HTTP endpoints.
 * Applications must put these operations behind their own authentication
 * and authorization layer.
 *
 * ## Configuration
 *
 * ```yaml
 * tramai:
 *   sovereign:
 *     ops:
 *       enabled: true
 *       mutations-enabled: false
 *       max-page-size: 100
 * ```
 *
 * Read-capable, mutation-disabled by default.
 */
@AutoConfiguration(after = [dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration::class])
@EnableConfigurationProperties(SovereignOpsProperties::class)
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SovereignOpsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun sovereignApprovalOperations(
        approvalStore: ObjectProvider<ApprovalStore>,
        properties: SovereignOpsProperties,
    ): SovereignApprovalOperations =
        DefaultSovereignApprovalOperations(
            store = approvalStore.ifAvailable
                ?: throw IllegalStateException("tramai-sovereign-ops-store-unavailable"),
            properties = properties,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignSuspendedInvocationOperations(
        suspendedInvocationStore: ObjectProvider<SuspendedInvocationStore>,
        properties: SovereignOpsProperties,
    ): SovereignSuspendedInvocationOperations =
        DefaultSovereignSuspendedInvocationOperations(
            store = suspendedInvocationStore.ifAvailable,
            properties = properties,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignAuditOperations(
        auditStore: ObjectProvider<AuditStore>,
    ): SovereignAuditOperations =
        DefaultSovereignAuditOperations(
            store = auditStore.ifAvailable,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignRuntimeOperations(
        auditStore: ObjectProvider<AuditStore>,
        approvalStore: ObjectProvider<ApprovalStore>,
        approvalContinuationStore: ObjectProvider<ApprovalContinuationStore>,
        suspendedInvocationStore: ObjectProvider<SuspendedInvocationStore>,
    ): SovereignRuntimeOperations =
        DefaultSovereignRuntimeOperations(
            auditStore = auditStore.ifAvailable,
            approvalStore = approvalStore.ifAvailable,
            approvalContinuationStore = approvalContinuationStore.ifAvailable,
            suspendedInvocationStore = suspendedInvocationStore.ifAvailable,
        )
}
