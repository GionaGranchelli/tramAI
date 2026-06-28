package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerObserverContribution
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerStatusStore
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the sovereign ops worker status Actuator endpoint
 * and optional health indicator.
 *
 * Runs after [SovereignOpsAutoConfiguration] so the status store bean is available.
 *
 * Endpoint conditions (disabled by default):
 * - Actuator is on the classpath (`@ConditionalOnClass(Endpoint::class, HealthIndicator::class)`)
 * - A [SovereignOpsAuditOutboxWorkerStatusStore] bean exists
 * - `tramai.sovereign.ops.actuator.worker-status.enabled=true`
 * - No custom [SovereignOpsWorkerStatusEndpoint] bean has been registered
 *
 * Health indicator conditions (disabled by default, independent property):
 * - Same classpath and store conditions
 * - `tramai.sovereign.ops.actuator.worker-health.enabled=true`
 * - No custom bean named `tramaiSovereignOpsWorkerHealthIndicator` exists
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
@ConditionalOnClass(Endpoint::class, HealthIndicator::class)
@EnableConfigurationProperties(
    SovereignOpsWorkerStatusEndpointProperties::class,
    ApprovedContinuationResumeWorkerMetricsProperties::class,
)
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

    @Bean("tramaiSovereignOpsWorkerHealthIndicator")
    @ConditionalOnMissingBean(name = ["tramaiSovereignOpsWorkerHealthIndicator"])
    @ConditionalOnBean(SovereignOpsAuditOutboxWorkerStatusStore::class)
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.actuator.worker-health",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun sovereignOpsWorkerHealthIndicator(
        statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
    ): SovereignOpsWorkerHealthIndicator =
        SovereignOpsWorkerHealthIndicator(statusStore)

    @Bean("tramaiApprovedContinuationResumeWorkerHealthIndicator")
    @ConditionalOnMissingBean(name = ["tramaiApprovedContinuationResumeWorkerHealthIndicator"])
    @ConditionalOnBean(ApprovedContinuationResumeWorkerStatusStore::class)
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.actuator.approved-resume-worker-health",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun approvedContinuationResumeWorkerHealthIndicator(
        statusStore: ApprovedContinuationResumeWorkerStatusStore,
        queueStatusStore: ObjectProvider<ApprovedContinuationResumeQueueStatusStore>,
    ): ApprovedContinuationResumeWorkerHealthIndicator =
        ApprovedContinuationResumeWorkerHealthIndicator(
            statusStore = statusStore,
            queueStatusStore = queueStatusStore.ifAvailable,
        )

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnProperty(
        prefix = "tramai.sovereign.ops.actuator.approved-resume-worker-metrics",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun approvedContinuationResumeWorkerMetricsObserverContribution(
        meterRegistry: MeterRegistry,
        metricsProperties: ApprovedContinuationResumeWorkerMetricsProperties,
    ): ApprovedContinuationResumeWorkerObserverContribution =
        ApprovedContinuationResumeWorkerObserverContribution(
            ApprovedContinuationResumeWorkerMetricsObserver(
                meterRegistry = meterRegistry,
                properties = metricsProperties,
            ),
        )

    @Bean
    @ConditionalOnBean(
        value = [MeterRegistry::class, ApprovedContinuationResumeQueueStatusStore::class],
    )
    @ConditionalOnExpression(
        "'\${tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled:false}' == 'true' " +
        "&& '\${tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-enabled:true}' == 'true'"
    )
    fun approvedResumeQueueMetricsSnapshotProvider(
        queueStatusStore: ApprovedContinuationResumeQueueStatusStore,
        meterRegistry: MeterRegistry,
        metricsProperties: ApprovedContinuationResumeWorkerMetricsProperties,
    ): ApprovedResumeQueueMetricsSnapshotProvider {
        val provider = ApprovedResumeQueueMetricsSnapshotProvider(
            queueStatusStore = queueStatusStore,
            refreshInterval = metricsProperties.queueSnapshotRefreshInterval,
        )
        provider.registerGauges(meterRegistry)
        return provider
    }
}
