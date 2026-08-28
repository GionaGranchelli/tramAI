package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

abstract class StepAttemptRecordStoreContractTest {
    protected lateinit var harness: AttemptStoreHarness
    protected val store: StepAttemptRecordStore get() = harness.store

    protected abstract fun createHarness(): AttemptStoreHarness

    @BeforeEach
    fun setUpStore() {
        harness = createHarness()
    }

    @AfterEach
    fun closeStore() {
        harness.close()
    }

    @Test
    fun `1 record and retrieve full record`() {
        runBlocking {
            val record = fullRecord()
            assertThat(store.recordStepAttempt(record)).isEqualTo(record)
            assertThat(store.latestStepAttempt(record.runId, record.stepName)).isEqualTo(record)
        }
    }

    @Test
    fun `2 nullable fields round trip as null`() {
        runBlocking {
            val record = minimalRecord()
            store.recordStepAttempt(record)
            assertThat(store.listStepAttempts(record.runId)).containsExactly(record)
        }
    }

    @Test
    fun `3 record replaces existing identity`() {
        runBlocking {
            val original = minimalRecord()
            val replacement = original.copy(workerId = "replacement")
            store.recordStepAttempt(original)
            store.recordStepAttempt(replacement)
            assertThat(store.listStepAttempts(original.runId)).containsExactly(replacement)
        }
    }

    @Test
    fun `4 update changes existing record`() {
        runBlocking {
            val original = minimalRecord()
            val updated = original.copy(status = StepAttemptStatus.COMPLETED, completedAt = 20L)
            store.recordStepAttempt(original)
            assertThat(store.updateStepAttempt(updated)).isEqualTo(updated)
            assertThat(store.listStepAttempts(original.runId)).containsExactly(updated)
        }
    }

    @Test
    fun `5 update rejects missing record`() {
        assertThatThrownBy { runBlocking { store.updateStepAttempt(minimalRecord()) } }
            .isInstanceOf(WorkflowPersistenceFailureException::class.java)
            .hasMessage("Workflow persistence write failed")
            .hasNoCause()
    }

    @Test
    fun `6 successful CAS replaces exact expected`() {
        runBlocking {
            val original = minimalRecord()
            val updated = original.copy(status = StepAttemptStatus.FAILED)
            store.recordStepAttempt(original)
            assertThat(store.compareAndSetStepAttempt(original, updated)).isTrue()
            assertThat(store.listStepAttempts(original.runId)).containsExactly(updated)
        }
    }

    @Test
    fun `7 stale CAS preserves current record`() {
        runBlocking {
            val original = minimalRecord()
            val current = original.copy(workerId = "current")
            store.recordStepAttempt(current)
            assertThat(store.compareAndSetStepAttempt(original, original.copy(workerId = "stale"))).isFalse()
            assertThat(store.listStepAttempts(original.runId)).containsExactly(current)
        }
    }

    @Test
    fun `8 concurrent CAS has exactly one winner`() {
        runBlocking {
            val original = minimalRecord()
            val first = original.copy(workerId = "first")
            val second = original.copy(workerId = "second")
            store.recordStepAttempt(original)
            val results = listOf(first, second).map { updated ->
                async(Dispatchers.Default) { store.compareAndSetStepAttempt(original, updated) }
            }
            results.joinAll()
            assertThat(results.count { it.await() }).isEqualTo(1)
            assertThat(store.listStepAttempts(original.runId).single()).isIn(first, second)
        }
    }

    @Test
    fun `9 CAS cannot change attempt identity`() {
        runBlocking {
            val original = minimalRecord()
            store.recordStepAttempt(original)
            assertThat(store.compareAndSetStepAttempt(original, original.copy(attemptId = "other"))).isFalse()
            assertThat(store.compareAndSetStepAttempt(original, original.copy(stepName = "other"))).isFalse()
            assertThat(store.compareAndSetStepAttempt(original, original.copy(runId = "other"))).isFalse()
            assertThat(store.listStepAttempts(original.runId)).containsExactly(original)
        }
    }

    @Test
    fun `10 latest picks the max startedAt`() {
        runBlocking {
            listOf(
                minimalRecord(attemptId = "z", startedAt = 9),
                minimalRecord(attemptId = "a", startedAt = 10),
                minimalRecord(attemptId = "b", startedAt = 11),
            ).forEach { store.recordStepAttempt(it) }
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("b")
        }
    }

