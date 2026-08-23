package dev.tramai.testing.persistence.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Shared compatibility contract for every [SuspendedInvocationStore]
 * implementation (Epic 8.1c). Runners in each owning module extend this class
 * and provide a fresh [createStore] per test.
 *
 * The contract covers CRUD, the sensitive-replay-envelope release path, the
 * digest + envelope-binding invariants, ID validation, and real parallel
 * races. It deliberately does NOT pin implementation-specific concerns:
 * restart durability, encryption format, file permissions, corruption
 * handling, schema versions, SQL resource/locking behavior, or the JDBC-only
 * unique replay-envelope-digest constraint.
 */
abstract class SuspendedInvocationStoreTck {

    protected abstract fun createStore(): SuspendedInvocationStore

    // ── Creation / read ────────────────────────────────────────────

    @Test
    fun `create then get round-trips every metadata field`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()

        store.create(metadata, envelope)

        val retrieved = store.get(metadata.approvalId)
        assertThat(retrieved).isNotNull
        assertThat(retrieved).isEqualTo(metadata)
        assertThat(retrieved!!.tokenBudgetSnapshot).isEqualTo(SuspendedInvocationFixtures.TOKEN_BUDGET)
        assertThat(retrieved.toolSecurity).isEqualTo(SuspendedInvocationFixtures.TOOL_SECURITY)
        assertThat(retrieved.replayEnvelopeDigest).isEqualTo(metadata.replayEnvelopeDigest)
    }

    @Test
    fun `get on missing approvalId returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.get("missing-id")).isNull()
    }

    @Test
    fun `duplicate create with same approvalId fails`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record("dup-1")
        store.create(metadata, envelope)

        assertFailsWith<IllegalArgumentException> {
            store.create(metadata, envelope)
        }
    }

    @Test
    fun `create rejects blank approvalId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(approvalId = "  ")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects control characters in approvalId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(approvalId = "bad\nid")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects approvalId with surrounding whitespace`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(approvalId = "  padded-id  ")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects oversized approvalId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(approvalId = "x".repeat(257))
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects blank toolCallId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(toolCallId = "")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects blank toolName`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(toolName = "")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects blank correlationId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(correlationId = "")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects blank conversationId when present`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(conversationId = " ")
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `get with blank approvalId fails`() = runBlocking<Unit> {
        val store = createStore()
        assertFailsWith<IllegalArgumentException> { store.get("") }
        assertFailsWith<IllegalArgumentException> { store.get("  x  ") }
    }

    @Test
    fun `reveal and remove with blank approvalId fail`() = runBlocking<Unit> {
        val store = createStore()
        assertFailsWith<IllegalArgumentException> { store.revealReplayEnvelope("") }
        assertFailsWith<IllegalArgumentException> { store.remove("") }
    }

    // ── Sensitive release path ─────────────────────────────────────

    @Test
    fun `reveal returns envelope whose messages equal the originals`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        val originalMessages = envelope.revealForResume().messages

        store.create(metadata, envelope)
        val revealed = store.revealReplayEnvelope(metadata.approvalId)

        assertThat(revealed).isNotNull
        assertThat(revealed!!.revealForResume().messages).isEqualTo(originalMessages)
    }

    @Test
    fun `reveal on missing approvalId returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.revealReplayEnvelope("missing-id")).isNull()
    }

    @Test
    fun `get never exposes the replay messages`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)

        // The only message-bearing access is revealReplayEnvelope; get() must
        // surface the safe metadata shape and nothing else.
        val retrieved = store.get(metadata.approvalId)!!
        assertThat(retrieved::class.java).isEqualTo(SuspendedInvocationMetadata::class.java)
        assertThat(retrieved.toolCallId).isEqualTo(metadata.toolCallId)
        assertThat(retrieved.toolName).isEqualTo(metadata.toolName)
        assertThat(retrieved.replayEnvelopeDigest).isEqualTo(metadata.replayEnvelopeDigest)
    }

    @Test
    fun `replay envelope toString is redacted`() = runBlocking<Unit> {
        val envelope = SuspendedInvocationFixtures.envelope(
            listOf(Message(role = MessageRole.USER, content = "secret-prompt")),
        )
        assertThat(envelope.toString()).isEqualTo("[REDACTED]")
        assertThat(envelope.toString()).doesNotContain("secret-prompt")
    }

    @Test
    fun `reveal does not remove the invocation`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)

        store.revealReplayEnvelope(metadata.approvalId)

        // The sensitive release path is not a consume: the record survives
        // until an explicit remove.
        assertThat(store.get(metadata.approvalId)).isEqualTo(metadata)
        assertThat(store.revealReplayEnvelope(metadata.approvalId)).isNotNull
    }

    @Test
    fun `multiple reveals return equal content without consuming`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)

        val first = store.revealReplayEnvelope(metadata.approvalId)!!
        val second = store.revealReplayEnvelope(metadata.approvalId)!!
        assertThat(second.revealForResume().messages).isEqualTo(first.revealForResume().messages)
    }

    // ── Digest + envelope binding ──────────────────────────────────

    @Test
    fun `create rejects replay envelope digest mismatch`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(
            digestOverride = Sha256Digest.of(
                "sha256:9999999999999999999999999999999999999999999999999999999999999999",
            ),
        )
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects tampered envelope messages`() = runBlocking<Unit> {
        val store = createStore()
        // Envelope messages changed after the canonical digest was computed
        // over the original set — metadata digest must not match them.
        val tamperedMessages = SuspendedInvocationFixtures.messages().mapIndexed { index, message ->
            if (index == 0) message.copy(content = "tampered-prompt") else message
        }
        val metadata = SuspendedInvocationFixtures.metadata(replayEnvelopeDigest = SuspendedInvocationFixtures.digest())
        val envelope = SuspendedInvocationFixtures.envelope(tamperedMessages)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects envelope without assistant tool calls`() = runBlocking<Unit> {
        val store = createStore()
        val metadata = SuspendedInvocationFixtures.metadata(replayEnvelopeDigest = SuspendedInvocationFixtures.digest())
        val envelope = SuspendedInvocationFixtures.envelope(
            listOf(Message(role = MessageRole.USER, content = "no tool calls")),
        )
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects toolCallId that does not exist in the envelope`() = runBlocking<Unit> {
        val store = createStore()
        // Metadata names a tool call that the (digest-valid) envelope does not contain.
        val metadata = SuspendedInvocationFixtures.metadata(
            toolCallId = "different-tool-call",
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(),
        )
        val envelope = SuspendedInvocationFixtures.envelope()
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects toolCallIndex out of bounds`() = runBlocking<Unit> {
        val store = createStore()
        val metadata = SuspendedInvocationFixtures.metadata(
            toolCallIndex = 3,
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(),
        )
        val envelope = SuspendedInvocationFixtures.envelope()
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects toolName that does not match the envelope`() = runBlocking<Unit> {
        val store = createStore()
        val metadata = SuspendedInvocationFixtures.metadata(
            toolName = "other_tool",
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(),
        )
        val envelope = SuspendedInvocationFixtures.envelope()
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    // ── Redaction invariants (raw selected arguments never reach a store) ──

    @Test
    fun `create rejects selected tool call that is not redacted`() = runBlocking<Unit> {
        val store = createStore()
        // Raw selected arguments with a canonical digest over THAT envelope:
        // only the redaction invariant can reject it, not the digest check.
        val rawMessages = SuspendedInvocationFixtures.messagesWithRawSelectedArguments()
        val metadata = SuspendedInvocationFixtures.metadata(
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(rawMessages),
        )
        val envelope = SuspendedInvocationFixtures.envelope(rawMessages)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects duplicate selected toolCallId across the envelope`() = runBlocking<Unit> {
        val store = createStore()
        val duplicateMessages = SuspendedInvocationFixtures.messagesWithDuplicateSelectedId()
        val metadata = SuspendedInvocationFixtures.metadata(
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(duplicateMessages),
        )
        val envelope = SuspendedInvocationFixtures.envelope(duplicateMessages)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects extra or misplaced redaction sentinel`() = runBlocking<Unit> {
        val store = createStore()
        val extraSentinelMessages = SuspendedInvocationFixtures.messagesWithExtraSentinel()
        val metadata = SuspendedInvocationFixtures.metadata(
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(extraSentinelMessages),
        )
        val envelope = SuspendedInvocationFixtures.envelope(extraSentinelMessages)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects selected tool call outside the latest assistant batch`() = runBlocking<Unit> {
        val store = createStore()
        val earlierBatchMessages = SuspendedInvocationFixtures.messagesWithSelectedCallInEarlierBatch()
        val metadata = SuspendedInvocationFixtures.metadata(
            replayEnvelopeDigest = SuspendedInvocationFixtures.digest(earlierBatchMessages),
        )
        val envelope = SuspendedInvocationFixtures.envelope(earlierBatchMessages)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects negative historySize`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record(historySize = -1)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    @Test
    fun `create rejects envelope smaller than its historySize`() = runBlocking<Unit> {
        val store = createStore()
        // Two messages cannot account for five history entries.
        val (metadata, envelope) = SuspendedInvocationFixtures.record(historySize = 5)
        assertFailsWith<IllegalArgumentException> { store.create(metadata, envelope) }
    }

    // ── Remove ─────────────────────────────────────────────────────

    @Test
    fun `remove returns the created metadata`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)

        val removed = store.remove(metadata.approvalId)
        assertThat(removed).isEqualTo(metadata)
    }

    @Test
    fun `remove then get returns null`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)
        store.remove(metadata.approvalId)
        assertThat(store.get(metadata.approvalId)).isNull()
    }

    @Test
    fun `remove then reveal returns null`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record()
        store.create(metadata, envelope)
        store.remove(metadata.approvalId)
        assertThat(store.revealReplayEnvelope(metadata.approvalId)).isNull()
    }

    @Test
    fun `remove on missing approvalId returns null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.remove("missing-id")).isNull()
    }

    @Test
    fun `create after remove succeeds with the same approvalId`() = runBlocking<Unit> {
        val store = createStore()
        val (metadata, envelope) = SuspendedInvocationFixtures.record("recreate-1")
        store.create(metadata, envelope)
        store.remove(metadata.approvalId)

        val (metadata2, envelope2) = SuspendedInvocationFixtures.record("recreate-1", correlationId = "correlation-2")
        store.create(metadata2, envelope2)
        assertThat(store.get("recreate-1")).isEqualTo(metadata2)
    }

    // ── Concurrency ────────────────────────────────────────────────

    @Test
    fun `concurrent create with same approvalId - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val id = "race-create-$round"
            val records = (0 until 8).map { index ->
                val (metadata, envelope) = SuspendedInvocationFixtures.record(
                    approvalId = id,
                    correlationId = "corr-$index",
                )
                metadata to envelope
            }

            val outcomes = runInParallel(records) { (metadata, envelope) ->
                runCatching { store.create(metadata, envelope) }
            }

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.count { it.isFailure }).isEqualTo(7)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull() })
                .allMatch { it is IllegalArgumentException }
        }
    }

    @Test
    fun `concurrent remove - exactly one winner returns metadata`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val id = "race-remove-$round"
            val (metadata, envelope) = SuspendedInvocationFixtures.record(id)
            store.create(metadata, envelope)

            val outcomes = runInParallel(1..8) {
                runCatching { store.remove(id) }
            }

            assertThat(outcomes.count { it.isSuccess && it.getOrNull() != null }).isEqualTo(1)
            assertThat(outcomes.count { it.isSuccess && it.getOrNull() == null }).isEqualTo(7)
        }
    }

    @Test
    fun `concurrent reveal while remove - valid outcomes only`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val id = "race-reveal-$round"
            val (metadata, envelope) = SuspendedInvocationFixtures.record(id)
            store.create(metadata, envelope)

            val outcomes = runInParallel(1..8) {
                if (it % 2 == 0) {
                    runCatching { store.revealReplayEnvelope(id) }.map { saw -> "reveal:$saw" }
                } else {
                    runCatching { store.remove(id) }.map { found -> "remove:${found != null}" }
                }
            }

            // No crashes, and exactly one remove wins (reveals may observe the
            // entry before the delete or the empty state after it — both valid).
            assertThat(outcomes.filter { it.isFailure }).isEmpty()
            assertThat(outcomes.filter { it.isSuccess && it.getOrNull() == "remove:true" }).hasSize(1)
        }
    }

    // ── Parallel-race helper ───────────────────────────────────────

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
