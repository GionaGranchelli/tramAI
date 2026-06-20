package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserverContribution
import io.opentelemetry.api.OpenTelemetry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configures an [SovereignOpsAuditOutboxWorkerObserverContribution]
 * wrapping [OpenTelemetrySovereignOpsAuditOutboxWorkerObserver] when:
 *
 * 1. An [OpenTelemetry] bean is present in the context, AND
 * 2. No OT contribution has been registered yet.
 *
 * Runs before [SovereignOpsAutoConfiguration] so the OT observer
 * contribution is available when the base auto-config builds the final
 * composite observer chain. The recording observer wraps the composite,
 * so status snapshots update and OT metrics fire simultaneously.
 */
@AutoConfiguration
@AutoConfigureBefore(SovereignOpsAutoConfiguration::class)
open class SovereignOpsOutboxObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry::class)
    @ConditionalOnMissingBean(name = ["openTelemetrySovereignOpsAuditOutboxWorkerObserverContribution"])
    open fun openTelemetrySovereignOpsAuditOutboxWorkerObserverContribution(
        openTelemetry: OpenTelemetry,
    ): SovereignOpsAuditOutboxWorkerObserverContribution =
        SovereignOpsAuditOutboxWorkerObserverContribution(
            OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(openTelemetry),
        )
}
