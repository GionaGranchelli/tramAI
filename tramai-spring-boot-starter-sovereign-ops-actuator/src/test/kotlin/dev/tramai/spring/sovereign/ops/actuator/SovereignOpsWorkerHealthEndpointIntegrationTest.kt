package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxWorkerStatusStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Integration tests for the sovereign ops worker health indicator.
 *
 * These tests verify two levels of registration:
 * - Level 1 (bean registration): the auto-configuration creates a bean named
 *   [tramaiSovereignOpsWorkerHealthIndicator] when [tramai.sovereign.ops.actuator.worker-health.enabled]
 *   is set to true.
 * - Level 2 (Actuator health-tree integration): Spring Boot's [HealthContributorAutoConfiguration]
 *   registers the health indicator in the [HealthContributorRegistry] under the expected
 *   component name [tramaiSovereignOpsWorker] — not the raw bean name.
 */
class SovereignOpsWorkerHealthEndpointIntegrationTest {

    // -----------------------------------------------------------------------
    // Level 1 — Bean-registration tests
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Level 2 — Actuator health-tree integration tests (HealthContributorRegistry)
    // -----------------------------------------------------------------------

    @Test
    fun `health component is registered in HealthContributorRegistry under expected name`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HealthEndpointAutoConfiguration::class.java,
                    HealthContributorAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                val registry = ctx.getBean(HealthContributorRegistry::class.java)

                val contributor = registry.getContributor("tramaiSovereignOpsWorker")
                assertThat(contributor)
                    .describedAs(
                        "Expected a health contributor named tramaiSovereignOpsWorker " +
                            "in the registry, not the raw bean name " +
                            "tramaiSovereignOpsWorkerHealthIndicator",
                    )
                    .isNotNull()

                // The raw bean name must NOT appear as a component name
                assertThat(registry.getContributor("tramaiSovereignOpsWorkerHealthIndicator"))
                    .describedAs(
                        "The raw bean name tramaiSovereignOpsWorkerHealthIndicator " +
                            "must not appear as a health component name — only " +
                            "tramaiSovereignOpsWorker is the expected component name",
                    )
                    .isNull()
            }
    }

    @Test
    fun `health component is a HealthIndicator and reports correct status`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HealthEndpointAutoConfiguration::class.java,
                    HealthContributorAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(RunningStatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                val registry = ctx.getBean(HealthContributorRegistry::class.java)
                val contributor =
                    registry.getContributor("tramaiSovereignOpsWorker")
                        ?: throw AssertionError(
                            "Expected contributor tramaiSovereignOpsWorker to be present",
                        )

                assertThat(contributor)
                    .isInstanceOf(HealthIndicator::class.java)

                val health = (contributor as HealthIndicator).health()
                assertThat(health.status).isEqualTo(Status.UP)
            }
    }

    @Test
    fun `no health component in registry when health is disabled`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HealthEndpointAutoConfiguration::class.java,
                    HealthContributorAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .run { ctx ->
                val registry = ctx.getBean(HealthContributorRegistry::class.java)

                assertThat(registry.getContributor("tramaiSovereignOpsWorker"))
                    .describedAs(
                        "No health contributor should be registered " +
                            "when worker-health is disabled",
                    )
                    .isNull()
            }
    }

    @Test
    fun `custom named bean maps to same health component in registry`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HealthEndpointAutoConfiguration::class.java,
                    HealthContributorAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(
                StatusStoreConfig::class.java,
                CustomHealthIndicatorConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                val registry = ctx.getBean(HealthContributorRegistry::class.java)
                val contributor =
                    registry.getContributor("tramaiSovereignOpsWorker")
                        ?: throw AssertionError(
                            "Expected contributor tramaiSovereignOpsWorker to be present " +
                                "with custom bean backoff",
                        )

                assertThat(contributor).isInstanceOf(HealthIndicator::class.java)

                val health = (contributor as HealthIndicator).health()
                assertThat(health.status).isEqualTo(Status.UP)
                assertThat(health.details["custom"]).isEqualTo(true)
            }
    }

    @Test
    fun `status endpoint and health component coexist in health tree`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HealthEndpointAutoConfiguration::class.java,
                    HealthContributorAutoConfiguration::class.java,
                    SovereignOpsActuatorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
                "tramai.sovereign.ops.actuator.worker-health.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerStatusEndpoint::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerHealthIndicator::class.java)

                val registry = ctx.getBean(HealthContributorRegistry::class.java)
                val contributor =
                    registry.getContributor("tramaiSovereignOpsWorker")
                        ?: throw AssertionError(
                            "Expected contributor tramaiSovereignOpsWorker to be present " +
                                "when both worker-status and worker-health are enabled",
                        )

                assertThat(contributor).isInstanceOf(HealthIndicator::class.java)

                val health = (contributor as HealthIndicator).health()
                assertThat(health.status).isEqualTo(Status.UNKNOWN)
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
