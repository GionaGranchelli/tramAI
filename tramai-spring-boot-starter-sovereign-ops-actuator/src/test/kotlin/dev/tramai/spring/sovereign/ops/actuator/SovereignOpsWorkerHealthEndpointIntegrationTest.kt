package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxWorkerStatusStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

class SovereignOpsWorkerHealthEndpointIntegrationTest {

    @Test
    fun `health indicator bean is registered under the expected named bean`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                val beanNames = ctx.getBeanNamesForType(HealthIndicator::class.java)

                assertThat(beanNames)
                    .describedAs(
                        "Expected a health indicator bean named " +
                            "tramaiSovereignOpsWorkerHealthIndicator",
                    )
                    .contains("tramaiSovereignOpsWorkerHealthIndicator")

                // Verify the bean is the correct type
                val indicator = ctx.getBean(
                    "tramaiSovereignOpsWorkerHealthIndicator",
                    HealthIndicator::class.java,
                )
                assertThat(indicator).isInstanceOf(SovereignOpsWorkerHealthIndicator::class.java)
            }
    }

    @Test
    fun `health indicator bean has expected status through the bean API`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withUserConfiguration(RunningStatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                val indicator = ctx.getBean(
                    "tramaiSovereignOpsWorkerHealthIndicator",
                    HealthIndicator::class.java,
                )
                assertThat(indicator.health().status).isEqualTo(Status.UP)
            }
    }

    @Test
    fun `health indicator is absent when health is disabled`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .run { ctx ->
                val beanNames = ctx.getBeanNamesForType(HealthIndicator::class.java)
                assertThat(beanNames)
                    .describedAs(
                        "No HealthIndicator beans should exist " +
                            "when worker-health is disabled",
                    )
                    .doesNotContain("tramaiSovereignOpsWorkerHealthIndicator")
            }
    }

    @Test
    fun `custom named health indicator registered under same bean name`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withUserConfiguration(
                StatusStoreConfig::class.java,
                CustomHealthIndicatorConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerHealthIndicator::class.java)

                val indicator = ctx.getBean(
                    "tramaiSovereignOpsWorkerHealthIndicator",
                    HealthIndicator::class.java,
                )
                assertThat(indicator.health().details["custom"]).isEqualTo(true)
            }
    }

    @Test
    fun `worker status endpoint and health indicator bean can coexist`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerStatusEndpoint::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerHealthIndicator::class.java)

                val indicator = ctx.getBean(
                    "tramaiSovereignOpsWorkerHealthIndicator",
                    HealthIndicator::class.java,
                )
                assertThat(indicator.health().status).isEqualTo(Status.UNKNOWN)
            }
    }
}

@Configuration
open class RunningStatusStoreConfig {
    @Bean
    open fun runningStatusStore(): SovereignOpsAuditOutboxWorkerStatusStore {
        val store = InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(
                enabled = true,
                recoverPrepared = true,
                dispatchPending = true,
                batchSize = 10,
                initialDelay = Duration.ofSeconds(5),
                interval = Duration.ofSeconds(30),
            ),
        )
        store.markLifecycleStarted()
        return store
    }
}
