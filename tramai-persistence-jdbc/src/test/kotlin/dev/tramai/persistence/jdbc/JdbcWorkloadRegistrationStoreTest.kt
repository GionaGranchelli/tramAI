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
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * JDBC-specific proofs the shared TCK cannot show: durability across store
 * instances (restart), configuration-rebinding enforcement between two
 * independent JDBC stores on the same database, and DB-level atomic create
 * races across store instances.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkloadRegistrationStoreTest {
    private lateinit var postgres: PostgreSQLContainer<*>
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
}
