package dev.tramai.persistence.jdbc

import dev.tramai.controlplane.ConfigurationFingerprint
import dev.tramai.controlplane.CreateResult
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.RegistrationConflictReason
import dev.tramai.controlplane.WorkloadLifecycleState
import dev.tramai.controlplane.WorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * JDBC-backed [WorkloadRegistrationStore] (Epic 0.7.1c) over the
 * `tramai_workload_registration` and `tramai_configuration_revision` tables
 * (migration V8).
 *
 * ## Atomicity
 * [create] runs inside an explicit transaction: the configuration-revision
 * binding is established/verified and the registration inserted in one commit.
 * A Postgres unique violation aborts the transaction, so classification of an
 * existing registration happens on a fresh connection after rollback — never
 * inside the aborted transaction.
 *
 * ## Database-level authority
 * The primary keys themselves enforce one registration per deployment scope
 * and one immutable fingerprint per `(configuration_id, configuration_version)`
 * — two JDBC store instances pointing at the same database cannot race around
 * the contract.
 *
 * The store never implements authority rules; it only stores what
 * [dev.tramai.controlplane.WorkloadRegistrationAuthority] decides.
 */
class JdbcWorkloadRegistrationStore(
    private val dataSource: DataSource,
) : WorkloadRegistrationStore {
    override suspend fun find(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): RegisteredWorkload? =
        withSafeJdbc({ "Database operation failed while reading a workload registration" }) {
            readScope(workloadId, environmentId, deploymentId)
        }

    private fun readScope(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): RegisteredWorkload? =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT r.workload_id, r.environment_id, r.deployment_id,
                           r.configuration_id, r.configuration_version,
                           cr.fingerprint,
                           r.owner, r.purpose, r.lifecycle_state, r.state_version
                    FROM tramai_workload_registration r
                    JOIN tramai_configuration_revision cr
                      ON cr.configuration_id = r.configuration_id
                     AND cr.configuration_version = r.configuration_version
                    WHERE r.workload_id = ? AND r.environment_id = ? AND r.deployment_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, workloadId.value)
                    statement.setString(2, environmentId.value)
                    statement.setString(3, deploymentId.value)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toRegisteredWorkload() else null
                    }
                }
        }

    override suspend fun create(registration: RegisteredWorkload): CreateResult =
        withSafeJdbc(
            {
                "Database operation failed while registering workload " +
                    "${registration.identity.workloadId.value} in " +
                    "${registration.identity.environmentId.value}/${registration.identity.deploymentId.value}"
            },
        ) {
            var connection: Connection? = null
            try {
                connection = dataSource.connection
                connection.autoCommit = false
                createWithinTransaction(connection, registration)
            } catch (e: SQLException) {
                connection?.rollbackQuietly()
                if (isUniqueViolation(e)) {
                    // Scope already registered — classify on a fresh connection.
                    return@withSafeJdbc classifyExisting(registration)
                }
                throw e
            } finally {
                connection?.closeQuietly()
            }
        }

    private fun createWithinTransaction(
        conn: Connection,
        registration: RegisteredWorkload,
    ): CreateResult {
        val configurationId = registration.identity.configuration.id.value
        val configurationVersion = registration.identity.configuration.version.value

        // 1) Establish the global configuration binding if absent.
        conn
            .prepareStatement(
                """
                INSERT INTO tramai_configuration_revision (configuration_id, configuration_version, fingerprint)
                VALUES (?, ?, ?)
                ON CONFLICT (configuration_id, configuration_version) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, configurationId)
                statement.setString(2, configurationVersion)
                statement.setString(3, registration.configurationFingerprint.value)
                statement.executeUpdate()
            }

        // 2) Verify the binding: a (configurationId, version) pair can never be
        //    rebound to a different fingerprint.
        conn
            .prepareStatement(
                """
                SELECT fingerprint FROM tramai_configuration_revision
                WHERE configuration_id = ? AND configuration_version = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, configurationId)
                statement.setString(2, configurationVersion)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        val boundFingerprint = rs.getString(1)
                        if (boundFingerprint != registration.configurationFingerprint.value) {
                            conn.rollbackQuietly()
                            return CreateResult.Conflicting(
                                existing = boundRegistrationFor(configurationId, configurationVersion),
                                reason = RegistrationConflictReason.CONFIGURATION_REBINDING,
                            )
                        }
                    }
                }
            }

        // 3) Insert the registration for the deployment scope.
        conn
            .prepareStatement(
                """
                INSERT INTO tramai_workload_registration (
                    workload_id, environment_id, deployment_id,
                    configuration_id, configuration_version,
                    owner, purpose, lifecycle_state, state_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, registration.identity.workloadId.value)
                statement.setString(2, registration.identity.environmentId.value)
                statement.setString(3, registration.identity.deploymentId.value)
                statement.setString(4, configurationId)
                statement.setString(5, configurationVersion)
                statement.setString(6, registration.metadata.owner)
                statement.setString(7, registration.metadata.purpose)
                statement.setString(8, registration.lifecycle.name)
                statement.setLong(9, registration.stateVersion.value)
                statement.executeUpdate()
            }
        conn.commit()
        return CreateResult.Created(registration)
    }

    /** Runs on a fresh connection (the failed transaction is aborted). */
    private fun classifyExisting(registration: RegisteredWorkload): CreateResult {
        val scope = registration.identity
        val existing =
            readScope(scope.workloadId, scope.environmentId, scope.deploymentId)
                ?: error("Unique violation reported but no registration exists for the scope")
        return if (existing.isSameDeclaration(registration)) {
            CreateResult.Idempotent(existing)
        } else {
            CreateResult.Conflicting(existing, RegistrationConflictReason.CONFLICTING_REGISTRATION)
        }
    }

    private fun boundRegistrationFor(
        configurationId: String,
        configurationVersion: String,
    ): RegisteredWorkload =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT r.workload_id, r.environment_id, r.deployment_id,
                           r.configuration_id, r.configuration_version,
                           cr.fingerprint,
                           r.owner, r.purpose, r.lifecycle_state, r.state_version
                    FROM tramai_workload_registration r
                    JOIN tramai_configuration_revision cr
                      ON cr.configuration_id = r.configuration_id
                     AND cr.configuration_version = r.configuration_version
                    WHERE r.configuration_id = ? AND r.configuration_version = ?
                    ORDER BY r.workload_id, r.environment_id, r.deployment_id
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, configurationId)
                    statement.setString(2, configurationVersion)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            rs.toRegisteredWorkload()
                        } else {
                            error("Configuration binding exists without an owning registration")
                        }
                    }
                }
        }

    override suspend fun compareAndSet(
        expected: RegisteredWorkload,
        updated: RegisteredWorkload,
    ): Boolean =
        withSafeJdbc(
            {
                "Database operation failed while updating workload registration " +
                    "${expected.identity.workloadId.value}/${expected.identity.environmentId.value}/${expected.identity.deploymentId.value}"
            },
        ) {
            val scope = expected.identity
            require(updated.identity == scope) {
                "compareAndSet must not change the deployment scope of a registration"
            }
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        UPDATE tramai_workload_registration
                        SET configuration_id = ?, configuration_version = ?,
                            owner = ?, purpose = ?, lifecycle_state = ?, state_version = ?
                        WHERE workload_id = ? AND environment_id = ? AND deployment_id = ?
                          AND state_version = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, updated.identity.configuration.id.value)
                        statement.setString(2, updated.identity.configuration.version.value)
                        statement.setString(3, updated.metadata.owner)
                        statement.setString(4, updated.metadata.purpose)
                        statement.setString(5, updated.lifecycle.name)
                        statement.setLong(6, updated.stateVersion.value)
                        statement.setString(7, scope.workloadId.value)
                        statement.setString(8, scope.environmentId.value)
                        statement.setString(9, scope.deploymentId.value)
                        statement.setLong(10, expected.stateVersion.value)
                        statement.executeUpdate() == 1
                    }
            }
        }

    private fun ResultSet.toRegisteredWorkload(): RegisteredWorkload =
        RegisteredWorkload(
            identity =
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
            configurationFingerprint = ConfigurationFingerprint(getString("fingerprint")),
            metadata =
                WorkloadMetadata(
                    owner = getString("owner"),
                    purpose = getString("purpose"),
                ),
            lifecycle = WorkloadLifecycleState.valueOf(getString("lifecycle_state")),
            stateVersion = WorkloadStateVersion(getLong("state_version")),
        )

    private fun Connection.rollbackQuietly() {
        runCatching { rollback() }
    }

    private fun Connection.closeQuietly() {
        runCatching { close() }
    }

    private fun isUniqueViolation(e: SQLException): Boolean = e.sqlState == "23505"

    private fun RegisteredWorkload.isSameDeclaration(other: RegisteredWorkload): Boolean =
        identity == other.identity &&
            configurationFingerprint == other.configurationFingerprint &&
            metadata == other.metadata
}
