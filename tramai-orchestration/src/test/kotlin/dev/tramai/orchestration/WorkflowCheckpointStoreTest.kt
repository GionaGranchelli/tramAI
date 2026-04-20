@file:OptIn(ExperimentalTramAIOrchestration::class)

package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

class WorkflowCheckpointStoreTest {
    @Test
    fun `file checkpoint store round trips and enforces revision conflicts`() {
        val directory = createTempDirectory("tramai-file-store")
        try {
            val store = FileWorkflowCheckpointStore(directory)
            val checkpoint = sampleCheckpoint(
                workflowName = "file-workflow",
                workflowId = "wf-1",
                statePayload = "draft\nreview",
            )

            val first = runBlocking { store.save(checkpoint) }
            val second = runBlocking {
                store.save(
                    checkpoint.copy(
                        nextStepIndex = 2,
                        stepExecutions = 2,
                        lastCompletedStepName = "review",
                    ),
                    expectedRevision = first.revision,
                )
            }
            val loaded = runBlocking { store.load("file-workflow", "wf-1") }

            assertThat(first.revision).isEqualTo(1)
            assertThat(second.revision).isEqualTo(2)
            assertThat(loaded).isEqualTo(second)

            assertThatThrownBy {
                runBlocking {
                    store.save(
                        checkpoint.copy(nextStepIndex = 3),
                        expectedRevision = first.revision,
                    )
                }
            }
                .isInstanceOf(WorkflowCheckpointConflictException::class.java)
                .hasMessageContaining("expected revision 1")

            runBlocking {
                store.delete(
                    workflowName = "file-workflow",
                    workflowId = "wf-1",
                    expectedRevision = second.revision,
                )
            }
            assertThat(runBlocking { store.load("file-workflow", "wf-1") }).isNull()
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun `markdown checkpoint store writes readable markdown and round trips payload`() {
        val directory = createTempDirectory("tramai-markdown-store")
        try {
            val store = MarkdownWorkflowCheckpointStore(directory)
            val checkpoint = sampleCheckpoint(
                workflowName = "markdown-workflow",
                workflowId = "wf-md",
                statePayload = "line-1\n```json\n{\"ok\":true}\n```",
            )

            val persisted = runBlocking { store.save(checkpoint) }
            val checkpointPath = directory
                .resolve("markdown-workflow")
                .resolve("wf-md")
                .resolve("checkpoint.md")
            val markdown = Files.readString(checkpointPath)
            val loaded = runBlocking { store.load("markdown-workflow", "wf-md") }

            assertThat(markdown)
                .contains("# Tramai Workflow Checkpoint")
                .contains("## State Payload")
                .contains("```json")
            assertThat(markdown).contains(checkpoint.statePayload)
            assertThat(loaded).isEqualTo(persisted)
        } finally {
            deleteRecursively(directory)
        }
    }

    @Test
    fun `jdbc checkpoint store round trips and rejects stale revisions`() {
        val backend = FakeJdbcBackend()
        val store = JdbcWorkflowCheckpointStore(backend.dataSource())
        val checkpoint = sampleCheckpoint(
            workflowName = "jdbc-workflow",
            workflowId = "wf-jdbc",
            statePayload = "state-1",
        )

        val first = runBlocking { store.save(checkpoint) }
        val loaded = runBlocking { store.load("jdbc-workflow", "wf-jdbc") }
        val second = runBlocking {
            store.save(
                checkpoint.copy(
                    nextStepIndex = 2,
                    stepExecutions = 2,
                    lastCompletedStepName = "review",
                    statePayload = "state-2",
                ),
                expectedRevision = first.revision,
            )
        }

        assertThat(first.revision).isEqualTo(1)
        assertThat(loaded).isEqualTo(first)
        assertThat(second.revision).isEqualTo(2)
        assertThat(runBlocking { store.load("jdbc-workflow", "wf-jdbc") }).isEqualTo(second)
        assertThat(store.createTableSql()).contains("CREATE TABLE")
            .contains("tramai_workflow_checkpoint")

        assertThatThrownBy {
            runBlocking {
                store.save(
                    checkpoint.copy(nextStepIndex = 3),
                    expectedRevision = first.revision,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("expected revision 1")

        assertThatThrownBy {
            runBlocking {
                store.delete(
                    workflowName = "jdbc-workflow",
                    workflowId = "wf-jdbc",
                    expectedRevision = first.revision,
                )
            }
        }
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            .hasMessageContaining("expected revision 1")

        runBlocking {
            store.delete(
                workflowName = "jdbc-workflow",
                workflowId = "wf-jdbc",
                expectedRevision = second.revision,
            )
        }
        assertThat(runBlocking { store.load("jdbc-workflow", "wf-jdbc") }).isNull()
    }
}

private fun sampleCheckpoint(
    workflowName: String,
    workflowId: String,
    statePayload: String,
): WorkflowCheckpoint = WorkflowCheckpoint(
    workflowName = workflowName,
    workflowId = workflowId,
    nextStepIndex = 1,
    stepExecutions = 1,
    lastCompletedStepName = "draft",
    statePayload = statePayload,
    metadata = mapOf("tenant" to "alpha", "format" to "test"),
    savedAtEpochMillis = 1234,
)

private fun deleteRecursively(directory: Path) {
    Files.walk(directory)
        .sorted(Comparator.reverseOrder())
        .forEach(Files::deleteIfExists)
}

private class FakeJdbcBackend {
    private val rows = linkedMapOf<Pair<String, String>, WorkflowCheckpoint>()

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

    private fun connectionProxy(): Connection = proxy(Connection::class.java) { method, args ->
        when (method.name) {
            "prepareStatement" -> preparedStatementProxy(args[0] as String)
            "close", "commit", "rollback", "setAutoCommit" -> Unit
            "getAutoCommit" -> true
            "isClosed" -> false
            "unwrap" -> throw SQLException("Unsupported")
            "isWrapperFor" -> false
            else -> defaultValue(method.returnType)
        }
    }

    private fun preparedStatementProxy(sql: String): PreparedStatement {
        val normalizedSql = sql.trim().uppercase()
        val parameters = linkedMapOf<Int, Any?>()

        return proxy(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "setString", "setInt", "setLong" -> {
                    parameters[args[0] as Int] = args[1]
                    Unit
                }
                "executeQuery" -> resultSetProxy(selectRow(parameters))
                "executeUpdate" -> executeUpdate(normalizedSql, parameters)
                "close" -> Unit
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun selectRow(parameters: Map<Int, Any?>): WorkflowCheckpoint? = rows[
        (parameters[1] as String) to (parameters[2] as String)
    ]

    private fun executeUpdate(
        normalizedSql: String,
        parameters: Map<Int, Any?>,
    ): Int = when {
        normalizedSql.startsWith("INSERT INTO") -> {
            val checkpoint = WorkflowCheckpoint(
                workflowName = parameters[1] as String,
                workflowId = parameters[2] as String,
                nextStepIndex = parameters[3] as Int,
                stepExecutions = parameters[4] as Int,
                lastCompletedStepName = parameters[5] as String?,
                statePayload = parameters[6] as String,
                revision = parameters[7] as Long,
                metadata = decodeMetadata(parameters[8] as String),
                savedAtEpochMillis = parameters[9] as Long,
            )
            rows[checkpoint.workflowName to checkpoint.workflowId] = checkpoint
            1
        }
        normalizedSql.startsWith("UPDATE") -> {
            val key = (parameters[8] as String) to (parameters[9] as String)
            val existing = rows[key] ?: return 0
            val expectedRevision = parameters[10] as Long
            if (existing.revision != expectedRevision) {
                return 0
            }

            rows[key] = existing.copy(
                nextStepIndex = parameters[1] as Int,
                stepExecutions = parameters[2] as Int,
                lastCompletedStepName = parameters[3] as String?,
                statePayload = parameters[4] as String,
                revision = parameters[5] as Long,
                metadata = decodeMetadata(parameters[6] as String),
                savedAtEpochMillis = parameters[7] as Long,
            )
            1
        }
        normalizedSql.startsWith("DELETE FROM") -> {
            val key = (parameters[1] as String) to (parameters[2] as String)
            val existing = rows[key] ?: return 0
            val expectedRevision = parameters[3] as Long?
            if (expectedRevision != null && existing.revision != expectedRevision) {
                return 0
            }
            rows.remove(key)
            1
        }
        else -> error("Unsupported SQL '$normalizedSql'")
    }

    private fun resultSetProxy(row: WorkflowCheckpoint?): ResultSet {
        var consumed = false
        return proxy(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> if (!consumed && row != null) {
                    consumed = true
                    true
                } else {
                    false
                }
                "getString" -> row?.stringValue(args[0])
                "getInt" -> row?.intValue(args[0]) ?: 0
                "getLong" -> row?.longValue(args[0]) ?: 0L
                "close" -> Unit
                "wasNull" -> row == null
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultValue(method.returnType)
            }
        }
    }
}

private fun WorkflowCheckpoint.stringValue(column: Any?): String? = when (column) {
    "workflow_name" -> workflowName
    "workflow_id" -> workflowId
    "last_completed_step_name" -> lastCompletedStepName
    "state_payload" -> statePayload
    "metadata_payload" -> encodeMetadata(metadata)
    else -> error("Unsupported column '$column'")
}

private fun WorkflowCheckpoint.intValue(column: Any?): Int = when (column) {
    "next_step_index" -> nextStepIndex
    "step_executions" -> stepExecutions
    else -> error("Unsupported column '$column'")
}

private fun WorkflowCheckpoint.longValue(column: Any?): Long = when (column) {
    "revision" -> revision
    "saved_at_epoch_millis" -> savedAtEpochMillis
    else -> error("Unsupported column '$column'")
}

private fun <T> proxy(
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

private fun defaultValue(returnType: Class<*>): Any? = when {
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
