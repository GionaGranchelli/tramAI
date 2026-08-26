package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.MinimalStoreConfig
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
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
                        expectedAttemptCount = 0,
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
                        expectedAttemptCount = 0,
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

    // -- recoverPrepared tests ------------------------------------------------

    @Test
    fun `recoverPrepared requires mutations enabled`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.recoverPrepared(limit = 10) }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-mutations-disabled")
            }
    }

    @Test
    fun `recoverPrepared with default resolver skips prepared records as unresolved`() {
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
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("prepared-2", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }

                assertThat(summary.inspected).isEqualTo(2)
                assertThat(summary.movedToPending).isEqualTo(0)
                assertThat(summary.markedFailedPermanent).isEqualTo(0)
                assertThat(summary.skippedUnresolved).isEqualTo(2)
                assertThat(summary.resolverFailures).isEqualTo(0)
            }
    }

    @Test
    fun `recoverPrepared moves committed denied records to pending`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                CommittedDeniedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }
                val stored = runBlocking { outboxStore.get("prepared-1") }

                assertThat(summary.inspected).isEqualTo(1)
                assertThat(summary.movedToPending).isEqualTo(1)
                assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            }
    }

    @Test
    fun `recoverPrepared marks not committed records as permanently failed with fixed error code`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                NotCommittedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }
                val stored = runBlocking { outboxStore.get("prepared-1") }

                assertThat(summary.inspected).isEqualTo(1)
                assertThat(summary.markedFailedPermanent).isEqualTo(1)
                assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
                assertThat(stored.lastErrorCode).isEqualTo("prepared-recovery-not-committed")
            }
    }

    @Test
    fun `recoverPrepared not committed uses fixed safe error code without sensitive text`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                NotCommittedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                runBlocking { ops.recoverPrepared(limit = 10) }
                val stored = runBlocking { outboxStore.get("prepared-1") }

                assertThat(stored!!.lastErrorCode).doesNotContain("/private/path")
                assertThat(stored.lastErrorCode).doesNotContain("user@example.com")
                assertThat(stored.lastErrorCode).isEqualTo("prepared-recovery-not-committed")
            }
    }

    @Test
    fun `recoverPrepared does not touch pending records`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                CommittedDeniedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("pending", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }
                val stored = runBlocking { outboxStore.get("pending") }

                assertThat(summary.inspected).isEqualTo(0)
                assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            }
    }

    @Test
    fun `recoverPrepared does not touch emitted records`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                CommittedDeniedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("emitted", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.markReadyForDispatch("emitted", SovereignOpsAuditOutboxStatus.PREPARED)
                    val claimed = outboxStore.claimPending("test", 10, Instant.now())
                    for (c in claimed) {
                        outboxStore.markEmitted(
                            c.outboxId,
                            SovereignOpsAuditOutboxStatus.EMITTING,
                            c.attemptCount,
                            Instant.now(),
                        )
                    }
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }

                assertThat(summary.inspected).isEqualTo(0)
            }
    }

    @Test
    fun `recoverPrepared respects limit`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                CommittedDeniedResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("p1", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("p2", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("p3", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 2) }

                assertThat(summary.inspected).isEqualTo(2)
                assertThat(summary.movedToPending).isEqualTo(2)
            }
    }

    @Test
    fun `recoverPrepared rejects invalid limit`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)

                val ex = runCatching {
                    runBlocking { ops.recoverPrepared(limit = 0) }
                }.exceptionOrNull()

                assertThat(ex).hasMessageContaining("tramai-sovereign-ops-invalid-limit")
            }
    }

    @Test
    fun `recoverPrepared summary counts are correct for mixed decisions`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                MixedDecisionResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("p1", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("p2", SovereignOpsAuditOutboxStatus.PREPARED))
                    outboxStore.append(record("p3", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }

                assertThat(summary.inspected).isEqualTo(3)
                assertThat(summary.movedToPending).isEqualTo(1)
                assertThat(summary.markedFailedPermanent).isEqualTo(1)
                assertThat(summary.skippedUnresolved).isEqualTo(1)
                assertThat(summary.resolverFailures).isEqualTo(0)
            }
    }

    @Test
    fun `recoverPrepared handles resolver RuntimeException without leaking exception message`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                ThrowingResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }
                val stored = runBlocking { outboxStore.get("prepared-1") }

                assertThat(summary.inspected).isEqualTo(1)
                assertThat(summary.movedToPending).isEqualTo(0)
                assertThat(summary.resolverFailures).isEqualTo(1)
                assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
                assertThat(stored.lastErrorCode).isNull()
            }
    }

    @Test
    fun `recoverPrepared custom resolver bean is not overridden`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                DurableOutboxStoreConfig::class.java,
                CustomRecoveryResolverConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignOpsAuditOutboxOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    outboxStore.append(record("prepared-1", SovereignOpsAuditOutboxStatus.PREPARED))
                }

                val summary = runBlocking { ops.recoverPrepared(limit = 10) }

                assertThat(summary.inspected).isEqualTo(1)
                assertThat(summary.movedToPending).isEqualTo(1)
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

// -- Recovery resolver test configs -----------------------------------------

class CommittedDeniedResolverConfig {
    @Bean
    open fun committedDeniedResolver(): SovereignOpsApprovalRecoveryResolver =
        SovereignOpsApprovalRecoveryResolver { _ ->
            SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED
        }
}

class NotCommittedResolverConfig {
    @Bean
    open fun notCommittedResolver(): SovereignOpsApprovalRecoveryResolver =
        SovereignOpsApprovalRecoveryResolver { _ ->
            SovereignOpsPreparedRecoveryDecision.NOT_COMMITTED
        }
}

class MixedDecisionResolverConfig {
    private var counter = 0

    @Bean
    open fun mixedDecisionResolver(): SovereignOpsApprovalRecoveryResolver =
        SovereignOpsApprovalRecoveryResolver { _ ->
            val current = counter++
            when {
                current == 0 -> SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED
                current == 1 -> SovereignOpsPreparedRecoveryDecision.NOT_COMMITTED
                else -> SovereignOpsPreparedRecoveryDecision.UNKNOWN
            }
        }
}

class ThrowingResolverConfig {
    @Bean
    open fun throwingResolver(): SovereignOpsApprovalRecoveryResolver =
        SovereignOpsApprovalRecoveryResolver { _ ->
            throw RuntimeException("test-resolver-error /private/path user@example.com")
        }
}

class CustomRecoveryResolverConfig {
    @Bean
    @Primary
    open fun customRecoveryResolver(): SovereignOpsApprovalRecoveryResolver =
        SovereignOpsApprovalRecoveryResolver { _ ->
            SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED
        }
}
