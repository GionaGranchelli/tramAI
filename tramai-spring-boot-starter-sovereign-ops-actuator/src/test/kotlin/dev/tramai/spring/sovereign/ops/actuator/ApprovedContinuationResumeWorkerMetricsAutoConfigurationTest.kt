package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerObserver
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerObserverContribution
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ApprovedContinuationResumeWorkerMetricsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignOpsAutoConfiguration::class.java,
                SovereignOpsActuatorAutoConfiguration::class.java,
            ),
        )
        .withBean("simpleMeterRegistry", SimpleMeterRegistry::class.java)

    @Test
    fun `metrics observer contribution not created by default`() {
        contextRunner
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorkerObserverContribution::class.java)
            }
    }

    @Test
    fun `metrics observer contribution created when enabled`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovedContinuationResumeWorkerObserverContribution::class.java)
                val contribution = ctx.getBean(ApprovedContinuationResumeWorkerObserverContribution::class.java)
                assertThat(contribution.observer).isInstanceOf(ApprovedContinuationResumeWorkerMetricsObserver::class.java)
            }
    }

    @Test
    fun `custom contribution coexistence is possible`() {
        contextRunner
            .withUserConfiguration(CustomObserverContributionConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                val contributions = ctx.getBeanProvider(ApprovedContinuationResumeWorkerObserverContribution::class.java)
                    .orderedStream()
                    .toList()
                // custom contribution + metrics contribution
                assertThat(contributions).hasSize(2)
            }
    }

    @Test
    fun `queue snapshot provider not created without queue status store`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedResumeQueueMetricsSnapshotProvider::class.java)
            }
    }

    @Test
    fun `queue snapshot provider created when queue status store and metrics enabled`() {
        contextRunner
            .withUserConfiguration(QueueStatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovedResumeQueueMetricsSnapshotProvider::class.java)
            }
    }

    @Test
    fun `queue snapshot provider not created when metrics disabled even with store`() {
        contextRunner
            .withUserConfiguration(QueueStatusStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedResumeQueueMetricsSnapshotProvider::class.java)
            }
    }

    @Test
    fun `queue snapshot provider not created when no MeterRegistry`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignOpsAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(QueueStatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedResumeQueueMetricsSnapshotProvider::class.java)
            }
    }

    @Test
    fun `metrics observer registers queue gauges when store is present`() {
        contextRunner
            .withUserConfiguration(QueueStatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled=true",
            )
            .run { ctx ->
                val registry = ctx.getBean(MeterRegistry::class.java)
                val eligibleGauge = registry.find("tramai.sovereign.approved_resume_queue.eligible_now").gauge()
                assertThat(eligibleGauge).isNotNull
            }
    }
}

@Configuration
open class CustomObserverContributionConfig {
    @Bean
    open fun customObserverContribution(): ApprovedContinuationResumeWorkerObserverContribution =
        ApprovedContinuationResumeWorkerObserverContribution(
            ApprovedContinuationResumeWorkerObserver.Noop,
        )
}

@Configuration
open class QueueStatusStoreConfig {
    @Bean
    open fun queueStatusStore(): ApprovedContinuationResumeQueueStatusStore =
        object : ApprovedContinuationResumeQueueStatusStore {
            override suspend fun snapshot(now: java.time.Instant) =
                ApprovedContinuationResumeQueueSnapshot(
                    eligibleNow = 0, delayedRetry = 0, activeLeases = 0,
                    expiredLeases = 0, terminalFailures = 0,
                    oldestEligibleAgeSeconds = null, oldestRetryDueInSeconds = null,
                    lastErrorCodeCounts = emptyMap(),
                )
        }
}
