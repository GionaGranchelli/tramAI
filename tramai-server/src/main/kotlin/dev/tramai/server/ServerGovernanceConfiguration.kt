package dev.tramai.server

import dev.tramai.controlplane.WorkloadRegistrationAuthority
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wiring for the governed server deployment (0.7.1d): the Spring binding layer is thin and
 * the decision logic lives in [serverGovernanceFrom], which has no Spring types at all.
 */
@Configuration(proxyBeanMethods = false)
internal class ServerGovernanceConfiguration {
    @Bean
    @ConditionalOnMissingBean
    internal fun serverGovernanceProperties(
        @Value("\${tramai.server.governed.workload-id:}") workloadId: String,
        @Value("\${tramai.server.governed.configuration-id:}") configurationId: String,
        @Value("\${tramai.server.governed.configuration-version:}") configurationVersion: String,
        @Value("\${tramai.server.governed.environment-id:}") environmentId: String,
        @Value("\${tramai.server.governed.deployment-id:}") deploymentId: String,
    ): ServerGovernanceProperties =
        ServerGovernanceProperties(
            workloadId = workloadId,
            configurationId = configurationId,
            configurationVersion = configurationVersion,
            environmentId = environmentId,
            deploymentId = deploymentId,
        )

    @Bean
    @ConditionalOnMissingBean
    internal fun serverGovernance(
        authority: ObjectProvider<WorkloadRegistrationAuthority>,
        properties: ServerGovernanceProperties,
    ): ServerGovernance = serverGovernanceFrom(properties, authority.ifAvailable)
}
