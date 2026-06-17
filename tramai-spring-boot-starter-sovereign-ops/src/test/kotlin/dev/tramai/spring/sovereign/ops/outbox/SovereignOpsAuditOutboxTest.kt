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
import java.time.Instant

class SovereignOpsAuditOutboxTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    // ── P1: Durability gate tests ─────────────────────────────────

    @Test
    fun `denyApproval rejects non-durable outbox store`() {
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
                        ops.denyApproval("test-approval", "admin", "Test reason")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-audit-outbox-not-durable")
            }
    }

    @Test
    fun `denyApproval with durable outbox writes approval transition and outbox record atomically`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                DurableOutboxStoreConfig::class.java,
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

                // Find the outbox record by deterministic event key
                val approvalIdDigest = sha256Hex("sovereign-ops-approval:test-approval")
                val eventKey = "deny:$approvalIdDigest:1"
                val record = runBlocking { outboxStore.findByEventKey(eventKey) }
                assertThat(record).isNotNull
                // Record should be EMITTED (dispatcher runs successfully)
                assertThat(record!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
                // Must not contain raw reason
                assertThat(record.reasonDigest).isNotEqualTo("Administrative denial")
                // Must not contain raw approval ID
                assertThat(record.aggregateIdDigest).isNotEqualTo("test-approval")
            }
    }

    // ── P1: Outbox record security invariants ──────────────────────

    @Test
    fun `outbox record does not expose raw reason`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                val rawReason = "Contains sensitive: SSN-123"
                runBlocking {
                    ops.denyApproval("test-approval", "admin", rawReason)
                }

                // Find record by event key — always works regardless of dispatch status
                val approvalIdDigest = sha256Hex("sovereign-ops-approval:test-approval")
                val eventKey = "deny:$approvalIdDigest:1"
                val record = runBlocking { outboxStore.findByEventKey(eventKey) }
                assertThat(record).isNotNull
                assertThat(record!!.reasonDigest).isNotEqualTo(rawReason)
                // reasonDigest should be sha256("sovereign-ops-reason:$rawReason")
                val expectedDigest = sha256Hex("sovereign-ops-reason:$rawReason")
                assertThat(record.reasonDigest).isEqualTo(expectedDigest)
            }
    }

    @Test
    fun `outbox record does not expose raw approval ID`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Test reason")
                }

                val approvalIdDigest = sha256Hex("sovereign-ops-approval:test-approval")
                val eventKey = "deny:$approvalIdDigest:1"
                val record = runBlocking { outboxStore.findByEventKey(eventKey) }
                assertThat(record).isNotNull
                assertThat(record!!.aggregateIdDigest).isNotEqualTo("test-approval")
                assertThat(record.aggregateIdDigest).isEqualTo(approvalIdDigest)
            }
    }

    // ── P1: Outbox dispatch emits correct audit metadata ───────────

    @Test
    fun `outbox dispatch emits correct pre-digested audit metadata`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                val auditStore = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Violation of clause 3.1")
                }

                val approvalIdDigest = sha256Hex("sovereign-ops-approval:test-approval")
                val eventKey = "deny:$approvalIdDigest:1"
                val record = runBlocking { outboxStore.findByEventKey(eventKey) }
                assertThat(record).isNotNull

                // Audit stream ID uses the original aggregateIdDigest (not double-hashed)
                val auditStreamId = "sovereign-ops-approval:${record!!.aggregateIdDigest}"
                val events = runBlocking { auditStore.readStream(auditStreamId) }
                assertThat(events).isNotEmpty

                val event = events.last()
                // The audit stream ID uses aggregateIdDigest directly
                assertThat(event.auditStreamId).isEqualTo("sovereign-ops-approval:${record.aggregateIdDigest}")
                // The approvalIdDigest metadata should match the outbox record
                assertThat(event.metadata).containsKey("approvalIdDigest")
                assertThat(event.metadata["approvalIdDigest"]).isEqualTo(record.aggregateIdDigest)
                // The reasonDigest should match the outbox record (not double-hashed)
                assertThat(event.metadata).containsKey("reasonDigest")
                assertThat(event.metadata["reasonDigest"]).isEqualTo(record.reasonDigest)
                // Reason length should match the outbox record
                assertThat(event.metadata).containsKey("reasonLength")
                assertThat(event.metadata["reasonLength"]).isEqualTo("23")
            }
    }

    // ── P1: CancellationException propagation ─────────────────────

    @Test
    fun `denyApproval propagates CancellationException from outbox dispatcher`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                CancellationEmittingOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Test")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(CancellationException::class.java)
            }
    }

    // ── P1: Retryability ──────────────────────────────────────────

    @Test
    fun `dispatcher can retry FAILED_RETRYABLE outbox records`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
                DurableOutboxStoreConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                val digester = sha256Hex("sovereign-ops-approval:test-approval")
                val eventKey = "deny:$digester:1"

                // Manually create a FAILED_RETRYABLE record
                val record = SovereignOpsAuditOutboxRecord(
                    aggregateIdDigest = digester,
                    eventKey = eventKey,
                    actor = "admin",
                    workflowRunId = "wf-1",
                    correlationId = null,
                    approvalStatus = "DENIED",
                    approvalVersion = 1L,
                    reasonDigest = sha256Hex("sovereign-ops-reason:Test"),
                    reasonLength = 4,
                    status = SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
                    lastErrorCode = "RuntimeException",
                )
                runBlocking { outboxStore.append(record) }

                // First claim — should pick up FAILED_RETRYABLE
                val claimed = runBlocking {
                    outboxStore.claimPending(
                        claimedBy = "test-dispatcher",
                        limit = 10,
                        now = Instant.now(),
                    )
                }
                assertThat(claimed).hasSize(1)
                assertThat(claimed[0].status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
                assertThat(claimed[0].attemptCount).isEqualTo(1)

                // Mark as FAILED_RETRYABLE again
                runBlocking {
                    outboxStore.markFailed(
                        record.outboxId,
                        SovereignOpsAuditOutboxStatus.EMITTING,
                        "RetryableError",
                        retryable = true,
                    )
                }
                val failed = runBlocking { outboxStore.get(record.outboxId) }
                assertThat(failed!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)

                // Second claim — should pick it up again
                val claimed2 = runBlocking {
                    outboxStore.claimPending(
                        claimedBy = "test-dispatcher",
                        limit = 10,
                        now = Instant.now(),
                    )
                }
                assertThat(claimed2).hasSize(1)
                assertThat(claimed2[0].status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
                assertThat(claimed2[0].attemptCount).isEqualTo(2)
            }
    }

    // ── Existing fail-closed tests ─────────────────────────────────

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
                DurableOutboxStoreConfig::class.java,
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

    // ── Bean wiring tests ──────────────────────────────────────────

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

    // ── Mutual exclusion: eventKey uniqueness ──────────────────────

    @Test
    fun `duplicate eventKey is rejected on append`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .run { ctx ->
                val outboxStore = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                val digest = sha256Hex("sovereign-ops-approval:test")

                val record1 = SovereignOpsAuditOutboxRecord(
                    outboxId = "id-1",
                    aggregateIdDigest = digest,
                    eventKey = "deny:$digest:1",
                    actor = "admin",
                    workflowRunId = null,
                    correlationId = null,
                    approvalStatus = "DENIED",
                    approvalVersion = 1L,
                    reasonDigest = "r1",
                    reasonLength = 2,
                )
                val record2 = record1.copy(outboxId = "id-2")

                runBlocking { outboxStore.append(record1) }
                val ex = runCatching {
                    runBlocking { outboxStore.append(record2) }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-event-key")
            }
    }
}

