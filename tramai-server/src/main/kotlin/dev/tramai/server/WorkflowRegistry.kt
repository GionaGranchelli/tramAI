package dev.tramai.server

import dev.tramai.orchestration.JdbcWorkflowCheckpointStore
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowPersistence
import dev.tramai.orchestration.WorkflowStateCodec
import dev.tramai.scheduler.JdbcWorkflowSchedulerStore
import org.slf4j.LoggerFactory
import java.sql.SQLException
import javax.sql.DataSource

class WorkflowRegistry(
    private val dataSource: DataSource? = null,
) {
    private val logger = LoggerFactory.getLogger(WorkflowRegistry::class.java)
    private val entries = linkedMapOf<String, WorkflowEntry<*, *>>()
    private var jdbcTablesCreated = false

    @Synchronized
    fun <S, R> register(
        workflow: Workflow<S, R>,
        stateCodec: WorkflowStateCodec<S>,
        defaultPersistence: (String) -> WorkflowPersistence<S>? = defaultPersistenceFactory(stateCodec),
    ) {
        require(workflow.name.isNotBlank()) { "Workflow name must not be blank" }
        require(!entries.containsKey(workflow.name)) {
            "Workflow '${workflow.name}' is already registered"
        }
        entries[workflow.name] = WorkflowEntry(
            workflow = workflow,
            stateCodec = stateCodec,
            persistenceFactory = defaultPersistence,
        )
    }

    fun get(workflowName: String): WorkflowEntry<*, *> =
        entries[workflowName] ?: throw WorkflowNotRegisteredException(workflowName)

    fun list(): List<WorkflowEntry<*, *>> = entries.values.toList()

    private fun <S> defaultPersistenceFactory(
        stateCodec: WorkflowStateCodec<S>,
    ): (String) -> WorkflowPersistence<S>? = { _ ->
        val source = dataSource
        if (source == null) {
            null
        } else {
            createJdbcTablesIfNeeded(source)
            val schedulerStore = JdbcWorkflowSchedulerStore(source)
            WorkflowPersistence(
                checkpointStore = JdbcWorkflowCheckpointStore(source),
                stateCodec = stateCodec,
                delayWakeupScheduler = schedulerStore,
            )
        }
    }

    @Synchronized
    private fun createJdbcTablesIfNeeded(source: DataSource) {
        if (jdbcTablesCreated) {
            return
        }
        val checkpointStore = JdbcWorkflowCheckpointStore(source)
        val schedulerStore = JdbcWorkflowSchedulerStore(source)
        try {
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    try {
                        statement.execute(checkpointStore.createTableSql())
                    } catch (error: SQLException) {
                        if (!error.isTableAlreadyExists()) {
                            throw error
                        }
                        logger.debug("Workflow checkpoint table already exists", error)
                    }
                }
                schedulerStore.createTableSql().forEach { sql ->
                    connection.createStatement().use { statement ->
                        statement.execute(sql)
                    }
                }
            }
        } catch (error: SQLException) {
            logger.error("Failed to create workflow JDBC tables", error)
            throw IllegalStateException("Failed to create workflow JDBC tables", error)
        }
        jdbcTablesCreated = true
    }
}

data class WorkflowEntry<S, R>(
    val workflow: Workflow<S, R>,
    val stateCodec: WorkflowStateCodec<S>,
    val persistenceFactory: (String) -> WorkflowPersistence<S>?,
) {
    fun decodeState(payload: String): S = stateCodec.decode(payload)

    suspend fun run(
        initialState: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>?,
    ): R = workflow.run(
        initialState = initialState,
        context = context,
        observer = observer,
        persistence = persistence,
    )

    suspend fun resume(
        context: WorkflowContext,
        observer: WorkflowObserver,
        persistence: WorkflowPersistence<S>,
    ): R = workflow.resume(
        context = context,
        observer = observer,
        persistence = persistence,
    )
}

class WorkflowNotRegisteredException(
    workflowName: String,
) : RuntimeException("Workflow '$workflowName' is not registered")

private fun SQLException.isTableAlreadyExists(): Boolean =
    sqlState == "42S01" || errorCode == 42101
