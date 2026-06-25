package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.DefaultApprovalGateway
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview [ApprovalGateway].
 *
 * Creates a [DefaultApprovalGateway] when all required backing stores
 * and an [ApprovalGatewayRequestFactory] are available.
 *
 * Does **not** create a default [ApprovalGatewayRequestFactory] — applications
 * must provide one because request construction depends on workflow-specific
 * metadata (replay envelopes, digests, resume tokens, correlation IDs).
 *
 * ## Activation
 *
 * The bean is created when:
 * - [ApprovalStore] is available
 * - [ApprovalContinuationStore] is available
 * - [SuspendedInvocationStore] is available
 * - [ApprovalGatewayRequestFactory] is available
 * - No user-provided [ApprovalGateway] bean exists
 *
 * Missing any single store dependency prevents the gateway from being created.
 * Because the store parameters use [ObjectProvider], a missing store produces
 * a clear startup error rather than silently skipping the bean.
 *
 * @see DefaultApprovalGateway
 * @see ApprovalGatewayRequestFactory
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
class ApprovalGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalGateway::class)
    @ConditionalOnBean(ApprovalGatewayRequestFactory::class)
    fun approvalGateway(
        approvalStoreProvider: ObjectProvider<ApprovalStore>,
        approvalContinuationStoreProvider: ObjectProvider<ApprovalContinuationStore>,
        suspendedInvocationStoreProvider: ObjectProvider<SuspendedInvocationStore>,
        requestFactory: ApprovalGatewayRequestFactory,
    ): ApprovalGateway {
        val approvalStore = approvalStoreProvider.ifAvailable
            ?: throw IllegalStateException(
                "tramai-sovereign-approval-gateway-missing-approval-store",
            )
        val continuationStore = approvalContinuationStoreProvider.ifAvailable
            ?: throw IllegalStateException(
                "tramai-sovereign-approval-gateway-missing-continuation-store",
            )
        val suspendedInvocationStore = suspendedInvocationStoreProvider.ifAvailable
            ?: throw IllegalStateException(
                "tramai-sovereign-approval-gateway-missing-suspended-invocation-store",
            )

        return DefaultApprovalGateway(
            approvalStore = approvalStore,
            continuationStore = continuationStore,
            suspendedInvocationStore = suspendedInvocationStore,
            requestFactory = requestFactory,
        )
    }
}
