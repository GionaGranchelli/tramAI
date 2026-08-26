package dev.tramai.testing.persistence.checkpoint

import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowRecoveryRecord
import dev.tramai.orchestration.WorkflowRecoveryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1f: the shared, cross-implementation
 * [dev.tramai.orchestration.WorkflowCheckpointStore] compatibility contract.
 * Every concrete store — in-memory, properties-file, Markdown, and JDBC —
 * must treat a checkpoint as a versioned logical record identified by
 * (workflowName, workflowId), with store-owned revision progression,
 * optimistic concurrency, delete/idempotency semantics, and recovery-state
 * persistence.
 *
 * A checkpoint is NOT "a file containing state": filesystem-safe naming,
 * Markdown formatting and JDBC primary keys are implementation mechanics and
 * must not change what constitutes a unique checkpoint. [WorkflowCheckpointCatalog]
 * is a distinct optional SPI (Markdown intentionally does not implement it)
 * and is outside this contract.
 */
abstract class WorkflowCheckpointStoreTck {

    /** Fresh isolated storage per case; the runner owns setup/teardown. */
    protected abstract fun createStore(): WorkflowCheckpointStore

    private val savedAt = WorkflowCheckpointFixtures.SAVED_AT_EPOCH_MILLIS

    /** A caller-chosen generation token: stores must never persist or honor it. */
    private val callerChosenGeneration = "evil/fake"

