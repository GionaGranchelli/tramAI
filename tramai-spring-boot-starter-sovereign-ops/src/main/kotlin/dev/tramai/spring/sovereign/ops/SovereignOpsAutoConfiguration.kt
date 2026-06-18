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
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRecoveryResolver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxBackgroundWorker
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerLifecycle
import dev.tramai.spring.sovereign.ops.outbox.UnknownSovereignOpsApprovalRecoveryResolver
import dev.tramai.spring.sovereign.ops.outbox.validateSovereignOpsAuditOutboxWorkerProperties
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
    fun sovereignOpsApprovalRecoveryResolver(): SovereignOpsApprovalRecoveryResolver =
        UnknownSovereignOpsApprovalRecoveryResolver

    @Bean
    @ConditionalOnMissingBean
    fun sovereignOpsAuditOutboxOperations(
        outboxStore: SovereignOpsAuditOutboxStore,
        outboxDispatcher: ObjectProvider<SovereignOpsAuditOutboxDispatcher>,
        recoveryResolver: SovereignOpsApprovalRecoveryResolver,
        properties: SovereignOpsProperties,
    ): SovereignOpsAuditOutboxOperations =
        DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = outboxStore,
            outboxDispatcher = outboxDispatcher.ifAvailable,
            recoveryResolver = recoveryResolver,
            properties = properties,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.outbox.worker",
        name = ["enabled"],
        havingValue = "true",
    )
    fun sovereignOpsAuditOutboxBackgroundWorker(
        operations: SovereignOpsAuditOutboxOperations,
        properties: SovereignOpsProperties,
        outboxDispatcher: ObjectProvider<SovereignOpsAuditOutboxDispatcher>,
    ): SovereignOpsAuditOutboxBackgroundWorker {
        val rawProps = properties.outbox.worker
        val dispatcherAvailable = outboxDispatcher.ifAvailable != null

        val effectiveWorkerProps = if (rawProps.dispatchPending && !dispatcherAvailable) {
            if (rawProps.failOnMissingDispatcher) {
                throw IllegalStateException("tramai-sovereign-ops-outbox-worker-missing-dispatcher")
            }
            rawProps.copy(dispatchPending = false)
        } else {
            rawProps
        }

        validateSovereignOpsAuditOutboxWorkerProperties(effectiveWorkerProps)

        return SovereignOpsAuditOutboxBackgroundWorker(
            operations = operations,
            properties = effectiveWorkerProps,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.outbox.worker",
        name = ["enabled"],
        havingValue = "true",
    )
    fun sovereignOpsAuditOutboxWorkerLifecycle(
        worker: SovereignOpsAuditOutboxBackgroundWorker,
        properties: SovereignOpsProperties,
        outboxDispatcher: ObjectProvider<SovereignOpsAuditOutboxDispatcher>,
    ): SovereignOpsAuditOutboxWorkerLifecycle {
        val rawProps = properties.outbox.worker
        val dispatcherAvailable = outboxDispatcher.ifAvailable != null

        val effectiveWorkerProps = if (rawProps.dispatchPending && !dispatcherAvailable) {
            if (rawProps.failOnMissingDispatcher) {
                throw IllegalStateException("tramai-sovereign-ops-outbox-worker-missing-dispatcher")
            }
            rawProps.copy(dispatchPending = false)
        } else {
            rawProps
        }

        return SovereignOpsAuditOutboxWorkerLifecycle(
            worker = worker,
            properties = effectiveWorkerProps,
        )
    }

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