    @Test
    fun `11 list ordering is deterministic across reads`() {
        runBlocking {
            listOf(
                minimalRecord(stepName = "z", attemptId = "b", startedAt = 2),
                minimalRecord(stepName = "b", attemptId = "c", startedAt = 1),
                minimalRecord(stepName = "a", attemptId = "z", startedAt = 1),
                minimalRecord(stepName = "a", attemptId = "a", startedAt = 1),
            ).forEach { store.recordStepAttempt(it) }
            val first = store.listStepAttempts("run")
            // Primary keys (startedAt, stepName) order is contract across stores;
            // the equal-key tie authority is store-specific (in-memory: creation order).
            assertThat(first.map { "${it.startedAt}:${it.stepName}" }).containsExactly("1:a", "1:a", "1:b", "2:z")
            val second = store.listStepAttempts("run")
            assertThat(second).containsExactlyElementsOf(first)
        }
    }

    @Test
    fun `12 resolution approval survives recreation`() {
        runBlocking {
            val approved = fullRecord()
            store.recordStepAttempt(approved)
            val recreated = harness.recreate()
            assertThat(recreated.listStepAttempts(approved.runId)).containsExactly(approved)
        }
    }

    @Test
    fun `13 attempt evidence is independent from checkpoint deletion`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val record = fullRecord().copy(resolutionAction = StepAttemptResolutionAction.WORKFLOW_FAILED)
            val persisted = checkpointStore.save(testCheckpoint(record.runId))
            store.recordStepAttempt(record)
            checkpointStore.delete(
                "workflow", record.runId,
                expectedRevision = persisted.revision,
                expectedGeneration = persisted.checkpointGeneration,
            )
            assertThat(checkpointStore.load("workflow", record.runId)).isNull()
            assertThat(store.listStepAttempts(record.runId)).containsExactly(record)
        }
    }

    @Test
    fun `14 unknown status fails closed`() {
        harness.assertMalformedFailsClosed("status", "NOT_A_STATUS")
    }

    @Test
    fun `15 unknown replay policy fails closed`() {
        harness.assertMalformedFailsClosed("replayPolicy", "NOT_A_POLICY")
    }

    @Test
    fun `16 unknown resolution action fails closed`() {
        harness.assertMalformedFailsClosed("resolutionAction", base64UrlEncodeNoPadding("NOT_AN_ACTION"))
    }

    @Test
    fun `17 missing mandatory field fails closed`() {
        harness.assertMalformedFailsClosed("runId", null)
    }

    @Test
    fun `18 fingerprint mismatch fails closed`() {
        runBlocking {
            val record = minimalRecord()
            if (!harness.supportsPersistentCorruption) {
                assertThatThrownBy {
                    StepAttemptRecordCodec.requireValidFingerprint(record, "bad", "contract fixture")
                }.isInstanceOf(CorruptStepAttemptException::class.java)
                return@runBlocking
            }
            store.recordStepAttempt(record)
            harness.corruptFingerprint(record)
            assertThatThrownBy { runBlocking { store.listStepAttempts(record.runId) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
        }
    }

    @Test
    fun `19 cancellation does not partially replace a record`() {
        runBlocking {
            val original = minimalRecord()
            store.recordStepAttempt(original)
            harness.cancelReplacement(original, original.copy(workerId = "cancelled"))
            assertThat(store.listStepAttempts(original.runId)).containsExactly(original)
        }
    }

    @Test
    fun `20 store remains usable after cancellation and failed CAS`() {
        runBlocking {
            val original = minimalRecord()
            store.recordStepAttempt(original)
            harness.cancelReplacement(original, original.copy(workerId = "cancelled"))
            assertThat(store.compareAndSetStepAttempt(original.copy(workerId = "stale"), original.copy(workerId = "no"))).isFalse()
            val final = original.copy(workerId = "usable")
            assertThat(store.compareAndSetStepAttempt(original, final)).isTrue()
            assertThat(store.listStepAttempts(original.runId)).containsExactly(final)
        }
    }

    // ── #318 — durable creation-order authority (cross-store contract) ──────

    @Test
    fun `21 equal-time latest uses creation order`() {
        runBlocking {
            // Lexical attemptId order intentionally opposes creation order.
            store.recordStepAttempt(minimalRecord(attemptId = "z", startedAt = 10))
            store.recordStepAttempt(minimalRecord(attemptId = "a", startedAt = 10))
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("a")
        }
    }

    @Test
    fun `22 equal-key list preserves creation order across recreation`() {
        runBlocking {
            store.recordStepAttempt(minimalRecord(attemptId = "z", startedAt = 10))
            store.recordStepAttempt(minimalRecord(attemptId = "a", startedAt = 10))
            assertThat(store.listStepAttempts("run").map { it.attemptId }).containsExactly("z", "a")
            val recreated = harness.recreate()
            assertThat(recreated.listStepAttempts("run").map { it.attemptId }).containsExactly("z", "a")
        }
    }

    @Test
    fun `23 update preserves creation order`() {
        runBlocking {
            store.recordStepAttempt(minimalRecord(attemptId = "z", startedAt = 10))
            store.recordStepAttempt(minimalRecord(attemptId = "a", startedAt = 10))
            store.updateStepAttempt(minimalRecord(attemptId = "z", startedAt = 10).copy(status = StepAttemptStatus.COMPLETED, completedAt = 20L))
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("a")
            assertThat(store.listStepAttempts("run").map { it.attemptId }).containsExactly("z", "a")
        }
    }

    @Test
    fun `24 CAS preserves creation order`() {
        runBlocking {
            val first = minimalRecord(attemptId = "z", startedAt = 10)
            store.recordStepAttempt(first)
            store.recordStepAttempt(minimalRecord(attemptId = "a", startedAt = 10))
            assertThat(store.compareAndSetStepAttempt(first, first.copy(status = StepAttemptStatus.FAILED))).isTrue()
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("a")
            assertThat(store.listStepAttempts("run").map { it.attemptId }).containsExactly("z", "a")
        }
    }

    @Test
    fun `25 re-record preserves creation order`() {
        runBlocking {
            val first = minimalRecord(attemptId = "z", startedAt = 10)
            store.recordStepAttempt(first)
            store.recordStepAttempt(minimalRecord(attemptId = "a", startedAt = 10))
            // The contract allows recordStepAttempt to replace an existing identity;
            // a replacement is NOT a new attempt creation.
            store.recordStepAttempt(first.copy(workerId = "replacement"))
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("a")
            assertThat(store.listStepAttempts("run").map { it.attemptId }).containsExactly("z", "a")
        }
    }

    @Test
    fun `26 concurrent creates get distinct durable chronology`() {
        runBlocking {
            val ids = (1..4).map { "id$it" }
            val start = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(ids.size)
            val deferreds = ids.map { id ->
                async(Dispatchers.Default) {
                    start.await()
                    store.recordStepAttempt(minimalRecord(attemptId = id, startedAt = 10))
                    finished.countDown()
                }
            }
            start.countDown()
            assertThat(withContext(Dispatchers.IO) { finished.await(10, TimeUnit.SECONDS) }).isTrue()
            deferreds.joinAll()
            val listed = store.listStepAttempts("run")
            assertThat(listed.map { it.attemptId }).containsExactlyInAnyOrderElementsOf(ids)
            assertThat(listed.map { it.attemptId }.distinct()).hasSize(ids.size)
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo(listed.last().attemptId)
            val sequences = ids.map { harness.readSequence("run", "step", it) }
            if (sequences.all { it != null }) {
                assertThat(sequences.distinct()).hasSize(ids.size)
            }
        }
    }

    @Test
    fun `27 legacy records fall back deterministically`() {
        runBlocking {
            if (!harness.supportsLegacyRecords) return@runBlocking
            harness.writeLegacyRecord(minimalRecord(attemptId = "b", startedAt = 10))
            harness.writeLegacyRecord(minimalRecord(attemptId = "a", startedAt = 10))
            // Legacy-vs-legacy ties keep the deterministic attemptId fallback.
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("b")
            assertThat(store.listStepAttempts("run").map { it.attemptId }).containsExactly("a", "b")
            // A new sequenced record with equal startedAt outranks legacy chronology.
            store.recordStepAttempt(minimalRecord(attemptId = "new", startedAt = 10))
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("new")
        }
    }

    @Test
    fun `28 sequence corruption fails closed`() {
        runBlocking {
            if (!harness.supportsSequenceCorruption) return@runBlocking
            val record = minimalRecord()
            store.recordStepAttempt(record)
            harness.corruptSequence(record)
            assertThatThrownBy { runBlocking { store.listStepAttempts(record.runId) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
        }
    }

    @Test
    fun `32 counter regression cannot duplicate chronology`() {
        runBlocking {
            if (!harness.supportsSequenceReset) return@runBlocking
            listOf("a", "b", "c").forEach { store.recordStepAttempt(minimalRecord(attemptId = it, startedAt = 10)) }
            // Regress the run's counter authority below already-persisted sequences.
            harness.resetSequenceCounter("run", 1)
            store.recordStepAttempt(minimalRecord(attemptId = "d", startedAt = 10))
            // The new attempt must be sequenced above the durable maximum, never reusing 2.
            assertThat(harness.readSequence("run", "step", "d")).isNotNull().isGreaterThan(3)
        }
    }

    protected fun minimalRecord(
        runId: String = "run",
        stepName: String = "step",
        attemptId: String = "attempt",
        startedAt: Long = 10,
    ): StepAttemptRecord = StepAttemptRecord(
        runId = runId,
        stepName = stepName,
        attemptId = attemptId,
        workerId = "worker",
        leaseToken = "lease",
        status = StepAttemptStatus.STARTED,
        startedAt = startedAt,
        replayPolicy = ReplayPolicy.IDEMPOTENT,
    )

    protected fun fullRecord(): StepAttemptRecord = minimalRecord().copy(
        status = StepAttemptStatus.UNKNOWN,
        completedAt = 11,
        idempotencyKey = "idempotency\nkey",
        replayPolicy = ReplayPolicy.EXTERNALLY_IDEMPOTENT,
        inputFingerprint = "input=fingerprint",
        outputSummary = "summary\nwith unicode ☃",
        resolutionReason = "operator approved",
        resolutionAtEpochMillis = 12,
        resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
        approvedIdempotencyKey = "approved-key",
    )

    private fun testCheckpoint(runId: String) = WorkflowCheckpoint(
        workflowName = "workflow",
        workflowId = runId,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "state",
    )
}

interface AttemptStoreHarness {
    val store: StepAttemptRecordStore
    val supportsPersistentCorruption: Boolean get() = false
    val supportsLegacyRecords: Boolean get() = false
    val supportsSequenceCorruption: Boolean get() = false
    val supportsSequenceReset: Boolean get() = false
    fun recreate(): StepAttemptRecordStore = store
    fun close() = Unit

    /** Writes a legacy (pre-sequence) payload for [record] directly to storage. */
    suspend fun writeLegacyRecord(record: StepAttemptRecord) = Unit

    /** Corrupts the persisted creation sequence of [record] without touching record fields. */
    suspend fun corruptSequence(record: StepAttemptRecord) = Unit

    /** Regresses the run's sequence counter authority to [value]. */
    suspend fun resetSequenceCounter(runId: String, value: Long) = Unit

    /** Reads the persisted creation sequence of [record], or null when legacy/absent. */
    suspend fun readSequence(runId: String, stepName: String, attemptId: String): Long? = null

    fun assertMalformedFailsClosed(field: String, replacement: String?) {
        val lines = StepAttemptRecordCodec.encode(testCodecRecord()).lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$field=") }
        if (replacement == null) lines.removeAt(index) else lines[index] = "$field=$replacement"
        // The codec is internal; it fails closed with the internal corruption
        // carrier. The public fixed-text StepAttemptRecordCorruptionException is
        // proven at the store boundary (PersistenceSafeFailureBoundaryTest).
        assertThatThrownBy { StepAttemptRecordCodec.decode(lines.joinToString("\n")) }
            .isInstanceOf(CorruptStepAttemptException::class.java)
    }

    suspend fun corruptFingerprint(record: StepAttemptRecord) = Unit

    suspend fun corruptSchemaVersion(record: StepAttemptRecord) = Unit

    suspend fun cancelReplacement(original: StepAttemptRecord, updated: StepAttemptRecord) {
        val job = Job().also(Job::cancel)
        val deferred = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + job).async {
            store.compareAndSetStepAttempt(original, updated)
        }
        deferred.join()
    }
}

class InMemoryStepAttemptRecordStoreContractTest : StepAttemptRecordStoreContractTest() {
    override fun createHarness(): AttemptStoreHarness = object : AttemptStoreHarness {
        override val store = InMemoryWorkflowCheckpointStore()
    }
}

class FileStepAttemptRecordStoreContractTest : StepAttemptRecordStoreContractTest() {
    @TempDir
    lateinit var root: Path

    override fun createHarness(): AttemptStoreHarness = FileAttemptStoreHarness(root)

    @Test
    fun `file store rejects blank identity components`() {
        runBlocking {
            assertThatThrownBy { runBlocking { store.recordStepAttempt(minimalRecord(runId = "")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { store.recordStepAttempt(minimalRecord(stepName = "")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { store.recordStepAttempt(minimalRecord(attemptId = "")) } }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { runBlocking { store.listStepAttempts("") } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `file latest step attempt does not read unrelated step directories`() {
        runBlocking {
            store.recordStepAttempt(minimalRecord(stepName = "current", attemptId = "a1", startedAt = 1))
            // A corrupt record in an unrelated step directory must not break latest for "current":
            // latestStepAttempt resolves only the requested step's directory.
            val otherPath = root.resolve(base64UrlEncodeNoPadding("run"))
                .resolve(base64UrlEncodeNoPadding("other"))
                .resolve(base64UrlEncodeNoPadding("x") + ".attempt.properties")
            Files.createDirectories(otherPath.parent)
            Files.writeString(otherPath, "not a valid attempt record")
            assertThat(store.latestStepAttempt("run", "current")?.attemptId).isEqualTo("a1")
        }
    }
}

class JdbcStepAttemptRecordStoreContractTest : StepAttemptRecordStoreContractTest() {
    private lateinit var jdbcHarness: JdbcAttemptStoreHarness

    override fun createHarness(): AttemptStoreHarness {
        jdbcHarness = JdbcAttemptStoreHarness()
        return jdbcHarness
    }

    @Test
    fun `jdbc unknown record schema version fails closed`() {
        runBlocking {
            store.recordStepAttempt(minimalRecord())
            val recorded = store.listStepAttempts("run").single()
            jdbcHarness.corruptSchemaVersion(recorded)
            assertThatThrownBy { runBlocking { store.listStepAttempts("run") } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
                .hasMessage("Persisted step-attempt record is invalid")
                .hasNoCause()
        }
    }

    @Test
    fun `29 jdbc update cannot launder sequence corruption`() {
        runBlocking {
            val record = minimalRecord()
            store.recordStepAttempt(record)
            jdbcHarness.corruptSequence(record)
            // Updating must verify the existing row first; it must not re-sign a
            // corrupted sequence into valid persisted state.
            assertThatThrownBy { runBlocking { store.updateStepAttempt(record.copy(status = StepAttemptStatus.COMPLETED, completedAt = 20L)) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
            assertThatThrownBy { runBlocking { store.listStepAttempts(record.runId) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
        }
    }

    @Test
    fun `30 jdbc re-record cannot launder sequence corruption`() {
        runBlocking {
            val record = minimalRecord()
            store.recordStepAttempt(record)
            jdbcHarness.corruptSequence(record)
            assertThatThrownBy { runBlocking { store.recordStepAttempt(record.copy(workerId = "replacement")) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
        }
    }

    @Test
    fun `31 jdbc CAS fails closed on sequence corruption`() {
        runBlocking {
            val record = minimalRecord()
            store.recordStepAttempt(record)
            jdbcHarness.corruptSequence(record)
            assertThatThrownBy { runBlocking { store.compareAndSetStepAttempt(record, record.copy(status = StepAttemptStatus.FAILED)) } }
                .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
        }
    }
}

private class FileAttemptStoreHarness(private val root: Path) : AttemptStoreHarness {
    private val blockWrite = AtomicBoolean()
    private val writeStarted = CountDownLatch(1)
    override val store: StepAttemptRecordStore = FileStepAttemptRecordStore.forTest(
        root,
        AtomicFileWriter {
            if (blockWrite.get()) {
                writeStarted.countDown()
                Thread.sleep(30_000)
            }
        },
    )
    override val supportsPersistentCorruption = true
    override val supportsLegacyRecords = true
    override val supportsSequenceCorruption = true
    override val supportsSequenceReset = true

    override fun recreate(): StepAttemptRecordStore = FileStepAttemptRecordStore(root)

    override suspend fun resetSequenceCounter(runId: String, value: Long) {
        val path = root.resolve(base64UrlEncodeNoPadding(runId)).resolve(".attempt-sequence")
        Files.writeString(path, value.toString())
    }

    override suspend fun writeLegacyRecord(record: StepAttemptRecord) {
        val path = root.resolve(base64UrlEncodeNoPadding(record.runId))
            .resolve(base64UrlEncodeNoPadding(record.stepName))
            .resolve(base64UrlEncodeNoPadding(record.attemptId) + ".attempt.properties")
        Files.createDirectories(path.parent)
        Files.writeString(path, StepAttemptRecordCodec.encode(record) + "record_hash=${StepAttemptRecordCodec.fingerprint(record)}\n")
    }

    override suspend fun corruptSequence(record: StepAttemptRecord) {
        val path = root.resolve(base64UrlEncodeNoPadding(record.runId))
            .resolve(base64UrlEncodeNoPadding(record.stepName))
            .resolve(base64UrlEncodeNoPadding(record.attemptId) + ".attempt.properties")
        Files.writeString(path, Files.readString(path).replace(Regex("attemptSequence=.*"), "attemptSequence=999"))
    }

    override suspend fun readSequence(runId: String, stepName: String, attemptId: String): Long? {
        val path = root.resolve(base64UrlEncodeNoPadding(runId))
            .resolve(base64UrlEncodeNoPadding(stepName))
            .resolve(base64UrlEncodeNoPadding(attemptId) + ".attempt.properties")
        if (!Files.exists(path)) return null
        return Regex("attemptSequence=(\\d+)").find(Files.readString(path))?.groupValues?.get(1)?.toLongOrNull()
    }

    override suspend fun corruptFingerprint(record: StepAttemptRecord) {
        val path = root.resolve(base64UrlEncodeNoPadding(record.runId))
            .resolve(base64UrlEncodeNoPadding(record.stepName))
            .resolve(base64UrlEncodeNoPadding(record.attemptId) + ".attempt.properties")
        Files.writeString(path, Files.readString(path).replace(Regex("record_hash=.*"), "record_hash=bad"))
    }

    override suspend fun cancelReplacement(original: StepAttemptRecord, updated: StepAttemptRecord) {
        blockWrite.set(true)
        val job = Job()
        val deferred = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + job).async {
            store.compareAndSetStepAttempt(original, updated)
        }
        assertThat(withContext(Dispatchers.IO) { writeStarted.await(5, TimeUnit.SECONDS) }).isTrue()
        job.cancel()
        deferred.join()
        blockWrite.set(false)
    }
}

private class JdbcAttemptStoreHarness : AttemptStoreHarness {
    private val pool = JdbcConnectionPool.create("jdbc:h2:mem:attempt_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val dataSource: DataSource = pool
    override val store = JdbcStepAttemptRecordStore(dataSource)
    override val supportsPersistentCorruption = true
    override val supportsSequenceCorruption = true
    override val supportsSequenceReset = true

    init {
        dataSource.connection.use { conn ->
            store.createSchemaSql().forEach { sql -> conn.createStatement().use { it.execute(sql) } }
        }
    }

    override fun recreate(): StepAttemptRecordStore = JdbcStepAttemptRecordStore(dataSource)

    override suspend fun resetSequenceCounter(runId: String, value: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE tramai_workflow_step_attempt_sequence SET next_value = ? WHERE run_id = ?").use { statement ->
                statement.setLong(1, value)
                statement.setString(2, runId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun corruptSequence(record: StepAttemptRecord) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE tramai_workflow_step_attempt SET attempt_sequence = ? WHERE run_id = ? AND step_name = ? AND attempt_id = ?",
            ).use { statement ->
                statement.setLong(1, 999)
                statement.setString(2, record.runId)
                statement.setString(3, record.stepName)
                statement.setString(4, record.attemptId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun readSequence(runId: String, stepName: String, attemptId: String): Long? = try {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT attempt_sequence FROM tramai_workflow_step_attempt WHERE run_id = ? AND step_name = ? AND attempt_id = ?",
            ).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, stepName)
                statement.setString(3, attemptId)
                statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getLong(1) else null }
            }
        }
    } catch (_: java.sql.SQLException) {
        null
    }

    override suspend fun corruptFingerprint(record: StepAttemptRecord) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE tramai_workflow_step_attempt SET record_hash = ? WHERE run_id = ? AND step_name = ? AND attempt_id = ?",
            ).use { statement ->
                statement.setString(1, "bad")
                statement.setString(2, record.runId)
                statement.setString(3, record.stepName)
                statement.setString(4, record.attemptId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun corruptSchemaVersion(record: StepAttemptRecord) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE tramai_workflow_step_attempt SET record_schema_version = ? WHERE run_id = ? AND step_name = ? AND attempt_id = ?",
            ).use { statement ->
                statement.setString(1, "999")
                statement.setString(2, record.runId)
                statement.setString(3, record.stepName)
                statement.setString(4, record.attemptId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun cancelReplacement(original: StepAttemptRecord, updated: StepAttemptRecord) {
        val updateStarted = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val blockingStore = JdbcStepAttemptRecordStore(
            blockingUpdateDataSource(dataSource, updateStarted, releaseUpdate),
        )
        val job = Job()
        val deferred = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + job).async {
            blockingStore.compareAndSetStepAttempt(original, updated)
        }
        assertThat(withContext(Dispatchers.IO) { updateStarted.await(5, TimeUnit.SECONDS) }).isTrue()
        job.cancel()
        deferred.join()
        releaseUpdate.countDown()
    }

    override fun close() = pool.dispose()
}

private fun blockingUpdateDataSource(
    delegate: DataSource,
    updateStarted: CountDownLatch,
    releaseUpdate: CountDownLatch,
): DataSource = Proxy.newProxyInstance(
    DataSource::class.java.classLoader,
    arrayOf(DataSource::class.java),
) { _, method, args ->
    val result = invokeReflectively(delegate, method, args)
    if (method.name == "getConnection" && result is Connection) {
        blockingUpdateConnection(result, updateStarted, releaseUpdate)
    } else {
        result
    }
} as DataSource

private fun blockingUpdateConnection(
    delegate: Connection,
    updateStarted: CountDownLatch,
    releaseUpdate: CountDownLatch,
): Connection = Proxy.newProxyInstance(
    Connection::class.java.classLoader,
    arrayOf(Connection::class.java),
) { _, method, args ->
    val result = invokeReflectively(delegate, method, args)
    if (method.name == "prepareStatement" && result is PreparedStatement) {
        blockingUpdateStatement(result, updateStarted, releaseUpdate)
    } else {
        result
    }
} as Connection

private fun blockingUpdateStatement(
    delegate: PreparedStatement,
    updateStarted: CountDownLatch,
    releaseUpdate: CountDownLatch,
): PreparedStatement = Proxy.newProxyInstance(
    PreparedStatement::class.java.classLoader,
    arrayOf(PreparedStatement::class.java),
) { _, method, args ->
    when (method.name) {
        "executeUpdate" -> {
            updateStarted.countDown()
            releaseUpdate.await()
            invokeReflectively(delegate, method, args)
        }
        "cancel" -> {
            releaseUpdate.countDown()
            invokeReflectively(delegate, method, args)
        }
        else -> invokeReflectively(delegate, method, args)
    }
} as PreparedStatement

private fun invokeReflectively(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (error: InvocationTargetException) {
    throw error.targetException
}

private fun testCodecRecord() = StepAttemptRecord(
    runId = "run",
    stepName = "step",
    attemptId = "attempt",
    workerId = "worker",
    leaseToken = "lease",
    status = StepAttemptStatus.UNKNOWN,
    startedAt = 1,
    replayPolicy = ReplayPolicy.NON_REPLAYABLE,
    resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
)
