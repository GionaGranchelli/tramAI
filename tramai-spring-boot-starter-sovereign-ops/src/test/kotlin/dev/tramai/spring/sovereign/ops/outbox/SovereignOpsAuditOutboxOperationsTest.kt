package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.MinimalStoreConfig
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant

class SovereignOpsAuditOutboxOperationsTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    @Test
    fun `listOutboxRecords uses bounded cross-status default`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("pending", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
                    outboxStore.append(record("retryable", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("retryable", SovereignOpsAuditOutboxStatus.PREPARED)
                    outboxStore.markFailed(
                        outboxId = "retryable",
                        expectedStatus = SovereignOpsAuditOutboxStatus.PENDING,
                        errorCode = "DispatchFailure",
                        retryable = true,
                    )
                }

                val summaries = runBlocking { ops.listOutboxRecords(status = null, limit = null) }

                assertThat(summaries).hasSize(3)
                assertThat(summaries.map { it.status }).containsExactly(
                    SovereignOpsAuditOutboxStatus.PREPARED,
                    SovereignOpsAuditOutboxStatus.PENDING,
                    SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
                )
            }
    }

    @Test
    fun `listOutboxRecords filters by status`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("pending", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
                }

                val summaries = runBlocking {
                    ops.listOutboxRecords(
                        status = SovereignOpsAuditOutboxStatus.PENDING,
                        limit = 10,
                    )
                }

                val summary = summaries.single()
                assertThat(summary.outboxId).isEqualTo("pending")
                assertThat(summary.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            }
    }

    @Test
    fun `listOutboxRecords rejects invalid limit`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.listOutboxRecords(status = null, limit = 0) }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-invalid-limit")
            }
    }

    @Test
    fun `retryPending rejects missing dispatcher`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.retryPending(limit = 1) }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-audit-unavailable")
            }
    }

    @Test
    fun `retryPending dispatches retryable records`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("retryable", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("retryable", SovereignOpsAuditOutboxStatus.PREPARED)
                    outboxStore.markFailed(
                        outboxId = "retryable",
                        expectedStatus = SovereignOpsAuditOutboxStatus.PENDING,
                        errorCode = "DispatchFailure",
                        retryable = true,
                    )
                }

                val summary = runBlocking { ops.retryPending(limit = 10) }
                val stored = runBlocking { outboxStore.get("retryable") }

                assertThat(summary.claimed).isEqualTo(1)
                assertThat(summary.emitted).isEqualTo(1)
                assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
            }
    }

    @Test
    fun `markPreparedFailed requires mutations enabled`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.markPreparedFailed("prepared", "operator-close") }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-mutations-disabled")
            }
    }

    @Test
    fun `markPreparedFailed marks prepared record permanently failed with fixed error code`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking {
                    ops.markPreparedFailed("prepared", "operator closed after crash")
                }
                val stored = runBlocking { outboxStore.get("prepared") }

                assertThat(summary.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
                assertThat(stored!!.lastErrorCode).isEqualTo("operator-marked-prepared-failed")
                assertThat(summary.lastErrorCode).isEqualTo("operator-marked-prepared-failed")
            }
    }

    @Test
    fun `markPreparedFailed does not persist raw operator reason as lastErrorCode`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                runBlocking {
                    ops.markPreparedFailed(
                        "prepared",
                        "contains /private/path and user@example.com",
                    )
                }
                val stored = runBlocking { outboxStore.get("prepared") }

                // Raw sensitive text must NOT appear in lastErrorCode
                assertThat(stored!!.lastErrorCode).doesNotContain("/private/path")
                assertThat(stored!!.lastErrorCode).doesNotContain("user@example.com")
                assertThat(stored!!.lastErrorCode).isEqualTo("operator-marked-prepared-failed")
            }
    }

    @Test
    fun `markPreparedFailed rejects non prepared record`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("pending", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
                }

                val ex = runCatching {
                    runBlocking { ops.markPreparedFailed("pending", "operator-close") }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
            }
    }

    @Test
    fun `markPreparedFailed rejects blank reason`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.markPreparedFailed("prepared", " ") }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-invalid-reason")
            }
    }

    private fun record(
        outboxId: String,
        status: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateIdDigest = "digest-$outboxId",
            eventKey = "event-$outboxId",
            actor = "admin",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = "DENIED",
            approvalVersion = 1L,
            reasonDigest = "reason-$outboxId",
            reasonLength = 12,
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
            status = status,
        )
}
