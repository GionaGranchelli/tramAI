package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserverContribution
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant

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
                assertThat(ctx).doesNotHaveBean(SovereignOpsAuditOutboxWorkerObserverContribution::class.java)
            }
    }

    @Test
    fun `micrometer contribution is created when MeterRegistry exists`() {
        contextRunner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(RecordingSovereignOpsAuditOutboxWorkerObserver::class.java)

                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserverContribution::class.java)
                val contribution = ctx.getBean(SovereignOpsAuditOutboxWorkerObserverContribution::class.java)
                assertThat(contribution.observer).isInstanceOf(MicrometerSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `custom observer is not overridden by auto-configuration`() {
        contextRunner
            .withUserConfiguration(MeterRegistryConfig::class.java, CustomObserverConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(CustomSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `status recording and micrometer metrics work together`() {
        contextRunner
            .withUserConfiguration(MeterRegistryConfig::class.java)
            .run { ctx ->
                val statusStore = ctx.getBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val meterRegistry = ctx.getBean(MeterRegistry::class.java)

                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val now = Instant.now()
                val summary = SovereignOpsAuditOutboxWorkerRunSummary(
                    recovered = null,
                    dispatched = null,
                    startedAt = now,
                    completedAt = now.plus(Duration.ofMillis(500)),
                )

                observer.onCycleCompleted(summary)

                val snapshot = statusStore.snapshot()
                assertThat(snapshot.totalCyclesCompleted).isEqualTo(1)

                val cycleCounter = meterRegistry
                    .get("tramai.sovereign.ops.outbox.worker.cycles")
                    .counter()
                assertThat(cycleCounter.count()).isEqualTo(1.0)
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
