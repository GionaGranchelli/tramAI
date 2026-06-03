package dev.tramai.security.audit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditEngineTest {

    @Test
    fun `first event has no previousHash`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        val event = engine.emit(
            auditStreamId = "stream-1",
            eventId = UUID.randomUUID().toString(),
            workflowRunId = "workflow-1",
            correlationId = "corr-1",
            actor = "user-1",
            enforcementPoint = "policy-gate",
            decision = "ALLOW",
            policyVersion = "v1",
            workflowDigest = "digest-1",
            reasonCode = "ok",
            metadata = mapOf("a" to "1"),
            timestamp = "2026-06-01T12:00:00Z",
        )

        assertNull(event.previousEventHash)
    }

    @Test
    fun `second event links to first`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        val first = emitEvent(engine, "stream-1", 1)
        val second = emitEvent(engine, "stream-1", 2)

        assertEquals(first.eventHash, second.previousEventHash)
    }

    @Test
    fun `multiple events verify successfully`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        repeat(5) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val result = AuditChainVerifier.verify(store.readStream("stream-1"))

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `modified event field invalidates chain`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").toMutableList()
        events[1] = events[1].copy(decision = "DENY")

        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.sequenceNumber == 2L && it.message.contains("eventHash") })
    }

    @Test
    fun `modified metadata invalidates chain`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").toMutableList()
        events[1] = events[1].copy(metadata = events[1].metadata + ("extra" to "value"))

        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
    }

    @Test
    fun `reordered events invalidate chain`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val result = AuditChainVerifier.verify(store.readStream("stream-1").reversed())

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("sequenceNumber") })
    }

    @Test
    fun `missing middle event invalidates chain`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").filterIndexed { index, _ -> index != 1 }
        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("sequenceNumber") })
        assertTrue(result.errors.any { it.message.contains("previousEventHash") })
    }

    @Test
    fun `concurrent writes preserve unique ordered sequence numbers`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        val deferredEvents = (1..10).map { index ->
            async {
                emitEvent(engine, "stream-1", index)
            }
        }
        deferredEvents.awaitAll()

        val sequenceNumbers = store.readStream("stream-1").map { it.sequenceNumber }

        assertEquals((1L..10L).toList(), sequenceNumbers)
    }

    @Test
    fun `separate streams remain independent`() = runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store)

        emitEvent(engine, "stream-a", 1)
        emitEvent(engine, "stream-b", 1)
        emitEvent(engine, "stream-a", 2)
        emitEvent(engine, "stream-c", 1)

        assertEquals(listOf(1L, 2L), store.readStream("stream-a").map { it.sequenceNumber })
        assertEquals(listOf(1L), store.readStream("stream-b").map { it.sequenceNumber })
        assertEquals(listOf(1L), store.readStream("stream-c").map { it.sequenceNumber })
    }

    @Test
    fun `canonical map ordering produces stable hashes`() {
        val first = baseEvent(
            eventId = "event-1",
            metadata = linkedMapOf("b" to "2", "a" to "1"),
        )
        val second = baseEvent(
            eventId = "event-1",
            metadata = linkedMapOf("a" to "1", "b" to "2"),
        )

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson())
        assertEquals(first.calculateHash(), second.calculateHash())
    }

    private suspend fun emitEvent(engine: AuditEngine, streamId: String, index: Int): AuditEvent =
        engine.emit(
            auditStreamId = streamId,
            eventId = "event-$index-${UUID.randomUUID()}",
            workflowRunId = "workflow-$streamId",
            correlationId = "corr-$streamId",
            actor = "actor-$streamId",
            enforcementPoint = "policy-gate",
            decision = "ALLOW",
            policyVersion = "v1",
            workflowDigest = "digest-$streamId",
            reasonCode = "reason-$index",
            metadata = mapOf("index" to index.toString()),
            timestamp = "2026-06-01T12:00:0${index.coerceAtMost(9)}Z",
        )

    private fun baseEvent(
        eventId: String,
        metadata: Map<String, String>,
    ): AuditEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = "SHA-256",
        auditStreamId = "stream-1",
        eventId = eventId,
        sequenceNumber = 1L,
        workflowRunId = "workflow-1",
        correlationId = "corr-1",
        actor = "actor-1",
        enforcementPoint = "policy-gate",
        decision = "ALLOW",
        policyVersion = "v1",
        workflowDigest = "digest-1",
        previousEventHash = null,
        eventHash = "",
        timestamp = "2026-06-01T12:00:00Z",
        reasonCode = "ok",
        metadata = metadata,
    )
}
