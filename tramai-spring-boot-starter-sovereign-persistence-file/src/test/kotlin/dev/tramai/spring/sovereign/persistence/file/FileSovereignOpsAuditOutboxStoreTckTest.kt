package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists

/**
 * Epic 8.1e: FileSovereignOpsAuditOutboxStore must satisfy the shared outbox
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * encryption key and per-case isolation — storage technology (durability,
 * encryption format, permissions, corruption detection, record versions)
 * never contaminates the contract.
 *
 * Each [createStore] call gets a fresh `case-N` root directory; the store
 * provisions its own `outbox/` layout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {
    private val rootDir: Path = Files.createTempDirectory("tramai-outbox-tck-").toAbsolutePath()
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caseCounter = AtomicLong(0)

    @Test
    fun `a dispatchable refusal record survives a restart and stays claimable`() =
        runBlocking<Unit> {
            val caseDir = Files.createDirectories(rootDir.resolve("durable-${caseCounter.incrementAndGet()}"))
            val store = FileSovereignOpsAuditOutboxStore(root = caseDir, key = testKey)
            store.appendDispatchable(refusalRecord("durable-dispatch"))

            // A brand-new store over the same directory: the event-key index and the payload both come from disk.
            val restarted = FileSovereignOpsAuditOutboxStore(root = caseDir, key = testKey)
            val reloaded = restarted.get("durable-dispatch")
            assertThat(reloaded).isNotNull()
            requireNotNull(reloaded)
            assertThat(reloaded.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            assertThat(reloaded.evidenceKind).isEqualTo(SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL)
            assertThat(reloaded.actorIssuer).isEqualTo("https://issuer.example")
            assertThat(reloaded.actorIdentityDigest).isEqualTo("0123456789abcdef".repeat(4))
            assertThat(reloaded.approvalStatus).isNull()
            // The rebuilt index must find it too, or the duplicate check would be defeated after a restart.
            assertThat(restarted.findByEventKey("event-durable-dispatch")?.outboxId).isEqualTo("durable-dispatch")

            val claimed = restarted.claimPending("dispatcher-restart", 10, Instant.parse("2026-01-01T00:00:01Z"))
            assertThat(claimed.map { it.outboxId }).contains("durable-dispatch")
        }

    @Test
    fun `a rejected dispatchable append leaves no file behind`() =
        runBlocking<Unit> {
            val caseDir = Files.createDirectories(rootDir.resolve("atomic-${caseCounter.incrementAndGet()}"))
            val store = FileSovereignOpsAuditOutboxStore(root = caseDir, key = testKey)
            store.appendDispatchable(refusalRecord("atomic-winner"))
            val before = storedFileCount(caseDir)

            runCatching {
                runBlocking {
                    store.appendDispatchable(refusalRecord("atomic-loser").copy(eventKey = "event-atomic-winner"))
                }
            }
            runCatching {
                runBlocking {
                    val notPending =
                        refusalRecord("atomic-not-pending", SovereignOpsAuditOutboxStatus.PREPARED)
                    store.appendDispatchable(notPending)
                }
            }

            assertThat(storedFileCount(caseDir)).isEqualTo(before)
            assertThat(store.get("atomic-loser")).isNull()
            assertThat(store.get("atomic-not-pending")).isNull()
        }

    private fun storedFileCount(caseDir: Path): Long =
        Files.walk(caseDir).use { stream -> stream.filter { it.fileName.toString().endsWith(".tram.enc") }.count() }

    override fun createStore(): SovereignOpsAuditOutboxStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        return FileSovereignOpsAuditOutboxStore(root = caseDir, key = testKey)
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    private fun refusalRecord(
        outboxId: String,
        status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PENDING,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = "sha256:" + "a".repeat(64),
            operation = "authorizationRefusal",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = null,
            correlationId = null,
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = "sha256:" + "b".repeat(64),
            reasonLength = 11,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = status,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            refusalReason = "missing-capability",
            refusalExpectedVersion = 3,
            actorIssuer = "https://issuer.example",
            actorIdentityDigest = "0123456789abcdef".repeat(4),
        )
}
