package dev.tramai.orchestration

import dev.tramai.orchestration.WORKFLOW_DEFINITION_VERSION_METADATA_KEY
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 12.1c probe 5 (JDBC part) — descriptor/resource boundedness proven
 * deterministically with an instrumented DataSource (preferred portable
 * shape; no /proc dependency in the core assertions). The orchestration JDBC
 * stores open one connection per operation and must return it after every
 * operation: repeated open/use/close cycles must return the observed live
 * connection count to the same bounded range (zero live after each op) —
 * never monotonic growth.
 *
 * A Linux-only /proc/self/fd integration probe is included as corroborating
 * CI evidence (guarded, bounded-growth expectation only — never an arbitrary
 * zero-descriptor claim).
 */
class JdbcConnectionReleaseProbeTest {
    private lateinit var pool: JdbcConnectionPool

    @AfterEach
    fun tearDown() {
        runCatching { pool.dispose() }
    }

    @Test
    fun `checkpoint save and load return their connection after each operation`() {
        val (counting, store) = newStore()

        val checkpoint = checkpoint("wf", "w-1")
        runBlocking { store.save(checkpoint) }
        assertEquals(0, counting.live(), "save must release its connection")

        runBlocking { store.load("wf", "w-1") }
        assertEquals(0, counting.live(), "load must release its connection")

        runBlocking { store.delete("wf", "w-1") }
        assertEquals(0, counting.live(), "delete must release its connection")
    }

    @Test
    fun `repeated save load delete cycles keep live connections bounded`() {
        val (counting, store) = newStore()

        repeat(CYCLES) { cycle ->
            val cp = checkpoint("wf", "w-$cycle")
            runBlocking {
                store.save(cp)
                store.load("wf", "w-$cycle")
                store.delete("wf", "w-$cycle")
            }
            assertEquals(0, counting.live(), "cycle $cycle leaked a connection")
        }

        // The count never grows monotonically across cycles: every operation
        // returned its connection. Corroborating growth bound, not a zero claim.
        assertTrue(counting.opened() <= CYCLES * 3, "unexpected connection fan-out")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `linux proc fd count stays bounded across repeated jdbc cycles`() {
        val procFd = File("/proc/self/fd")
        org.junit.jupiter.api.Assumptions
            .assumeTrue(procFd.isDirectory, "/proc/self/fd unavailable")

        val (_, store) = newStore()
        // Warm path + baseline in the same JVM/thread the cycles run on.
        repeat(10) { cycle -> cycleOp(store, "warm-$cycle") }
        val baseline = procFd.listFiles()?.size ?: 0

        repeat(LINUX_FD_CYCLES) { cycle -> cycleOp(store, "fd-$cycle") }
        val after = procFd.listFiles()?.size ?: 0

        // Bounded growth only: H2 pool + JVM internals may legitimately hold a
        // few descriptors; monotonic leak would blow far past this bound.
        assertTrue(after - baseline < 20, "fd growth ${after - baseline} suggests a descriptor leak")
    }

    private fun cycleOp(
        store: JdbcWorkflowCheckpointStore,
        id: String,
    ) {
        val cp = checkpoint("wf", id)
        runBlocking {
            store.save(cp)
            store.load("wf", id)
            store.delete("wf", id)
        }
    }

    private fun checkpoint(
        name: String,
        id: String,
    ): WorkflowCheckpoint =
        WorkflowCheckpoint(
            workflowName = name,
            workflowId = id,
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "start",
            metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
        )

    private fun newStore(): Pair<CountingDataSource, JdbcWorkflowCheckpointStore> {
        pool =
            JdbcConnectionPool.create(
                "jdbc:h2:mem:tramai_fd_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
        pool.maxConnections = 10
        val counting = CountingDataSource(pool)
        val store = JdbcWorkflowCheckpointStore(dataSource = counting)
        pool.connection.use { conn ->
            conn.createStatement().use { it.execute(store.createTableSql()) }
        }
        return counting to store
    }

    /** DataSource wrapper counting live (opened-not-closed) connections. */
    private class CountingDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        private val liveCount = AtomicInteger()
        private val openedCount = AtomicInteger()

        fun live(): Int = liveCount.get()

        fun opened(): Int = openedCount.get()

        override fun getConnection(): Connection = track(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = track(delegate.getConnection(username, password))

        private fun track(connection: Connection): Connection {
            openedCount.incrementAndGet()
            liveCount.incrementAndGet()
            return object : Connection by connection {
                override fun close() {
                    try {
                        connection.close()
                    } finally {
                        liveCount.decrementAndGet()
                    }
                }
            }
        }
    }

    private companion object {
        const val CYCLES = 30
        const val LINUX_FD_CYCLES = 200
    }
}
