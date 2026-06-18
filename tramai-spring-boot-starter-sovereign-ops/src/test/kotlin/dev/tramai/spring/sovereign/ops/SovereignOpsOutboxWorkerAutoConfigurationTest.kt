package dev.tramai.spring.sovereign.ops

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxBackgroundWorker
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerLifecycle
import dev.tramai.spring.sovereign.ops.outbox.TestAuditEngineConfig
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class SovereignOpsOutboxWorkerAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    @Test
    fun `worker bean is not created by default`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(SovereignOpsAuditOutboxBackgroundWorker::class.java)
                assertThat(ctx).doesNotHaveBean(SovereignOpsAuditOutboxWorkerLifecycle::class.java)
            }
    }

    @Test
    fun `worker bean is created when enabled`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxBackgroundWorker::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerLifecycle::class.java)
            }
    }

    @Test
    fun `custom worker bean overrides default`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                CustomOutboxBackgroundWorkerConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignOpsAuditOutboxBackgroundWorker::class.java))
                    .hasSize(1)
                    .containsKey("customOutboxBackgroundWorker")
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerLifecycle::class.java)
            }
    }

    @Test
    fun `enabled dispatch worker fails without dispatcher when failOnMissingDispatcher true`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure)
                    .hasMessageContaining("tramai-sovereign-ops-outbox-worker-missing-dispatcher")
            }
    }

    @Test
    fun `enabled recovery-only worker does not require dispatcher`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxBackgroundWorker::class.java)
            }
    }

    @Test
    fun `missing dispatcher with failOnMissingDispatcher false skips dispatch and runs recovery only`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
                "tramai.sovereign.ops.outbox.worker.fail-on-missing-dispatcher=false",
                "tramai.sovereign.ops.outbox.worker.recover-prepared=true",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=true",
                "tramai.sovereign.ops.mutations-enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val worker = ctx.getBean(SovereignOpsAuditOutboxBackgroundWorker::class.java)
                // Run once — should only recover, not dispatch
                val summary = runBlocking { worker.runOnce() }
                assertThat(summary.recovered).isNotNull
                assertThat(summary.dispatched).isNull()
                // Check that dispatch was skipped (retryPending would throw without dispatcher)
                assertThat(summary.failure).isNull()
            }
    }

    @Test
    fun `missing dispatcher with both actions disabled but failOnMissingDispatcher false fails validation`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
                "tramai.sovereign.ops.outbox.worker.fail-on-missing-dispatcher=false",
                "tramai.sovereign.ops.outbox.worker.recover-prepared=false",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=true",
            )
            .run { ctx ->
                // dispatch-pending gets set to false because dispatcher missing,
                // then both actions are disabled -> validation fails
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure)
                    .hasMessageContaining("tramai-sovereign-ops-outbox-worker-invalid-actions")
            }
    }

    @Test
    fun `worker uses configured interval and batch size`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                RecordingOutboxOperationsConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
                "tramai.sovereign.ops.outbox.worker.interval=42s",
                "tramai.sovereign.ops.outbox.worker.batch-size=17",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
            )
            .run { ctx ->
                val properties = ctx.getBean(SovereignOpsProperties::class.java)
                val worker = ctx.getBean(SovereignOpsAuditOutboxBackgroundWorker::class.java)
                val operations = ctx.getBean(RecordingOutboxOperations::class.java)

                runBlocking { worker.runOnce() }

                assertThat(properties.outbox.worker.interval).isEqualTo(java.time.Duration.ofSeconds(42))
                assertThat(properties.outbox.worker.batchSize).isEqualTo(17)
                assertThat(operations.recoverLimits).containsExactly(17)
            }
    }
}

open class CustomOutboxBackgroundWorkerConfig {
    @Bean
    @Primary
    open fun customOutboxBackgroundWorker(): SovereignOpsAuditOutboxBackgroundWorker =
        SovereignOpsAuditOutboxBackgroundWorker(
            operations = RecordingOutboxOperations(),
            properties = SovereignOpsOutboxWorkerProperties(
                enabled = true,
                initialDelay = java.time.Duration.ofHours(1),
                dispatchPending = false,
            ),
        )
}

open class RecordingOutboxOperationsConfig {
    @Bean
    @Primary
    open fun recordingOutboxOperations(): RecordingOutboxOperations =
        RecordingOutboxOperations()
}

class RecordingOutboxOperations : SovereignOpsAuditOutboxOperations {
    val recoverLimits = mutableListOf<Int?>()
    val dispatchLimits = mutableListOf<Int?>()

    override suspend fun listOutboxRecords(
        status: SovereignOpsAuditOutboxStatus?,
        limit: Int?,
    ): List<SovereignOpsAuditOutboxSummary> = emptyList()

    override suspend fun retryPending(limit: Int?): SovereignOpsAuditOutboxDispatchResult {
        dispatchLimits += limit
        return SovereignOpsAuditOutboxDispatchResult(
            claimed = 0,
            emitted = 0,
            failedRetryable = 0,
            failedPermanent = 0,
        )
    }

    override suspend fun markPreparedFailed(
        outboxId: String,
        reason: String,
    ): SovereignOpsAuditOutboxSummary {
        throw UnsupportedOperationException("test stub")
    }

    override suspend fun recoverPrepared(limit: Int?): SovereignOpsAuditOutboxRecoverySummary {
        recoverLimits += limit
        return SovereignOpsAuditOutboxRecoverySummary(inspected = 0)
    }
}
