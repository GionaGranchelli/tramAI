package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserverContribution
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the Micrometer-backed sovereign ops audit outbox worker observer contribution.
 *
 * Runs before [SovereignOpsAutoConfiguration] so this contribution is
 * available when the base starter builds the final observer chain.
 *
 * The contribution is only created when:
 * - Micrometer is on the classpath (`MeterRegistry`)
 * - A [MeterRegistry] bean exists
 * - No Micrometer observer contribution has already been registered
 *
 * The base sovereign ops starter composes contributions behind the
 * recording observer, so status snapshots update while Micrometer metrics
 * are emitted.
 */
@AutoConfiguration
@AutoConfigureBefore(SovereignOpsAutoConfiguration::class)
@ConditionalOnClass(MeterRegistry::class)
open class SovereignOpsOutboxMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(name = ["micrometerSovereignOpsAuditOutboxWorkerObserverContribution"])
    open fun micrometerSovereignOpsAuditOutboxWorkerObserverContribution(
        meterRegistry: MeterRegistry,
    ): SovereignOpsAuditOutboxWorkerObserverContribution =
        SovereignOpsAuditOutboxWorkerObserverContribution(
            MicrometerSovereignOpsAuditOutboxWorkerObserver(meterRegistry),
        )
}
