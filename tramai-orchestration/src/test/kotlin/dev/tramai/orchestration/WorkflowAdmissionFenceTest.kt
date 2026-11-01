package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
