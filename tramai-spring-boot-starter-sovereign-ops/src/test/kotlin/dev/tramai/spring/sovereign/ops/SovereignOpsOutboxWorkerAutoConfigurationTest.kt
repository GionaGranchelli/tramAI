package dev.tramai.spring.sovereign.ops

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxBackgroundWorker
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerLifecycle
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
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

    // ── Observer wiring ────────────────────────────────────────────────

    @Test
    fun `status store bean exists when sovereign ops is enabled`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val store = ctx.getBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val snapshot = store.snapshot()
                assertThat(snapshot).isNotNull
                assertThat(snapshot.enabled).isFalse()
            }
    }

    @Test
    fun `status store snapshot does not expose sensitive details`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=false",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                val store = ctx.getBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val snapshot = store.snapshot()

                assertThat(snapshot.totalCyclesCompleted).isEqualTo(0)
                assertThat(snapshot.lastFailure).isNull()
                assertThat(snapshot.lastFailureAt).isNull()
                assertThat(snapshot.enabled).isTrue()
                assertThat(snapshot.batchSize).isPositive()
            }
    }

    @Test
    fun `status snapshot reflects effective dispatch disabled when dispatcher is missing`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=true",
                "tramai.sovereign.ops.outbox.worker.fail-on-missing-dispatcher=false",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                val store = ctx.getBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val snapshot = store.snapshot()
                assertThat(snapshot.dispatchPendingEnabled).isFalse()
            }
    }

    @Test
    fun `status snapshot shows effective dispatch true when dispatcher is present`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.dispatch-pending=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                val store = ctx.getBean(SovereignOpsAuditOutboxWorkerStatusStore::class.java)
                val snapshot = store.snapshot()
                assertThat(snapshot.dispatchPendingEnabled).isTrue()
            }
    }

    @Test
    fun `recording observer bean replaces default Noop`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val observer = ctx.getBean(SovereignOpsAuditOutboxWorkerObserver::class.java)
                assertThat(observer).isInstanceOf(RecordingSovereignOpsAuditOutboxWorkerObserver::class.java)
            }
    }

    @Test
    fun `custom observer bean is not overridden by recording observer`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                CustomObserverConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.ops.outbox.worker.enabled=true",
                "tramai.sovereign.ops.outbox.worker.initial-delay=1h",
            )
            .run { ctx ->
                assertThat(
                    ctx.getBeansOfType(SovereignOpsAuditOutboxWorkerObserver::class.java),
                ).hasSize(1)
                    .containsKey("customObserver")
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

open class CustomObserverConfig {
    @Bean
    @Primary
    open fun customObserver(): SovereignOpsAuditOutboxWorkerObserver =
        SovereignOpsAuditOutboxWorkerObserver.Noop
}
