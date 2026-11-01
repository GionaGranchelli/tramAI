package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.7.7a slice (b): execution admission.
 *
 * Cancellation alone cannot stop a call that starts a moment later, so the authority decides admission with the
 * same conditional write it uses to cancel. The two tests that matter are the ordering ones: admitted-then-cancelled
 * must be uncertain, cancelled-then-admitted must be refused. Everything else here is the fencing that makes those
 * two verdicts mean something.
 *
 * The runner does not call [WorkflowCheckpointStore.admit] yet, so nothing here claims a running worker is stopped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowAdmissionFenceTest {
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL(H2_URL)
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        JdbcWorkflowCheckpointStore(ds).let { store ->
            connect().use { conn -> conn.createStatement().use { it.execute(store.createTableSql()) } }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { connect().use { conn -> conn.createStatement().use { it.execute("DROP ALL OBJECTS") } } }
    }

    @Test
    fun `work admitted before a cancellation makes that cancellation uncertain`() {
        val store = jdbcStore()
        val saved = runBlocking { store.save(checkpoint("uncertain")) }

        val admission = runBlocking { store.admit(admissionRequest(saved)) }
        assertTrue(admission.admitted, "nothing has cancelled this run yet")

        val cancelled = runBlocking { store.cancel(request(saved)) }

        assertEquals(
            WorkflowLifecycleStatus.CANCELLED_UNCERTAIN,
            cancelled.status,
            "an admitted call may already have landed, so this can never be a clean cancellation",
        )
        assertEquals(cancelled, runBlocking { store.lifecycle("checkout", "uncertain") })
    }

    @Test
    fun `a cancellation refuses every later admission`() {
        val store = jdbcStore()
        val saved = runBlocking { store.save(checkpoint("refused")) }
        val cancelled = runBlocking { store.cancel(request(saved)) }
        val current = runBlocking { store.load("checkout", "refused") }!!

        val admission = runBlocking { store.admit(admissionRequest(current)) }

        assertEquals(false, admission.admitted, "a committed cancellation wins the row")
        assertEquals(cancelled.status, admission.cancellation?.status)
        assertEquals(cancelled.revision, admission.cancellation?.revision)
        assertEquals(0L, admission.sequence, "a refused admission does not advance the sequence")
    }

    @Test
    fun `a run with no admission at all still cancels cleanly`() {
        val store = jdbcStore()
        val saved = runBlocking { store.save(checkpoint("clean")) }

        val cancelled = runBlocking { store.cancel(request(saved)) }

        assertEquals(WorkflowLifecycleStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `admission is generation fenced as well as revision fenced`() {
        val store = jdbcStore()
        val saved = runBlocking { store.save(checkpoint("stale-generation")) }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking {
                store.admit(
                    WorkflowAdmissionRequest(
                        workflowName = "checkout",
                        workflowId = "stale-generation",
                        expectedRevision = saved.revision,
                        expectedGeneration = "generation-from-a-previous-life",
                    ),
                )
            }
        }

        assertNull(runBlocking { store.lifecycle("checkout", "stale-generation") })
    }

    @Test
    fun `admissions count up without moving the checkpoint revision`() {
        val store = jdbcStore()
        val saved = runBlocking { store.save(checkpoint("counting")) }

        val first = runBlocking { store.admit(admissionRequest(saved)) }
        val second = runBlocking { store.admit(admissionRequest(saved)) }

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(
            saved.revision,
            runBlocking { store.load("checkout", "counting") }?.revision,
            "admission is not a checkpoint write, so it must not invalidate a saver's CAS",
        )
    }

    @Test
    fun `the in-memory store derives the same verdicts`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint("memory")) }

        runBlocking { store.admit(admissionRequest(saved)) }
        val cancelled = runBlocking { store.cancel(request(saved)) }
        assertEquals(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN, cancelled.status)

        val current = runBlocking { store.load("checkout", "memory") }!!
        assertEquals(false, runBlocking { store.admit(admissionRequest(current)) }.admitted)
    }

    @Test
    fun `admission fails closed on a schema that predates the columns`() {
        val legacy = JdbcWorkflowCheckpointStore(dataSource, JdbcWorkflowCheckpointTable("tramai_admission_legacy"))
        connect().use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(legacy.createTableSql())
                DROPPED_COLUMNS.forEach { column ->
                    statement.execute("ALTER TABLE tramai_admission_legacy DROP COLUMN $column")
                }
            }
        }
        val saved = runBlocking { legacy.save(checkpoint("legacy-admission")) }

        // No admission column means the fence cannot be decided; admitting anyway would be an unfenced admission.
        assertFailsWith<WorkflowPersistenceFailureException> {
            runBlocking { legacy.admit(admissionRequest(saved)) }
        }
    }

    @Test
    fun `a store that cannot fence execution refuses to admit`() {
        val store = MarkdownWorkflowCheckpointStore(Files.createTempDirectory("admission-refusal"))
        val saved = runBlocking { store.save(checkpoint("unsupported")) }

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking { store.admit(admissionRequest(saved)) }
        }
    }

    @Test
    fun `concurrent admissions on separate connections receive distinct receipts`() {
        val saved = runBlocking { JdbcWorkflowCheckpointStore(dataSource).save(checkpoint("concurrent")) }
        val request = admissionRequest(saved)
        val callers = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            val futures =
                (1..callers).map {
                    pool.submit(
                        Callable {
                            start.await()
                            runBlocking { JdbcWorkflowCheckpointStore(dataSource).admit(request) }
                        },
                    )
                }
            start.countDown()
            val granted = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertTrue(granted.all { it.admitted }, "no cancellation is in play, so every caller is admitted")
            assertEquals(
                (1L..callers.toLong()).toSet(),
                granted.map { it.sequence }.toSet(),
                "each grant must carry its own sequence: a shared one is not a permit identity",
            )
            assertEquals(
                callers.toLong(),
                persistedAdmissionSequence("concurrent"),
                "the stored counter must equal the number of grants",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a granted admission does not read its receipt back after the increment`() {
        val recording = RecordingDataSource(dataSource)
        val store = JdbcWorkflowCheckpointStore(recording)
        val saved = runBlocking { JdbcWorkflowCheckpointStore(dataSource).save(checkpoint("receipt")) }
        recording.statements.clear()

        val admission = runBlocking { store.admit(admissionRequest(saved)) }
        assertTrue(admission.admitted)

        // A receipt read after the write can observe another caller's increment, so two grants may return the same
        // sequence. The receipt therefore has to come from the write's own locked read, never from a later SELECT.
        val statements = recording.statements.map { it.trimStart() }
        val incrementIndex = statements.indexOfLast { it.startsWith("UPDATE") }
        assertTrue(incrementIndex >= 0, "the admission must increment the counter")
        val readBackAfterIncrement =
            statements.drop(incrementIndex + 1).any { it.startsWith("SELECT") && it.contains("admission") }
        assertFalse(readBackAfterIncrement, "the receipt must not be read back after the increment")
    }

    private fun persistedAdmissionSequence(workflowId: String): Long =
        connect().use { connection ->
            val sql = "SELECT admission_seq FROM ${JdbcWorkflowCheckpointTable().tableName} WHERE workflow_id = ?"
            connection.prepareStatement(sql).use { it.readSequence(workflowId) }
        }

    private fun java.sql.PreparedStatement.readSequence(workflowId: String): Long {
        setString(1, workflowId)
        return executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getLong(1) else -1L }
    }

    /** Records the SQL a store prepares, so statement order can be asserted without touching timing. */
    private class RecordingDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val statements = CopyOnWriteArrayList<String>()

        override fun getConnection(): Connection = record(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = record(delegate.getConnection(username, password))

        private fun record(connection: Connection): Connection =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                val result = method.invoke(connection, *(args ?: emptyArray()))
                if (method.name == "prepareStatement" && args != null && args.isNotEmpty()) {
                    (args[0] as? String)?.let { statements += it }
                }
                result
            } as Connection
    }

    private fun jdbcStore() = JdbcWorkflowCheckpointStore(dataSource)

    private fun connect() = DriverManager.getConnection(H2_URL, "sa", "")

    private fun checkpoint(workflowId: String) =
        WorkflowCheckpoint(
            workflowName = "checkout",
            workflowId = workflowId,
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "{}",
            metadata = emptyMap(),
            savedAtEpochMillis = 1_700_000_000_000,
        )

    private fun admissionRequest(saved: WorkflowCheckpoint) =
        WorkflowAdmissionRequest(
            workflowName = saved.workflowName,
            workflowId = saved.workflowId,
            expectedRevision = saved.revision,
            expectedGeneration = saved.checkpointGeneration,
        )

    private fun request(saved: WorkflowCheckpoint) =
        WorkflowCancellationRequest(
            workflowName = saved.workflowName,
            workflowId = saved.workflowId,
            expectedRevision = saved.revision,
            expectedGeneration = saved.checkpointGeneration,
            actor = "operator-7",
            reason = "incident-42",
            cancelledAtEpochMillis = 1_700_000_000_500,
        )

    private companion object {
        const val H2_URL = "jdbc:h2:mem:admission_fence;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        val DROPPED_COLUMNS =
            listOf(
                "lifecycle_status",
                "cancelled_at_epoch_millis",
                "cancelled_by",
                "cancellation_reason",
                "admission_seq",
            )
    }
}