    private fun checkpoint(
        workflowName: String,
        workflowId: String,
        nextStepIndex: Int = 3,
        stepExecutions: Int = 5,
        lastCompletedStepName: String? = "validate",
        statePayload: String = """{"state":"review"}""",
        revision: Long = 0,
        metadata: Map<String, String> = mapOf("region" to "eu-west", "tenant" to "acme"),
        recoveryState: WorkflowRecoveryState = WorkflowRecoveryState.Normal,
        checkpointGeneration: String? = null,
    ): WorkflowCheckpoint = WorkflowCheckpointFixtures.checkpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = stepExecutions,
        lastCompletedStepName = lastCompletedStepName,
        statePayload = statePayload,
        revision = revision,
        metadata = metadata,
        savedAtEpochMillis = savedAt,
        recoveryState = recoveryState,
        checkpointGeneration = checkpointGeneration,
    )

    private suspend fun WorkflowCheckpointStore.saveCurrent(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long,
    ): WorkflowCheckpoint {
        val current = load(checkpoint.workflowName, checkpoint.workflowId)
        return save(
            checkpoint.copy(checkpointGeneration = current?.checkpointGeneration),
            expectedRevision,
        )
    }

    private suspend fun WorkflowCheckpointStore.deleteCurrent(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
    ) {
        val current = load(workflowName, workflowId)
        delete(workflowName, workflowId, expectedRevision, current?.checkpointGeneration)
    }

    private suspend fun WorkflowCheckpointStore.requireRecoveryCurrent(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        record: WorkflowRecoveryRecord,
    ): WorkflowCheckpoint {
        val current = load(workflowName, workflowId)
        return requireRecovery(
            workflowName,
            workflowId,
            expectedRevision,
            record,
            current?.checkpointGeneration,
        )
    }

    private suspend fun WorkflowCheckpointStore.clearRecoveryCurrent(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
    ): WorkflowCheckpoint {
        val current = load(workflowName, workflowId)
        return clearRecovery(
            workflowName,
            workflowId,
            expectedRevision,
            current?.checkpointGeneration,
        )
    }

    private fun recoveryRecord(
        reason: dev.tramai.orchestration.WorkflowRecoveryReason = dev.tramai.orchestration.WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
        stepName: String = "sendInvoice",
        attemptId: String = "attempt-42",
        priorWorkerId: String = "worker-7",
        idempotencyKey: String? = "idem-xyz",
        instructions: String? = "manual confirmation required",
    ): WorkflowRecoveryRecord = WorkflowCheckpointFixtures.recoveryRecord(
        reason = reason,
        stepName = stepName,
        attemptId = attemptId,
        priorWorkerId = priorWorkerId,
        idempotencyKey = idempotencyKey,
        instructions = instructions,
    )

    // ── A. Creation / read / identity ───────────────────────────────

    @Test
    fun `full checkpoint round-trips exactly`() = runBlocking<Unit> {
        val store = createStore()
        val rec = checkpoint("invoice-review", "run-001")
        val persisted = store.save(rec)
        assertThat(persisted.checkpointGeneration).isNotBlank()
        assertThat(store.load("invoice-review", "run-001")).isEqualTo(persisted)
    }

    @Test
    fun `save returns the persisted value`() = runBlocking<Unit> {
        val store = createStore()
        val persisted = store.save(checkpoint("invoice-review", "run-002"))
        assertThat(persisted.copy(checkpointGeneration = null))
            .isEqualTo(checkpoint("invoice-review", "run-002").copy(revision = 1))
        assertThat(persisted.checkpointGeneration).isNotBlank()
        assertThat(store.load("invoice-review", "run-002")).isEqualTo(persisted)
    }

    @Test
    fun `missing checkpoint loads null`() = runBlocking<Unit> {
        val store = createStore()
        assertThat(store.load("no-such", "never")).isNull()
    }

    @Test
    fun `first revision is always 1`() = runBlocking<Unit> {
        val store = createStore()
        val persisted = store.save(checkpoint("rev", "first"))
        assertThat(persisted.revision).isEqualTo(1)
        assertThat(store.load("rev", "first")?.revision).isEqualTo(1)
    }

    @Test
    fun `caller-supplied revision is not authoritative`() = runBlocking<Unit> {
        val store = createStore()
        val persisted = store.save(checkpoint("rev", "ignored-input", revision = 999))
        assertThat(persisted.revision).isEqualTo(1)
    }

    @Test
    fun `nullable lastCompletedStepName is preserved`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("nullable", "null-step", lastCompletedStepName = null))
        assertThat(store.load("nullable", "null-step")?.lastCompletedStepName).isNull()
    }

    @Test
    fun `state payload with multiline unicode and punctuation is preserved`() = runBlocking<Unit> {
        val store = createStore()
        val payload = "line-1\nline-2 🚀 \"quoted\" `code` {json:true} café — done"
        store.save(checkpoint("payload", "rich", statePayload = payload))
        assertThat(store.load("payload", "rich")?.statePayload).isEqualTo(payload)
    }

    @Test
    fun `metadata exact keys and values preserved`() = runBlocking<Unit> {
        val store = createStore()
        val metadata = mapOf("region" to "eu-west", "tenant" to "acme", "empty" to "")
        store.save(checkpoint("meta", "exact", metadata = metadata))
        assertThat(store.load("meta", "exact")?.metadata).isEqualTo(metadata)
    }

    @Test
    fun `Required recovery state round-trips exactly`() = runBlocking<Unit> {
        val store = createStore()
        val required = WorkflowCheckpointFixtures.required(recoveryRecord())
        store.save(checkpoint("rec", "required", recoveryState = required))
        assertThat(store.load("rec", "required")?.recoveryState).isEqualTo(required)
    }

    @Test
    fun `same workflowName different IDs are independent`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("shared", "id-a", nextStepIndex = 1))
        store.save(checkpoint("shared", "id-b", nextStepIndex = 9))
        assertThat(store.load("shared", "id-a")?.nextStepIndex).isEqualTo(1)
        assertThat(store.load("shared", "id-b")?.nextStepIndex).isEqualTo(9)
    }

    @Test
    fun `same workflowId different names are independent`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("name-a", "shared-id", stepExecutions = 1))
        store.save(checkpoint("name-b", "shared-id", stepExecutions = 7))
        assertThat(store.load("name-a", "shared-id")?.stepExecutions).isEqualTo(1)
        assertThat(store.load("name-b", "shared-id")?.stepExecutions).isEqualTo(7)
    }

    @Test
    fun `duplicate create raises typed conflict with fixed message`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("dup", "create"))
        val thrown = runCatching { store.save(checkpoint("dup", "create")) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(thrown?.message).isEqualTo("Workflow checkpoint conflict")
        assertThat(thrown?.cause).isNull()
    }

    @Test
    fun `rejected duplicate leaves the original unchanged`() = runBlocking<Unit> {
        val store = createStore()
        val original = store.save(checkpoint("dup", "unchanged", nextStepIndex = 3))
        runCatching { store.save(checkpoint("dup", "unchanged", nextStepIndex = 99)) }
        assertThat(store.load("dup", "unchanged")).isEqualTo(original)
    }

    @Test
    fun `distinct keys whose legacy sanitized paths collide remain distinct`() = runBlocking<Unit> {
        val store = createStore()
        // DefaultWorkflowCheckpointPathStrategy maps both to "order_a".
        store.save(checkpoint("order", "a/b", nextStepIndex = 1))
        store.save(checkpoint("order", "a?b", nextStepIndex = 2))
        assertThat(store.load("order", "a/b")?.nextStepIndex).isEqualTo(1)
        assertThat(store.load("order", "a?b")?.nextStepIndex).isEqualTo(2)
        assertThat(store.load("order", "a/b")).isNotEqualTo(store.load("order", "a?b"))
    }

    // ── B. Revision / optimistic concurrency ────────────────────────

    @Test
    fun `exact expected revision update succeeds and increments`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "exact"))
        val updated = store.saveCurrent(checkpoint("rev", "exact", nextStepIndex = 4), expectedRevision = 1)
        assertThat(updated.revision).isEqualTo(2)
        assertThat(updated.nextStepIndex).isEqualTo(4)
    }

    @Test
    fun `update changes fields and persists new values`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "fields", statePayload = "before"))
        store.saveCurrent(checkpoint("rev", "fields", statePayload = "after"), expectedRevision = 1)
        assertThat(store.load("rev", "fields")?.statePayload).isEqualTo("after")
    }

    @Test
    fun `stale lower revision conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "stale"))
        store.saveCurrent(checkpoint("rev", "stale", nextStepIndex = 4), expectedRevision = 1)
        val thrown = runCatching {
            store.saveCurrent(checkpoint("rev", "stale", nextStepIndex = 5), expectedRevision = 1)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(thrown?.message).isEqualTo("Workflow checkpoint conflict")
    }

    @Test
    fun `impossible higher revision conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "higher"))
        val thrown = runCatching {
            store.saveCurrent(checkpoint("rev", "higher", nextStepIndex = 5), expectedRevision = 99)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `expected revision on missing record conflicts`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.saveCurrent(checkpoint("rev", "missing"), expectedRevision = 1)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `expected null on existing record conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "exists"))
        val thrown = runCatching { store.save(checkpoint("rev", "exists", nextStepIndex = 9)) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `failed update leaves the record unchanged`() = runBlocking<Unit> {
        val store = createStore()
        val original = store.save(checkpoint("rev", "noop", nextStepIndex = 3))
        runCatching { store.saveCurrent(checkpoint("rev", "noop", nextStepIndex = 8), expectedRevision = 42) }
        assertThat(store.load("rev", "noop")).isEqualTo(original)
    }

    @Test
    fun `supplied revision 999 does not become 999 or 1000`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "chain", revision = 999))
        val updated = store.saveCurrent(checkpoint("rev", "chain", revision = 999), expectedRevision = 1)
        assertThat(updated.revision).isEqualTo(2)
    }

    @Test
    fun `repeated updates advance revision 1 2 3`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rev", "chain3"))
        val second = store.saveCurrent(checkpoint("rev", "chain3", nextStepIndex = 4), expectedRevision = 1)
        val third = store.saveCurrent(checkpoint("rev", "chain3", nextStepIndex = 5), expectedRevision = 2)
        assertThat(second.revision).isEqualTo(2)
        assertThat(third.revision).isEqualTo(3)
        assertThat(store.load("rev", "chain3")?.revision).isEqualTo(3)
    }

    // ── C. Delete / idempotency ─────────────────────────────────────

    @Test
    fun `exact revision deletes`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("del", "exact"))
        store.deleteCurrent("del", "exact", expectedRevision = 1)
        assertThat(store.load("del", "exact")).isNull()
    }

    @Test
    fun `stale revision delete conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("del", "stale"))
        store.saveCurrent(checkpoint("del", "stale", nextStepIndex = 4), expectedRevision = 1)
        val thrown = runCatching { store.deleteCurrent("del", "stale", expectedRevision = 1) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `stale delete leaves the record unchanged`() = runBlocking<Unit> {
        val store = createStore()
        val original = store.save(checkpoint("del", "kept", nextStepIndex = 3))
        runCatching { store.deleteCurrent("del", "kept", expectedRevision = 42) }
        assertThat(store.load("del", "kept")).isEqualTo(original)
    }

    @Test
    fun `missing record with expected revision delete conflicts`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching { store.deleteCurrent("del", "missing", expectedRevision = 1) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `missing record with no expected revision delete is a no-op`() = runBlocking<Unit> {
        val store = createStore()
        store.delete("del", "noop")
        assertThat(store.load("del", "noop")).isNull()
    }

    @Test
    fun `existing record with no expected revision deletes`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("del", "unconditional"))
        store.delete("del", "unconditional")
        assertThat(store.load("del", "unconditional")).isNull()
    }

    @Test
    fun `recreate after delete starts at revision 1`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("del", "recreate"))
        store.saveCurrent(checkpoint("del", "recreate", nextStepIndex = 4), expectedRevision = 1)
        store.deleteCurrent("del", "recreate", expectedRevision = 2)
        val recreated = store.save(checkpoint("del", "recreate", nextStepIndex = 1))
        assertThat(recreated.revision).isEqualTo(1)
        assertThat(store.load("del", "recreate")?.nextStepIndex).isEqualTo(1)
    }

    // ── D. Recovery state ───────────────────────────────────────────

    @Test
    fun `requireRecovery transitions Normal to Required with exact record`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rec", "require"))
        val record = recoveryRecord()
        val updated = store.requireRecoveryCurrent("rec", "require", expectedRevision = 1, record = record)
        assertThat(updated.revision).isEqualTo(2)
        assertThat(updated.recoveryState).isEqualTo(WorkflowRecoveryState.Required(record))
        assertThat(store.load("rec", "require")?.recoveryState).isEqualTo(WorkflowRecoveryState.Required(record))
    }

    @Test
    fun `requireRecovery preserves unrelated checkpoint fields`() = runBlocking<Unit> {
        val store = createStore()
        store.save(
            checkpoint(
                "rec", "preserve", nextStepIndex = 3, stepExecutions = 5,
                lastCompletedStepName = "validate", statePayload = """{"state":"review"}""",
                metadata = mapOf("region" to "eu-west"),
            ),
        )
        val record = recoveryRecord()
        store.requireRecoveryCurrent("rec", "preserve", expectedRevision = 1, record = record)
        val loaded = store.load("rec", "preserve")!!
        assertThat(loaded.nextStepIndex).isEqualTo(3)
        assertThat(loaded.stepExecutions).isEqualTo(5)
        assertThat(loaded.lastCompletedStepName).isEqualTo("validate")
        assertThat(loaded.statePayload).isEqualTo("""{"state":"review"}""")
        assertThat(loaded.metadata).isEqualTo(mapOf("region" to "eu-west"))
        assertThat(loaded.savedAtEpochMillis).isEqualTo(savedAt)
    }

    @Test
    fun `requireRecovery with stale revision conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rec", "stale"))
        store.saveCurrent(checkpoint("rec", "stale", nextStepIndex = 4), expectedRevision = 1)
        val thrown = runCatching {
            store.requireRecoveryCurrent("rec", "stale", expectedRevision = 1, record = recoveryRecord())
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `requireRecovery on missing checkpoint conflicts`() = runBlocking<Unit> {
        val store = createStore()
        val thrown = runCatching {
            store.requireRecoveryCurrent("rec", "missing", expectedRevision = 1, record = recoveryRecord())
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `failed requireRecovery leaves state unchanged`() = runBlocking<Unit> {
        val store = createStore()
        val original = store.save(checkpoint("rec", "noop"))
        runCatching {
            store.requireRecoveryCurrent("rec", "noop", expectedRevision = 99, record = recoveryRecord())
        }
        assertThat(store.load("rec", "noop")).isEqualTo(original)
    }

    @Test
    fun `clearRecovery transitions Required to Normal at revision plus one`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rec", "clear"))
        val record = recoveryRecord()
        store.requireRecoveryCurrent("rec", "clear", expectedRevision = 1, record = record)
        val cleared = store.clearRecoveryCurrent("rec", "clear", expectedRevision = 2)
        assertThat(cleared.revision).isEqualTo(3)
        assertThat(cleared.recoveryState).isEqualTo(WorkflowRecoveryState.Normal)
        assertThat(store.load("rec", "clear")?.recoveryState).isEqualTo(WorkflowRecoveryState.Normal)
    }

    @Test
    fun `clearRecovery with stale revision conflicts`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rec", "clear-stale"))
        store.requireRecoveryCurrent("rec", "clear-stale", expectedRevision = 1, record = recoveryRecord())
        val thrown = runCatching {
            store.clearRecoveryCurrent("rec", "clear-stale", expectedRevision = 1)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)
    }

    @Test
    fun `failed clearRecovery is non-mutating`() = runBlocking<Unit> {
        val store = createStore()
        store.save(checkpoint("rec", "clear-noop"))
        val record = recoveryRecord()
        val required = store.requireRecoveryCurrent("rec", "clear-noop", expectedRevision = 1, record = record)
        runCatching { store.clearRecoveryCurrent("rec", "clear-noop", expectedRevision = 99) }
        assertThat(store.load("rec", "clear-noop")).isEqualTo(required)
    }

    // ── E. Concurrency ──────────────────────────────────────────────

    @Test
    fun `concurrent create - exactly one winner at revision 1`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val outcomes = runInParallel(0 until 8) {
                runCatching { store.save(checkpoint("race-create-$round", "wf")) }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one create winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                .containsExactly(WorkflowCheckpointConflictException::class.java.name)
            assertThat(store.load("race-create-$round", "wf")?.revision).isEqualTo(1)
        }
    }

    @Test
    fun `competing same-revision updates - exactly one winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val name = "race-update-$round"
            store.save(checkpoint(name, "wf"))
            val payloads = (0 until 8).map { "payload-$round-$it" }
            val outcomes = runInParallel(payloads) { payload ->
                runCatching {
                    store.saveCurrent(checkpoint(name, "wf", statePayload = payload), expectedRevision = 1)
                }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one update winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                .containsExactly(WorkflowCheckpointConflictException::class.java.name)
            val winnerPayload = payloads[outcomes.indexOfFirst { it.isSuccess }]
            assertThat(store.load(name, "wf")?.revision).isEqualTo(2)
            assertThat(store.load(name, "wf")?.statePayload).isEqualTo(winnerPayload)
        }
    }

    @Test
    fun `concurrent update versus delete - exactly one legal winner`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val name = "race-del-$round"
            store.save(checkpoint(name, "wf"))
            val outcomes = runInParallel(0 until 2) { index ->
                if (index == 0) {
                    runCatching { store.saveCurrent(checkpoint(name, "wf", nextStepIndex = 9), expectedRevision = 1) }
                } else {
                    runCatching { store.deleteCurrent(name, "wf", expectedRevision = 1) }
                }
            }
            val successes = outcomes.count { it.isSuccess }
            assertThat(successes).withFailMessage {
                "round $round: expected exactly one legal winner, got $successes (outcomes: $outcomes)"
            }.isEqualTo(1)
            val final = store.load(name, "wf")
            if (outcomes[0].isSuccess) {
                assertThat(final?.revision).isEqualTo(2)
                assertThat(final?.nextStepIndex).isEqualTo(9)
            } else {
                assertThat(final).isNull()
            }
        }
    }

    @Test
    fun `competing requireRecovery - exactly one winner with exact record`() = runBlocking<Unit> {
        repeat(20) { round ->
            val store = createStore()
            val name = "race-rec-$round"
            store.save(checkpoint(name, "wf"))
            val records = (0 until 8).map { index ->
                recoveryRecord(attemptId = "attempt-$round-$index", priorWorkerId = "worker-$index")
            }
            val outcomes = runInParallel(records) { record ->
                runCatching { store.requireRecoveryCurrent(name, "wf", expectedRevision = 1, record = record) }
            }
            assertThat(outcomes.count { it.isSuccess }).withFailMessage {
                "round $round: expected exactly one recovery winner, got ${outcomes.count { it.isSuccess }}"
            }.isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                .containsExactly(WorkflowCheckpointConflictException::class.java.name)
            val winnerRecord = records[outcomes.indexOfFirst { it.isSuccess }]
            val final = store.load(name, "wf")!!
            assertThat(final.revision).isEqualTo(2)
            assertThat(final.recoveryState).isEqualTo(WorkflowRecoveryState.Required(winnerRecord))
        }
    }

    // ── Parallel-race helper (shared pattern from #269-#272) ────────

    /**
     * Runs [block] for every element of [items] on real parallel workers with
     * a start barrier, returning the outcomes in input order.
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

    // ── E. Incarnation authority (Epic 8.2f) ────────────────────────

    /**
     * P0-A discriminator: delete/recreate ABA. A stale capability captured
     * against the deleted incarnation must never mutate the recreated
     * successor, even though both live at revision 1.
     *
     * create old @ r1 → capture old capability → delete old @ r1 →
     * recreate successor @ r1 → old capability attempts save / delete /
     * requireRecovery / clearRecovery.
     *
     * Every stale operation must be rejected with a conflict and the
     * successor must stay value-identical. Pre-fix (revision-only fencing)
     * this test is RED for all store implementations: the recreated r1 is
     * indistinguishable from the deleted r1.
     */
    @Test
    fun `deleted-generation capability cannot mutate recreated successor`() = runBlocking<Unit> {
        val store = createStore()
        val name = "aba"
        val id = "recreate"
        val predecessor = store.save(checkpoint(name, id, nextStepIndex = 2))

        // Capture the old incarnation's capability, then delete it.
        store.delete(
            name,
            id,
            expectedRevision = predecessor.revision,
            expectedGeneration = predecessor.checkpointGeneration,
        )

        // Recreate the successor at revision 1.
        val successor = store.save(checkpoint(name, id, nextStepIndex = 5))
        assertThat(successor.revision).isEqualTo(1)

        // Every stale operation from the deleted incarnation must conflict.
        val staleSave = runCatching {
            store.save(
                checkpoint(
                    name,
                    id,
                    nextStepIndex = 9,
                    checkpointGeneration = predecessor.checkpointGeneration,
                ),
                expectedRevision = predecessor.revision,
            )
        }.exceptionOrNull()
        assertThat(staleSave)
            .withFailMessage("stale save mutated the recreated successor")
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)

        val staleDelete = runCatching {
            store.delete(
                name,
                id,
                expectedRevision = predecessor.revision,
                expectedGeneration = predecessor.checkpointGeneration,
            )
        }.exceptionOrNull()
        assertThat(staleDelete)
            .withFailMessage("stale delete removed the recreated successor")
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)

        val staleRequire = runCatching {
            store.requireRecovery(
                name,
                id,
                expectedRevision = predecessor.revision,
                expectedGeneration = predecessor.checkpointGeneration,
                record = recoveryRecord(),
            )
        }.exceptionOrNull()
        assertThat(staleRequire)
            .withFailMessage("stale requireRecovery mutated the recreated successor")
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)

        val staleClear = runCatching {
            store.clearRecovery(
                name,
                id,
                expectedRevision = predecessor.revision,
                expectedGeneration = predecessor.checkpointGeneration,
            )
        }.exceptionOrNull()
        assertThat(staleClear)
            .withFailMessage("stale clearRecovery mutated the recreated successor")
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)

        // The successor is value-identical.
        val final = store.load(name, id)
        assertThat(final).isEqualTo(successor)
    }

    @Test
    fun `generated checkpoint histories match the independent model after every action`() = runBlocking<Unit> {
        for (seed in 0L until WorkflowCheckpointLifecycleActionGenerator.SEED_COUNT) {
            val store = createStore()
            val name = "model-$seed"
            val id = "workflow"
            val generations = linkedMapOf<String, String>()
            var model = WorkflowCheckpointLifecycleModel.empty()
            WorkflowCheckpointLifecycleActionGenerator.generate(seed).forEachIndexed { step, action ->
                val expected = model.apply(action)
                val result = runCatching { execute(action, model, store, name, id, generations) }
                when (expected) {
                    is WorkflowCheckpointLifecycleOutcome.Rejected -> assertThat(result.exceptionOrNull())
                        .withFailMessage("seed $seed step $step ${action.describe()} must conflict")
                        .isInstanceOf(WorkflowCheckpointConflictException::class.java)
                    is WorkflowCheckpointLifecycleOutcome.Success,
                    is WorkflowCheckpointLifecycleOutcome.Observed,
                    -> assertThat(result.isSuccess)
                        .withFailMessage("seed $seed step $step ${action.describe()} failed: ${result.exceptionOrNull()}")
                        .isTrue()
                }
                model = expected.next
                assertStoreMatchesModel(store, name, id, model, generations, seed, step, action)
            }
        }
    }

    @Test
    fun `deleted-generation delete cannot remove recreated successor`() = runBlocking<Unit> {
        val store = createStore()
        val first = store.save(checkpoint("p2", "workflow"))
        store.delete("p2", "workflow", first.revision, first.checkpointGeneration)
        val successor = store.save(checkpoint("p2", "workflow", nextStepIndex = 7))

        val failure = runCatching {
            store.delete("p2", "workflow", first.revision, first.checkpointGeneration)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(store.load("p2", "workflow")).isEqualTo(successor)
    }

    @Test
    fun `every predecessor generation remains permanently fenced across three incarnations`() = runBlocking<Unit> {
        val store = createStore()
        val first = store.save(checkpoint("p3", "workflow", nextStepIndex = 1))
        store.delete("p3", "workflow", first.revision, first.checkpointGeneration)
        val second = store.save(checkpoint("p3", "workflow", nextStepIndex = 2))
        store.delete("p3", "workflow", second.revision, second.checkpointGeneration)
        val third = store.save(checkpoint("p3", "workflow", nextStepIndex = 3))

        listOf(first, second).forEach { predecessor ->
            val operations = listOf<suspend () -> Unit>(
                {
                    store.save(
                        checkpoint(
                            "p3",
                            "workflow",
                            nextStepIndex = 99,
                            checkpointGeneration = predecessor.checkpointGeneration,
                        ),
                        predecessor.revision,
                    )
                },
                { store.delete("p3", "workflow", predecessor.revision, predecessor.checkpointGeneration) },
                {
                    store.requireRecovery(
                        "p3",
                        "workflow",
                        predecessor.revision,
                        recoveryRecord(),
                        predecessor.checkpointGeneration,
                    )
                },
                {
                    store.clearRecovery(
                        "p3",
                        "workflow",
                        predecessor.revision,
                        predecessor.checkpointGeneration,
                    )
                },
            )
            operations.forEach { operation ->
                assertThat(runCatching { operation() }.exceptionOrNull())
                    .isInstanceOf(WorkflowCheckpointConflictException::class.java)
                assertThat(store.load("p3", "workflow")).isEqualTo(third)
            }
        }
    }

    @Test
    fun `revision advances only within its authoritative generation`() = runBlocking<Unit> {
        val store = createStore()
        val first = store.save(checkpoint("p4", "workflow"))
        val second = store.save(
            checkpoint("p4", "workflow", checkpointGeneration = first.checkpointGeneration),
            first.revision,
        )
        store.delete("p4", "workflow", second.revision, second.checkpointGeneration)
        val recreated = store.save(checkpoint("p4", "workflow"))

        assertThat(second.checkpointGeneration).isEqualTo(first.checkpointGeneration)
        assertThat(second.revision).isEqualTo(2)
        assertThat(recreated.revision).isEqualTo(1)
        assertThat(recreated.checkpointGeneration).isNotEqualTo(first.checkpointGeneration)
    }

    @Test
    fun `recovery transitions preserve generation and rejected stale operations are value-identical`() = runBlocking<Unit> {
        val store = createStore()
        val created = store.save(checkpoint("p5", "workflow"))
        val required = store.requireRecovery(
            "p5",
            "workflow",
            created.revision,
            recoveryRecord(),
            created.checkpointGeneration,
        )
        assertThat(required.revision).isEqualTo(2)
        assertThat(required.checkpointGeneration).isEqualTo(created.checkpointGeneration)

        assertThat(
            runCatching {
                store.clearRecovery("p5", "workflow", created.revision, created.checkpointGeneration)
            }.exceptionOrNull(),
        ).isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(store.load("p5", "workflow")).isEqualTo(required)

        val cleared = store.clearRecovery(
            "p5",
            "workflow",
            required.revision,
            required.checkpointGeneration,
        )
        assertThat(cleared.revision).isEqualTo(3)
        assertThat(cleared.checkpointGeneration).isEqualTo(created.checkpointGeneration)
        assertThat(cleared.recoveryState).isEqualTo(WorkflowRecoveryState.Normal)
    }

    @Test
    fun `successor lifecycle racing predecessor mutation never authorizes predecessor`() = runBlocking<Unit> {
        repeat(12) { round ->
            val store = createStore()
            val name = "p6-$round"
            val first = store.save(checkpoint(name, "workflow", nextStepIndex = 1))
            store.delete(name, "workflow", first.revision, first.checkpointGeneration)
            val successor = store.save(checkpoint(name, "workflow", nextStepIndex = 2))

            val outcomes = runInParallel(0 until 2) { index ->
                if (index == 0) {
                    runCatching {
                        store.save(
                            checkpoint(
                                name,
                                "workflow",
                                nextStepIndex = 3,
                                checkpointGeneration = successor.checkpointGeneration,
                            ),
                            successor.revision,
                        )
                    }
                } else {
                    runCatching {
                        store.save(
                            checkpoint(
                                name,
                                "workflow",
                                nextStepIndex = 99,
                                checkpointGeneration = first.checkpointGeneration,
                            ),
                            first.revision,
                        )
                    }
                }
            }

            assertThat(outcomes[0].isSuccess).isTrue()
            assertThat(outcomes[1].exceptionOrNull())
                .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            val final = store.load(name, "workflow")!!
            assertThat(final.checkpointGeneration).isEqualTo(successor.checkpointGeneration)
            assertThat(final.revision).isEqualTo(2)
            assertThat(final.nextStepIndex).isEqualTo(3)
        }
    }

    @Test
    fun `caller-supplied generation on create is ignored and the store token is authoritative`() = runBlocking<Unit> {
        val store = createStore()
        // The caller cannot choose or impersonate a generation: the store
        // mints its own token even when the create claims "evil/fake".
        val persisted = store.save(checkpoint("g-own", "workflow", checkpointGeneration = callerChosenGeneration))
        assertThat(persisted.checkpointGeneration).isNotBlank()
        assertThat(persisted.checkpointGeneration).isNotEqualTo(callerChosenGeneration)
        assertThat(store.load("g-own", "workflow")).isEqualTo(persisted)

        // The caller-chosen string is not a valid capability for the minted record.
        assertThat(
            runCatching {
                store.save(
                    checkpoint("g-own", "workflow", nextStepIndex = 4, checkpointGeneration = callerChosenGeneration),
                    persisted.revision,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(WorkflowCheckpointConflictException::class.java)

        // The store-minted token is the authoritative capability.
        val updated = store.save(
            checkpoint("g-own", "workflow", nextStepIndex = 4, checkpointGeneration = persisted.checkpointGeneration),
            persisted.revision,
        )
        assertThat(updated.revision).isEqualTo(2)
        assertThat(updated.checkpointGeneration).isEqualTo(persisted.checkpointGeneration)
    }

    @Test
    fun `every recreate mints a genuinely distinct generation`() = runBlocking<Unit> {
        val store = createStore()
        val first = store.save(checkpoint("g-distinct", "workflow", nextStepIndex = 1))
        store.delete("g-distinct", "workflow", first.revision, first.checkpointGeneration)
        val second = store.save(checkpoint("g-distinct", "workflow", nextStepIndex = 2))
        store.delete("g-distinct", "workflow", second.revision, second.checkpointGeneration)
        val third = store.save(checkpoint("g-distinct", "workflow", nextStepIndex = 3))

        assertThat(first.checkpointGeneration).isNotEqualTo(second.checkpointGeneration)
        assertThat(second.checkpointGeneration).isNotEqualTo(third.checkpointGeneration)
        assertThat(first.checkpointGeneration).isNotEqualTo(third.checkpointGeneration)
    }

    private suspend fun execute(
        action: WorkflowCheckpointLifecycleAction,
        model: WorkflowCheckpointLifecycleModel,
        store: WorkflowCheckpointStore,
        name: String,
        id: String,
        generations: MutableMap<String, String>,
    ) {
        val current = model.current
        val record = recoveryRecord()
        fun predecessor(index: Int): ModeledCheckpointGeneration = model.predecessors[index]
        when (action) {
            WorkflowCheckpointLifecycleAction.Create -> {
                val alias = "G${model.predecessors.size + 1}"
                val persisted = store.save(
                    checkpoint(
                        name,
                        id,
                        nextStepIndex = 1,
                        stepExecutions = 1,
                        statePayload = "state-create-${model.predecessors.size + 1}",
                        metadata = mapOf("incarnation" to "${model.predecessors.size + 1}"),
                        checkpointGeneration = callerChosenGeneration,
                    ),
                )
                // Generation is store-owned: a caller-chosen token is never persisted.
                assertThat(persisted.checkpointGeneration).isNotBlank()
                assertThat(persisted.checkpointGeneration).isNotEqualTo(callerChosenGeneration)
                // Every recreate mints a token distinct from all prior incarnations.
                assertThat(persisted.checkpointGeneration).isNotIn(generations.values)
                generations[alias] = requireNotNull(persisted.checkpointGeneration)
            }
            is WorkflowCheckpointLifecycleAction.UpdateCurrent -> store.save(
                checkpoint(
                    name,
                    id,
                    nextStepIndex = action.nextStepIndex,
                    stepExecutions = requireNotNull(current).stepExecutions + 1,
                    statePayload = action.statePayload,
                    metadata = current.metadata,
                    recoveryState = current.recoveryState.toRuntime(record),
                    checkpointGeneration = generations[current.generation],
                ),
                current.revision,
            )
            WorkflowCheckpointLifecycleAction.UpdateStaleRevision -> store.save(
                checkpoint(
                    name,
                    id,
                    checkpointGeneration = current?.let { generations[it.generation] },
                ),
                (current?.revision ?: 1) + 1,
            )
            is WorkflowCheckpointLifecycleAction.UpdatePredecessorGeneration -> predecessor(action.predecessorIndex).let {
                store.save(
                    checkpoint(name, id, checkpointGeneration = generations[it.generation]),
                    it.finalRevision,
                )
            }
            WorkflowCheckpointLifecycleAction.RequireRecoveryCurrent -> store.requireRecovery(
                name, id, requireNotNull(current).revision, record, generations[current.generation],
            )
            WorkflowCheckpointLifecycleAction.RequireRecoveryStaleRevision -> store.requireRecovery(
                name, id, (current?.revision ?: 1) + 1, record, current?.let { generations[it.generation] },
            )
            is WorkflowCheckpointLifecycleAction.RequireRecoveryPredecessor -> predecessor(action.predecessorIndex).let {
                store.requireRecovery(name, id, it.finalRevision, record, generations[it.generation])
            }
            WorkflowCheckpointLifecycleAction.ClearRecoveryCurrent -> store.clearRecovery(
                name, id, requireNotNull(current).revision, generations[current.generation],
            )
            WorkflowCheckpointLifecycleAction.ClearRecoveryStaleRevision -> store.clearRecovery(
                name, id, (current?.revision ?: 1) + 1, current?.let { generations[it.generation] },
            )
            is WorkflowCheckpointLifecycleAction.ClearRecoveryPredecessor -> predecessor(action.predecessorIndex).let {
                store.clearRecovery(name, id, it.finalRevision, generations[it.generation])
            }
            WorkflowCheckpointLifecycleAction.DeleteCurrent -> store.delete(
                name, id, requireNotNull(current).revision, generations[current.generation],
            )
            WorkflowCheckpointLifecycleAction.DeleteStaleRevision -> store.delete(
                name, id, (current?.revision ?: 1) + 1, current?.let { generations[it.generation] },
            )
            is WorkflowCheckpointLifecycleAction.DeletePredecessor -> predecessor(action.predecessorIndex).let {
                store.delete(name, id, it.finalRevision, generations[it.generation])
            }
            WorkflowCheckpointLifecycleAction.DeleteUnconditional -> store.delete(name, id)
            WorkflowCheckpointLifecycleAction.Observe -> store.load(name, id)
        }
    }

    private suspend fun assertStoreMatchesModel(
        store: WorkflowCheckpointStore,
        name: String,
        id: String,
        model: WorkflowCheckpointLifecycleModel,
        generations: Map<String, String>,
        seed: Long,
        step: Int,
        action: WorkflowCheckpointLifecycleAction,
    ) {
        val actual = store.load(name, id)
        val expected = model.current
        val message = "seed $seed step $step ${action.describe()}"
        if (expected == null) {
            assertThat(actual).withFailMessage(message).isNull()
            return
        }
        assertThat(actual).withFailMessage(message).isNotNull()
        actual!!
        assertThat(actual.checkpointGeneration).withFailMessage(message).isEqualTo(generations[expected.generation])
        assertThat(actual.revision).withFailMessage(message).isEqualTo(expected.revision)
        assertThat(actual.nextStepIndex).withFailMessage(message).isEqualTo(expected.nextStepIndex)
        assertThat(actual.stepExecutions).withFailMessage(message).isEqualTo(expected.stepExecutions)
        assertThat(actual.statePayload).withFailMessage(message).isEqualTo(expected.statePayload)
        assertThat(actual.metadata).withFailMessage(message).isEqualTo(expected.metadata)
        assertThat(actual.recoveryState).withFailMessage(message).isEqualTo(expected.recoveryState.toRuntime(recoveryRecord()))
    }

    private fun ModeledRecoveryState.toRuntime(record: WorkflowRecoveryRecord): WorkflowRecoveryState = when (this) {
        ModeledRecoveryState.Normal -> WorkflowRecoveryState.Normal
        ModeledRecoveryState.Required -> WorkflowRecoveryState.Required(record)
    }
}
