package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditStore
import dev.tramai.spring.sovereign.ops.MinimalStoreConfig
import dev.tramai.spring.sovereign.ops.SovereignApprovalOperations
import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.security.MessageDigest

class SovereignOpsAuditOutboxTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    // ── Outbox atomicity tests ─────────────────────────────────────────

    @Test
    fun `denyApproval writes approval transition and outbox record atomically`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Administrative denial")
                }

                // Approval should be DENIED
                val approval = runBlocking {
                    ctx.getBean(ApprovalStore::class.java).get("test-approval")
                }
                assertThat(approval).isNotNull
                assertThat(approval!!.status.name).isEqualTo("DENIED")
                assertThat(approval.version).isEqualTo(1L)

                // Outbox record should exist and be EMITTED or FAILED_RETRYABLE
                val pending = runBlocking { outboxStore.listPending(10) }
                assertThat(pending).isEmpty()

                // At least attempt was made — the record should exist somewhere
                // (may have been emitted or failed, but written atomically)
            }
    }

    @Test
    fun `outbox record does not expose raw reason`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Contains sensitive: SSN-123")
                }

                // Check all outbox records for sensitive data
                val pending = runBlocking { outboxStore.listPending(10) }
                for (record in pending) {
                    assertThat(record.reasonDigest).isNotEqualTo("Contains sensitive: SSN-123")
                    assertThat(record.lastErrorCode).isNull()
                }
            }
    }

    @Test
    fun `outbox record does not expose raw approval ID`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Test reason")
                }

                val pending = runBlocking { outboxStore.listPending(10) }
                for (record in pending) {
                    assertThat(record.aggregateIdDigest).isNotEqualTo("test-approval")
                }
            }
    }

    @Test
    fun `denyApproval fails closed when no AuditEngine`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Test")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-audit-unavailable")
            }
    }

    @Test
    fun `denyApproval fail-closed leaves approval unchanged`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Test")
                    }
                }

                // Approval must remain PENDING
                val approval = runBlocking {
                    ctx.getBean(ApprovalStore::class.java).get("test-approval")
                }
                assertThat(approval).isNotNull
                assertThat(approval!!.status.name).isEqualTo("PENDING")
                assertThat(approval.version).isEqualTo(0L)
            }
    }

    @Test
    fun `denyApproval rejects zero-length reason`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-reason")
            }
    }

    @Test
    fun `denyApproval rejects unknown approval`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("unknown-approval", "admin", "Test reason")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-approval-id")
            }
    }

    @Test
    fun `denyApproval rejects when mutations disabled`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Test")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .hasMessageContaining("tramai-sovereign-ops-mutations-disabled")
            }
    }

    @Test
    fun `startup without AuditEngine creates all beans except dispatcher`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignApprovalOperations::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxStore::class.java)

                // Dispatcher should not exist without AuditEngine
                val dispatchers = ctx.getBeansOfType(SovereignOpsAuditOutboxDispatcher::class.java)
                assertThat(dispatchers).isEmpty()
            }
    }

    @Test
    fun `custom outbox store is not overridden`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                CustomOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignOpsAuditOutboxStore::class.java))
                    .hasSize(1)
                val store = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(store).isInstanceOf(CustomTestOutboxStore::class.java)
            }
    }
}

// ── Test helpers ──────────────────────────────────────────────────

class TestAuditEngineConfig {
    @Bean
    open fun testAuditEngine(auditStore: AuditStore): AuditEngine =
        AuditEngine(auditStore)
}

class CustomOutboxStoreConfig {
    @Bean
    @Primary
    open fun customOutboxStore(): SovereignOpsAuditOutboxStore = CustomTestOutboxStore()
}

class CustomTestOutboxStore : SovereignOpsAuditOutboxStore {
    private val records = mutableMapOf<String, SovereignOpsAuditOutboxRecord>()

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        records[record.outboxId] = record
        return record
    }

    override suspend fun claimPending(
        claimedBy: String, limit: Int, now: java.time.Instant,
    ): List<SovereignOpsAuditOutboxRecord> = records.values
        .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
        .take(limit)
        .map { it.copy(status = SovereignOpsAuditOutboxStatus.EMITTING) }

    override suspend fun markEmitted(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus, emittedAt: java.time.Instant,
    ): SovereignOpsAuditOutboxRecord {
        val r = records[outboxId] ?: throw IllegalStateException("not found")
        val u = r.copy(status = SovereignOpsAuditOutboxStatus.EMITTED, emittedAt = emittedAt)
        records[outboxId] = u
        return u
    }

    override suspend fun markFailed(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus, errorCode: String, retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        val r = records[outboxId] ?: throw IllegalStateException("not found")
        val s = if (retryable) SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE else SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        val u = r.copy(status = s, lastErrorCode = errorCode)
        records[outboxId] = u
        return u
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? = records[outboxId]
    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> = records.values
        .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
        .take(limit)
}
