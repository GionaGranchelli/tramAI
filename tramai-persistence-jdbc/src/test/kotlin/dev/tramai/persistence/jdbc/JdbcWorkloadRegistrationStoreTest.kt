package dev.tramai.persistence.jdbc

import dev.tramai.controlplane.ConfigurationFingerprint
import dev.tramai.controlplane.CreateResult
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.RegistrationConflictReason
import dev.tramai.controlplane.WorkloadLifecycleState
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.WorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.controlplane.testing.runInParallel
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertFailsWith

/**
 * JDBC-specific proofs the shared TCK cannot show: durability across store
 * instances (restart), configuration-rebinding enforcement between two
 * independent JDBC stores on the same database, DB-level atomic create races
 * across store instances, and the migration-level bounds that stop
 * out-of-contract rows from entering the authoritative tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkloadRegistrationStoreTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource

    private fun newStore(): WorkloadRegistrationStore = JdbcWorkloadRegistrationStore(dataSource)

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("control_plane_test")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        JdbcWorkloadRegistrationStoreTckTest.executeSchema(dataSource)
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { postgres.stop() }
    }

    private fun wipe() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM tramai_workload_registration")
                stmt.execute("DELETE FROM tramai_configuration_revision")
            }
        }
    }

    /**
     * Proves the migration defends the same bounds the typed identity and
     * metadata values defend: a row that cannot be reconstructed into valid
     * Kotlin types must not be insertable in the first place.
     */
    @Test
    fun `database rejects rows outside the bounded identity and metadata contracts`() =
        runBlocking<Unit> {
            wipe()

            // Exactly at the contract bound: accepted, and readable back through the
            // typed mapper — the database bound is never stricter than the contract.
            val atBound =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(workload = "w".repeat(ID_BOUND)),
                    metadata =
                        WorkloadRegistrationFixtures.metadata(
                            owner = "o".repeat(OWNER_BOUND),
                            purpose = "p".repeat(PURPOSE_BOUND),
                        ),
                )
            assertThat(newStore().create(atBound)).isInstanceOf(CreateResult.Created::class.java)
            wipe()

            // One character over a bound: the database itself refuses the row.
            assertCheckViolation("configuration_id") {
                insertRawConfigurationRevision("c".repeat(ID_BOUND + 1), REVISION_VERSION)
            }
            assertCheckViolation("configuration_version") {
                insertRawConfigurationRevision(REVISION_ID, "v".repeat(ID_BOUND + 1))
            }
            for (column in IDENTITY_COLUMNS) {
                assertCheckViolation(column) {
                    insertRawRegistration(mapOf(column to "x".repeat(ID_BOUND + 1)))
                }
            }
            assertCheckViolation("owner") {
                insertRawRegistration(mapOf("owner" to "o".repeat(OWNER_BOUND + 1)))
            }
            assertCheckViolation("purpose") {
                insertRawRegistration(mapOf("purpose" to "p".repeat(PURPOSE_BOUND + 1)))
            }
        }

    private fun insertRawConfigurationRevision(
        configurationId: String,
        configurationVersion: String,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(INSERT_RAW_CONFIGURATION_REVISION).use { statement ->
                statement.setString(1, configurationId)
                statement.setString(2, configurationVersion)
                statement.setString(3, VALID_FINGERPRINT)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Inserts a registration row straight into SQL, bypassing every typed value.
     * The parent revision row is always valid, so only the overridden column can
     * violate a bound.
     */
    private fun insertRawRegistration(overrides: Map<String, String>) {
        insertRawConfigurationRevision(REVISION_ID, REVISION_VERSION)
        val values = VALID_COLUMNS + overrides
        val columns = values.keys.joinToString(", ")
        val placeholders = values.keys.joinToString(", ") { "?" }
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO tramai_workload_registration ($columns, lifecycle_state, state_version) " +
                        "VALUES ($placeholders, '$ACTIVE_STATE', 1)",
                ).use { statement ->
                    values.values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.executeUpdate()
                }
        }
    }

    private fun assertCheckViolation(
        column: String,
        block: () -> Unit,
    ) {
        val failure =
            assertFailsWith<SQLException>("$column outside its contract bound must be rejected") { block() }
        assertThat(failure.sqlState)
            .describedAs("$column must fail its CHECK constraint, not some other error")
            .isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `registration state survives store restart`() =
        runBlocking<Unit> {
            wipe()
            val identity = WorkloadRegistrationFixtures.identity()
            val registration = WorkloadRegistrationFixtures.registration(identity = identity)
            assertThat(newStore().create(registration)).isInstanceOf(CreateResult.Created::class.java)

            // A brand-new store instance over the same database is a restart.
            val restarted = newStore()
            assertThat(
                restarted.find(identity.workloadId, identity.environmentId, identity.deploymentId),
            ).isEqualTo(registration)

            // Authority rules survive the restart too.
            val authority = WorkloadRegistrationAuthority(newStore())
            val outcome =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadMetadata(owner = "Restart Team", purpose = "Fraud review"),
                )
            assertThat(outcome).isInstanceOf(dev.tramai.controlplane.MetadataUpdateOutcome.Applied::class.java)
        }

    @Test
    fun `configuration rebinding is rejected across independent JDBC stores`() =
        runBlocking<Unit> {
            wipe()
            val first = WorkloadRegistrationFixtures.identity(deployment = "eu-west-amsterdam-01")
            val second = WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01")
            assertThat(
                newStore().create(WorkloadRegistrationFixtures.registration(identity = first)),
            ).isInstanceOf(CreateResult.Created::class.java)

            // A DIFFERENT store instance attempts the same (config, version) with a
            // different fingerprint: database-level authority rejects it.
            val result =
                newStore().create(
                    WorkloadRegistrationFixtures.registration(
                        identity = second,
                        configurationFingerprint = ConfigurationFingerprint("sha256:ffff"),
                    ),
                )

            assertThat(result).isInstanceOf(CreateResult.Conflicting::class.java)
            result as CreateResult.Conflicting
            assertThat(result.reason).isEqualTo(RegistrationConflictReason.CONFIGURATION_REBINDING)
        }

    @Test
    fun `concurrent creates across two store instances yield one authoritative outcome`() =
        runBlocking<Unit> {
            repeat(5) { iteration ->
                wipe()
                val identity = WorkloadRegistrationFixtures.identity(deployment = "race-$iteration")
                val registration = WorkloadRegistrationFixtures.registration(identity = identity)

                val results =
                    runInParallel(
                        { newStore().create(registration) },
                        { newStore().create(registration) },
                    )

                val created = results.filterIsInstance<CreateResult.Created>()
                val idempotent = results.filterIsInstance<CreateResult.Idempotent>()
                assertThat(created.size).isEqualTo(1)
                assertThat(created.size + idempotent.size).isEqualTo(2)
            }
        }

    @Test
    fun `registration is never silently replaced across store instances`() =
        runBlocking<Unit> {
            wipe()
            val identity = WorkloadRegistrationFixtures.identity()
            val original = WorkloadRegistrationFixtures.registration(identity = identity)
            newStore().create(original)

            // A second store tries to claim the same scope with a different
            // configuration version: must conflict, never overwrite.
            val conflicting =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(configurationVersion = "99"),
                )
            val result = newStore().create(conflicting)

            assertThat(result).isInstanceOf(CreateResult.Conflicting::class.java)
            result as CreateResult.Conflicting
            assertThat(result.reason).isEqualTo(RegistrationConflictReason.CONFLICTING_REGISTRATION)
            assertThat(
                newStore().find(identity.workloadId, identity.environmentId, identity.deploymentId),
            ).isEqualTo(original)
        }

    @Test
    fun `retired registration remains terminal after restart`() =
        runBlocking<Unit> {
            wipe()
            val identity = WorkloadRegistrationFixtures.identity()
            newStore().create(WorkloadRegistrationFixtures.registration(identity = identity))
            val authority = WorkloadRegistrationAuthority(newStore())
            authority.transitionLifecycle(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadLifecycleState.RETIRED,
            )

            val resurrectedAuthority = WorkloadRegistrationAuthority(newStore())
            val restarted =
                resurrectedAuthority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion(2),
                    WorkloadLifecycleState.ACTIVE,
                )

            assertThat(restarted)
                .isInstanceOf(dev.tramai.controlplane.LifecycleTransitionOutcome.InvalidTransition::class.java)
            val record = newStore().find(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(record?.lifecycle).isEqualTo(WorkloadLifecycleState.RETIRED)
            assertThat(record?.stateVersion).isEqualTo(WorkloadStateVersion(2))
        }

    /** Bounds the migration and the typed contracts must agree on. */
    private companion object {
        const val ID_BOUND = 128
        const val OWNER_BOUND = 256
        const val PURPOSE_BOUND = 512
        const val REVISION_ID = "claims-prod"
        const val REVISION_VERSION = "17"
        const val VALID_FINGERPRINT = "sha256:raw"
        const val ACTIVE_STATE = "ACTIVE"
        const val CHECK_VIOLATION = "23514"
        const val INSERT_RAW_CONFIGURATION_REVISION =
            "INSERT INTO tramai_configuration_revision " +
                "(configuration_id, configuration_version, fingerprint) VALUES (?, ?, ?) " +
                "ON CONFLICT (configuration_id, configuration_version) DO NOTHING"

        val IDENTITY_COLUMNS =
            listOf(
                "workload_id",
                "environment_id",
                "deployment_id",
                "configuration_id",
                "configuration_version",
            )

        val VALID_COLUMNS =
            mapOf(
                "workload_id" to "claims",
                "environment_id" to "production",
                "deployment_id" to "eu-west-amsterdam-01",
                "configuration_id" to REVISION_ID,
                "configuration_version" to REVISION_VERSION,
                "owner" to "Claims Team",
                "purpose" to "Fraud review",
            )
    }
}
