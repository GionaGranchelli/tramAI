package dev.tramai.persistence.file

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileAuditStoreTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-audit-test-").toAbsolutePath()
    private val now: Instant = Instant.parse("2025-06-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.of("UTC"))

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val testKey = testKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }

    private fun createConfig() = FileBackedStoreConfiguration(
        rootDirectory = rootDir,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    /** Creates a unique event ID with timestamp-based suffix for test isolation. */
    private var eventCounter = 0L
    private fun nextEventId(): String = "evt-${++eventCounter}"

    /**
     * Builds an audit event factory for appendNext.
     * The factory computes the hash if needed and handles sequence/previousEventHash.
     */
    private fun eventFactory(
        auditStreamId: String,
        eventId: String,
        decision: String = "APPROVED",
        enforcementPoint: String = "test-gate",
        actor: String? = "test-actor",
        metadata: Map<String, String> = emptyMap(),
    ): (AuditEvent?) -> AuditEvent = { latest ->
        val seq = (latest?.sequenceNumber ?: 0L) + 1L
        val raw = AuditEvent(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = auditStreamId,
            eventId = eventId,
            sequenceNumber = seq,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            actor = actor,
            enforcementPoint = enforcementPoint,
            decision = decision,
            policyVersion = "v1",
            workflowDigest = "sha256:0001",
            previousEventHash = latest?.eventHash,
            eventHash = "",  // Will be computed below
            timestamp = now.plusSeconds(seq),
            reasonCode = null,
            metadata = metadata,
        )
        // Compute the correct hash
        raw.copy(eventHash = raw.copy(eventHash = "").calculateHash())
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `append and reopen`() = runBlocking {
        val store1 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-append-1"

        val event1 = store1.appendNext(streamId, eventFactory(streamId, nextEventId()))
        assertEquals(1L, event1.sequenceNumber)
        assertTrue(event1.eventHash.isNotBlank())

        val event2 = store1.appendNext(streamId, eventFactory(streamId, nextEventId()))
        assertEquals(2L, event2.sequenceNumber)
        assertEquals(event1.eventHash, event2.previousEventHash)

        // Reopen
        val store2 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val stream = store2.readStream(streamId)
        assertEquals(2, stream.size)
        assertEquals(event1.eventId, stream[0].eventId)
        assertEquals(event2.eventId, stream[1].eventId)
        assertEquals(event1.eventHash, stream[0].eventHash)
        assertEquals(event2.eventHash, stream[1].eventHash)
    }

    @Test
    fun `valid chain survives reopen`() = runBlocking {
        val store1 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-chain-1"

        val e1 = store1.appendNext(streamId, eventFactory(streamId, nextEventId()))
        val e2 = store1.appendNext(streamId, eventFactory(streamId, nextEventId()))
        val e3 = store1.appendNext(streamId, eventFactory(streamId, nextEventId()))

        // Reopen and read - should validate entire chain
        val store2 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val stream = store2.readStream(streamId)
        assertEquals(3, stream.size)

        for (i in 1 until stream.size) {
            assertEquals(stream[i - 1].eventHash, stream[i].previousEventHash)
            assertEquals(stream[i - 1].sequenceNumber + 1, stream[i].sequenceNumber)
        }
    }

    @Test
    fun `concurrent append preserves contiguous sequence`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-concurrent-1"

        // Append first event to create the stream
        store.appendNext(streamId, eventFactory(streamId, nextEventId(), decision = "FIRST"))

        // Launch 10 concurrent appends
        coroutineScope {
            val tasks = (1..10).map { i ->
                async {
                    store.appendNext(
                        streamId,
                        eventFactory(streamId, nextEventId(), decision = "EVT-$i"),
                    )
                }
            }
            tasks.map { it.await() }
        }

        val stream = store.readStream(streamId)
        assertEquals(11, stream.size)

        // Verify contiguous sequencing
        for (i in 1 until stream.size) {
            assertEquals(
                stream[i - 1].sequenceNumber + 1,
                stream[i].sequenceNumber,
                "Sequence gap at index $i",
            )
        }

        // Verify hash chain
        for (i in 1 until stream.size) {
            assertEquals(
                stream[i - 1].eventHash,
                stream[i].previousEventHash,
                "Hash chain broken at index $i",
            )
        }
    }

    @Test
    fun `duplicate event ID rejected`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-dup-1"

        val eventId = nextEventId()
        store.appendNext(streamId, eventFactory(streamId, eventId))

        // Try to reuse same eventId
        assertThrows<IllegalArgumentException> {
            store.appendNext(streamId, eventFactory(streamId, eventId))
        }
    }

    @Test
    fun `missing middle event rejected`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-missing-1"

        val e1 = store.appendNext(streamId, eventFactory(streamId, nextEventId()))
        val e2 = store.appendNext(streamId, eventFactory(streamId, nextEventId()))
        val e3 = store.appendNext(streamId, eventFactory(streamId, nextEventId()))

        // Manually delete the middle file to simulate a missing event
        val digest = FileStoreSha256.digest("audit-stream", streamId)
        val streamDir = rootDir.resolve("audit/$digest")
        val files = streamDir.toFile().listFiles()!!
            .filter { it.name.endsWith(".tram.enc") }
            .sortedBy { it.name }

        // Middle file is index 1 (0-indexed)
        val deleted = files[1].delete()
        assertTrue(deleted, "Middle file should be deleted")

        // Reopening and reading should fail validation due to missing event
        assertThrows<IllegalArgumentException> {
            store.readStream(streamId)
        }
    }

    @Test
    fun `cross-stream substitution rejected`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamA = "stream-cross-A"
        val streamB = "stream-cross-B"

        store.appendNext(streamA, eventFactory(streamA, nextEventId()))
        store.appendNext(streamB, eventFactory(streamB, nextEventId()))

        // Read each stream - should work fine
        val streamAevents = store.readStream(streamA)
        val streamBevents = store.readStream(streamB)
        assertEquals(1, streamAevents.size)
        assertEquals(1, streamBevents.size)

        // Cross-stream substitution: try to graft an event with wrong auditStreamId
        // The store's append validation checks auditStreamId match, so the factory
        // would need to produce an event with a different stream ID
        assertThrows<IllegalArgumentException> {
            val eventId = nextEventId()
            val factory: (AuditEvent?) -> AuditEvent = { latest ->
                val seq = (latest?.sequenceNumber ?: 0L) + 1L
                val raw = AuditEvent(
                    schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "STREAM-A".lowercase() + "-fake",  // wrong stream
                    eventId = eventId,
                    sequenceNumber = seq,
                    workflowRunId = "wf-1",
                    correlationId = "corr-1",
                    actor = "attacker",
                    enforcementPoint = "test",
                    decision = "EVIL",
                    policyVersion = "v1",
                    workflowDigest = "sha256:0001",
                    previousEventHash = latest?.eventHash,
                    eventHash = "",
                    timestamp = now,
                    reasonCode = null,
                    metadata = emptyMap(),
                )
                raw.copy(eventHash = raw.copy(eventHash = "").calculateHash())
            }
            store.appendNext(streamA, factory)
        }
    }

    @Test
    fun `ciphertext tampering rejected`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val streamId = "stream-tamper-1"

        store.appendNext(streamId, eventFactory(streamId, nextEventId()))

        // Find the encrypted file and corrupt it
        val digest = FileStoreSha256.digest("audit-stream", streamId)
        val streamDir = rootDir.resolve("audit/$digest")
        val file = streamDir.toFile().listFiles()!!.first { it.name.endsWith(".tram.enc") }

        // Read the envelope JSON, corrupt the ciphertext
        val originalJson = file.readText()
        val envelope = EncryptedFileEnvelopeV1.fromJson(originalJson)
        val corruptedCiphertext = envelope.ciphertextBase64.dropLast(1) + "X"  // corrupt base64
        val corruptedEnvelope = envelope.copy(ciphertextBase64 = corruptedCiphertext)
        file.writeText(corruptedEnvelope.toJson())

        // Reading the stream should fail with corruption
        assertThrows<RuntimeException> {
            store.readStream(streamId)
        }
    }
}
