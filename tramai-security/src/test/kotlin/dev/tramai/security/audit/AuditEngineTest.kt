package dev.tramai.security.audit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

class AuditEngineTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    // ---------------------------------------------------------------
    // Helper: emit an event using the new AuditEngine signature
    // ---------------------------------------------------------------
    private suspend fun emitEvent(
        engine: AuditEngine,
        streamId: String,
        index: Int,
    ): AuditEvent = engine.emit(
        auditStreamId = streamId,
        workflowRunId = "workflow-$streamId",
        correlationId = "corr-$streamId",
        actor = "actor-$streamId",
        enforcementPoint = "policy-gate",
        decision = "ALLOW",
        policyVersion = "v1",
        workflowDigest = "digest-$streamId",
        reasonCode = "reason-$index",
        metadata = mapOf("index" to index.toString()),
    )

    // ---------------------------------------------------------------
    // Helper: build a bare AuditEvent for serialization tests
    // ---------------------------------------------------------------
    private fun baseEvent(
        eventId: String,
        metadata: Map<String, String>,
        timestamp: Instant = fixedClock.instant(),
        nullFields: Boolean = false,
    ): AuditEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "stream-1",
        eventId = eventId,
        sequenceNumber = 1L,
        workflowRunId = if (nullFields) null else "workflow-1",
        correlationId = if (nullFields) null else "corr-1",
        actor = if (nullFields) null else "actor-1",
        enforcementPoint = "policy-gate",
        decision = "ALLOW",
        policyVersion = if (nullFields) null else "v1",
        workflowDigest = if (nullFields) null else "digest-1",
        previousEventHash = null,
        eventHash = "",
        timestamp = timestamp,
        reasonCode = if (nullFields) null else "ok",
        metadata = metadata,
    )

    // ===============================================================
    //  1. First event has no previousHash
    // ===============================================================
    @Test
    fun `first event has no previousHash`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val event = engine.emit(
            auditStreamId = "stream-1",
            workflowRunId = "workflow-1",
            correlationId = "corr-1",
            actor = "user-1",
            enforcementPoint = "policy-gate",
            decision = "ALLOW",
            policyVersion = "v1",
            workflowDigest = "digest-1",
            reasonCode = "ok",
            metadata = mapOf("a" to "1"),
        )

        assertNull(event.previousEventHash)
        assertEquals(1L, event.sequenceNumber)
        assertNotNull(event.eventId)
        assertNotNull(event.eventHash)
        assertTrue(event.eventHash.isNotEmpty())
    }
    }

    // ===============================================================
    //  2. Second event links to first
    // ===============================================================
    @Test
    fun `second event links to first`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val first = emitEvent(engine, "stream-1", 1)
        val second = emitEvent(engine, "stream-1", 2)

        assertEquals(first.eventHash, second.previousEventHash)
        assertEquals(2L, second.sequenceNumber)
    }
    }

    // ===============================================================
    //  3. Multiple events verify successfully
    // ===============================================================
    @Test
    fun `multiple events verify successfully`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(5) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val result = AuditChainVerifier.verify(store.readStream("stream-1"))
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    }

    // ===============================================================
    //  4. Modified event field invalidates chain
    // ===============================================================
    @Test
    fun `modified event field invalidates chain`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").toMutableList()
        events[1] = events[1].copy(decision = "DENY")

        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.sequenceNumber == 2L && it.message.contains("eventHash") })
    }
    }

    // ===============================================================
    //  5. Modified metadata invalidates chain
    // ===============================================================
    @Test
    fun `modified metadata invalidates chain`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").toMutableList()
        events[1] = events[1].copy(metadata = events[1].metadata + ("extra" to "value"))

        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
    }
    }

    // ===============================================================
    //  6. Reordered events invalidate chain
    // ===============================================================
    @Test
    fun `reordered events invalidate chain`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val result = AuditChainVerifier.verify(store.readStream("stream-1").reversed())

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("sequenceNumber") })
    }
    }

    // ===============================================================
    //  7. Missing middle event invalidates chain
    // ===============================================================
    @Test
    fun `missing middle event invalidates chain`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(3) { index ->
            emitEvent(engine, "stream-1", index + 1)
        }

        val events = store.readStream("stream-1").filterIndexed { index, _ -> index != 1 }
        val result = AuditChainVerifier.verify(events)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("sequenceNumber") })
        assertTrue(result.errors.any { it.message.contains("previousEventHash") })
    }
    }

    // ===============================================================
    //  8. Concurrent 10 writes produce unique ordered sequence numbers
    // ===============================================================
    @Test
    fun `concurrent writes preserve unique ordered sequence numbers`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val deferredEvents = (1..10).map { index ->
            async {
                emitEvent(engine, "stream-1", index)
            }
        }
        deferredEvents.awaitAll()

        val sequenceNumbers = store.readStream("stream-1").map { it.sequenceNumber }
        assertEquals((1L..10L).toList(), sequenceNumbers)
    }
    }

    // ===============================================================
    //  9. Separate streams remain independent
    // ===============================================================
    @Test
    fun `separate streams remain independent`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        emitEvent(engine, "stream-a", 1)
        emitEvent(engine, "stream-b", 1)
        emitEvent(engine, "stream-a", 2)
        emitEvent(engine, "stream-c", 1)

        assertEquals(listOf(1L, 2L), store.readStream("stream-a").map { it.sequenceNumber })
        assertEquals(listOf(1L), store.readStream("stream-b").map { it.sequenceNumber })
        assertEquals(listOf(1L), store.readStream("stream-c").map { it.sequenceNumber })
    }
    }

    // ===============================================================
    //  10. Stable hashes with different map ordering
    // ===============================================================
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

    // ===============================================================
    //  11. Two engines sharing one store, concurrent
    // ===============================================================
    @Test
    fun `two engines share one store concurrent`() { runTest {
        val store = InMemoryAuditStore()
        val engine1 = AuditEngine(store, clock = fixedClock)
        val engine2 = AuditEngine(store, clock = fixedClock)

        val deferredA = (1..5).map { i ->
            async { engine1.emit(
                auditStreamId = "shared",
                workflowRunId = null, correlationId = null, actor = null,
                enforcementPoint = "gate", decision = "ALLOW",
                policyVersion = null, workflowDigest = null, reasonCode = null,
                metadata = mapOf("from" to "a", "seq" to i.toString()),
            ) }
        }
        val deferredB = (1..5).map { i ->
            async { engine2.emit(
                auditStreamId = "shared",
                workflowRunId = null, correlationId = null, actor = null,
                enforcementPoint = "gate", decision = "ALLOW",
                policyVersion = null, workflowDigest = null, reasonCode = null,
                metadata = mapOf("from" to "b", "seq" to i.toString()),
            ) }
        }

        (deferredA + deferredB).awaitAll()

        val events = store.readStream("shared")
        assertEquals(10, events.size)
        assertEquals((1L..10L).toList(), events.map { it.sequenceNumber })

        val result = AuditChainVerifier.verify(events)
        assertTrue(result.isValid)
    }
    }

    // ===============================================================
    //  12. Delayed store wrapper (CompletableDeferred gate)
    // ===============================================================
    @Test
    fun `delayed store wrapper`() { runTest {
        val gate = CompletableDeferred<Unit>()
        val inner = InMemoryAuditStore()
        val store = object : AuditStore {
            override suspend fun appendNext(auditStreamId: String, eventFactory: (AuditEvent?) -> AuditEvent): AuditEvent {
                gate.await()
                return inner.appendNext(auditStreamId, eventFactory)
            }
            override suspend fun readStream(auditStreamId: String): List<AuditEvent> = inner.readStream(auditStreamId)
            override suspend fun latestEvent(auditStreamId: String): AuditEvent? = inner.latestEvent(auditStreamId)
        }

        val engine = AuditEngine(store, clock = fixedClock)

        val deferred = async {
            engine.emit(
                auditStreamId = "delayed",
                workflowRunId = null, correlationId = null, actor = null,
                enforcementPoint = "gate", decision = "ALLOW",
                policyVersion = null, workflowDigest = null, reasonCode = null,
                metadata = emptyMap(),
            )
        }

        // Give the coroutine time to reach gate.await()
        kotlinx.coroutines.yield()
        assertTrue(inner.readStream("delayed").isEmpty())

        gate.complete(Unit)
        val event = deferred.await()
        assertNotNull(event)
        assertEquals(1L, event.sequenceNumber)
    }
    }

    // ===============================================================
    //  13. 100+ concurrent appends
    // ===============================================================
    @Test
    fun `one hundred concurrent appends`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        coroutineScope {
            val deferred = (1..100).map { i ->
                async {
                    engine.emit(
                        auditStreamId = "hundred",
                        workflowRunId = null, correlationId = null, actor = null,
                        enforcementPoint = "gate", decision = "ALLOW",
                        policyVersion = null, workflowDigest = null, reasonCode = null,
                        metadata = mapOf("i" to i.toString()),
                    )
                }
            }
            deferred.awaitAll()
        }

        val events = store.readStream("hundred")
        assertEquals(100, events.size)
        assertEquals((1L..100L).toList(), events.map { it.sequenceNumber })

        val result = AuditChainVerifier.verify(events)
        assertTrue(result.isValid)
    }
    }

    // ===============================================================
    //  14. Wrong streamId rejected by store
    // ===============================================================
    @Test
    fun `wrong streamId rejected`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-a") {
                AuditEvent(
                    schemaVersion = 1,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-b", // mismatched!
                    eventId = "e1",
                    sequenceNumber = 1L,
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = null,
                    eventHash = "",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                ).copy(eventHash = "placeholder")
            }
        }
        assertTrue(exception.message?.contains("auditStreamId") == true)
    }
    }

    // ===============================================================
    //  15. Wrong sequence rejected by store
    // ===============================================================
    @Test
    fun `wrong sequence rejected`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)
        emitEvent(engine, "stream-s", 1)

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-s") {
                AuditEvent(
                    schemaVersion = 1,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-s",
                    eventId = "e2",
                    sequenceNumber = 42L, // wrong!
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = it?.eventHash,
                    eventHash = "",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                ).copy(eventHash = "placeholder")
            }
        }
        assertTrue(exception.message?.contains("sequenceNumber") == true)
    }
    }

    // ===============================================================
    //  16. Wrong previousHash rejected by store
    // ===============================================================
    @Test
    fun `wrong previousHash rejected`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)
        emitEvent(engine, "stream-p", 1)

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-p") {
                AuditEvent(
                    schemaVersion = 1,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-p",
                    eventId = "e2",
                    sequenceNumber = 2L,
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = "wrong-hash",
                    eventHash = "",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                ).copy(eventHash = "placeholder")
            }
        }
        assertTrue(exception.message?.contains("previousEventHash") == true || exception.message?.contains("eventHash") == true)
    }
    }

    // ===============================================================
    //  17. Wrong eventHash rejected by store
    // ===============================================================
    @Test
    fun `wrong eventHash rejected`() { runTest {
        val store = InMemoryAuditStore()

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-h") {
                AuditEvent(
                    schemaVersion = 1,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-h",
                    eventId = "e1",
                    sequenceNumber = 1L,
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = null,
                    eventHash = "definitely-not-the-correct-hash",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                )
            }
        }
        assertTrue(exception.message?.contains("eventHash") == true)
    }
    }

    // ===============================================================
    //  18. Duplicate eventId rejected by store
    // ===============================================================
    @Test
    fun `duplicate eventId rejected`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)
        val firstEvent = emitEvent(engine, "stream-d", 1)
        val firstEventId = firstEvent.eventId
        val firstEventHash = firstEvent.eventHash

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-d") {
                // Use same eventId as first event
                AuditEvent(
                    schemaVersion = 1,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-d",
                    eventId = firstEventId, // duplicate!
                    sequenceNumber = 2L,
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = firstEventHash,
                    eventHash = "",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                ).copy(
                    eventHash = AuditEvent(
                        schemaVersion = 1,
                        hashAlgorithm = AuditHashAlgorithm.SHA_256,
                        auditStreamId = "stream-d",
                        eventId = firstEventId,
                        sequenceNumber = 2L,
                        workflowRunId = null, correlationId = null, actor = null,
                        enforcementPoint = "gate", decision = "ALLOW",
                        policyVersion = null, workflowDigest = null,
                        previousEventHash = firstEventHash,
                        eventHash = "",
                        timestamp = fixedClock.instant(),
                        reasonCode = null,
                        metadata = emptyMap(),
                    ).copy(eventHash = "").calculateHash(),
                )
            }
        }
        assertTrue(exception.message?.contains("Duplicate") == true || exception.message?.contains("eventId") == true)
    }
    }

    // ===============================================================
    //  19. Mutable metadata cannot alter evidence
    // ===============================================================
    @Test
    fun `mutable metadata cannot alter evidence`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val mutableMeta = mutableMapOf("key" to "original")
        val event = engine.emit(
            auditStreamId = "stream-m",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mutableMeta,
        )

        // Mutate the original map reference
        mutableMeta["key"] = "modified"

        val stored = store.readStream("stream-m").first()
        assertEquals("original", stored.metadata["key"])
        assertEquals(event.eventHash, stored.eventHash)

        // Verify hash still matches (metadata wasn't altered in storage)
        val result = AuditChainVerifier.verify(store.readStream("stream-m"))
        assertTrue(result.isValid)
    }
    }

    // ===============================================================
    //  20. Mixed-stream verification rejected
    // ===============================================================
    @Test
    fun `mixed-stream verification rejected`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        emitEvent(engine, "stream-x", 1)
        emitEvent(engine, "stream-y", 1)

        val mixed = store.readStream("stream-x") + store.readStream("stream-y")
        val result = AuditChainVerifier.verify(mixed)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("auditStreamId") })
    }
    }

    // ===============================================================
    //  21. Explicit null serialization is stable
    // ===============================================================
    @Test
    fun `explicit null serialization stable`() {
        val event = baseEvent(
            eventId = "null-test",
            metadata = emptyMap(),
            nullFields = true,
        )
        val json = event.toCanonicalJson()

        // All nullable fields should appear as explicit null
        assertTrue(json.contains("\"workflowRunId\":null"))
        assertTrue(json.contains("\"correlationId\":null"))
        assertTrue(json.contains("\"actor\":null"))
        assertTrue(json.contains("\"policyVersion\":null"))
        assertTrue(json.contains("\"workflowDigest\":null"))
        assertTrue(json.contains("\"previousEventHash\":null"))
        assertTrue(json.contains("\"reasonCode\":null"))

        // eventHash should be serialized even when empty
        assertTrue(json.contains("\"eventHash\":\"\""))

        // Verify hash stability
        val hash1 = event.calculateHash()
        val hash2 = event.copy(eventHash = "").calculateHash()
        assertEquals(hash1, hash2)
    }

    // ===============================================================
    //  22. Timestamp from injected Clock
    // ===============================================================
    @Test
    fun `timestamp from injected Clock`() { runTest {
        val customInstant = Instant.parse("2025-12-25T10:30:00Z")
        val customClock = Clock.fixed(customInstant, ZoneId.of("UTC"))
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = customClock)

        val event = engine.emit(
            auditStreamId = "clock-test",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = emptyMap(),
        )

        assertEquals(customInstant, event.timestamp)
    }
    }

    // ===============================================================
    //  Extra: empty list verification
    // ===============================================================
    @Test
    fun `empty list verification returns valid`() {
        val result = AuditChainVerifier.verify(emptyList())
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    // ===============================================================
    //  Extra: consistent schemaVersion check in verifier
    // ===============================================================
    @Test
    fun `inconsistent schemaVersion fails verification`() {
        val events = listOf(
            baseEvent(eventId = "e1", metadata = emptyMap()),
            baseEvent(eventId = "e2", metadata = emptyMap()).copy(schemaVersion = 2),
        )
        // Manually fix eventHash so it's valid
        val fixed = events.map { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }
        val result = AuditChainVerifier.verify(fixed)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("schemaVersion") })
    }

    // ===============================================================
    //  Extra: consistent hashAlgorithm does not fail verification
    // ===============================================================
    @Test
    fun `consistent hashAlgorithm passes verification`() {
        val events = listOf(
            baseEvent(eventId = "e1", metadata = emptyMap()),
            baseEvent(eventId = "e2", metadata = emptyMap()).copy(
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                sequenceNumber = 2L,
                previousEventHash = null,
            ),
        )
        // Both have same hashAlgorithm, hash recalc will catch different json
        val fixed = events.map { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }
        val result = AuditChainVerifier.verify(fixed)
        // Hash chain broken because previousEventHash is null
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("previousEventHash") })
    }

    // ===============================================================
    //  Extra: unique eventIds check in verifier
    // ===============================================================
    @Test
    fun `duplicate eventId fails verification`() {
        val events = listOf(
            baseEvent(eventId = "dup", metadata = emptyMap()),
            AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = "stream-1",
                eventId = "dup",
                sequenceNumber = 2L,
                workflowRunId = null, correlationId = null, actor = null,
                enforcementPoint = "gate", decision = "ALLOW",
                policyVersion = null, workflowDigest = null,
                previousEventHash = null,
                eventHash = "",
                timestamp = fixedClock.instant(),
                reasonCode = null,
                metadata = emptyMap(),
            ),
        )
        val fixed = events.map { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }
        val result = AuditChainVerifier.verify(fixed)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("Duplicate") || it.message.contains("eventId") })
    }

    // ===============================================================
    //  23. mutating original input metadata does not alter evidence
    // ===============================================================
    @Test
    fun `mutating original input metadata does not alter evidence`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val mutableMeta = HashMap<String, String>()
        mutableMeta["key"] = "value"
        engine.emit(
            auditStreamId = "stream-mm",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mutableMeta,
        )

        mutableMeta["key"] = "mutated"

        val stored = store.readStream("stream-mm").first()
        assertEquals("value", stored.metadata["key"])
    }
    }

    // ===============================================================
    //  24. metadata returned by appendNext cannot alter stored evidence
    // ===============================================================
    @Test
    fun `metadata returned by appendNext cannot alter stored evidence`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val returned = engine.emit(
            auditStreamId = "stream-mr",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mapOf("key" to "value"),
        )

        @Suppress("UNCHECKED_CAST")
        try {
            (returned.metadata as MutableMap<String, String>)["key"] = "hacked"
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot correctly prevents mutation
        }

        val stored = store.readStream("stream-mr").first()
        assertEquals("value", stored.metadata["key"])
    }
    }

    // ===============================================================
    //  25. metadata returned by readStream cannot alter stored evidence
    // ===============================================================
    @Test
    fun `metadata returned by readStream cannot alter stored evidence`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        engine.emit(
            auditStreamId = "stream-rs",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mapOf("key" to "value"),
        )

        val readEvents = store.readStream("stream-rs")
        @Suppress("UNCHECKED_CAST")
        try {
            (readEvents.first().metadata as MutableMap<String, String>)["key"] = "hacked"
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot correctly prevents mutation
        }

        val stored = store.readStream("stream-rs").first()
        assertEquals("value", stored.metadata["key"])
    }
    }

    // ===============================================================
    //  26. metadata returned by latestEvent cannot alter stored evidence
    // ===============================================================
    @Test
    fun `metadata returned by latestEvent cannot alter stored evidence`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        engine.emit(
            auditStreamId = "stream-le",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mapOf("key" to "value"),
        )

        val latest = store.latestEvent("stream-le")!!
        @Suppress("UNCHECKED_CAST")
        try {
            (latest.metadata as MutableMap<String, String>)["key"] = "hacked"
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot correctly prevents mutation
        }

        val stored = store.readStream("stream-le").first()
        assertEquals("value", stored.metadata["key"])
    }
    }

    // ===============================================================
    //  27. verifier still succeeds after attempted mutation
    // ===============================================================
    @Test
    fun `verifier still succeeds after attempted mutation`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        engine.emit(
            auditStreamId = "stream-vm",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mapOf("key" to "value"),
        )

        val returned = engine.emit(
            auditStreamId = "stream-vm",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mapOf("key" to "value2"),
        )

        @Suppress("UNCHECKED_CAST")
        try {
            (returned.metadata as MutableMap<String, String>)["key"] = "hacked"
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot correctly prevents mutation
        }

        val readEvents = store.readStream("stream-vm")
        @Suppress("UNCHECKED_CAST")
        try {
            (readEvents.last().metadata as MutableMap<String, String>)["key"] = "hacked2"
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot correctly prevents mutation
        }

        val result = AuditChainVerifier.verify(store.readStream("stream-vm"))
        assertTrue(result.isValid)
    }
    }

    // ===============================================================
    //  28. consistent schemaVersion 999 chain fails verification
    // ===============================================================
    @Test
    fun `consistent schemaVersion 999 chain fails verification`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        repeat(3) { index ->
            engine.emit(
                auditStreamId = "stream-sv",
                workflowRunId = null, correlationId = null, actor = null,
                enforcementPoint = "gate", decision = "ALLOW",
                policyVersion = null, workflowDigest = null, reasonCode = null,
                metadata = mapOf("i" to index.toString()),
            )
        }

        val tampered = store.readStream("stream-sv").map {
            val modified = it.copy(schemaVersion = 999)
            modified.copy(eventHash = modified.copy(eventHash = "").calculateHash())
        }

        val result = AuditChainVerifier.verify(tampered)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.contains("Unsupported schemaVersion") })
    }
    }

    // ===============================================================
    //  29. direct append with unsupported schemaVersion fails
    // ===============================================================
    @Test
    fun `direct append with unsupported schemaVersion fails`() { runTest {
        val store = InMemoryAuditStore()

        val exception = assertThrows<IllegalArgumentException> {
            store.appendNext("stream-bad") {
                AuditEvent(
                    schemaVersion = 999,
                    hashAlgorithm = AuditHashAlgorithm.SHA_256,
                    auditStreamId = "stream-bad",
                    eventId = "e1",
                    sequenceNumber = 1L,
                    workflowRunId = null, correlationId = null, actor = null,
                    enforcementPoint = "gate", decision = "ALLOW",
                    policyVersion = null, workflowDigest = null,
                    previousEventHash = null,
                    eventHash = "",
                    timestamp = fixedClock.instant(),
                    reasonCode = null,
                    metadata = emptyMap(),
                ).let { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }
            }
        }
        assertTrue(exception.message?.contains("Unsupported") == true)
    }
    }

    // ===============================================================
    //  30. 100 concurrent emits with barrier and two engines
    // ===============================================================
    @Test
    fun `100 concurrent emits with barrier and two engines`() { runTest {
        val store = InMemoryAuditStore()
        val engine1 = AuditEngine(store, clock = fixedClock)
        val engine2 = AuditEngine(store, clock = fixedClock)
        val total = 100

        val readyCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val startGate = CompletableDeferred<Unit>()

        val deferred = (1..total).map { i ->
            async(Dispatchers.Default) {
                readyCounter.incrementAndGet()
                if (readyCounter.get() == total) startGate.complete(Unit)
                startGate.await()
                val engine = if (i % 2 == 0) engine1 else engine2
                engine.emit(
                    auditStreamId = "stream-100",
                    workflowRunId = "wf-100",
                    correlationId = "corr-100",
                    actor = "actor-$i",
                    enforcementPoint = "gate",
                    decision = "ALLOW",
                    policyVersion = "v1",
                    workflowDigest = "digest-100",
                    reasonCode = "reason-$i",
                    metadata = mapOf("i" to i.toString()),
                )
            }
        }

        deferred.awaitAll()
        val events = store.readStream("stream-100")
        assertEquals(total.toLong(), events.size.toLong())
        assertEquals((1L..total.toLong()).toList(), events.map { it.sequenceNumber })
        assertEquals(total, events.map { it.eventId }.distinct().size)
        assertTrue(AuditChainVerifier.verify(events).isValid)
    }
    }

    // ===============================================================
    //  35. Deterministic regression: mutable HashMap metadata immutability
    // ===============================================================
    @Test
    fun `mutable HashMap metadata cannot alter stored evidence after emit`() { runTest {
        val store = InMemoryAuditStore()
        val engine = AuditEngine(store, clock = fixedClock)

        val mutableMeta = HashMap<String, String>()
        mutableMeta["key"] = "original"
        val event = engine.emit(
            auditStreamId = "stream-35",
            workflowRunId = null, correlationId = null, actor = null,
            enforcementPoint = "gate", decision = "ALLOW",
            policyVersion = null, workflowDigest = null, reasonCode = null,
            metadata = mutableMeta,
        )

        // Mutate the original map after emit
        mutableMeta["key"] = "modified"
        mutableMeta["extra"] = "injected"

        val stored = store.readStream("stream-35").first()
        assertEquals("original", stored.metadata["key"])
        assertNull(stored.metadata["extra"])
        assertEquals(event.eventHash, stored.eventHash)

        // Verify the chain is still valid
        val result = AuditChainVerifier.verify(store.readStream("stream-35"))
        assertTrue(result.isValid)
    }
    }

    // ===============================================================
    //  36. Emoji in metadata produces stable canonical JSON
    // ===============================================================
    @Test
    fun `emoji in metadata produces stable canonical json`() {
        val event = baseEvent(
            eventId = "emoji-test",
            metadata = mapOf("emoji" to "\uD83D\uDE80"), // 🚀
        ).let { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }

        val json1 = event.toCanonicalJson()
        val json2 = event.toCanonicalJson()
        assertEquals(json1, json2)

        val hash1 = event.calculateHash()
        val hash2 = event.copy(eventHash = "").calculateHash()
        assertEquals(hash1, hash2)
    }

    // ===============================================================
    //  37. Lone high surrogate in metadata escapes correctly
    // ===============================================================
    @Test
    fun `lone high surrogate in metadata escapes correctly`() {
        val event = baseEvent(
            eventId = "high-surrogate",
            metadata = mapOf("surrogate" to "\uD800"),
        )
        val json = event.toCanonicalJson()
        assertTrue(json.contains("\\ud800"), "JSON should contain \\\\ud800 escape: $json")
    }

    // ===============================================================
    //  38. Lone low surrogate in metadata escapes correctly
    // ===============================================================
    @Test
    fun `lone low surrogate in metadata escapes correctly`() {
        val event = baseEvent(
            eventId = "low-surrogate",
            metadata = mapOf("surrogate" to "\uDFFF"),
        )
        val json = event.toCanonicalJson()
        assertTrue(json.contains("\\udfff"), "JSON should contain \\\\udfff escape: $json")
    }

    // ===============================================================
    //  39. Distinct hashes for malformed surrogate vs question mark
    // ===============================================================
    @Test
    fun `distinct hashes for malformed surrogate vs question mark`() {
        val event1 = baseEvent(
            eventId = "surrogate-event",
            metadata = mapOf("val" to "\uD800x"),
        ).let { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }
        val event2 = baseEvent(
            eventId = "question-event",
            metadata = mapOf("val" to "?x"),
        ).let { it.copy(eventHash = it.copy(eventHash = "").calculateHash()) }

        assertNotEquals(event1.toCanonicalJson(), event2.toCanonicalJson())
        assertNotEquals(event1.eventHash, event2.eventHash)
    }
}
