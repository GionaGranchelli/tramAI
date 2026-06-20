package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SovereignOpsOutboxMicrometerAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignOpsAutoConfiguration::class.java,
                SovereignOpsOutboxMicrometerAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
        )

    @Test
    fun `recording observer is used when MeterRegistry is absent`() {
        contextRunner
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(RecordingSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `observer is created when MeterRegistry exists`() {
        contextRunner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(MicrometerSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `observer is not created when custom observer already exists`() {
        contextRunner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomObserverConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(CustomSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }
}

@Configuration
open class MeterRegistryConfig {
    @Bean
    open fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

@Configuration
open class CustomObserverConfig {
    @Bean
    open fun customObserver(): SovereignOpsAuditOutboxWorkerObserver =
        CustomSovereignOpsAuditOutboxWorkerObserver()
}

private class CustomSovereignOpsAuditOutboxWorkerObserver : SovereignOpsAuditOutboxWorkerObserver {
    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) = Unit
    override fun onCycleFailed(action: String, errorCode: String) = Unit
}
