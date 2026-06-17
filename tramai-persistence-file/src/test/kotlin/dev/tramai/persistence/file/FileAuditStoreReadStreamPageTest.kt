package dev.tramai.persistence.file

import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileAuditStoreReadStreamPageTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-audit-page-test-").toAbsolutePath()
    private val now: Instant = Instant.parse("2025-06-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.of("UTC"))

    private fun testKey() =
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

    private var eventCounter = 0L
    private fun nextEventId(): String = "evt-${++eventCounter}"

    private fun eventFactory(
        auditStreamId: String,
        eventId: String,
        decision: String = "APPROVED",
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
            actor = "test-actor",
            enforcementPoint = "test-gate",
            decision = decision,
            policyVersion = "v1",
            workflowDigest = "sha256:0001",
            previousEventHash = latest?.eventHash,
            eventHash = "",
            timestamp = now.plusSeconds(seq),
            reasonCode = null,
            metadata = emptyMap(),
        )
        raw.copy(eventHash = raw.copy(eventHash = "").calculateHash())
    }

    @BeforeEach
    fun setup() {
        Files.createDirectories(rootDir.resolve("audit"))
        Files.setPosixFilePermissions(
            rootDir.resolve("audit"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    private fun populateStream(store: FileAuditStore, streamId: String, count: Int) =
        runBlocking {
            repeat(count) { i ->
                store.appendNext(streamId, eventFactory(streamId, nextEventId(), decision = "EVT-$i"))
            }
        }

    @Test
    fun `readStreamPage returns first page when cursor is null`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-first", 10)

        val page = store.readStreamPage("page-first", afterSequenceNumber = null, limit = 5)

        assertEquals(5, page.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), page.map { it.sequenceNumber })
    }

    @Test
    fun `readStreamPage returns events after cursor`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-after", 10)

        val page = store.readStreamPage("page-after", afterSequenceNumber = 5L, limit = 5)

        assertEquals(5, page.size)
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), page.map { it.sequenceNumber })
    }

    @Test
    fun `readStreamPage respects limit`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-limit", 50)

        val page = store.readStreamPage("page-limit", afterSequenceNumber = null, limit = 7)

        assertEquals(7, page.size)
    }

    @Test
    fun `readStreamPage returns empty list after last event`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-end", 5)

        val page = store.readStreamPage("page-end", afterSequenceNumber = 5L, limit = 10)

        assertTrue(page.isEmpty())
    }

    @Test
    fun `readStreamPage returns empty list for unknown stream`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())

        val page = store.readStreamPage("unknown-stream", afterSequenceNumber = null, limit = 10)

        assertTrue(page.isEmpty())
    }

    @Test
    fun `readStreamPage results consistent with readStream`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-consistent", 20)

        val full = store.readStream("page-consistent")
        val page = store.readStreamPage("page-consistent", afterSequenceNumber = 5L, limit = 10)

        assertEquals(full.drop(5).take(10).map { it.sequenceNumber }, page.map { it.sequenceNumber })
        assertEquals(full.drop(5).take(10).map { it.eventId }, page.map { it.eventId })
    }

    @Test
    fun `readStreamPage returns valid event hashes`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-hash", 3)

        val page = store.readStreamPage("page-hash", afterSequenceNumber = null, limit = 3)

        assertEquals(3, page.size)
        for (event in page) {
            assertEquals(event.eventHash, event.copy(eventHash = "").calculateHash())
        }
    }

    @Test
    fun `readStreamPage rejects zero limit`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            store.readStreamPage("test-stream", afterSequenceNumber = null, limit = 0)
        }
        assertTrue(ex.message?.contains("invalid-limit") == true)
    }

    @Test
    fun `readStreamPage rejects negative limit`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            store.readStreamPage("test-stream", afterSequenceNumber = null, limit = -1)
        }
        assertTrue(ex.message?.contains("invalid-limit") == true)
    }

    @Test
    fun `readStreamPage rejects negative cursor`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            store.readStreamPage("test-stream", afterSequenceNumber = -1L, limit = 5)
        }
        assertTrue(ex.message?.contains("invalid-cursor") == true)
    }

    @Test
    fun `readStreamPage stops after limit even with more events`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-stop", 100)

        val page = store.readStreamPage("page-stop", afterSequenceNumber = null, limit = 3)

        assertEquals(3, page.size)
        assertEquals(listOf(1L, 2L, 3L), page.map { it.sequenceNumber })
    }

    @Test
    fun `separate streams have independent pagination`() = runBlocking {
        val store = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store, "page-indep-a", 3)
        populateStream(store, "page-indep-b", 5)

        val pageA = store.readStreamPage("page-indep-a", afterSequenceNumber = null, limit = 10)
        assertEquals(3, pageA.size)

        val pageB = store.readStreamPage("page-indep-b", afterSequenceNumber = 2L, limit = 10)
        assertEquals(3, pageB.size)
        assertEquals(listOf(3L, 4L, 5L), pageB.map { it.sequenceNumber })
    }

    @Test
    fun `readStreamPage survives reopen`() = runBlocking {
        val store1 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        populateStream(store1, "page-reopen", 10)

        val store2 = FileAuditStore(rootDir, testKey, createConfig(), FileStoreLease())
        val page1 = store2.readStreamPage("page-reopen", afterSequenceNumber = null, limit = 4)
        assertEquals(4, page1.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), page1.map { it.sequenceNumber })

        val page2 = store2.readStreamPage("page-reopen", afterSequenceNumber = 4L, limit = 4)
        assertEquals(4, page2.size)
        assertEquals(listOf(5L, 6L, 7L, 8L), page2.map { it.sequenceNumber })

        val page3 = store2.readStreamPage("page-reopen", afterSequenceNumber = 10L, limit = 10)
        assertTrue(page3.isEmpty())
    }
}
