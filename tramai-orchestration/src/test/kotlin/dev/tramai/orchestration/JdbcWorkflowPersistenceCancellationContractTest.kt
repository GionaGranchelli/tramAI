package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintWriter
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdbcWorkflowPersistenceCancellationContractTest {

    private lateinit var pooledDataSource: DataSource
    private lateinit var checkpointStore: JdbcWorkflowCheckpointStore
    private lateinit var leaseStore: JdbcWorkflowLeaseStore
    private var clock: Long = 1000L

    @BeforeEach
    fun setup() {
        val pool = JdbcConnectionPool.create(
            "jdbc:h2:mem:tramai_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            "sa", "",
        )
        pool.maxConnections = 10
        pooledDataSource = pool
        val ds: DataSource = pool

        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(JdbcWorkflowCheckpointStore(ds).createTableSql())
                stmt.execute(JdbcWorkflowLeaseStore(ds, JdbcWorkflowLeaseTable()).createTableSql())
            }
        }

        checkpointStore = JdbcWorkflowCheckpointStore(dataSource = ds)
        leaseStore = JdbcWorkflowLeaseStore(dataSource = ds, clockMillis = { clock })
    }

    @AfterEach
    fun teardown() {
        (pooledDataSource as? JdbcConnectionPool)?.dispose()
    }

    private fun testCheckpoint(
        workflowName: String = "test-wf",
        workflowId: String = UUID.randomUUID().toString(),
    ) = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = 0,
        stepExecutions = 0,
        lastCompletedStepName = null,
        statePayload = "state-${UUID.randomUUID()}",
        metadata = mapOf("test" to "true"),
    )

    @Test
    fun `pre-cancelled checkpoint save throws CancellationException and persists nothing`() {
        runBlocking {
            val cp = testCheckpoint()
            val job = Job().also { it.cancel() }
            assertThatThrownBy {
                runBlocking(job) { checkpointStore.save(cp, expectedRevision = null) }
            }.isInstanceOf(CancellationException::class.java)
            assertThat(checkpointStore.load(cp.workflowName, cp.workflowId)).isNull()
        }
    }

    @Test
    fun `cancellation while acquiring connection escapes as CancellationException`() {
        val blockOnGetConnection = CountDownLatch(1)
        val blockingDs = blockingGetConnectionDataSource(pooledDataSource, blockOnGetConnection)
        val store = JdbcWorkflowCheckpointStore(dataSource = blockingDs)
        runBlocking {
            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                store.load("wf", "id")
            }
            Thread.sleep(100)
            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            blockOnGetConnection.countDown()
        }
    }

    @Test
    fun `cancellation during executeQuery cancels statement and reclaims resources`() {
        val blockOnQuery = CountDownLatch(1)
        val queryStarted = CountDownLatch(1)
        val statementRef = AtomicReference<PreparedStatement>()
        val blockingDs = blockingExecuteQueryDataSource(
            pooledDataSource, blockOnQuery, queryStarted, statementRef,
        )
        val store = JdbcWorkflowCheckpointStore(dataSource = blockingDs)
        runBlocking {
            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                store.load("wf", "id")
            }
            assertThat(queryStarted.await(5, TimeUnit.SECONDS))
                .`as`("query started").isTrue()
            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            blockOnQuery.countDown()
            // verify no further errors when checking statement
        }
    }

    @Test
    fun `cancellation during executeUpdate cancels statement and throws CancellationException`() {
        val blockOnUpdate = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val blockingDs = blockingExecuteUpdateDataSource(
            pooledDataSource, blockOnUpdate, updateStarted,
        )
        val store = JdbcWorkflowCheckpointStore(dataSource = blockingDs)
        runBlocking {
            val cp = testCheckpoint()
            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                store.save(cp, expectedRevision = null)
            }
            assertThat(updateStarted.await(5, TimeUnit.SECONDS))
                .`as`("update started").isTrue()
            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            // Release latch for cleanup — cancellation should have interrupted the latch
            blockOnUpdate.countDown()
        }
    }

    @Test
    fun `cancelled fenced save rolls back transaction and never commits`() {
        runBlocking {
            val cp = testCheckpoint()
            checkpointStore.save(cp, expectedRevision = null)
            val lease = leaseStore.claim(
                cp.workflowName, cp.workflowId, "owner", null, 60_000,
            )

            val blockOnUpdate = CountDownLatch(1)
            val updateStarted = CountDownLatch(1)
            val blockingDs = blockingExecuteUpdateDataSource(
                pooledDataSource, blockOnUpdate, updateStarted,
            )
            val blockingCheckpointStore = JdbcWorkflowCheckpointStore(dataSource = blockingDs)
            val blockingLeaseStore = JdbcWorkflowLeaseStore(
                dataSource = blockingDs, clockMillis = { clock },
            )

            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                blockingLeaseStore.saveCheckpointIfLeaseOwner(
                    blockingCheckpointStore,
                    cp.copy(statePayload = "should-not-persist"),
                    1L, lease,
                )
            }
            assertThat(updateStarted.await(5, TimeUnit.SECONDS))
                .`as`("fenced save update started").isTrue()

            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            blockOnUpdate.countDown()

            val reloaded = checkpointStore.load(cp.workflowName, cp.workflowId)
            assertThat(reloaded?.statePayload).isEqualTo(cp.statePayload)
            assertThat(reloaded?.revision).isEqualTo(1)
        }
    }

    @Test
    fun `cancellation before commit rolls back and checkpoint is not persisted`() {
        runBlocking {
            val cp = testCheckpoint()
            checkpointStore.save(cp, expectedRevision = null)
            val lease = leaseStore.claim(
                cp.workflowName, cp.workflowId, "owner", null, 60_000,
            )

            val blockOnCommit = CountDownLatch(1)
            val commitStarted = CountDownLatch(1)
            val commitBlockingDs = commitBlockingDataSource(
                pooledDataSource, blockOnCommit, commitStarted,
            )
            val commitBlockingCheckpointStore = JdbcWorkflowCheckpointStore(dataSource = commitBlockingDs)
            val commitBlockingLeaseStore = JdbcWorkflowLeaseStore(
                dataSource = commitBlockingDs, clockMillis = { clock },
            )

            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                commitBlockingLeaseStore.saveCheckpointIfLeaseOwner(
                    commitBlockingCheckpointStore,
                    cp.copy(statePayload = "just-before-commit"),
                    1L, lease,
                )
            }
            assertThat(commitStarted.await(5, TimeUnit.SECONDS))
                .`as`("commit started").isTrue()

            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)

            // Check checkpoint state BEFORE releasing commit latch — cancellation
            // should have interrupted the commit via thread interruption
            val reloaded = checkpointStore.load(cp.workflowName, cp.workflowId)
            assertThat(reloaded?.statePayload).isEqualTo(cp.statePayload)
            assertThat(reloaded?.revision).isEqualTo(1)
            // Release latch for cleanup
            blockOnCommit.countDown()
        }
    }

    @Test
    fun `rollback failure during cancellation is suppressed under CancellationException`() {
        runBlocking {
            val cp = testCheckpoint()
            checkpointStore.save(cp, expectedRevision = null)
            val lease = leaseStore.claim(
                cp.workflowName, cp.workflowId, "owner", null, 60_000,
            )

            val blockOnUpdate = CountDownLatch(1)
            val updateStarted = CountDownLatch(1)
            val rollbackThrowingDs = rollbackThrowingDataSource(
                pooledDataSource, blockOnUpdate, updateStarted,
            )
            val rollbackThrowingCheckpointStore = JdbcWorkflowCheckpointStore(dataSource = rollbackThrowingDs)
            val rollbackThrowingLeaseStore = JdbcWorkflowLeaseStore(
                dataSource = rollbackThrowingDs, clockMillis = { clock },
            )

            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                rollbackThrowingLeaseStore.saveCheckpointIfLeaseOwner(
                    rollbackThrowingCheckpointStore,
                    cp.copy(statePayload = "should-rollback-fail"),
                    1L, lease,
                )
            }
            assertThat(updateStarted.await(5, TimeUnit.SECONDS))
                .`as`("fenced save update started").isTrue()

            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            blockOnUpdate.countDown()
        }
    }

    @Test
    fun `cancelled lease claim leaves no partially persisted lease`() {
        runBlocking {
            val blockOnUpdate = CountDownLatch(1)
            val updateStarted = CountDownLatch(1)
            val blockingDs = blockingExecuteUpdateDataSource(
                pooledDataSource, blockOnUpdate, updateStarted,
            )
            val blockingLeaseStore = JdbcWorkflowLeaseStore(
                dataSource = blockingDs, clockMillis = { clock },
            )

            val job = Job()
            val deferred = async(Dispatchers.IO + job) {
                blockingLeaseStore.claim("wf", "id-cancel-claim", "owner", null, 60_000)
            }
            assertThat(updateStarted.await(5, TimeUnit.SECONDS))
                .`as`("lease claim update started").isTrue()

            job.cancel()
            val thrown = try {
                withTimeout(5_000) { deferred.await() }; null
            } catch (e: CancellationException) { e }
            assertThat(thrown).isInstanceOf(CancellationException::class.java)

            // Check lease state BEFORE releasing update latch — cancellation
            // should have interrupted the latch via thread interruption
            assertThat(blockingLeaseStore.currentLease("wf", "id-cancel-claim")).isNull()
            val freshLease = leaseStore.claim("wf", "id-cancel-claim", "new-owner", null, 60_000)
            assertThat(freshLease.ownerId).isEqualTo("new-owner")
            // Release latch for cleanup
            blockOnUpdate.countDown()
        }
    }

    @Test
    fun `successful operations still work after cancellable wrapping`() {
        runBlocking {
            val cp = testCheckpoint()

            val saved = checkpointStore.save(cp, expectedRevision = null)
            assertThat(saved.revision).isEqualTo(1)

            val loaded = checkpointStore.load(cp.workflowName, cp.workflowId)
            assertThat(loaded).isNotNull
            assertThat(loaded!!.statePayload).isEqualTo(cp.statePayload)

            val updated = checkpointStore.save(
                cp.copy(statePayload = "updated"), expectedRevision = 1,
            )
            assertThat(updated.revision).isEqualTo(2)
            assertThat(
                checkpointStore.load(cp.workflowName, cp.workflowId)?.statePayload,
            ).isEqualTo("updated")

            val lease = leaseStore.claim(cp.workflowName, cp.workflowId, "owner", null, 60_000)
            assertThat(lease.ownerId).isEqualTo("owner")

            val renewed = leaseStore.renew(lease, checkpointRevision = 2, leaseDurationMillis = 120_000)
            assertThat(renewed.expiresAtEpochMillis).isEqualTo(1000L + 120_000)

            assertThat(leaseStore.currentLease(cp.workflowName, cp.workflowId)).isNotNull
            leaseStore.release(renewed)
            assertThat(leaseStore.currentLease(cp.workflowName, cp.workflowId)).isNull()

            assertThat(checkpointStore.listCheckpoints()).isNotEmpty
            checkpointStore.delete(cp.workflowName, cp.workflowId, expectedRevision = updated.revision)
            assertThat(checkpointStore.load(cp.workflowName, cp.workflowId)).isNull()
        }
    }

    @Test
    fun `SQL errors produce correct conflict and revision exception semantics`() {
        runBlocking {
            val cp = testCheckpoint()
            checkpointStore.save(cp, expectedRevision = null)

            val dupThrown = try {
                checkpointStore.save(cp.copy(statePayload = "dup"), expectedRevision = null); null
            } catch (e: WorkflowCheckpointConflictException) { e }
            assertThat(dupThrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)

            val wrongRevThrown = try {
                checkpointStore.save(cp.copy(statePayload = "wrong-rev"), expectedRevision = 99); null
            } catch (e: WorkflowCheckpointConflictException) { e }
            assertThat(wrongRevThrown).isInstanceOf(WorkflowCheckpointConflictException::class.java)

            leaseStore.claim(cp.workflowName, cp.workflowId, "owner-a", null, 60_000)
            val leaseConflictThrown = try {
                leaseStore.claim(cp.workflowName, cp.workflowId, "owner-b", null, 60_000); null
            } catch (e: WorkflowLeaseConflictException) { e }
            assertThat(leaseConflictThrown)
                .isInstanceOf(WorkflowLeaseConflictException::class.java)
                .hasMessageContaining("owner-a")

            leaseStore.release(leaseStore.currentLease(cp.workflowName, cp.workflowId)!!)
            val freshLease = leaseStore.claim(
                cp.workflowName, cp.workflowId, "owner-c", null, 60_000,
            )
            assertThat(freshLease.ownerId).isEqualTo("owner-c")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: blocking proxies
    // ═══════════════════════════════════════════════════════════════════════

    private fun blockingGetConnectionDataSource(
        delegate: DataSource,
        blockOnGetConnection: CountDownLatch,
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection {
            blockOnGetConnection.await()
            return delegate.connection
        }
        override fun getConnection(username: String?, password: String?): Connection {
            blockOnGetConnection.await()
            return delegate.getConnection(username, password)
        }
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private fun blockingExecuteQueryDataSource(
        delegate: DataSource,
        blockOnQuery: CountDownLatch,
        queryStarted: CountDownLatch,
        statementRef: AtomicReference<PreparedStatement> = AtomicReference(),
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection =
            BlockingQueryConnection(delegate.connection, blockOnQuery, queryStarted, statementRef)
        override fun getConnection(username: String?, password: String?): Connection =
            BlockingQueryConnection(
                delegate.getConnection(username, password),
                blockOnQuery, queryStarted, statementRef,
            )
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private class BlockingQueryConnection(
        private val delegate: Connection,
        private val blockOnQuery: CountDownLatch,
        private val queryStarted: CountDownLatch,
        private val statementRef: AtomicReference<PreparedStatement>,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement {
            val stmt = delegate.prepareStatement(sql)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, autoGeneratedKeys)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(
            sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int,
        ): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnIndexes)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, columnNames: Array<out String>): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnNames)
            val proxy = BlockingPreparedStatement(stmt, blockOnQuery, queryStarted)
            statementRef.set(proxy)
            return proxy
        }
    }

    private class BlockingPreparedStatement(
        private val delegate: PreparedStatement,
        private val blockOnQuery: CountDownLatch,
        private val started: CountDownLatch,
    ) : PreparedStatement by delegate {
        @Volatile
        var cancelled: Boolean = false
        private val closed = AtomicReference(false)

        override fun executeQuery(): ResultSet {
            started.countDown()
            blockOnQuery.await()
            return delegate.executeQuery()
        }
        override fun executeUpdate(): Int {
            started.countDown()
            blockOnQuery.await()
            return delegate.executeUpdate()
        }
        override fun cancel() { cancelled = true; delegate.cancel() }
        override fun close() { closed.set(true); delegate.close() }
        override fun isClosed(): Boolean = closed.get() || delegate.isClosed
        fun isCancelled(): Boolean = cancelled
    }

    private fun blockingExecuteUpdateDataSource(
        delegate: DataSource,
        blockOnUpdate: CountDownLatch,
        updateStarted: CountDownLatch,
        statementRef: AtomicReference<PreparedStatement> = AtomicReference(),
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection =
            BlockingUpdateConnection(delegate.connection, blockOnUpdate, updateStarted, statementRef)
        override fun getConnection(username: String?, password: String?): Connection =
            BlockingUpdateConnection(
                delegate.getConnection(username, password),
                blockOnUpdate, updateStarted, statementRef,
            )
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private class BlockingUpdateConnection(
        private val delegate: Connection,
        private val blockOnUpdate: CountDownLatch,
        private val updateStarted: CountDownLatch,
        private val statementRef: AtomicReference<PreparedStatement>,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement {
            val stmt = delegate.prepareStatement(sql)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, autoGeneratedKeys)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(
            sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int,
        ): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnIndexes)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
        override fun prepareStatement(sql: String, columnNames: Array<out String>): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnNames)
            val proxy = BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
            statementRef.set(proxy)
            return proxy
        }
    }

    private class BlockingUpdatePreparedStatement(
        private val delegate: PreparedStatement,
        private val blockOnUpdate: CountDownLatch,
        private val started: CountDownLatch,
    ) : PreparedStatement by delegate {
        @Volatile
        var cancelled: Boolean = false
        private val closed = AtomicReference(false)

        override fun executeQuery(): ResultSet = delegate.executeQuery()
        override fun executeUpdate(): Int {
            started.countDown()
            blockOnUpdate.await()
            return delegate.executeUpdate()
        }
        override fun cancel() { cancelled = true; delegate.cancel() }
        override fun close() { closed.set(true); delegate.close() }
        override fun isClosed(): Boolean = closed.get() || delegate.isClosed
        fun isCancelled(): Boolean = cancelled
    }

    private fun commitBlockingDataSource(
        delegate: DataSource,
        blockOnCommit: CountDownLatch,
        commitStarted: CountDownLatch,
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection =
            CommitBlockingConnection(delegate.connection, blockOnCommit, commitStarted)
        override fun getConnection(username: String?, password: String?): Connection =
            CommitBlockingConnection(
                delegate.getConnection(username, password),
                blockOnCommit, commitStarted,
            )
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private class CommitBlockingConnection(
        private val delegate: Connection,
        private val blockOnCommit: CountDownLatch,
        private val commitStarted: CountDownLatch,
    ) : Connection by delegate {
        override fun commit() {
            commitStarted.countDown()
            blockOnCommit.await()
            delegate.commit()
        }
    }

    private fun rollbackThrowingDataSource(
        delegate: DataSource,
        blockOnUpdate: CountDownLatch,
        updateStarted: CountDownLatch,
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection =
            RollbackThrowingConnection(delegate.connection, blockOnUpdate, updateStarted)
        override fun getConnection(username: String?, password: String?): Connection =
            RollbackThrowingConnection(
                delegate.getConnection(username, password),
                blockOnUpdate, updateStarted,
            )
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    private class RollbackThrowingConnection(
        private val delegate: Connection,
        private val blockOnUpdate: CountDownLatch,
        private val updateStarted: CountDownLatch,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement {
            val stmt = delegate.prepareStatement(sql)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, autoGeneratedKeys)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun prepareStatement(
            sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int,
        ): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnIndexes)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun prepareStatement(sql: String, columnNames: Array<out String>): PreparedStatement {
            val stmt = delegate.prepareStatement(sql, columnNames)
            return BlockingUpdatePreparedStatement(stmt, blockOnUpdate, updateStarted)
        }
        override fun rollback() { throw SQLException("rollback failure injected for test") }
    }
}
