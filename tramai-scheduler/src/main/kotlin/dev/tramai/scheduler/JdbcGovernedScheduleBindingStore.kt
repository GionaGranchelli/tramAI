package dev.tramai.scheduler

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import javax.sql.DataSource

/**
 * Durable governed schedule bindings in JDBC (0.7.1d).
 *
 * One additive table of its own, owned by this store: the released scheduler tables are not
 * altered, and no migration of an already-released schema is rewritten. Only the deployment
 * is bound here — a schedule is not a run, so it never carries a run identity.
 */
class JdbcGovernedScheduleBindingStore(
    private val dataSource: DataSource,
) : GovernedScheduleBindingStore {
    fun createTableSql(): List<String> = listOf(GOVERNANCE_TABLE)

    override suspend fun putGovernedScheduleBinding(binding: GovernedScheduleBinding) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            var committed = false
            try {
                connection.prepareStatement(DELETE_SQL).use { statement ->
                    statement.setString(1, binding.scheduleId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(INSERT_SQL).use { statement ->
                    statement.setString(1, binding.scheduleId)
                    statement.setString(2, binding.deploymentIdentity.workloadId.value)
                    listOf(
                        binding.scheduleId,
                        binding.deploymentIdentity.workloadId.value,
                        binding.deploymentIdentity.configuration.id.value,
                        binding.deploymentIdentity.configuration.version.value,
                        binding.deploymentIdentity.environmentId.value,
                        binding.deploymentIdentity.deploymentId.value,
                    ).forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeUpdate()
                }
                connection.commit()
                committed = true
            } finally {
                if (!committed) connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    override suspend fun getGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding? {
        dataSource.connection.use { connection -> return connection.readGovernedScheduleBinding(scheduleId) }
    }

    private fun java.sql.Connection.readGovernedScheduleBinding(scheduleId: String): GovernedScheduleBinding? =
        prepareStatement(SELECT_SQL).use { statement ->
            statement.setString(1, scheduleId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return null
                return resultSet.toBinding()
            }
        }

    private fun java.sql.ResultSet.toBinding() =
        GovernedScheduleBinding(
            scheduleId = getString("schedule_id"),
            deploymentIdentity =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(getString("workload_id")),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId(getString("configuration_id")),
                            version = ConfigurationVersion(getString("configuration_version")),
                        ),
                    environmentId = EnvironmentId(getString("environment_id")),
                    deploymentId = DeploymentId(getString("deployment_id")),
                ),
        )

    private companion object {
        private const val GOVERNANCE_TABLE = """
            CREATE TABLE IF NOT EXISTS workflow_schedule_governance (
                schedule_id VARCHAR(255) PRIMARY KEY,
                workload_id VARCHAR(255) NOT NULL,
                configuration_id VARCHAR(255) NOT NULL,
                configuration_version VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                deployment_id VARCHAR(255) NOT NULL
            )
            """

        private const val DELETE_SQL = "DELETE FROM workflow_schedule_governance WHERE schedule_id = ?"

        private const val INSERT_SQL =
            "INSERT INTO workflow_schedule_governance (" +
                "schedule_id, workload_id, configuration_id, configuration_version, " +
                "environment_id, deployment_id) VALUES (?, ?, ?, ?, ?, ?)"

        private const val SELECT_SQL =
            "SELECT schedule_id, workload_id, configuration_id, configuration_version, " +
                "environment_id, deployment_id FROM workflow_schedule_governance WHERE schedule_id = ?"
    }
}
