package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import io.opentelemetry.api.OpenTelemetry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configures [OpenTelemetrySovereignOpsAuditOutboxWorkerObserver]
 * when:
 *
 * 1. An [OpenTelemetry] bean is present in the context, AND
 * 2. No custom [SovereignOpsAuditOutboxWorkerObserver] has been registered.
 *
 * Runs before [SovereignOpsAutoConfiguration] so the OT observer is created
 * before the Noop fallback. Without an [OpenTelemetry] bean the standard
 * [SovereignOpsAuditOutboxWorkerObserver.Noop] (registered by
 * [SovereignOpsAutoConfiguration]) remains active.
 */
@AutoConfiguration
@AutoConfigureBefore(SovereignOpsAutoConfiguration::class)
open class SovereignOpsOutboxObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SovereignOpsAuditOutboxWorkerObserver::class)
    @ConditionalOnBean(OpenTelemetry::class)
    open fun openTelemetrySovereignOpsAuditOutboxWorkerObserver(
        openTelemetry: OpenTelemetry,
    ): SovereignOpsAuditOutboxWorkerObserver =
        OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry)
}
