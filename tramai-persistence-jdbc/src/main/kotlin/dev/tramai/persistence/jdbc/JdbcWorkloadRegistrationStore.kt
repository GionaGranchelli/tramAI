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
import java.sql.PreparedStatement
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
 *
 * TooManyFunctions: the class carries the persistence-SPI surface plus small
 * per-query resource/row helpers; splitting them into a separate helper class
 * would spread one storage concern across files.
 */
@Suppress("TooManyFunctions")
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
        queryRegistration(SELECT_REGISTRATION_WHERE_SCOPE) { statement ->
            var index = 1
            statement.setString(index++, workloadId.value)
            statement.setString(index++, environmentId.value)
            statement.setString(index, deploymentId.value)
        }

    override suspend fun create(registration: RegisteredWorkload): CreateResult =
        withSafeJdbc(
            {
                "Database operation failed while registering workload " +
                    "${registration.identity.workloadId.value}/" +
                    "${registration.identity.environmentId.value}/${registration.identity.deploymentId.value}"
            },
        ) {
            val connection = dataSource.connection
            try {
                connection.autoCommit = false
                ensureConfigurationBinding(connection, registration)?.let { return@withSafeJdbc it }
                insertRegistration(connection, registration)
                connection.commit()
                CreateResult.Created(registration)
            } catch (e: SQLException) {
                connection.rollbackQuietly()
                if (isUniqueViolation(e)) {
                    // Scope already registered — classify on a fresh connection.
                    classifyExisting(registration)
                } else {
                    throw e
                }
            } finally {
                connection.closeQuietly()
            }
        }

    /**
     * Establishes the global configuration binding (insert-if-absent) and
     * verifies it cannot be rebound. Returns a [CreateResult.Conflicting]
     * when the pair is already bound to a different fingerprint; null when the
     * binding matches and registration may proceed.
     */
    private fun ensureConfigurationBinding(
        conn: Connection,
        registration: RegisteredWorkload,
    ): CreateResult? {
        val configurationId = registration.identity.configuration.id.value
        val configurationVersion = registration.identity.configuration.version.value
        val requestedFingerprint = registration.configurationFingerprint.value

        conn.prepareStatement(INSERT_CONFIGURATION_REVISION).use { statement ->
            var index = 1
            statement.setString(index++, configurationId)
            statement.setString(index++, configurationVersion)
            statement.setString(index, requestedFingerprint)
            statement.executeUpdate()
        }

        val boundFingerprint =
            conn.prepareStatement(SELECT_CONFIGURATION_FINGERPRINT).use { statement ->
                var index = 1
                statement.setString(index++, configurationId)
                statement.setString(index, configurationVersion)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else requestedFingerprint
                }
            }
        if (boundFingerprint != requestedFingerprint) {
            conn.rollbackQuietly()
            return CreateResult.Conflicting(
                existing = boundRegistrationFor(configurationId, configurationVersion),
                reason = RegistrationConflictReason.CONFIGURATION_REBINDING,
            )
        }
        return null
    }

    private fun insertRegistration(
        conn: Connection,
        registration: RegisteredWorkload,
    ) {
        conn.prepareStatement(INSERT_WORKLOAD_REGISTRATION).use { statement ->
            var index = 1
            statement.setString(index++, registration.identity.workloadId.value)
            statement.setString(index++, registration.identity.environmentId.value)
            statement.setString(index++, registration.identity.deploymentId.value)
            statement.setString(index++, registration.identity.configuration.id.value)
            statement.setString(index++, registration.identity.configuration.version.value)
            statement.setString(index++, registration.metadata.owner)
            statement.setString(index++, registration.metadata.purpose)
            statement.setString(index++, registration.lifecycle.name)
            statement.setLong(index, registration.stateVersion.value)
            statement.executeUpdate()
        }
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
    ): RegisteredWorkload {
        val registration =
            queryRegistration(SELECT_REGISTRATION_WHERE_CONFIGURATION) { statement ->
                var index = 1
                statement.setString(index++, configurationId)
                statement.setString(index, configurationVersion)
            }
        return checkNotNull(registration) {
            "Configuration binding exists without an owning registration"
        }
    }

    override suspend fun compareAndSet(
        expected: RegisteredWorkload,
        updated: RegisteredWorkload,
    ): Boolean {
        require(updated.identity == expected.identity) {
            "compareAndSet must not change registration identity"
        }
        require(updated.configurationFingerprint == expected.configurationFingerprint) {
            "compareAndSet must not change configuration fingerprint"
        }
        return withSafeJdbc(
            { "Database operation failed while updating workload registration " + expected.identity.scopeKey() },
        ) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPDATE_WORKLOAD_REGISTRATION).use { statement ->
                    var index = 1
                    statement.setString(index++, updated.metadata.owner)
                    statement.setString(index++, updated.metadata.purpose)
                    statement.setString(index++, updated.lifecycle.name)
                    statement.setLong(index++, updated.stateVersion.value)
                    statement.setString(index++, expected.identity.workloadId.value)
                    statement.setString(index++, expected.identity.environmentId.value)
                    statement.setString(index++, expected.identity.deploymentId.value)
                    statement.setLong(index, expected.stateVersion.value)
                    statement.executeUpdate() == 1
                }
            }
        }
    }

    /**
     * Executes a registration SELECT with the given parameter binder and maps
     * the first row, or null when no row matches. Resource handling is
     * sequential try/finally so nesting stays flat.
     */
    private fun queryRegistration(
        selectSql: String,
        bind: (PreparedStatement) -> Unit,
    ): RegisteredWorkload? {
        val connection = dataSource.connection
        connection.use { conn ->
            return queryOnConnection(conn, selectSql, bind)
        }
    }

    private fun queryOnConnection(
        conn: Connection,
        selectSql: String,
        bind: (PreparedStatement) -> Unit,
    ): RegisteredWorkload? =
        conn.prepareStatement(selectSql).use { statement ->
            bind(statement)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toRegisteredWorkload() else null
            }
        }

    private fun WorkloadDeploymentIdentity.scopeKey(): String {
        val workload = workloadId.value
        val environment = environmentId.value
        val deployment = deploymentId.value
        return "$workload/$environment/$deployment"
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

private const val INSERT_CONFIGURATION_REVISION =
    """
    INSERT INTO tramai_configuration_revision (configuration_id, configuration_version, fingerprint)
    VALUES (?, ?, ?)
    ON CONFLICT (configuration_id, configuration_version) DO NOTHING
    """

private const val SELECT_CONFIGURATION_FINGERPRINT =
    """
    SELECT fingerprint FROM tramai_configuration_revision
    WHERE configuration_id = ? AND configuration_version = ?
    """

private const val INSERT_WORKLOAD_REGISTRATION =
    """
    INSERT INTO tramai_workload_registration (
        workload_id, environment_id, deployment_id,
        configuration_id, configuration_version,
        owner, purpose, lifecycle_state, state_version
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val UPDATE_WORKLOAD_REGISTRATION =
    """
    UPDATE tramai_workload_registration
    SET owner = ?, purpose = ?, lifecycle_state = ?, state_version = ?
    WHERE workload_id = ? AND environment_id = ? AND deployment_id = ?
      AND state_version = ?
    """

private const val SELECT_REGISTRATION_WHERE_SCOPE =
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
    """

private const val SELECT_REGISTRATION_WHERE_CONFIGURATION =
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
    """
