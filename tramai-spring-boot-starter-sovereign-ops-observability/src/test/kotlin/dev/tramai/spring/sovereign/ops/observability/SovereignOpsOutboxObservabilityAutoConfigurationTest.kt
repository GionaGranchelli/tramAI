package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

class SovereignOpsOutboxObservabilityAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignOpsAutoConfiguration::class.java,
                SovereignOpsOutboxObservabilityAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
        )

    @Test
    fun `RecordingObserver is used when no OpenTelemetry bean is present`() {
        contextRunner
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(RecordingSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `OpenTelemetry observer is created when OpenTelemetry bean is present`() {
        contextRunner
            .withUserConfiguration(OpenTelemetryConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(OpenTelemetrySovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `custom observer bean is not overridden by auto-configuration`() {
        contextRunner
            .withUserConfiguration(CustomObserverConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(CustomTestObserver::class.java)
            }
    }

    @Test
    fun `custom observer takes precedence even when OpenTelemetry is present`() {
        contextRunner
            .withUserConfiguration(
                OpenTelemetryConfig::class.java,
                CustomObserverConfig::class.java,
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(CustomTestObserver::class.java)
            }
    }

    // ── Configurations ──────────────────────────────────────────────

    @Configuration
    open class OpenTelemetryConfig {
        @Bean
        open fun openTelemetry(): OpenTelemetry =
            io.opentelemetry.sdk.OpenTelemetrySdk.builder().build()
    }

    @Configuration
    open class CustomObserverConfig {
        @Bean
        @Primary
        open fun customObserver(): SovereignOpsAuditOutboxWorkerObserver =
            CustomTestObserver()
    }

    open class CustomTestObserver : SovereignOpsAuditOutboxWorkerObserver {
        override fun onCycleCompleted(summary: dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary) = Unit
        override fun onCycleFailed(action: String, errorCode: String) = Unit
    }
}
