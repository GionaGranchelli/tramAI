package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditStore
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxOperations
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
 * Read-capable, mutation-disabled by default — inspection is safer than
 * state mutation. Set `mutations-enabled: true` to allow administrative
 * denial of approvals.
 *
 * ## Durability gate
 * Mutations require a durable outbox store. The default auto-configured
 * [InMemorySovereignOpsAuditOutboxStore] is non-durable, so denyApproval
 * fails closed with `tramai-sovereign-ops-audit-outbox-not-durable`.
 * Applications must provide a durable [SovereignOpsAuditOutboxStore]
 * implementation before mutations can proceed.
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
    @ConditionalOnBean(AuditEngine::class)
    fun sovereignOpsAuditOutboxDispatcher(
        outboxStore: SovereignOpsAuditOutboxStore,
        auditEngine: AuditEngine,
    ): SovereignOpsAuditOutboxDispatcher =
        SovereignOpsAuditOutboxDispatcher(
            outboxStore = outboxStore,
            auditEmitter = AuditEngineSovereignOpsAuditEmitter(auditEngine),
        )

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
        outboxStore: SovereignOpsAuditOutboxStore,
        digestService: SovereignOpsAuditDigestService,
    ): SovereignApprovalOperations =
        DefaultSovereignApprovalOperations(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            properties = properties,
            outboxDispatcher = outboxDispatcher.ifAvailable,
            outboxStore = outboxStore,
            digestService = digestService,
        )

    @Bean
    @ConditionalOnMissingBean
    fun sovereignOpsAuditOutboxOperations(
        outboxStore: SovereignOpsAuditOutboxStore,
        outboxDispatcher: ObjectProvider<SovereignOpsAuditOutboxDispatcher>,
        properties: SovereignOpsProperties,
    ): SovereignOpsAuditOutboxOperations =
        DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = outboxStore,
            outboxDispatcher = outboxDispatcher.ifAvailable,
            properties = properties,
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
