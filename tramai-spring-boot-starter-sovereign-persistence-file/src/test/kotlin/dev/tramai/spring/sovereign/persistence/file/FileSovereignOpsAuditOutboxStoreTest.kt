package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.persistence.file.AesGcmFileEncryption
import dev.tramai.persistence.file.EncryptedFileEnvelopeV1
import dev.tramai.persistence.file.FileStoreSha256
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSovereignOpsAuditOutboxStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isDurable returns true`() {
        val store = store()

        assertThat(store.isDurable()).isTrue
    }

    @Test
    fun `append persists PREPARED record`() { runBlocking {
        val store = store()
        val record = record("prepared")

        store.append(record)

        assertThat(store.get("prepared")).isEqualTo(record)
    }
    }

    @Test
    fun `append rejects non-PREPARED record`() {
        val store = store()
        val record = record("pending", status = SovereignOpsAuditOutboxStatus.PENDING)

        assertThatThrownBy {
            runBlocking { store.append(record) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-outbox-invalid-status")
    }

    @Test
    fun `duplicate outboxId rejected`() {
        val store = store()

        runBlocking { store.append(record("same")) }

        assertThatThrownBy {
            runBlocking { store.append(record("same")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-id")
    }

    @Test
    fun `duplicate eventKey rejected`() {
        val store = store()

        runBlocking {
            store.append(record("first", eventKey = "duplicate-event-key"))
        }

        assertThatThrownBy {
            runBlocking {
                store.append(record("second", eventKey = "duplicate-event-key"))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-event-key")
    }

    @Test
    fun `get returns persisted record`() { runBlocking {
        val store = store()
        val record = record("get-record")

        store.append(record)

        assertThat(store.get("get-record")).isEqualTo(record)
    }
    }

    @Test
    fun `findByEventKey works after restart`() { runBlocking {
        val key = testKey()
        val store = store(key = key)
        val record = record("event-key-record", eventKey = "event-key-after-restart")
        store.append(record)

        val restarted = store(key = key)

        assertThat(restarted.findByEventKey("event-key-after-restart")).isEqualTo(record)
    }
    }

    @Test
    fun `listByStatus returns records by status`() { runBlocking {
        val store = store()
        store.append(record("prepared"))
        store.append(record("pending"))
        store.markReadyForDispatch("pending", SovereignOpsAuditOutboxStatus.PREPARED)
        store.append(record("emitting"))
        store.markReadyForDispatch("emitting", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("dispatcher", 1, BASE_NOW)

        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10).map { it.outboxId })
            .containsExactly("prepared")
        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).map { it.outboxId })
            .containsExactly("pending")
        assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.EMITTING, 10).map { it.outboxId })
            .containsExactly("emitting")
    }
    }

    @Test
    fun `listExpiredEmitting returns only expired EMITTING`() { runBlocking {
        val store = store()
        store.append(record("expired"))
        store.markReadyForDispatch("expired", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("old-dispatcher", 1, BASE_NOW.minus(Duration.ofMinutes(10)))
        store.append(record("active"))
        store.markReadyForDispatch("active", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("current-dispatcher", 1, BASE_NOW)

        val expired = store.listExpiredEmitting(BASE_NOW, 10)

        assertThat(expired.map { it.outboxId }).containsExactly("expired")
    }
    }

    @Test
    fun `markReadyForDispatch moves PREPARED to PENDING`() { runBlocking {
        val store = store()
        store.append(record("ready"))

        val updated = store.markReadyForDispatch("ready", SovereignOpsAuditOutboxStatus.PREPARED)

        assertThat(updated.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        assertThat(store.get("ready")?.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
    }
    }

    @Test
    fun `markReadyForDispatch rejects wrong expected status`() {
        val store = store()
        runBlocking {
            store.append(record("wrong-expected"))
            store.markReadyForDispatch("wrong-expected", SovereignOpsAuditOutboxStatus.PREPARED)
        }

        assertThatThrownBy {
            runBlocking {
                store.markReadyForDispatch("wrong-expected", SovereignOpsAuditOutboxStatus.PREPARED)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `markReadyForDispatch rejects non-PREPARED expectedStatus`() {
        val store = store()
        runBlocking {
            store.append(record("non-prepared-expected"))
        }

        assertThatThrownBy {
            runBlocking {
                store.markReadyForDispatch(
                    "non-prepared-expected",
                    SovereignOpsAuditOutboxStatus.PENDING,
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
    }

    @Test
    fun `claimPending claims PENDING records`() { runBlocking {
        val store = store()
        store.append(record("pending-claim"))
        store.markReadyForDispatch("pending-claim", SovereignOpsAuditOutboxStatus.PREPARED)

        val claimed = store.claimPending("dispatcher", 10, BASE_NOW)

        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
        assertThat(claimed.single().attemptCount).isEqualTo(1)
        assertThat(claimed.single().claimedBy).isEqualTo("dispatcher")
    }
    }

    @Test
    fun `claimPending claims FAILED_RETRYABLE records`() { runBlocking {
        val store = store()
        store.append(record("retryable-claim"))
        store.markReadyForDispatch("retryable-claim", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("dispatcher", 1, BASE_NOW)
        store.markFailed(
            "retryable-claim",
            SovereignOpsAuditOutboxStatus.EMITTING,
            1,
            "transient-error",
            retryable = true,
        )

        val claimed = store.claimPending("retry-dispatcher", 10, BASE_NOW.plusSeconds(1))

        assertThat(claimed.map { it.outboxId }).containsExactly("retryable-claim")
        assertThat(claimed.single().attemptCount).isEqualTo(2)
    }
    }

    @Test
    fun `claimPending reclaims expired EMITTING records`() { runBlocking {
        val store = store()
        store.append(record("expired-emitting"))
        store.markReadyForDispatch("expired-emitting", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("old-dispatcher", 1, BASE_NOW.minus(Duration.ofMinutes(10)))

        val claimed = store.claimPending("new-dispatcher", 10, BASE_NOW)

        assertThat(claimed.map { it.outboxId }).containsExactly("expired-emitting")
        assertThat(claimed.single().claimedBy).isEqualTo("new-dispatcher")
        assertThat(claimed.single().attemptCount).isEqualTo(2)
    }
    }

    @Test
    fun `claimPending never claims PREPARED records`() { runBlocking {
        val store = store()
        store.append(record("prepared-only"))

        assertThat(store.claimPending("dispatcher", 10, BASE_NOW)).isEmpty()
    }
    }

    @Test
    fun `claimPending never claims EMITTED records`() { runBlocking {
        val store = store()
        store.append(record("emitted"))
        store.markReadyForDispatch("emitted", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("dispatcher", 1, BASE_NOW)
        store.markEmitted("emitted", SovereignOpsAuditOutboxStatus.EMITTING, 1, BASE_NOW.plusSeconds(1))

        assertThat(store.claimPending("dispatcher", 10, BASE_NOW.plus(Duration.ofMinutes(10)))).isEmpty()
    }
    }

    @Test
    fun `claimPending never claims FAILED_PERMANENT records`() { runBlocking {
        val store = store()
        store.append(record("permanent"))
        store.markFailed(
            "permanent",
            SovereignOpsAuditOutboxStatus.PREPARED,
            0,
            "permanent-error",
            retryable = false,
        )

        assertThat(store.claimPending("dispatcher", 10, BASE_NOW)).isEmpty()
    }
    }

    @Test
    fun `markEmitted moves EMITTING to EMITTED`() { runBlocking {
        val store = store()
        store.append(record("emit"))
        store.markReadyForDispatch("emit", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("dispatcher", 1, BASE_NOW)

        val emitted = store.markEmitted("emit", SovereignOpsAuditOutboxStatus.EMITTING, 1, BASE_NOW.plusSeconds(1))

        assertThat(emitted.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        assertThat(emitted.emittedAt).isEqualTo(BASE_NOW.plusSeconds(1))
    }
    }

    @Test
    fun `markFailed retryable moves to FAILED_RETRYABLE`() { runBlocking {
        val store = store()
        store.append(record("retryable-failure"))
        store.markReadyForDispatch("retryable-failure", SovereignOpsAuditOutboxStatus.PREPARED)
        store.claimPending("dispatcher", 1, BASE_NOW)

        val failed = store.markFailed(
            "retryable-failure",
            SovereignOpsAuditOutboxStatus.EMITTING,
            1,
            "retryable-error",
            retryable = true,
        )

        assertThat(failed.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)
        assertThat(failed.lastErrorCode).isEqualTo("retryable-error")
    }
    }

    @Test
    fun `markFailed non-retryable moves PREPARED to FAILED_PERMANENT`() { runBlocking {
        val store = store()
        store.append(record("prepared-failure"))

        val failed = store.markFailed(
            "prepared-failure",
            SovereignOpsAuditOutboxStatus.PREPARED,
            0,
            "mutation-failed",
            retryable = false,
        )

        assertThat(failed.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
    }
    }

    @Test
    fun `status transitions survive restart`() { runBlocking {
        val key = testKey()
        val store = store(key = key)
        store.append(record("restart-status"))
        store.markReadyForDispatch("restart-status", SovereignOpsAuditOutboxStatus.PREPARED)

        val restarted = store(key = key)

        assertThat(restarted.get("restart-status")?.status)
            .isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
    }
    }

    @Test
    fun `event-key index survives restart`() { runBlocking {
        val key = testKey()
        val store = store(key = key)
        val record = record("restart-index", eventKey = "restart-index-event")
        store.append(record)

        val restarted = store(key = key)

        assertThat(restarted.findByEventKey("restart-index-event")).isEqualTo(record)
    }
    }

    @Test
    fun `no raw security fields persisted`() { runBlocking {
        val key = testKey()
        val store = store(key = key)
        val rawApprovalId = "approval-raw-secret-123"
        val rawReason = "deny because of sensitive operator detail"
        val rawToken = "approval-token-secret"
        val rawPrompt = "prompt with private user data"
        val secureRecord = record(
            outboxId = "secure",
            aggregateIdDigest = FileStoreSha256.digest("approval", rawApprovalId),
            reasonDigest = FileStoreSha256.digest("reason", rawReason),
        )

        store.append(secureRecord)

        val encrypted = Files.readString(recordPath("secure"))
        val decryptedJson = persisted("secure", key).toJson()
        for (raw in listOf(rawApprovalId, rawReason, rawToken, rawPrompt)) {
            assertThat(encrypted).doesNotContain(raw)
            assertThat(decryptedJson).doesNotContain(raw)
        }
        assertThat(decryptedJson).contains(secureRecord.aggregateIdDigest)
        assertThat(decryptedJson).contains(secureRecord.reasonDigest)
    }
    }

    @Test
    fun `outboxRecordVersion increments on each write`() { runBlocking {
        val key = testKey()
        val store = store(key = key)
        store.append(record("versioned"))
        assertThat(persisted("versioned", key).outboxRecordVersion).isEqualTo(0L)

        store.markReadyForDispatch("versioned", SovereignOpsAuditOutboxStatus.PREPARED)
        assertThat(persisted("versioned", key).outboxRecordVersion).isEqualTo(1L)

        store.claimPending("dispatcher", 1, BASE_NOW)
        assertThat(persisted("versioned", key).outboxRecordVersion).isEqualTo(2L)
    }
    }

    @Test
    fun `constructor rebuilds event key index from filesystem`() { runBlocking {
        val key = testKey()

        val first = FileSovereignOpsAuditOutboxStore(root = tempDir, key = key)
        try {
            first.append(record("one", eventKey = "same-event"))
        } finally {
            first.close()
        }

        val reopened = FileSovereignOpsAuditOutboxStore(root = tempDir, key = key)
        assertThat(reopened.findByEventKey("same-event")).isNotNull
    }
    }

    @Test
    fun `append rejects duplicate event key after constructor reopen`() { runBlocking {
        val key = testKey()

        val first = FileSovereignOpsAuditOutboxStore(root = tempDir, key = key)
        try {
            first.append(record("one", eventKey = "same-event"))
        } finally {
            first.close()
        }

        val reopened = FileSovereignOpsAuditOutboxStore(root = tempDir, key = key)

        assertThatThrownBy {
            runBlocking { reopened.append(record("two", eventKey = "same-event")) }
        }.hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-event-key")
    }
    }

    private fun store(
        key: SecretKey = testKey(),
        claimLeaseDuration: Duration = SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY,
    ): FileSovereignOpsAuditOutboxStore =
        FileSovereignOpsAuditOutboxStore(
            root = tempDir,
            key = key,
            claimLeaseDuration = claimLeaseDuration,
        )

    private fun record(
        outboxId: String,
        status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PREPARED,
        eventKey: String = "event-$outboxId",
        aggregateIdDigest: String = "a".repeat(64),
        reasonDigest: String = "b".repeat(64),
    ): SovereignOpsAuditOutboxRecord = SovereignOpsAuditOutboxRecord(
        outboxId = outboxId,
        aggregateType = "approval",
        aggregateIdDigest = aggregateIdDigest,
        operation = "denyApproval",
        eventKey = eventKey,
        actor = "operator-1",
        workflowRunId = "workflow-$outboxId",
        correlationId = "correlation-$outboxId",
        approvalStatus = "DENIED",
        approvalVersion = 7L,
        reasonDigest = reasonDigest,
        reasonLength = 42,
        createdAt = BASE_NOW,
        status = status,
    )

    private fun persisted(
        outboxId: String,
        key: SecretKey,
    ): PersistedSovereignOpsAuditOutboxRecordV1 {
        val digest = FileStoreSha256.digest(RECORD_TYPE, outboxId)
        val envelope = EncryptedFileEnvelopeV1.fromJson(Files.readString(recordPath(outboxId)))
        val plaintext = AesGcmFileEncryption.decrypt(
            key = key,
            envelope = envelope,
            expectedRecordType = RECORD_TYPE,
            expectedRecordKeyDigest = digest,
            expectedKeyId = KEY_ID,
        )
        return PersistedSovereignOpsAuditOutboxRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
    }

    private fun recordPath(outboxId: String): Path {
        val digest = FileStoreSha256.digest(RECORD_TYPE, outboxId)
        return tempDir.resolve("ops-audit-outbox").resolve("$digest.tram.enc")
    }

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private companion object {
        private const val RECORD_TYPE = "ops-audit-outbox"
        private const val KEY_ID = "default"
        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
