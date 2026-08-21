package dev.tramai.security.audit

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryAuditStoreReadStreamPageTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    private fun createStoreWithEvents(count: Int): InMemoryAuditStore {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = clock)
        repeat(count) { i ->
            runTest {
                engine.emit(
                    auditStreamId = "test-stream",
                    workflowRunId = "wf-1",
                    correlationId = "corr-1",
                    actor = "tester",
                    enforcementPoint = "gate",
                    decision = "ALLOW",
                    policyVersion = "v1",
                    workflowDigest = "digest-1",
                    reasonCode = "reason-$i",
                    metadata = emptyMap(),
                )
            }
        }
        return store
    }

    @Test
    fun `readStreamPage returns first page when cursor is null`() { runTest {
        val store = createStoreWithEvents(10)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = null, limit = 5)

        assertEquals(5, page.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), page.map { it.sequenceNumber })
    }
    }

    @Test
    fun `readStreamPage returns events after cursor`() { runTest {
        val store = createStoreWithEvents(10)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = 5L, limit = 5)

        assertEquals(5, page.size)
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), page.map { it.sequenceNumber })
    }
    }

    @Test
    fun `readStreamPage respects limit`() { runTest {
        val store = createStoreWithEvents(100)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = null, limit = 10)

        assertEquals(10, page.size)
    }
    }

    @Test
    fun `readStreamPage returns empty list after last event`() { runTest {
        val store = createStoreWithEvents(5)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = 5L, limit = 10)

        assertTrue(page.isEmpty())
    }
    }

    @Test
    fun `readStreamPage returns empty list for unknown stream`() { runTest {
        val store = createStoreWithEvents(5)
        val page = store.readStreamPage("unknown-stream", afterSequenceNumber = null, limit = 10)

        assertTrue(page.isEmpty())
    }
    }

    @Test
    fun `readStreamPage rejects zero limit`() {
        val store = InMemoryAuditStore()
        assertThrows<IllegalArgumentException> {
            runTest {
                store.readStreamPage("test-stream", afterSequenceNumber = null, limit = 0)
            }
        }
    }

    @Test
    fun `readStreamPage rejects negative limit`() {
        val store = InMemoryAuditStore()
        assertThrows<IllegalArgumentException> {
            runTest {
                store.readStreamPage("test-stream", afterSequenceNumber = null, limit = -1)
            }
        }
    }

    @Test
    fun `readStreamPage rejects negative cursor`() {
        val store = InMemoryAuditStore()
        assertThrows<IllegalArgumentException> {
            runTest {
                store.readStreamPage("test-stream", afterSequenceNumber = -1L, limit = 5)
            }
        }
    }

    @Test
    fun `readStreamPage with cursor returns partial page at end`() { runTest {
        val store = createStoreWithEvents(10)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = 8L, limit = 5)

        assertEquals(2, page.size)
        assertEquals(listOf(9L, 10L), page.map { it.sequenceNumber })
    }
    }

    @Test
    fun `readStreamPage returns valid event hashes`() { runTest {
        val store = createStoreWithEvents(3)
        val page = store.readStreamPage("test-stream", afterSequenceNumber = null, limit = 3)

        assertEquals(3, page.size)
        for (event in page) {
            assertEquals(event.eventHash, event.copy(eventHash = "").calculateHash())
        }
    }
    }

    @Test
    fun `readStreamPage results are consistent with readStream`() { runTest {
        val store = createStoreWithEvents(20)
        val full = store.readStream("test-stream")
        val page = store.readStreamPage("test-stream", afterSequenceNumber = 5L, limit = 10)

        assertEquals(full.drop(5).take(10).map { it.sequenceNumber }, page.map { it.sequenceNumber })
        assertEquals(full.drop(5).take(10).map { it.eventId }, page.map { it.eventId })
    }
    }

    @Test
    fun `readStream returns latestEvent unchanged`() { runTest {
        val store = createStoreWithEvents(5)
        val latest = store.latestEvent("test-stream")
        assertEquals(5L, latest?.sequenceNumber)
    }
    }

    @Test
    fun `latestEvent returns null for empty stream`() { runTest {
        val store = InMemoryAuditStore()
        val latest = store.latestEvent("empty-stream")
        assertEquals(null, latest)
    }
    }

    @Test
    fun `separate streams have independent pagination`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = clock)

        repeat(3) { engine.emit(
            auditStreamId = "stream-a",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = emptyMap(),
        ) }
        repeat(5) { engine.emit(
            auditStreamId = "stream-b",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = emptyMap(),
        ) }

        val pageA = store.readStreamPage("stream-a", afterSequenceNumber = null, limit = 10)
        assertEquals(3, pageA.size)

        val pageB = store.readStreamPage("stream-b", afterSequenceNumber = 2L, limit = 10)
        assertEquals(3, pageB.size)
        assertEquals(listOf(3L, 4L, 5L), pageB.map { it.sequenceNumber })
    }
    }
}
