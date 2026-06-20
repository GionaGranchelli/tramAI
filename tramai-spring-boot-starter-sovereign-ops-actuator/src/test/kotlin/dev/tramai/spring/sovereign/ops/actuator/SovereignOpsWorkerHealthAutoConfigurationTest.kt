package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SovereignOpsWorkerHealthAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
        )

    @Test
    fun `health indicator is disabled by default`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerHealthIndicator::class.java)
                assertThat(ctx).doesNotHaveBean("tramaiSovereignOpsWorkerHealthIndicator")
            }
    }

    @Test
    fun `health indicator is created when enabled and status store exists`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerHealthIndicator::class.java)
                assertThat(ctx).hasBean("tramaiSovereignOpsWorkerHealthIndicator")
            }
    }

    @Test
    fun `health indicator is not created without status store`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerHealthIndicator::class.java)
                assertThat(ctx).doesNotHaveBean("tramaiSovereignOpsWorkerHealthIndicator")
            }
    }

    @Test
    fun `health indicator backs off when custom named health indicator exists`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java, CustomHealthIndicatorConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerHealthIndicator::class.java)
                val indicator = ctx.getBean("tramaiSovereignOpsWorkerHealthIndicator", HealthIndicator::class.java)
                assertThat(indicator.health().details["custom"]).isEqualTo(true)
            }
    }

    @Test
    fun `worker status endpoint property does not create health indicator`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerStatusEndpoint::class.java)
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerHealthIndicator::class.java)
                assertThat(ctx).doesNotHaveBean("tramaiSovereignOpsWorkerHealthIndicator")
            }
    }

    @Test
    fun `worker health property does not create worker status endpoint`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerHealthIndicator::class.java)
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerStatusEndpoint::class.java)
            }
    }
}

@Configuration
open class CustomHealthIndicatorConfig {
    @Bean("tramaiSovereignOpsWorkerHealthIndicator")
    open fun customHealthIndicator(): HealthIndicator =
        HealthIndicator {
            Health.up()
                .withDetail("custom", true)
                .build()
        }
}
