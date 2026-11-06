package dev.tramai.examples.timeline

import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.UninstrumentedAttemptObservationStore
import dev.tramai.persistence.jdbc.JdbcModelAttemptObservationStore
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.testing.GovernanceProjectionTestSeeder
import dev.tramai.security.reconstruction.EvidenceBackedReconstructor
import dev.tramai.security.reconstruction.HistoricalConfigurationReference
import dev.tramai.security.reconstruction.HistoricalConfigurationSource
import dev.tramai.security.reconstruction.ReconstructionLimitation
import dev.tramai.security.reconstruction.ReconstructionResult
import dev.tramai.security.reconstruction.ReconstructionStatus
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.SemanticTimelineQuery
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Epic 0.7.5h — real persistence to real reconstruction, on both halves, in PostgreSQL.
 *
 * What is real: the governance projection is written by the shipped materializer (through the
 * `:tramai-security` test fixture) and read through the public `GovernanceQueryService.jdbc` port, and the
 * observations are persisted through the real `JdbcModelAttemptObservationStore` and read back from real
 * `model_attempt_observations` rows.
 *
 * What is not: the attempt *records* are constructed by the example, not produced by a governed engine
 * run. This test qualifies persistence-to-reconstruction, not the producer path — that path is proven
 * separately in 0.7.5g (`ProducerPathConfidentialityTest`, `ProducerPathProvenanceTest`,
 * `JdbcAttemptObservationConfidentialityTest`). Neither proof substitutes for the other.
 *
 * The four evidence states are all reachable, with one honest caveat: `UNSUPPORTED` requires *both*
 * sources to be silent, which needs an uninstrumented attempt store — an instrumented PostgreSQL store
 * always answers — so that case is proven with `UninstrumentedAttemptObservationStore` rather than
 * pretended against a table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimelineReconstructionPersistenceTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var url: String
    private lateinit var user: String
    private lateinit var password: String

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("timeline_reconstruction")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        url = postgres.jdbcUrl
        user = postgres.username
        password = postgres.password
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        migrate("tramai/security/governance/postgres/V1__governance_projection_read_model.sql")
        migrate("tramai/persistence/jdbc/postgres/V9__model_attempt_observations.sql")
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { postgres.stop() }
    }

    @BeforeEach
    fun clean() {
        execute("DELETE FROM model_attempt_observations")
    }

    @Test
    fun `recorded attempts plus a materialized projection reconstruct as SUPPORTED`() {
        runBlocking {
            GovernanceProjectionTestSeeder.seedDeniedPolicyDecision(dataSource)
            val store = JdbcModelAttemptObservationStore(dataSource)
            TimelineReconstructionExample.recordedAttempts(RUN_ID).forEach(store::append)

            val result = reconstruct(store, GovernanceQueryService.jdbc(dataSource))

            // Both sources answered from real persisted state.
            assertThat(result.status).isEqualTo(ReconstructionStatus.SUPPORTED)
            assertThat(result.timeline.history).isEqualTo(AttemptHistoryState.RECORDED)
            assertThat(result.timeline.governance).isInstanceOf(GovernanceSection.Snapshot::class.java)
            val snapshot = result.timeline.governance as GovernanceSection.Snapshot
            assertThat(snapshot.view.decisions).isNotEmpty()
            assertThat(result.timeline.attempts()).hasSize(2)
            // ... and the result still states what it cannot establish.
            assertThat(result.limitations)
                .contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED)
        }
    }

    @Test
    fun `recorded attempts without a governance scope reconstruct as PARTIAL`() {
        runBlocking {
            val store = JdbcModelAttemptObservationStore(dataSource)
            store.append(TimelineReconstructionExample.recordedAttempts(RUN_ID).first())

            val result = reconstruct(store, governance = null)

            assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
            assertThat(result.timeline.governance).isEqualTo(GovernanceSection.NotQueried)
            assertThat(result.limitations).contains(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)
            // The attempt really was read back from PostgreSQL, so PARTIAL is not a read failure.
            assertThat(result.timeline.attempts()).hasSize(1)
        }
    }

    @Test
    fun `an empty instrumented projection reconstructs as PARTIAL and never as nothing happened`() {
        runBlocking {
            // A real, migrated, empty store: instrumented, and it answers "no attempts recorded".
            val store = JdbcModelAttemptObservationStore(dataSource)

            val result = reconstruct(store, GovernanceQueryService.jdbc(dataSource))

            assertThat(result.timeline.history).isEqualTo(AttemptHistoryState.EMPTY)
            assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
            assertThat(result.limitations).contains(ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED)
            assertThat(result.timeline.attempts()).isEmpty()
        }
    }

    @Test
    fun `an uninstrumented store cannot support a reconstruction`() {
        runBlocking {
            val result = reconstruct(UninstrumentedAttemptObservationStore, governance = null)

            // Both sources silent: the only path to UNSUPPORTED, and it is honest about it.
            assertThat(result.status).isEqualTo(ReconstructionStatus.UNSUPPORTED)
            assertThat(result.timeline.history).isEqualTo(AttemptHistoryState.UNINSTRUMENTED)
            assertThat(result.limitations)
                .contains(
                    ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED,
                    ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED,
                )
        }
    }

    @Test
    fun `a governance scope naming another run is refused rather than associated`() {
        runBlocking {
            GovernanceProjectionTestSeeder.seedDeniedPolicyDecision(dataSource)
            val store = JdbcModelAttemptObservationStore(dataSource)
            val reconstructor =
                EvidenceBackedReconstructor(
                    SemanticTimelineQuery.of(store, GovernanceQueryService.jdbc(dataSource)),
                    recordedSource(),
                )

            // Another run's governance facts must never be placed beside these attempts.
            assertThatThrownBy {
                reconstructor.reconstruct(
                    RUN_ID,
                    GovernanceQueryRequest(WORKLOAD, ENVIRONMENT, DEPLOYMENT, "run-other"),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    // ---------------------------------------------------------------- fixtures

    private fun reconstruct(
        store: AttemptObservationStore,
        governance: GovernanceQueryService?,
    ): ReconstructionResult =
        EvidenceBackedReconstructor(SemanticTimelineQuery.of(store, governance), recordedSource())
            .reconstruct(RUN_ID, governance?.let { scope() })

    private fun scope() = GovernanceQueryRequest(WORKLOAD, ENVIRONMENT, DEPLOYMENT, RUN_ID)

    private fun recordedSource() =
        HistoricalConfigurationSource {
            HistoricalConfigurationReference.Recorded(policyVersion = "policy-17")
        }

    private fun execute(sql: String) {
        connection().use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun migrate(resource: String) {
        val sql =
            this::class.java.classLoader
                .getResource(resource)
                ?.readText()
                ?: error("Migration not found: $resource")
        execute(sql)
    }

    private fun connection(): Connection = DriverManager.getConnection(url, user, password)

    private companion object {
        // The seeder materializes the projection for this run, so attempts and timeline must address the
        // same run: a mismatch reads an empty projection and would fail for the wrong reason.
        const val RUN_ID = GovernanceProjectionTestSeeder.WORKFLOW_RUN_ID
        const val WORKLOAD = GovernanceProjectionTestSeeder.WORKLOAD_ID
        const val ENVIRONMENT = GovernanceProjectionTestSeeder.ENVIRONMENT_ID
        const val DEPLOYMENT = GovernanceProjectionTestSeeder.DEPLOYMENT_ID
    }
}
