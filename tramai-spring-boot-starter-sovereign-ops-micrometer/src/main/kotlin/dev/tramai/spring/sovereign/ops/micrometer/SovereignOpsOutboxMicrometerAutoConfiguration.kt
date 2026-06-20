package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the Micrometer-backed sovereign ops audit outbox worker observer.
 *
 * Runs before [SovereignOpsAutoConfiguration] so this observer is available
 * before the fallback recording observer is registered. Follows the same
 * pattern as `SovereignOpsOutboxObservabilityAutoConfiguration`.
 *
 * The observer is only created when:
 * - Micrometer is on the classpath (`MeterRegistry`)
 * - A [MeterRegistry] bean exists
 * - No custom [SovereignOpsAuditOutboxWorkerObserver] has been registered
 *
 * ## Observer precedence
 *
 * When this module is on the classpath and a [MeterRegistry] bean exists,
 * the Micrometer observer replaces the default recording observer.
 * This means status snapshot recording does not update when Micrometer
 * metrics are active.
 *
 * To compose both metrics and status recording, manually wire a
 * [RecordingSovereignOpsAuditOutboxWorkerObserver] with the Micrometer
 * observer as its delegate.
 */
@AutoConfiguration
@AutoConfigureBefore(SovereignOpsAutoConfiguration::class)
@ConditionalOnClass(MeterRegistry::class)
open class SovereignOpsOutboxMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SovereignOpsAuditOutboxWorkerObserver::class)
    @ConditionalOnBean(MeterRegistry::class)
    open fun micrometerSovereignOpsAuditOutboxWorkerObserver(
        meterRegistry: MeterRegistry,
    ): SovereignOpsAuditOutboxWorkerObserver =
        MicrometerSovereignOpsAuditOutboxWorkerObserver(meterRegistry)
}
