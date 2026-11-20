package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.persistence.file.AesGcmFileEncryption
import dev.tramai.persistence.file.EncryptedFileEnvelopeV1
import dev.tramai.persistence.file.FileStoreSha256
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 0.7.6f slice 1 -- the **real** file-store write path must persist evidence, not just the codec.
 *
 * The append path is the one that matters: an attributed record written through the ordinary
 * [FileSovereignOpsAuditOutboxStore.append] must land as a V3 payload, survive a status transition, and
 * still carry every evidence field after a restart. Written against the store rather than the codec
 * because the codec being able to encode V3 says nothing about append choosing it.
 */
class FileSovereignOpsAuditOutboxEvidencePersistenceTest {
    @TempDir
    lateinit var tempDir: Path

    private val identityDigest = "0123456789abcdef".repeat(4)

    @Test
    fun `append writes an attributed record as a V3 payload`() =
        runBlocking<Unit> {
            val key = testKey()
            val store = store(key)

            store.append(attributedRecord(PREPARED_ID))

            assertThat(rawPayloadOf(key, PREPARED_ID)).contains("\"schemaVersion\":3")
        }

    @Test
    fun `an attributed record keeps every evidence field across a transition and a restart`() =
        runBlocking<Unit> {
            val key = testKey()
            val store = store(key)
            store.append(attributedRecord(RESTART_ID))

            store.markReadyForDispatch(RESTART_ID, SovereignOpsAuditOutboxStatus.PREPARED)

            val restarted = store(key)
            val reloaded = restarted.get(RESTART_ID)
            assertThat(reloaded).isNotNull()
            requireNotNull(reloaded)
            assertThat(reloaded.actorIssuer).isEqualTo("https://issuer.example")
            assertThat(reloaded.actorIdentityDigest).isEqualTo(identityDigest)
            assertThat(reloaded.previousApprovalStatus).isEqualTo("PENDING")
            assertThat(reloaded.previousApprovalVersion).isEqualTo(3L)
            assertThat(reloaded.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            // The transition rewrote the payload; it must still be V3, not a downgrade to the released shape.
            assertThat(rawPayloadOf(key, RESTART_ID)).contains("\"schemaVersion\":3")
        }

    @Test
    fun `a record without evidence keeps the released V1 shape`() =
        runBlocking<Unit> {
            val key = testKey()
            val store = store(key)

            store.append(
                attributedRecord(LEGACY_ID).copy(
                    actorIssuer = null,
                    actorIdentityDigest = null,
                    previousApprovalStatus = null,
                    previousApprovalVersion = null,
                ),
            )

            val raw = rawPayloadOf(key, LEGACY_ID)
            assertThat(raw).contains("\"schemaVersion\":1")
            assertThat(raw).doesNotContain("actorIssuer")
        }

    // --- helpers ---

    private fun store(key: SecretKey): FileSovereignOpsAuditOutboxStore =
        FileSovereignOpsAuditOutboxStore(
            root = tempDir,
            key = key,
        )

    private fun attributedRecord(outboxId: String): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = "a".repeat(64),
            operation = "denyApproval",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = "workflow-$outboxId",
            correlationId = "correlation-$outboxId",
            approvalStatus = "DENIED",
            approvalVersion = 4L,
            reasonDigest = "b".repeat(64),
            reasonLength = 42,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            status = SovereignOpsAuditOutboxStatus.PREPARED,
            actorIssuer = "https://issuer.example",
            actorIdentityDigest = identityDigest,
            previousApprovalStatus = "PENDING",
            previousApprovalVersion = 3L,
        )

    /** Decrypts the stored record so the assertion is about the bytes on disk, not about an in-memory field. */
    private fun rawPayloadOf(
        key: SecretKey,
        outboxId: String,
    ): String {
        val outboxDir = tempDir.resolve("ops-audit-outbox")
        val file =
            Files.list(outboxDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".tram.enc") }.toList()
            }
        val digest = FileStoreSha256.digest("ops-audit-outbox", outboxId)
        val target =
            file.firstOrNull { it.fileName.toString() == "$digest.tram.enc" }
                ?: error("no stored payload for $outboxId in $outboxDir")
        val envelope = EncryptedFileEnvelopeV1.fromJson(Files.readString(target))
        val plaintext = AesGcmFileEncryption.decrypt(key, envelope, "ops-audit-outbox", digest)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun testKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private companion object {
        private const val PREPARED_ID = "prepared-evidence"
        private const val RESTART_ID = "restart-evidence"
        private const val LEGACY_ID = "legacy-shape"
    }
}
