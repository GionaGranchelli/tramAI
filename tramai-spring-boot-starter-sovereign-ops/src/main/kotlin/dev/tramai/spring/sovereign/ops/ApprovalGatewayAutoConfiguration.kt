package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.DefaultApprovalGateway
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview [ApprovalGateway].
 *
 * Creates a [DefaultApprovalGateway] when **all** required backing stores
 * and an [ApprovalGatewayRequestFactory] are available. Missing any single
 * dependency simply prevents the bean from being created — startup does not fail.
 *
 * Does **not** create a default [ApprovalGatewayRequestFactory] — applications
 * must provide one because request construction depends on workflow-specific
 * metadata (replay envelopes, digests, resume tokens, correlation IDs).
 *
 * ## Activation
 *
 * The bean is created when:
 * - [ApprovalStore], [ApprovalContinuationStore], [SuspendedInvocationStore] are available
 * - [ApprovalGatewayRequestFactory] is available
 * - No user-provided [ApprovalGateway] bean exists
 *
 * Missing any single dependency → no [ApprovalGateway] bean is created, startup unaffected.
 *
 * @see DefaultApprovalGateway
 * @see ApprovalGatewayRequestFactory
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
class ApprovalGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalGateway::class)
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
