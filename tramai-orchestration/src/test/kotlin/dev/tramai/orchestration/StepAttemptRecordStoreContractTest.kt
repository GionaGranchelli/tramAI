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
            .isInstanceOfAny(IllegalStateException::class.java, IllegalArgumentException::class.java)
            .hasMessageContaining("does not exist")
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
            if (!harness.supportsIdentityFencing) {
                assertThat(original.sameIdentityAs(original.copy(attemptId = "other"))).isFalse()
                return@runBlocking
            }
            store.recordStepAttempt(original)
            assertThat(store.compareAndSetStepAttempt(original, original.copy(attemptId = "other"))).isFalse()
            assertThat(store.compareAndSetStepAttempt(original, original.copy(stepName = "other"))).isFalse()
            assertThat(store.compareAndSetStepAttempt(original, original.copy(runId = "other"))).isFalse()
            assertThat(store.listStepAttempts(original.runId)).containsExactly(original)
        }
    }

    @Test
    fun `10 latest uses startedAt then attemptId`() {
        runBlocking {
            listOf(
                minimalRecord(attemptId = "z", startedAt = 9),
                minimalRecord(attemptId = "a", startedAt = 10),
                minimalRecord(attemptId = "b", startedAt = 10),
            ).forEach { store.recordStepAttempt(it) }
            assertThat(store.latestStepAttempt("run", "step")?.attemptId).isEqualTo("b")
        }
    }

    @Test
    fun `11 list ordering is deterministic`() {
        runBlocking {
            listOf(
                minimalRecord(stepName = "z", attemptId = "b", startedAt = 2),
                minimalRecord(stepName = "b", attemptId = "c", startedAt = 1),
                minimalRecord(stepName = "a", attemptId = "z", startedAt = 1),
                minimalRecord(stepName = "a", attemptId = "a", startedAt = 1),
            ).forEach { store.recordStepAttempt(it) }
            assertThat(store.listStepAttempts("run").map { "${it.startedAt}:${it.stepName}:${it.attemptId}" })
                .containsExactly("1:a:a", "1:a:z", "1:b:c", "2:z:b")
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
            checkpointStore.save(testCheckpoint(record.runId))
            store.recordStepAttempt(record)
            checkpointStore.delete("workflow", record.runId, expectedRevision = 1)
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
                }.isInstanceOf(StepAttemptRecordCorruptionException::class.java)
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
    val supportsIdentityFencing: Boolean get() = true
    fun recreate(): StepAttemptRecordStore = store
    fun close() = Unit

    fun assertMalformedFailsClosed(field: String, replacement: String?) {
        val lines = StepAttemptRecordCodec.encode(testCodecRecord()).lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("$field=") }
        if (replacement == null) lines.removeAt(index) else lines[index] = "$field=$replacement"
        assertThatThrownBy { StepAttemptRecordCodec.decode(lines.joinToString("\n")) }
            .isInstanceOf(StepAttemptRecordCorruptionException::class.java)
    }

    suspend fun corruptFingerprint(record: StepAttemptRecord) = Unit

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
        override val supportsIdentityFencing = false
    }
}

class FileStepAttemptRecordStoreContractTest : StepAttemptRecordStoreContractTest() {
    @TempDir
    lateinit var root: Path

    override fun createHarness(): AttemptStoreHarness = FileAttemptStoreHarness(root)
}

class JdbcStepAttemptRecordStoreContractTest : StepAttemptRecordStoreContractTest() {
    override fun createHarness(): AttemptStoreHarness = JdbcAttemptStoreHarness()
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

    override fun recreate(): StepAttemptRecordStore = FileStepAttemptRecordStore(root)

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

    init {
        dataSource.connection.use { conn -> conn.createStatement().use { it.execute(store.createTableSql()) } }
    }

    override fun recreate(): StepAttemptRecordStore = JdbcStepAttemptRecordStore(dataSource)

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

private fun StepAttemptRecord.sameIdentityAs(other: StepAttemptRecord): Boolean =
    runId == other.runId && stepName == other.stepName && attemptId == other.attemptId