// ── Test configurations ─────────────────────────────────────────────

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

/**
 * A durable in-memory outbox store for tests that exercise the
 * [SovereignOpsAuditOutboxStore.isDurable] gate.
 *
 * Also tracks event keys for [findByEventKey] support.
 */
class DurableTestInMemoryOutboxStore : SovereignOpsAuditOutboxStore {

    private val store = mutableMapOf<String, SovereignOpsAuditOutboxRecord>()
    private val eventKeyIndex = mutableMapOf<String, String>()

    override fun isDurable(): Boolean = true

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(!store.containsKey(record.outboxId)) {
            "tramai-sovereign-ops-outbox-duplicate-id"
        }
        require(!eventKeyIndex.containsKey(record.eventKey)) {
            "tramai-sovereign-ops-outbox-duplicate-event-key"
        }
        store[record.outboxId] = record
        eventKeyIndex[record.eventKey] = record.outboxId
        return record
    }

    override suspend fun claimPending(
        claimedBy: String, limit: Int, now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> {
        val claimed = mutableListOf<SovereignOpsAuditOutboxRecord>()
        for ((id, record) in store) {
            if (claimed.size >= limit) break
            val eligible = when (record.status) {
                SovereignOpsAuditOutboxStatus.PENDING -> true
                SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE -> true
                SovereignOpsAuditOutboxStatus.EMITTING -> {
                    val expiresAt = record.claimExpiresAt
                    expiresAt != null && expiresAt.isBefore(now)
                }
                else -> false
            }
            if (!eligible) continue
            val updated = record.copy(
                status = SovereignOpsAuditOutboxStatus.EMITTING,
                attemptCount = record.attemptCount + 1,
                claimedBy = claimedBy,
                claimedAt = now,
                claimExpiresAt = now.plus(java.time.Duration.ofMinutes(5)),
            )
            if (store.replace(id, record, updated)) {
                claimed.add(updated)
            }
        }
        return claimed
    }

    override suspend fun markEmitted(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus, emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        val r = store[outboxId] ?: throw IllegalStateException("not found")
        require(r.status == expectedStatus) { "status mismatch" }
        val u = r.copy(status = SovereignOpsAuditOutboxStatus.EMITTED, emittedAt = emittedAt)
        store[outboxId] = u
        return u
    }

    override suspend fun markFailed(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String, retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        val r = store[outboxId] ?: throw IllegalStateException("not found")
        require(r.status == expectedStatus) { "status mismatch" }
        val s = if (retryable) SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        else SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        val u = r.copy(status = s, lastErrorCode = errorCode)
        store[outboxId] = u
        return u
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? = store[outboxId]

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? {
        val id = eventKeyIndex[eventKey] ?: return null
        return store[id]
    }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        store.values.filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }.take(limit)
}

