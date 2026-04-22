package dev.tramai.orchestration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.nio.file.Files
import java.util.logging.Logger
import javax.sql.DataSource
class WorkflowLeaseStoreTest {
    @Test
    fun `in memory lease store claims renews and releases ownership`() {
        var now = 1_000L
        val store = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 3,
                leaseDurationMillis = 500,
            )
        }
        val renewed = runBlocking {
            store.renew(
                lease = claimed,
                checkpointRevision = 4,
                leaseDurationMillis = 500,
            )
        }
        assertThat(claimed.ownerId).isEqualTo("node-a")
        assertThat(claimed.checkpointRevision).isEqualTo(3)
        assertThat(renewed.checkpointRevision).isEqualTo(4)
        assertThat(renewed.expiresAtEpochMillis).isEqualTo(1_500L)
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isEqualTo(renewed)
        runBlocking { store.release(renewed) }
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isNull()
    }
    @Test
    fun `in memory lease store rejects competing active claims`() {
        val store = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.claim(
                    workflowName = "lease-workflow",
                    workflowId = "wf-1",
                    ownerId = "node-b",
                    checkpointRevision = 1,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessageContaining("already leased by owner 'node-a'")
    }
    @Test
    fun `in memory lease store rejects renewal after lease expiry`() {
        var now = 1_000L
        val store = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-expired",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        now = 1_600L
        assertThatThrownBy {
            runBlocking {
                store.renew(
                    lease = claimed,
                    checkpointRevision = 2,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessageContaining("lease has expired before renewal")
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-expired") }).isNull()
    }
    @Test
    fun `in memory lease store rejects release by the wrong owner`() {
        val store = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-release-conflict",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.release(
                    claimed.copy(
                        leaseId = "other-lease",
                        ownerId = "node-b",
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessageContaining("leased by owner 'node-a'")
            .hasMessageContaining("not 'node-b'")
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-release-conflict") }).isEqualTo(claimed)
    }
    @Test
    fun `file lease store claims renews and releases ownership`() {
        var now = 1_000L
        val directory = createTempDirectory("tramai-file-lease")
        try {
            val store = FileWorkflowLeaseStore(
                rootDirectory = directory,
                clockMillis = { now },
            )
            val claimed = runBlocking {
                store.claim(
                    workflowName = "lease-workflow",
                    workflowId = "wf-1",
                    ownerId = "node-a",
                    checkpointRevision = 2,
                    leaseDurationMillis = 500,
                )
            }
            val renewed = runBlocking {
                store.renew(
                    lease = claimed,
                    checkpointRevision = 3,
                    leaseDurationMillis = 750,
                )
            }
            assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isEqualTo(renewed)
            assertThatThrownBy {
                runBlocking {
                    store.claim(
                        workflowName = "lease-workflow",
                        workflowId = "wf-1",
                        ownerId = "node-b",
                        checkpointRevision = 3,
                        leaseDurationMillis = 500,
                    )
                }
            }
                .isInstanceOf(WorkflowLeaseConflictException::class.java)
                .hasMessageContaining("node-a")
            runBlocking { store.release(renewed) }
            assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isNull()
        } finally {
            Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
    @Test
    fun `workflow resume refuses to start when another owner holds the lease`() {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val checkpointPersistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = LeaseResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflow = workflow<LeaseResumeState>("lease-resume") {
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(answer = "final:${state.value}") },
            )
        }.build { it.answer ?: error("answer must exist") }
        runBlocking {
            workflow.run(
                initialState = LeaseResumeState("invoice-123"),
                context = WorkflowContext(workflowId = "wf-lease"),
                persistence = checkpointPersistence,
            )
        }
        val checkpoint = runBlocking { checkpointStore.load("lease-resume", "wf-lease") }!!
        runBlocking {
            leaseStore.claim(
                workflowName = "lease-resume",
                workflowId = "wf-lease",
                ownerId = "node-a",
                checkpointRevision = checkpoint.revision,
                leaseDurationMillis = 5_000,
            )
        }
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = WorkflowContext(workflowId = "wf-lease"),
                    persistence = WorkflowPersistence(
                        checkpointStore = checkpointStore,
                        stateCodec = LeaseResumeStateCodec,
                        leaseStore = leaseStore,
                        leasePolicy = WorkflowLeasePolicy(
                            ownerId = "node-b",
                            leaseDurationMillis = 5_000,
                        ),
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessageContaining("node-a")
    }
    @Test
    fun `workflow failure releases lease so another owner can take over`() {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val persistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = LeaseResumeStateCodec,
            leaseStore = leaseStore,
            leasePolicy = WorkflowLeasePolicy(
                ownerId = "node-a",
                leaseDurationMillis = 5_000,
            ),
        )
        val workflow = workflow<LeaseResumeState>("lease-failure") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(answer = "draft:${state.value}") },
            )
            localStep(
                name = "explode",
                transform = { _, _ -> error("boom") },
            )
        }.build { it.answer ?: error("answer must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = LeaseResumeState("invoice-123"),
                    context = WorkflowContext(workflowId = "wf-failure"),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom")
        val claimedByNextOwner = runBlocking {
            leaseStore.claim(
                workflowName = "lease-failure",
                workflowId = "wf-failure",
                ownerId = "node-b",
                checkpointRevision = checkpointStore.load("lease-failure", "wf-failure")?.revision,
                leaseDurationMillis = 5_000,
            )
        }
        assertThat(claimedByNextOwner.ownerId).isEqualTo("node-b")
    }
    @Test
    fun `jdbc lease store claims replaces expired leases renews and releases`() {
        var now = 1_000L
        val backend = FakeJdbcLeaseBackend()
        val store = JdbcWorkflowLeaseStore(
            dataSource = backend.dataSource(),
            clockMillis = { now },
        )
        val first = runBlocking {
            store.claim(
                workflowName = "jdbc-lease",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThat(store.createTableSql())
            .contains("CREATE TABLE")
            .contains("tramai_workflow_lease")
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isEqualTo(first)
        assertThatThrownBy {
            runBlocking {
                store.claim(
                    workflowName = "jdbc-lease",
                    workflowId = "wf-1",
                    ownerId = "node-b",
                    checkpointRevision = 1,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessageContaining("node-a")
        now = 2_000L
        val replacement = runBlocking {
            store.claim(
                workflowName = "jdbc-lease",
                workflowId = "wf-1",
                ownerId = "node-b",
                checkpointRevision = 2,
                leaseDurationMillis = 750,
            )
        }
        val renewed = runBlocking {
            store.renew(
                lease = replacement,
                checkpointRevision = 3,
                leaseDurationMillis = 1_000,
            )
        }
        assertThat(replacement.ownerId).isEqualTo("node-b")
        assertThat(renewed.checkpointRevision).isEqualTo(3)
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isEqualTo(renewed)
        runBlocking { store.release(renewed) }
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isNull()
    }
}
private data class LeaseResumeState(
    val value: String,
    val answer: String? = null,
)
private object LeaseResumeStateCodec : WorkflowStateCodec<LeaseResumeState> {
    override fun encode(state: LeaseResumeState): String = listOf(
        state.value,
        state.answer.orEmpty(),
    ).joinToString("|")
    override fun decode(payload: String): LeaseResumeState {
        val parts = payload.split("|", limit = 2)
        return LeaseResumeState(
            value = parts[0],
            answer = parts.getOrNull(1).orEmpty().ifBlank { null },
        )
    }
}
private class FakeJdbcLeaseBackend {
    private val rows = linkedMapOf<Pair<String, String>, WorkflowLease>()
    fun dataSource(): DataSource = object : DataSource {
        override fun getConnection(): Connection = connectionProxy()
        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connectionProxy()
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("Unsupported")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
    private fun connectionProxy(): Connection = proxyLease(Connection::class.java) { method, args ->
        when (method.name) {
            "prepareStatement" -> preparedStatementProxy(args[0] as String)
            "close", "commit", "rollback", "setAutoCommit" -> Unit
            "getAutoCommit" -> true
            "isClosed" -> false
            "unwrap" -> throw SQLException("Unsupported")
            "isWrapperFor" -> false
            else -> defaultLeaseValue(method.returnType)
        }
    }
    private fun preparedStatementProxy(sql: String): PreparedStatement {
        val normalizedSql = sql.trim().uppercase()
        val parameters = linkedMapOf<Int, Any?>()
        return proxyLease(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "setString", "setLong", "setInt", "setBoolean" -> {
                    parameters[args[0] as Int] = args[1]
                    Unit
                }
                "setNull" -> {
                    parameters[args[0] as Int] = null
                    Unit
                }
                "executeQuery" -> resultSetProxy(selectRow(parameters))
                "executeUpdate" -> executeUpdate(normalizedSql, parameters)
                "close" -> Unit
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultLeaseValue(method.returnType)
            }
        }
    }
    private fun selectRow(parameters: Map<Int, Any?>): WorkflowLease? = rows[
        (parameters[1] as String) to (parameters[2] as String)
    ]
    private fun executeUpdate(
        normalizedSql: String,
        parameters: Map<Int, Any?>,
    ): Int = when {
        normalizedSql.startsWith("INSERT INTO") -> {
            val lease = WorkflowLease(
                workflowName = parameters[1] as String,
                workflowId = parameters[2] as String,
                leaseId = parameters[3] as String,
                ownerId = parameters[4] as String,
                checkpointRevision = parameters[5] as Long?,
                acquiredAtEpochMillis = parameters[6] as Long,
                expiresAtEpochMillis = parameters[7] as Long,
            )
            val key = lease.workflowName to lease.workflowId
            if (rows.containsKey(key)) {
                throw SQLException("Duplicate key")
            }
            rows[key] = lease
            1
        }
        normalizedSql.startsWith("UPDATE TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("LEASE_ID = ?") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS <= ?") -> {
            val key = (parameters[6] as String) to (parameters[7] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[10] as Long
            if (existing.leaseId != parameters[8] as String || existing.ownerId != parameters[9] as String) {
                return 0
            }
            if (existing.expiresAtEpochMillis > now) {
                return 0
            }
            rows[key] = existing.copy(
                leaseId = parameters[1] as String,
                ownerId = parameters[2] as String,
                checkpointRevision = parameters[3] as Long?,
                acquiredAtEpochMillis = parameters[4] as Long,
                expiresAtEpochMillis = parameters[5] as Long,
            )
            1
        }
        normalizedSql.startsWith("UPDATE TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("CHECKPOINT_REVISION = ?") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS > ?") -> {
            val key = (parameters[3] as String) to (parameters[4] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[7] as Long
            if (existing.leaseId != parameters[5] as String || existing.ownerId != parameters[6] as String) {
                return 0
            }
            if (existing.expiresAtEpochMillis <= now) {
                return 0
            }
            rows[key] = existing.copy(
                checkpointRevision = parameters[1] as Long?,
                expiresAtEpochMillis = parameters[2] as Long,
            )
            1
        }
        normalizedSql.startsWith("DELETE FROM TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("LEASE_ID = ?") -> {
            val key = (parameters[1] as String) to (parameters[2] as String)
            val existing = rows[key] ?: return 0
            if (existing.leaseId != parameters[3] as String || existing.ownerId != parameters[4] as String) {
                return 0
            }
            rows.remove(key)
            1
        }
        normalizedSql.startsWith("DELETE FROM TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS <= ?") -> {
            val key = (parameters[1] as String) to (parameters[2] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[3] as Long
            if (existing.expiresAtEpochMillis > now) {
                return 0
            }
            rows.remove(key)
            1
        }
        else -> error("Unsupported SQL '$normalizedSql'")
    }
    private fun resultSetProxy(row: WorkflowLease?): ResultSet {
        var consumed = false
        var lastWasNull = false
        return proxyLease(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> if (!consumed && row != null) {
                    consumed = true
                    true
                } else {
                    false
                }
                "getString" -> {
                    lastWasNull = false
                    row?.stringLeaseValue(args[0])
                }
                "getLong" -> {
                    val value = row?.longLeaseValue(args[0])
                    lastWasNull = value == null
                    value ?: 0L
                }
                "wasNull" -> lastWasNull
                "close" -> Unit
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultLeaseValue(method.returnType)
            }
        }
    }
}
private fun WorkflowLease.stringLeaseValue(column: Any?): String = when (column) {
    "workflow_name" -> workflowName
    "workflow_id" -> workflowId
    "lease_id" -> leaseId
    "owner_id" -> ownerId
    else -> error("Unsupported column '$column'")
}
private fun WorkflowLease.longLeaseValue(column: Any?): Long? = when (column) {
    "checkpoint_revision" -> checkpointRevision
    "acquired_at_epoch_millis" -> acquiredAtEpochMillis
    "expires_at_epoch_millis" -> expiresAtEpochMillis
    else -> error("Unsupported column '$column'")
}
private fun <T> proxyLease(
    type: Class<T>,
    handler: (method: java.lang.reflect.Method, args: Array<out Any?>) -> Any?,
): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        handler(method, args ?: emptyArray())
    } as T
}
private fun defaultLeaseValue(returnType: Class<*>): Any? = when {
    !returnType.isPrimitive -> null
    returnType == Boolean::class.javaPrimitiveType -> false
    returnType == Int::class.javaPrimitiveType -> 0
    returnType == Long::class.javaPrimitiveType -> 0L
    returnType == Short::class.javaPrimitiveType -> 0.toShort()
    returnType == Byte::class.javaPrimitiveType -> 0.toByte()
    returnType == Double::class.javaPrimitiveType -> 0.0
    returnType == Float::class.javaPrimitiveType -> 0f
    returnType == Char::class.javaPrimitiveType -> '\u0000'
    else -> null
}
