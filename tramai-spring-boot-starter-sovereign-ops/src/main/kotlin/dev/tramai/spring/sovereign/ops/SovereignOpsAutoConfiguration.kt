package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditStore
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for TramAI sovereign operations services.
 *
 * Activates when `tramai.sovereign.ops.enabled=true` (default).
 * Adds internal service beans for safe operational inspection of:
 * - Approvals (with outbox-backed auditable mutations)
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
 * Read-capable, mutation-disabled by default. When mutations are enabled,
 * approval denials use a transactional outbox pattern: the approval
 * transition and audit outbox record are created atomically. Audit
 * emission can be retried from the outbox if the initial dispatch fails.
 *
 * An [AuditEngine] bean is required for mutation auditing. Without one,
 * state-changing operations fail closed with
 * `tramai-sovereign-ops-audit-unavailable`.
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
    fun sovereignOpsAuditDigestService(): SovereignOpsAuditDigestService =
        DefaultSovereignOpsAuditDigestService

    @Bean
    @ConditionalOnMissingBean
    fun sovereignOpsAuditOutboxStore(): SovereignOpsAuditOutboxStore =
        InMemorySovereignOpsAuditOutboxStore()

    @Bean
    @ConditionalOnMissingBean
    fun sovereignOpsAuditOutboxDispatcher(
        outboxStore: SovereignOpsAuditOutboxStore,
        auditEngine: ObjectProvider<AuditEngine>,
    ): SovereignOpsAuditOutboxDispatcher? {
        val emitter = auditEngine.ifAvailable
            ?.let { AuditEngineSovereignOpsAuditEmitter(it) }
            ?: return null
        return SovereignOpsAuditOutboxDispatcher(
            outboxStore = outboxStore,
            auditEmitter = emitter,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ApprovalStore::class)
    fun sovereignOpsApprovalMutationStore(
        approvalStore: ApprovalStore,
        outboxStore: SovereignOpsAuditOutboxStore,
    ): SovereignOpsApprovalMutationStore =
        InMemorySovereignOpsApprovalMutationStore(
            approvalStore = approvalStore,
            outboxStore = outboxStore,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ApprovalStore::class)
    fun sovereignApprovalOperations(
        approvalStore: ApprovalStore,
        mutationStore: SovereignOpsApprovalMutationStore,
        properties: SovereignOpsProperties,
        outboxDispatcher: ObjectProvider<SovereignOpsAuditOutboxDispatcher>,
        digestService: SovereignOpsAuditDigestService,
    ): SovereignApprovalOperations =
        DefaultSovereignApprovalOperations(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            properties = properties,
            outboxDispatcher = outboxDispatcher.ifAvailable,
            digestService = digestService,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignSuspendedInvocationOperations(
        suspendedInvocationStore: ObjectProvider<SuspendedInvocationStore>,
    ): SovereignSuspendedInvocationOperations =
        DefaultSovereignSuspendedInvocationOperations(
            store = suspendedInvocationStore.ifAvailable,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignAuditOperations(
        auditStore: ObjectProvider<AuditStore>,
        properties: SovereignOpsProperties,
    ): SovereignAuditOperations =
        DefaultSovereignAuditOperations(
            store = auditStore.ifAvailable,
            properties = properties,
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