class DurableOutboxStoreConfig {
    @Bean
    @Primary
    open fun durableOutboxStore(): SovereignOpsAuditOutboxStore = DurableTestInMemoryOutboxStore()
}

/**
 * Outbox store config that causes the dispatcher to throw CancellationException,
 * used to test CancellationException propagation.
 */
class CancellationEmittingOutboxStoreConfig {
    @Bean
    @Primary
    open fun durableOutboxStore(): SovereignOpsAuditOutboxStore = CancellationEmittingOutboxStore()
}

class CancellationEmittingOutboxStore : SovereignOpsAuditOutboxStore {

    private val delegate = DurableTestInMemoryOutboxStore()

    override fun isDurable(): Boolean = true

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord =
        delegate.append(record)

    override suspend fun claimPending(
        claimedBy: String, limit: Int, now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> =
        delegate.claimPending(claimedBy, limit, now)

    override suspend fun markEmitted(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus, emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        // Throw CancellationException instead of emitting
        throw CancellationException("Simulated cancellation during dispatch")
    }

    override suspend fun markFailed(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String, retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord =
        delegate.markFailed(outboxId, expectedStatus, errorCode, retryable)

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? =
        delegate.get(outboxId)

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? =
        delegate.findByEventKey(eventKey)

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        delegate.listPending(limit)
}

class CustomTestOutboxStore : SovereignOpsAuditOutboxStore {
    private val records = mutableMapOf<String, SovereignOpsAuditOutboxRecord>()
    private val eventKeyIndex = mutableMapOf<String, String>()

    override fun isDurable(): Boolean = true

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(!records.containsKey(record.outboxId)) { "duplicate id" }
        require(!eventKeyIndex.containsKey(record.eventKey)) { "duplicate event key" }
        records[record.outboxId] = record
        eventKeyIndex[record.eventKey] = record.outboxId
        return record
    }

    override suspend fun claimPending(
        claimedBy: String, limit: Int, now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> = records.values
        .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
        .take(limit)
        .map { it.copy(status = SovereignOpsAuditOutboxStatus.EMITTING) }

    override suspend fun markEmitted(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus, emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        val r = records[outboxId] ?: throw IllegalStateException("not found")
        val u = r.copy(status = SovereignOpsAuditOutboxStatus.EMITTED, emittedAt = emittedAt)
        records[outboxId] = u
        return u
    }

    override suspend fun markFailed(
        outboxId: String, expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String, retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        val r = records[outboxId] ?: throw IllegalStateException("not found")
        val s = if (retryable) SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        else SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        val u = r.copy(status = s, lastErrorCode = errorCode)
        records[outboxId] = u
        return u
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? = records[outboxId]

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? {
        val id = eventKeyIndex[eventKey] ?: return null
        return records[id]
    }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> = records.values
        .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
        .take(limit)
}

// ── Test helper ────────────────────────────────────────────────────

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
