package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the sovereign ops worker status Actuator endpoint.
 *
 * Runs after [SovereignOpsAutoConfiguration] so the status store bean is available.
 * The endpoint is only created when:
 * - Actuator is on the classpath (`@ConditionalOnClass(Endpoint::class)`)
 * - A [SovereignOpsAuditOutboxWorkerStatusStore] bean exists
 * - `tramai.sovereign.ops.actuator.worker-status.enabled=true`
 * - No custom [SovereignOpsWorkerStatusEndpoint] bean has been registered
 *
 * The endpoint is disabled by default.
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
@ConditionalOnClass(Endpoint::class)
@EnableConfigurationProperties(SovereignOpsWorkerStatusEndpointProperties::class)
class SovereignOpsActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SovereignOpsAuditOutboxWorkerStatusStore::class)
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.actuator.worker-status",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun sovereignOpsWorkerStatusEndpoint(
        statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
    ): SovereignOpsWorkerStatusEndpoint =
        SovereignOpsWorkerStatusEndpoint(statusStore)
}
