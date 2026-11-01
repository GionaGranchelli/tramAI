package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.7.7a slice (a): the lifecycle authority itself — conditional cancellation, generation fenced, idempotent,
 * retained, and refused by profiles that cannot answer atomically.
 *
 * These are store-level tests on purpose. The execution fences (B1-B5) are a later slice and are held until the
 * execution-admission race has its own contract, so nothing here claims that a running worker is stopped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowCancellationAuthorityTest {
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
    fun `a cancellation commits at the next revision and records who cancelled`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }

        val cancelled = runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration)) }

        assertEquals(WorkflowLifecycleStatus.CANCELLED, cancelled.status)
        assertEquals(saved.revision + 1, cancelled.revision, "the cancellation is the row's next revision")
        assertEquals(saved.checkpointGeneration, cancelled.checkpointGeneration)
        assertEquals("operator-7", cancelled.cancelledBy)
        assertEquals("incident-42", cancelled.reason)
        assertEquals(cancelled, runBlocking { store.lifecycle("checkout", "run-1") })
    }

    @Test
    fun `cancelling twice reports one linearization point, not two`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }

        val first = runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration)) }
        val second = runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration)) }

        assertEquals(first, second, "a repeated command returns the recorded state")
        assertEquals(saved.revision + 1, second.revision, "the revision is not advanced again")

        val current = runBlocking { store.load("checkout", "run-1") }!!.revision
        val third = runBlocking { store.cancel(request(current, saved.checkpointGeneration)) }
        assertEquals(first, third, "a retry against the committed revision must not re-linearize")
        assertEquals(saved.revision + 1, runBlocking { store.load("checkout", "run-1") }?.revision)
    }

    @Test
    fun `a stale revision cannot cancel`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }
        runBlocking {
            store.save(
                checkpoint(nextStepIndex = 3).copy(checkpointGeneration = saved.checkpointGeneration),
                expectedRevision = saved.revision,
            )
        }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration)) }
        }
        assertNull(runBlocking { store.lifecycle("checkout", "run-1") }, "a refused cancel records nothing")
    }

    @Test
    fun `a stale generation cannot cancel even when the revision matches`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { store.cancel(request(saved.revision, "generation-from-another-run")) }
        }
        assertNull(runBlocking { store.lifecycle("checkout", "run-1") })
    }

    @Test
    fun `an uncertain cancellation is never reported as a clean one`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }

        val cancelled =
            runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration, uncertain = true)) }

        assertEquals(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN, cancelled.status)
        assertEquals(
            WorkflowLifecycleStatus.CANCELLED_UNCERTAIN,
            runBlocking { store.lifecycle("checkout", "run-1") }?.status,
        )
    }

    @Test
    fun `a cancelled run's checkpoint cannot be deleted`() {
        val store = InMemoryWorkflowCheckpointStore()
        val saved = runBlocking { store.save(checkpoint()) }
        val cancelled = runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration)) }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking {
                store.delete("checkout", "run-1", cancelled.revision, cancelled.checkpointGeneration)
            }
        }
        assertNotNull(runBlocking { store.load("checkout", "run-1") }, "the cancellation evidence is retained")
    }

    @Test
    fun `cancelling a run that does not exist is a conflict`() {
        val store = InMemoryWorkflowCheckpointStore()

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { store.cancel(request(1, null)) }
        }
    }

    @Test
    fun `a store that cannot record cancellation refuses instead of cancelling locally`() {
        val markdown =
            MarkdownWorkflowCheckpointStore(
                java.nio.file.Files
                    .createTempDirectory("cancel-refusal"),
            )

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking { markdown.cancel(request(1, null)) }
        }
        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking { markdown.lifecycle("checkout", "run-1") }
        }
    }

    @Test
    fun `jdbc cancellation is durable and survives a new store instance`() {
        val store = JdbcWorkflowCheckpointStore(dataSource)
        val saved = runBlocking { store.save(checkpoint(workflowId = "jdbc-durable")) }

        val cancelled =
            runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration, "jdbc-durable")) }

        // A fresh instance over the same database is the only witness that survives a process restart.
        val restarted = JdbcWorkflowCheckpointStore(dataSource)
        assertEquals(cancelled, runBlocking { restarted.lifecycle("checkout", "jdbc-durable") })
    }

    @Test
    fun `jdbc cancellation refuses a stale generation and records nothing`() {
        val store = JdbcWorkflowCheckpointStore(dataSource)
        val saved = runBlocking { store.save(checkpoint(workflowId = "jdbc-aba")) }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { store.cancel(request(saved.revision, "stale-generation", "jdbc-aba")) }
        }
        assertNull(runBlocking { store.lifecycle("checkout", "jdbc-aba") })
    }

    @Test
    fun `jdbc cancellation is idempotent`() {
        val store = JdbcWorkflowCheckpointStore(dataSource)
        val saved = runBlocking { store.save(checkpoint(workflowId = "jdbc-idempotent")) }

        val first = runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration, "jdbc-idempotent")) }
        val second =
            runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration, "jdbc-idempotent")) }
        assertEquals(first, second)
        assertEquals(saved.revision + 1, runBlocking { store.load("checkout", "jdbc-idempotent") }?.revision)

        // The retry an operator actually makes: re-read the run, then cancel again. Its expected revision is the
        // committed one, so only the "never overwrite a committed cancellation" guard can refuse a second
        // write here. Without it this call would silently own a second linearization point.
        val current = runBlocking { store.load("checkout", "jdbc-idempotent") }!!.revision
        val third = runBlocking { store.cancel(request(current, saved.checkpointGeneration, "jdbc-idempotent")) }
        assertEquals(first, third, "a retry against the committed revision must not re-linearize")
        assertEquals(current, runBlocking { store.load("checkout", "jdbc-idempotent") }?.revision)
    }

    @Test
    fun `jdbc refuses to delete a cancelled checkpoint`() {
        val store = JdbcWorkflowCheckpointStore(dataSource)
        val saved = runBlocking { store.save(checkpoint(workflowId = "jdbc-retention")) }
        val cancelled =
            runBlocking { store.cancel(request(saved.revision, saved.checkpointGeneration, "jdbc-retention")) }

        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking {
                store.delete("checkout", "jdbc-retention", cancelled.revision, cancelled.checkpointGeneration)
            }
        }
        assertNotNull(runBlocking { store.load("checkout", "jdbc-retention") })
    }

    @Test
    fun `a migrated table records cancellations and keeps reading legacy rows`() {
        val legacy =
            JdbcWorkflowCheckpointStore(dataSource, JdbcWorkflowCheckpointTable(tableName = "tramai_legacy_cp"))
        connect().use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(legacy.createTableSql())
                // The pre-cancellation schema: the same table without the lifecycle columns, which is what an
                // operator upgrading an existing deployment actually has.
                LIFECYCLE_COLUMNS.forEach { column ->
                    statement.execute("ALTER TABLE tramai_legacy_cp DROP COLUMN $column")
                }
            }
        }
        val saved = runBlocking { legacy.save(checkpoint(workflowId = "migrated")) }
        // Existing rows keep loading: the migration is additive, and nothing about ordinary checkpoint reads
        // depends on the lifecycle columns.
        assertNotNull(runBlocking { legacy.load("checkout", "migrated") })
        // Before the migration the lifecycle question cannot be answered at all. It fails closed rather than
        // reporting "not cancelled" from a table that has no such column — the upgrade is a precondition, not
        // an optional step.
        assertFailsWith<WorkflowPersistenceFailureException> {
            runBlocking { legacy.lifecycle("checkout", "migrated") }
        }

        connect().use { conn ->
            conn.createStatement().use { statement -> legacy.lifecycleMigrationSql().forEach(statement::execute) }
        }

        val cancelled = runBlocking { legacy.cancel(request(saved.revision, saved.checkpointGeneration, "migrated")) }
        assertEquals(WorkflowLifecycleStatus.CANCELLED, cancelled.status)
        assertEquals("incident-42", runBlocking { legacy.lifecycle("checkout", "migrated") }?.reason)
    }

    @Test
    fun `a schema without the lifecycle columns can still delete a checkpoint`() {
        val legacy =
            JdbcWorkflowCheckpointStore(
                dataSource,
                JdbcWorkflowCheckpointTable(tableName = "tramai_legacy_delete"),
            )
        connect().use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(legacy.createTableSql())
                LIFECYCLE_COLUMNS.forEach { column ->
                    statement.execute("ALTER TABLE tramai_legacy_delete DROP COLUMN $column")
                }
            }
        }
        val saved = runBlocking { legacy.save(checkpoint(workflowId = "legacy-delete")) }

        // Run completion must keep working on an un-migrated schema: no lifecycle column means no cancellation
        // can be recorded, so there is no evidence to retain.
        runBlocking { legacy.delete("checkout", "legacy-delete", saved.revision, saved.checkpointGeneration) }

        assertNull(runBlocking { legacy.load("checkout", "legacy-delete") })
    }

    private fun connect() = DriverManager.getConnection(H2_URL, "sa", "")

    private fun checkpoint(
        workflowId: String = "run-1",
        nextStepIndex: Int = 0,
    ) = WorkflowCheckpoint(
        workflowName = "checkout",
        workflowId = workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "{}",
        metadata = emptyMap(),
        savedAtEpochMillis = 1_700_000_000_000,
    )

    private fun request(
        expectedRevision: Long,
        expectedGeneration: String?,
        workflowId: String = "run-1",
        uncertain: Boolean = false,
    ) = WorkflowCancellationRequest(
        workflowName = "checkout",
        workflowId = workflowId,
        expectedRevision = expectedRevision,
        expectedGeneration = expectedGeneration,
        actor = "operator-7",
        reason = "incident-42",
        cancelledAtEpochMillis = 1_700_000_000_500,
        uncertain = uncertain,
    )

    private companion object {
        const val H2_URL = "jdbc:h2:mem:cancellation_authority;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        val LIFECYCLE_COLUMNS =
            listOf("lifecycle_status", "cancelled_at_epoch_millis", "cancelled_by", "cancellation_reason")
    }
}
