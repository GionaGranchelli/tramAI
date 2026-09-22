package dev.tramai.persistence.jdbc

import dev.tramai.controlplane.MetadataUpdateOutcome
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * The 0.7.1e command precondition over the DURABLE authority (0.7.1c store), across instances and
 * across reconstruction.
 *
 * The point of this test is that the stale rule is not in-process state: instance A must lose to
 * instance B's committed mutation, and it must still lose after a fresh store instance and a fresh
 * authority are built over the same database. Anything weaker would let a command succeed merely
 * because it was issued by the process that happened to hold the older record.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcWorkloadRegistrationStaleAcrossInstancesTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("control_plane_stale")
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
        postgres.stop()
    }

    /** A separate "instance": its own store over the shared database, behind the command port. */
    private fun newInstance() = WorkloadRegistrationAuthority(JdbcWorkloadRegistrationStore(dataSource))

    @Test
    fun `a stale expected version is rejected across instances and after reconstruction`() =
        runBlocking<Unit> {
            val scope = WorkloadRegistrationFixtures.identity(deployment = "cross-instance-1")
            val instanceA = newInstance()
            val instanceB = newInstance()
            instanceA.register(
                scope,
                WorkloadRegistrationFixtures.fingerprint(),
                WorkloadRegistrationFixtures.metadata(),
            )

            // A observes vN.
            val observedByA =
                instanceA.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)!!.observedVersion

            // B commits the next version while A still holds vN.
            val committedByB =
                instanceB.updateMetadata(
                    scope.workloadId,
                    scope.environmentId,
                    scope.deploymentId,
                    observedByA,
                    WorkloadRegistrationFixtures.metadata(owner = "Instance B"),
                )
            assertThat(committedByB).isInstanceOf(MetadataUpdateOutcome.Applied::class.java)
            val currentByB = (committedByB as MetadataUpdateOutcome.Applied).exposure.stateVersion
            assertThat(currentByB).isEqualTo(observedByA.next())

            // A submits the version it read: precondition failure naming B's committed version.
            val staleFromA =
                instanceA.updateMetadata(
                    scope.workloadId,
                    scope.environmentId,
                    scope.deploymentId,
                    observedByA,
                    WorkloadRegistrationFixtures.metadata(owner = "Instance A"),
                )
            assertThat(staleFromA).isInstanceOf(MetadataUpdateOutcome.Stale::class.java)
            (staleFromA as MetadataUpdateOutcome.Stale).let {
                assertThat(it.expectedVersion).isEqualTo(observedByA)
                assertThat(it.currentVersion).isEqualTo(currentByB)
            }
            assertThat(owner(instanceA, scope)).isEqualTo("Instance B")

            // Same rule after reconstruction: a fresh store instance and a fresh authority.
            val reconstructed = newInstance()
            val staleAfterReconstruction =
                reconstructed.updateMetadata(
                    scope.workloadId,
                    scope.environmentId,
                    scope.deploymentId,
                    observedByA,
                    WorkloadRegistrationFixtures.metadata(owner = "Instance A after restart"),
                )
            assertThat(staleAfterReconstruction).isInstanceOf(MetadataUpdateOutcome.Stale::class.java)
            (staleAfterReconstruction as MetadataUpdateOutcome.Stale).let {
                assertThat(it.expectedVersion).isEqualTo(observedByA)
                assertThat(it.currentVersion).isEqualTo(currentByB)
            }
            assertThat(owner(reconstructed, scope)).isEqualTo("Instance B")
        }

    @Test
    fun `re-reading the authoritative version lets the stale writer reconcile and win exactly once`() =
        runBlocking<Unit> {
            val scope = WorkloadRegistrationFixtures.identity(deployment = "cross-instance-2")
            val writer = newInstance()
            val other = newInstance()
            writer.register(scope, WorkloadRegistrationFixtures.fingerprint(), WorkloadRegistrationFixtures.metadata())
            val observed =
                writer.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)!!.observedVersion
            other.updateMetadata(
                scope.workloadId,
                scope.environmentId,
                scope.deploymentId,
                observed,
                WorkloadRegistrationFixtures.metadata(owner = "Other"),
            )

            // The stale writer re-reads instead of retrying blindly, then succeeds once.
            val current =
                writer.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)!!.observedVersion
            val recovered =
                writer.updateMetadata(
                    scope.workloadId,
                    scope.environmentId,
                    scope.deploymentId,
                    current,
                    WorkloadRegistrationFixtures.metadata(owner = "Reconciled Writer"),
                )

            assertThat(recovered).isInstanceOf(MetadataUpdateOutcome.Applied::class.java)
            val advanced = (recovered as MetadataUpdateOutcome.Applied).exposure.stateVersion
            assertThat(advanced).isEqualTo(current.next())
            assertThat(owner(writer, scope)).isEqualTo("Reconciled Writer")
        }

    private suspend fun owner(
        authority: WorkloadRegistrationAuthority,
        scope: dev.tramai.core.identity.WorkloadDeploymentIdentity,
    ): String =
        authority
            .authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)!!
            .exposure.metadata.owner
}
