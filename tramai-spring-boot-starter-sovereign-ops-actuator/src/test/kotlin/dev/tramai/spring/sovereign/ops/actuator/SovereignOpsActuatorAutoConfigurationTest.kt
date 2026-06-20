package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.SovereignOpsOutboxWorkerProperties
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxWorkerStatusStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusSnapshot
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

class SovereignOpsActuatorAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignOpsAutoConfiguration::class.java,
                SovereignOpsActuatorAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
        )

    @Test
    fun `endpoint is not created by default`() {
        contextRunner
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerStatusEndpoint::class.java)
            }
    }

    @Test
    fun `endpoint is created when worker status actuator endpoint is enabled`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerStatusEndpoint::class.java)
                val endpoint = ctx.getBean(SovereignOpsWorkerStatusEndpoint::class.java)
                val snapshot = endpoint.status()
                assertThat(snapshot).isNotNull
            }
    }

    @Test
    fun `read operation returns sanitized worker status snapshot`() {
        contextRunner
            .withUserConfiguration(StatusStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
            )
            .run { ctx ->
                val endpoint = ctx.getBean(SovereignOpsWorkerStatusEndpoint::class.java)
                val snapshot = endpoint.status()

                assertThat(snapshot.enabled).isFalse()
                assertThat(snapshot.running).isFalse()
                assertThat(snapshot.recoverPreparedEnabled).isTrue()
                assertThat(snapshot.dispatchPendingEnabled).isFalse()
                assertThat(snapshot.batchSize).isEqualTo(42)
                assertThat(snapshot.initialDelayMillis).isEqualTo(Duration.ofSeconds(5).toMillis())
                assertThat(snapshot.intervalMillis).isEqualTo(Duration.ofMinutes(1).toMillis())
                assertThat(snapshot.totalCyclesCompleted).isZero()
                assertThat(snapshot.totalCyclesFailed).isZero()
                assertThat(snapshot.lastCycleStartedAt).isNull()
                assertThat(snapshot.lastCycleCompletedAt).isNull()
                assertThat(snapshot.lastCycleDurationMillis).isNull()
                assertThat(snapshot.lastRecovered).isNull()
                assertThat(snapshot.lastDispatched).isNull()
                assertThat(snapshot.lastFailure).isNull()
                assertThat(snapshot.lastFailureAt).isNull()
            }
    }

    @Test
    fun `endpoint is not created without status store`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignOpsActuatorAutoConfiguration::class.java),
            )
            .withPropertyValues(
                "tramai.sovereign.ops.actuator.worker-status.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsWorkerStatusEndpoint::class.java)
            }
    }

    @Test
    fun `endpoint exposes read operation only`() {
        val statusMethod = SovereignOpsWorkerStatusEndpoint::class.java.getDeclaredMethod("status")
        assertThat(statusMethod.returnType).isEqualTo(SovereignOpsAuditOutboxWorkerStatusSnapshot::class.java)
        assertThat(statusMethod.getDeclaredAnnotation(org.springframework.boot.actuate.endpoint.annotation.ReadOperation::class.java))
            .isNotNull()

        val methods = SovereignOpsWorkerStatusEndpoint::class.java.declaredMethods
            .filter {
                it.getDeclaredAnnotation(org.springframework.boot.actuate.endpoint.annotation.ReadOperation::class.java) != null
            }
        assertThat(methods).containsExactly(statusMethod)

        val writeOps = SovereignOpsWorkerStatusEndpoint::class.java.declaredMethods
            .filter {
                it.getDeclaredAnnotation(org.springframework.boot.actuate.endpoint.annotation.WriteOperation::class.java) != null
            }
        assertThat(writeOps).isEmpty()
    }
}

@Configuration
open class StatusStoreConfig {
    @Bean
    open fun statusStore(): SovereignOpsAuditOutboxWorkerStatusStore =
        InMemorySovereignOpsAuditOutboxWorkerStatusStore(
            SovereignOpsOutboxWorkerProperties(
                recoverPrepared = true,
                dispatchPending = false,
                batchSize = 42,
                initialDelay = Duration.ofSeconds(5),
                interval = Duration.ofMinutes(1),
            ),
        )
}
