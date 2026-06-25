package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.DefaultApprovalGateway
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
 * 2. **Default gateway** — [DefaultApprovalGateway] is created as fallback when the
 *    generic backing stores ([ApprovalStore], [ApprovalContinuationStore],
 *    [SuspendedInvocationStore]) and an [ApprovalGatewayRequestFactory] are available
 *    but no [SovereignOpsApprovalRequestMutationStore] exists. This path writes the
 *    three stores sequentially without transactional atomicity.
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
 * Does **not** create a default [ApprovalGatewayRequestFactory] — applications must
 * provide one because request construction depends on workflow-specific metadata
 * (replay envelopes, digests, resume tokens, correlation IDs).
 *
 * @see SovereignOpsTransactionalApprovalGateway
 * @see DefaultApprovalGateway
 * @see ApprovalGatewayRequestFactory
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
    ): ApprovalGateway =
        SovereignOpsTransactionalApprovalGateway(
            mutationStore = mutationStore,
            requestFactory = requestFactory,
        )

    @Bean
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
