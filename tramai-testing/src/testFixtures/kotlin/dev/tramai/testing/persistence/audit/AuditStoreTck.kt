package dev.tramai.testing.persistence.audit

import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1d: the shared, cross-implementation [AuditStore] compatibility
 * contract. Every concrete store — the engine's in-memory default, the
 * encrypted file store, and JDBC — must obey the same externally observable
 * append, hash-chain, pagination, isolation, validation, failure, and
 * concurrency semantics regardless of whether storage is memory, encrypted
 * files, or PostgreSQL.
 *
 * Out of scope (implementation-specific, covered by each store's own tests):
 * restart durability, encryption format, file permissions, corruption
 * handling, SQL schema internals, indexes, and query strategy.
 *
 * Concurrency uses real parallel workers with a start barrier and a deferred
 * release — no sleeps, no `Instant.now` (timestamps come from the fixed
 * fixture clock).
 */
abstract class AuditStoreTck {

    /** Fresh isolated storage per case; the runner owns setup/teardown. */
    protected abstract fun createStore(): AuditStore

    private fun appendValid(
        store: AuditStore,
        streamId: String,
        eventId: String,
        metadata: Map<String, String> = emptyMap(),
    ): AuditEvent = runBlocking {
        store.appendNext(streamId, AuditStoreFixtures.factory(streamId, eventId, metadata = metadata))
    }

    // ── Append / chain semantics ────────────────────────────────────

    @Test
    fun `first append receives latest null`() = runBlocking<Unit> {
        val store = createStore()
        var observed: AuditEvent? = AuditEvent(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = "sentinel",
            eventId = "sentinel",
            sequenceNumber = 99,
            workflowRunId = null,
            correlationId = null,
            actor = null,
            enforcementPoint = "sentinel",
            decision = "sentinel",
            policyVersion = null,
            workflowDigest = null,
            previousEventHash = null,
            eventHash = "sentinel",
            timestamp = AuditStoreFixtures.BASE_TIME,
            reasonCode = null,
        )
        store.appendNext("stream-a", { latest ->
            observed = latest
            AuditStoreFixtures.event("stream-a", "evt-1", latest)
        })
        assertThat(observed).isNull()
    }

    @Test
    fun `first event has sequence 1`() = runBlocking<Unit> {
        val store = createStore()
        val first = appendValid(store, "stream-a", "evt-1")
        assertThat(first.sequenceNumber).isEqualTo(1L)
    }

    @Test
    fun `first event previousEventHash is null`() = runBlocking<Unit> {
        val store = createStore()
        val first = appendValid(store, "stream-a", "evt-1")
        assertThat(first.previousEventHash).isNull()
    }

    @Test
    fun `second append factory receives the exact latest event`() = runBlocking<Unit> {
        val store = createStore()
        val first = appendValid(store, "stream-a", "evt-1")
        var observed: AuditEvent? = null
        store.appendNext("stream-a", { latest ->
            observed = latest
            AuditStoreFixtures.event("stream-a", "evt-2", latest)
        })
        assertThat(observed).isEqualTo(first)
    }

    @Test
    fun `second event has sequence 2`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-1")
        val second = appendValid(store, "stream-a", "evt-2")
        assertThat(second.sequenceNumber).isEqualTo(2L)
    }

    @Test
    fun `second event previousEventHash equals first eventHash`() = runBlocking<Unit> {
        val store = createStore()
        val first = appendValid(store, "stream-a", "evt-1")
        val second = appendValid(store, "stream-a", "evt-2")
        assertThat(second.previousEventHash).isEqualTo(first.eventHash)
    }

    @Test
    fun `every metadata field round-trips`() = runBlocking<Unit> {
        val store = createStore()
        val metadata = mapOf(
            "decision.actor" to "user:alice",
            "enforcement.point" to "test-gate",
            "policy.version" to "v1",
            "workflow.digest" to "sha256:0001",
            "arbitrary" to "value",
        )
        val appended = store.appendNext("stream-a") { latest ->
            AuditStoreFixtures.event(
                auditStreamId = "stream-a",
                eventId = "evt-roundtrip",
                latest = latest,
                timestamp = AuditStoreFixtures.BASE_TIME.plusSeconds(1),
                metadata = metadata,
                decision = "DENIED",
            )
        }
        val read = store.readStream("stream-a").single()
        assertThat(read).isEqualTo(appended)
        assertThat(read.sequenceNumber).isEqualTo(1L)
        assertThat(read.eventId).isEqualTo("evt-roundtrip")
        assertThat(read.auditStreamId).isEqualTo("stream-a")
        assertThat(read.schemaVersion).isEqualTo(CURRENT_AUDIT_SCHEMA_VERSION)
        assertThat(read.workflowRunId).isEqualTo("wf-1")
        assertThat(read.correlationId).isEqualTo("corr-1")
        assertThat(read.actor).isEqualTo("user:alice")
        assertThat(read.enforcementPoint).isEqualTo("test-gate")
        assertThat(read.decision).isEqualTo("DENIED")
        assertThat(read.policyVersion).isEqualTo("v1")
        assertThat(read.workflowDigest).isEqualTo("sha256:0001")
        assertThat(read.timestamp).isEqualTo(AuditStoreFixtures.BASE_TIME.plusSeconds(1))
        assertThat(read.reasonCode).isEqualTo("reason-1")
        assertThat(read.metadata).isEqualTo(metadata)
    }

    @Test
    fun `append rejects wrong auditStreamId`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-wrong-stream", latest, auditStreamIdOverride = "other-stream")
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-stream-id-mismatch")
    }

    @Test
    fun `append rejects wrong sequence`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-1")
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-wrong-seq", latest, sequenceNumber = 5L)
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-sequence-gap")
    }

    @Test
    fun `append rejects wrong previousEventHash`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-1")
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event(
                    "stream-a", "evt-wrong-prev", latest,
                    previousEventHash = "0000000000000000000000000000000000000000000000000000000000000000",
                )
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-hash-chain-broken")
    }

    @Test
    fun `append rejects invalid eventHash`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-wrong-hash", latest, eventHash = "deadbeef")
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-event-hash-mismatch")
    }

    @Test
    fun `append rejects unsupported schema version`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-old-schema", latest, schemaVersion = 2)
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-schema-version-unsupported")
    }

    @Test
    fun `append rejects duplicate event ID in the same stream`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-dup")
        val thrown = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-dup", latest)
            }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-duplicate-event-id")
    }

    @Test
    fun `append rejects blank audit stream ID`() = runBlocking<Unit> {
        val store = createStore()
        for (blank in listOf("", "   ")) {
            val thrown = runCatching {
                store.appendNext(blank) { latest ->
                    AuditStoreFixtures.event(blank, "evt-blank-stream", latest)
                }
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("audit-store-invalid-stream-id")
        }
    }

    @Test
    fun `append rejects blank event ID`() = runBlocking<Unit> {
        val store = createStore()
        for (blank in listOf("", "   ")) {
            val thrown = runCatching {
                store.appendNext("stream-a") { latest ->
                    AuditStoreFixtures.event("stream-a", blank, latest)
                }
            }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(thrown?.message).isEqualTo("audit-store-invalid-event-id")
        }
    }

    @Test
    fun `rejected append leaves the stream unchanged`() = runBlocking<Unit> {
        val store = createStore()
        val rejected = runCatching {
            store.appendNext("stream-a") { latest ->
                AuditStoreFixtures.event("stream-a", "evt-bad", latest, sequenceNumber = 42L)
            }
        }
        assertThat(rejected.isFailure).isTrue()
        assertThat(store.readStream("stream-a")).isEmpty()
        assertThat(store.latestEvent("stream-a")).isNull()
        // a subsequent valid append still starts at sequence 1
        val first = appendValid(store, "stream-a", "evt-ok")
        assertThat(first.sequenceNumber).isEqualTo(1L)
    }

    @Test
    fun `event factory is invoked exactly once per append`() = runBlocking<Unit> {
        val store = createStore()
        var invocations = 0
        store.appendNext("stream-a") { latest ->
            invocations++
            AuditStoreFixtures.event("stream-a", "evt-once", latest)
        }
        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun `factory exception propagates unchanged and appends nothing`() = runBlocking<Unit> {
        val store = createStore()
        val boom = IllegalStateException("factory exploded")
        val thrown = runCatching {
            store.appendNext("stream-a") { boom.let { throw it } }
        }.exceptionOrNull()
        assertThat(thrown).isSameAs(boom)
        assertThat(store.readStream("stream-a")).isEmpty()
        assertThat(store.latestEvent("stream-a")).isNull()
    }

    @Test
    fun `CancellationException propagates as the same instance and appends nothing`() = runBlocking<Unit> {
        val store = createStore()
        val cancellation = CancellationException("cancelled by test")
        val thrown = runCatching {
            store.appendNext("stream-a") { cancellation.let { throw it } }
        }.exceptionOrNull()
        assertThat(thrown).isSameAs(cancellation)
        assertThat(store.readStream("stream-a")).isEmpty()
        assertThat(store.latestEvent("stream-a")).isNull()
    }

    // ── Read / latest semantics ─────────────────────────────────────

    @Test
    fun `missing stream readStream returns emptyList`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.readStream("no-such-stream")).isEmpty()
    }

    @Test
    fun `missing stream latestEvent returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.latestEvent("no-such-stream")).isNull()
    }

    @Test
    fun `readStream returns events in ascending sequence order`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-1")
        appendValid(store, "stream-a", "evt-2")
        appendValid(store, "stream-a", "evt-3")
        val read = store.readStream("stream-a")
        assertThat(read.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L)
        assertThat(read.map { it.eventId }).containsExactly("evt-1", "evt-2", "evt-3")
    }

    @Test
    fun `latestEvent equals the final event`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "evt-1")
        val second = appendValid(store, "stream-a", "evt-2")
        assertThat(store.latestEvent("stream-a")).isEqualTo(second)
    }

    @Test
    fun `independent streams never bleed into one another`() = runBlocking<Unit> {
        val store = createStore()
        appendValid(store, "stream-a", "a-1")
        appendValid(store, "stream-a", "a-2")
        appendValid(store, "stream-b", "b-1")
        assertThat(store.readStream("stream-a").map { it.eventId }).containsExactly("a-1", "a-2")
        assertThat(store.readStream("stream-b").map { it.eventId }).containsExactly("b-1")
        assertThat(store.latestEvent("stream-a")?.eventId).isEqualTo("a-2")
        assertThat(store.latestEvent("stream-b")?.eventId).isEqualTo("b-1")
        assertThat(store.readStream("stream-a").all { it.auditStreamId == "stream-a" }).isTrue()
    }

    @Test
    fun `mutating the source metadata map after append cannot mutate stored evidence`() = runBlocking<Unit> {
        val store = createStore()
        val source = mutableMapOf("key" to "value")
        store.appendNext("stream-a") { latest ->
            AuditStoreFixtures.event("stream-a", "evt-isolated", latest, metadata = source)
        }
        source["key"] = "mutated"
        assertThat(store.readStream("stream-a").single().metadata).isEqualTo(mapOf("key" to "value"))
    }

    @Test
    fun `mutating returned metadata cannot modify the persisted event`() = runBlocking<Unit> {
        val store = createStore()
        val appended = appendValid(store, "stream-a", "evt-immutable", metadata = mapOf("key" to "value"))
        runCatching { (appended.metadata as MutableMap<String, String>)["key"] = "mutated" }
        assertThat(store.readStream("stream-a").single().metadata).isEqualTo(mapOf("key" to "value"))
    }

    // ── Pagination semantics ────────────────────────────────────────

    private suspend fun seedFive(store: AuditStore) {
        repeat(5) { i ->
            store.appendNext("stream-a", AuditStoreFixtures.factory("stream-a", "evt-${i + 1}"))
        }
    }

    @Test
    fun `page with null cursor starts from sequence 1`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = null, limit = 10)
        assertThat(page.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `page with cursor 0 starts from sequence 1`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 0L, limit = 10)
        assertThat(page.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `page cursor N returns events starting at N+1`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 2L, limit = 10)
        assertThat(page.map { it.sequenceNumber }).containsExactly(3L, 4L, 5L)
    }

    @Test
    fun `page respects the limit`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = null, limit = 2)
        assertThat(page.map { it.sequenceNumber }).containsExactly(1L, 2L)
    }

    @Test
    fun `page returns a partial page at the end of the stream`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 2L, limit = 10)
        assertThat(page.map { it.sequenceNumber }).containsExactly(3L, 4L, 5L)
    }

    @Test
    fun `page with cursor at the final event returns empty`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 5L, limit = 10)
        assertThat(page).isEmpty()
    }

    @Test
    fun `page on a missing stream returns empty`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.readStreamPage("no-such-stream", afterSequenceNumber = null, limit = 10)).isEmpty()
    }

    @Test
    fun `page rejects zero limit`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching { store.readStreamPage("stream-a", afterSequenceNumber = null, limit = 0) }
            .exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-store-invalid-limit")
    }

    @Test
    fun `page rejects negative limit`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching { store.readStreamPage("stream-a", afterSequenceNumber = null, limit = -1) }
            .exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-store-invalid-limit")
    }

    @Test
    fun `page rejects negative cursor`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching { store.readStreamPage("stream-a", afterSequenceNumber = -1L, limit = 10) }
            .exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown?.message).isEqualTo("audit-store-invalid-cursor")
    }

    @Test
    fun `page preserves ascending order`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 1L, limit = 3)
        assertThat(page.map { it.sequenceNumber }).containsExactly(2L, 3L, 4L)
    }

    @Test
    fun `page contents equal the corresponding slice of readStream`() = runBlocking<Unit> {
        val store = createStore()
        seedFive(store)
        val page = store.readStreamPage("stream-a", afterSequenceNumber = 1L, limit = 2)
        assertThat(page).isEqualTo(store.readStream("stream-a").filter { it.sequenceNumber > 1L }.take(2))
    }

    @Test
    fun `read paths reject blank audit stream ID`() = runBlocking<Unit> {
        val store = createStore()
        for (blank in listOf("", "   ")) {
            val ops = listOf<suspend () -> Any?>(
                { store.readStream(blank) },
                { store.readStreamPage(blank, afterSequenceNumber = null, limit = 10) },
                { store.latestEvent(blank) },
            )
            for (op in ops) {
                val thrown = runCatching { op() }.exceptionOrNull()
                assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(thrown?.message).isEqualTo("audit-store-invalid-stream-id")
            }
        }
    }

    // ── Hash-chain integrity ────────────────────────────────────────

    @Test
    fun `valid multi-event stream satisfies chain invariants and verifier`() = runBlocking<Unit> {
        val store = createStore()
        val events = mutableListOf<AuditEvent>()
        repeat(4) { i ->
            events.add(appendValid(store, "stream-a", "evt-chain-${i + 1}"))
        }
        for (i in 1 until events.size) {
            assertThat(events[i].sequenceNumber).isEqualTo(events[i - 1].sequenceNumber + 1L)
            assertThat(events[i].previousEventHash).isEqualTo(events[i - 1].eventHash)
        }
        for (event in events) {
            assertThat(event.eventHash).isEqualTo(event.copy(eventHash = "").calculateHash())
        }
        val result = AuditChainVerifier.verify(events)
        assertThat(result.isValid).withFailMessage { result.errors.joinToString { it.message } }.isTrue()
    }

    // ── Concurrency ─────────────────────────────────────────────────

    @Test
    fun `concurrent same-stream appends all succeed with an uninterrupted chain`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val outcomes = runInParallel(0 until 8) { index ->
                runCatching {
                    store.appendNext("stream-a") { latest ->
                        AuditStoreFixtures.event("stream-a", "evt-$round-$index", latest)
                    }
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected 8 successes, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(8)
            val events = store.readStream("stream-a")
            assertThat(events.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
            assertThat(events.map { it.eventId }.toSet()).hasSize(8)
            val result = AuditChainVerifier.verify(events)
            assertThat(result.isValid).withFailMessage { result.errors.joinToString { it.message } }.isTrue()
        }
    }

    @Test
    fun `concurrent duplicate event ID - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val outcomes = runInParallel(0 until 8) {
                runCatching {
                    store.appendNext("stream-a") { latest ->
                        AuditStoreFixtures.event("stream-a", "evt-dup-$round", latest)
                    }
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull() })
                .allMatch { it is IllegalArgumentException && it.message == "audit-duplicate-event-id" }
            val events = store.readStream("stream-a")
            assertThat(events.map { it.eventId }).containsExactly("evt-dup-$round")
            val result = AuditChainVerifier.verify(events)
            assertThat(result.isValid).withFailMessage { result.errors.joinToString { it.message } }.isTrue()
        }
    }

    @Test
    fun `concurrent independent streams both start at sequence 1`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val outcomes = runInParallel(0 until 8) { index ->
                val stream = if (index % 2 == 0) "stream-a" else "stream-b"
                runCatching {
                    store.appendNext(stream) { latest ->
                        AuditStoreFixtures.event(stream, "evt-$round-$index", latest)
                    }
                }
            }
            assertThat(outcomes.count { it.isSuccess }).isEqualTo(8)
            val a = store.readStream("stream-a")
            val b = store.readStream("stream-b")
            assertThat(a.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L)
            assertThat(b.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L)
            assertThat(AuditChainVerifier.verify(a).isValid).isTrue()
            assertThat(AuditChainVerifier.verify(b).isValid).isTrue()
        }
    }

    // ── Parallel-race helper (shared pattern from #269/#270) ────────

    /**
     * Runs [block] for every element of [items] on real parallel workers with
     * a start barrier, returning the outcomes in input order. The barrier
     * gives every contender a scheduling opportunity before any of them is
     * allowed to proceed.
     */
    private suspend fun <T, R> runInParallel(
        items: List<T>,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val ready = Channel<Unit>(items.size)
        val release = CompletableDeferred<Unit>()
        val workers = items.map { item ->
            async(Dispatchers.Default) {
                ready.send(Unit)
                release.await()
                block(item)
            }
        }
        repeat(items.size) { ready.receive() }
        release.complete(Unit)
        workers.map { it.await() }
    }

    /** Range convenience overload for the race loops. */
    private suspend fun <R> runInParallel(
        range: IntRange,
        block: suspend (Int) -> R,
    ): List<R> = runInParallel(range.toList(), block)
}
