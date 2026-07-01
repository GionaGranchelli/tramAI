package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.DefaultApprovalGateway
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataFactory
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview [ApprovalGateway].
 *
 * Creates one of two gateway beans depending on availability:
 *
 * 1. **Transactional gateway** — [SovereignOpsTransactionalApprovalGateway] is created
 *    when a [SovereignOpsApprovalRequestMutationStore] and an
 *    [ApprovalGatewayRequestFactory] are available. This path commits approval,
 *    suspended invocation, continuation, and optional audit outbox records in a single
 *    database transaction.
 *
 *    If an [ApprovalGatewayAuditIntentFactory] bean is also available, it is wired into
 *    the transactional gateway so that approval-requested audit outbox intent is created
 *    atomically alongside the core records.
 *
 * 2. **Default gateway (explicit opt-in)** — [DefaultApprovalGateway] is created only when
 *    `tramai.sovereign.ops.approval-gateway.non-transactional-fallback-enabled=true`
 *    and the generic backing stores ([ApprovalStore], [ApprovalContinuationStore],
 *    [SuspendedInvocationStore]) and an [ApprovalGatewayRequestFactory] are available
 *    but no [SovereignOpsApprovalRequestMutationStore] exists. This path writes the
 *    three stores sequentially without transactional atomicity and is intended for
 *    tests, examples, or custom non-JDBC deployments that accept the lack of
 *    cross-store atomicity.
 *
 * The transactional gateway takes priority when the mutation store is available.
 *
 * ### Activation
 *
 * - With JDBC-backed persistence: the JDBC auto-config registers all required stores
 *   and the mutation store, producing the transactional gateway.
 * - With generic (in-memory/file) stores: the mutation store is absent, producing
 *   the default gateway.
 *
 * Missing any single dependency → no [ApprovalGateway] bean is created, startup unaffected.
 *
 * Does **not** create a default [ApprovalGatewayRequestFactory] or
 * [ApprovalGatewayAuditIntentFactory] — applications must provide these because request
 * construction and audit intent depend on workflow-specific metadata.
 *
 * @see SovereignOpsTransactionalApprovalGateway
 * @see DefaultApprovalGateway
 * @see ApprovalGatewayRequestFactory
 * @see ApprovalGatewayAuditIntentFactory
 */
@AutoConfiguration(
    after = [SovereignOpsAutoConfiguration::class],
    afterName = ["dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration"],
)
class ApprovalGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalGateway::class)
    @ConditionalOnBean(
        value = [
            SovereignOpsApprovalRequestMutationStore::class,
            ApprovalGatewayRequestFactory::class,
        ],
    )
    fun transactionalApprovalGateway(
        mutationStore: SovereignOpsApprovalRequestMutationStore,
        requestFactory: ApprovalGatewayRequestFactory,
        auditIntentFactory: ObjectProvider<ApprovalGatewayAuditIntentFactory>,
        inboxMetadataFactory: ObjectProvider<ApprovalInboxMetadataFactory>,
    ): ApprovalGateway =
        SovereignOpsTransactionalApprovalGateway(
            mutationStore = mutationStore,
            requestFactory = requestFactory,
            auditIntentFactory = auditIntentFactory.ifAvailable,
            inboxMetadataFactory = inboxMetadataFactory.ifAvailable,
        )

    @Bean
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.approval-gateway",
        name = ["non-transactional-fallback-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @ConditionalOnMissingBean(
        value = [
            ApprovalGateway::class,
            SovereignOpsApprovalRequestMutationStore::class,
        ],
    )
    @ConditionalOnBean(
        value = [
            ApprovalStore::class,
            ApprovalContinuationStore::class,
            SuspendedInvocationStore::class,
            ApprovalGatewayRequestFactory::class,
        ],
    )
    fun approvalGateway(
        approvalStore: ApprovalStore,
        approvalContinuationStore: ApprovalContinuationStore,
        suspendedInvocationStore: SuspendedInvocationStore,
        requestFactory: ApprovalGatewayRequestFactory,
    ): ApprovalGateway =
        DefaultApprovalGateway(
            approvalStore = approvalStore,
            continuationStore = approvalContinuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = requestFactory,
        )
}
