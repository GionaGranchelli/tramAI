package dev.tramai.security.governance

import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4d — materialization over the real store.
 *
 * The durable store is exercised here rather than an in-memory double, because the properties under
 * test — a rebuild reproducing the same snapshot, a weaker read failing to replace a stronger one —
 * are decided by the storage contract, and an in-memory stand-in would decide them differently.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceProjectionMaterializerTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_materializer")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val migration = "V1__governance_projection_read_model.sql"
        val sql =
            this::class.java.classLoader
                .getResource("tramai/security/governance/postgres/$migration")
                ?.readText()
                ?: error("Migration not found: $migration")
        setupConnection.createStatement().use { it.execute(sql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    private fun freshStore(): JdbcGovernanceProjectionStore {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DELETE FROM governance_projection_snapshot") }
        }
        return JdbcGovernanceProjectionStore(dataSource)
    }

    private fun materializerFor(
        store: GovernanceProjectionStore,
        source: GovernanceDecisionFactSource,
    ): GovernanceProjectionMaterializer =
        GovernanceProjectionMaterializer(
            GovernanceProjectionReader(WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore()), source),
            store,
        )

    private fun identity() = ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", "run-1", "run-1")

    // ------------------------------------------------------------------ determinism and idempotence

    @Test
    fun `materializing the same source facts twice publishes once and reproduces the snapshot`() =
        runBlocking<Unit> {
            val store = freshStore()
            val source = ScriptedFacts(records = listOf(decision(1), decision(5)), range = 1L..5L, accounted = 5L)
            val materializer = materializerFor(store, source)

            assertThat(materializer.materialize(identity(), WorkloadRegistrationFixtures.identity()))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val first = store.load(identity())!!

            // A rebuild is the same operation over the same facts, and the store says so rather than
            // writing an equal-looking row again.
            assertThat(materializer.materialize(identity(), WorkloadRegistrationFixtures.identity()))
                .isEqualTo(MaterializationOutcome.ALREADY_CURRENT)

            val second = store.load(identity())!!
            assertThat(second.snapshot.canonicalForm()).isEqualTo(first.snapshot.canonicalForm())
            assertThat(second.snapshot.semanticDigest).isEqualTo(first.snapshot.semanticDigest)
        }

    @Test
    fun `a rebuild does not change content, only reporting that it was already current`() =
        runBlocking<Unit> {
            val store = freshStore()
            val materializer =
                materializerFor(
                    store,
                    ScriptedFacts(
                        records = listOf(decision(1), decision(5)),
                        range = 1L..5L,
                        accounted = 5L,
                    ),
                )
            materializer.materialize(identity(), WorkloadRegistrationFixtures.identity())

            val before = store.load(identity())!!.snapshot.semanticDigest
            repeat(3) { materializer.materialize(identity(), WorkloadRegistrationFixtures.identity()) }

            assertThat(store.load(identity())!!.snapshot.semanticDigest).isEqualTo(before)
        }

    // ------------------------------------------------------------------ weaker reads cannot win

    @Test
    fun `a read that verified no range cannot replace an established snapshot`() =
        runBlocking<Unit> {
            val store = freshStore()
            materializerFor(
                store,
                ScriptedFacts(
                    records = listOf(decision(1), decision(5)),
                    range = 1L..5L,
                    accounted = 5L,
                ),
            ).materialize(identity(), WorkloadRegistrationFixtures.identity())

            // A bounded or interrupted read: it saw records, but established no range, so it has no
            // coverage to report and no head to name.
            val partial = ScriptedFacts(records = listOf(decision(1)), range = null, accounted = 0L)
            val outcome =
                materializerFor(store, partial).materialize(
                    identity(),
                    WorkloadRegistrationFixtures.identity(),
                )

            assertThat(outcome).isEqualTo(MaterializationOutcome.REJECTED_STALE)
            val stored = store.load(identity())!!
            assertThat(stored.snapshot.provenance.auditHeadPosition).isEqualTo(5L)
            assertThat(stored.completenessFor(DecisionKind.POLICY_DECISION)).isEqualTo(Completeness.COMPLETE)
        }

    // ------------------------------------------------------------------ independent claims and provenance

    @Test
    fun `persisted completeness is not derived from the verified range`() =
        runBlocking<Unit> {
            val store = freshStore()
            // A five-position range with an unclassifiable position: the range is verified, the record
            // set is not complete, and neither claim is read off the other.
            val source =
                ScriptedFacts(
                    records = listOf(decision(1), decision(5)),
                    range = 1L..5L,
                    accounted = 4L,
                    unclassified = setOf(3L),
                    completeness = Completeness.UNKNOWN,
                )
            materializerFor(store, source).materialize(identity(), WorkloadRegistrationFixtures.identity())

            val policy = store.load(identity())!!.decision(DecisionKind.POLICY_DECISION)!!

            assertThat(policy.coverage.verifiedRange).isEqualTo(1L..5L)
            assertThat(policy.coverage.unclassifiedPositions).containsExactly(3L)
            assertThat(policy.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(policy.coverage.accountedPositions).isEqualTo(4L)
        }

    @Test
    fun `the snapshot records both source versions separately, and claims no common instant`() =
        runBlocking<Unit> {
            val store = freshStore()
            val source = ScriptedFacts(records = listOf(decision(1)), range = 1L..4L)
            materializerFor(store, source).materialize(identity(), WorkloadRegistrationFixtures.identity())

            val provenance = store.load(identity())!!.snapshot.provenance

            assertThat(provenance.auditHeadPosition).isEqualTo(4L)
            assertThat(provenance.auditHeadHash).isEqualTo("hash-4")
            // No registration was made for this scope, so the registration version is not established
            // rather than zero-valued evidence.
            assertThat(provenance.registrationVersion).isEqualTo(NOT_ESTABLISHED)
            assertThat(provenance.readConsistency).isEqualTo(ReadConsistency.INDEPENDENT_SOURCES)
        }

    @Test
    fun `materializing reads its sources and changes neither of them`() =
        runBlocking<Unit> {
            val store = freshStore()
            val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
            val scope = WorkloadRegistrationFixtures.identity()
            authority.register(
                scope,
                WorkloadRegistrationFixtures.fingerprint(),
                WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
            )
            val source = ScriptedFacts(records = listOf(decision(1)), range = 1L..1L)
            val before = authority.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)

            GovernanceProjectionMaterializer(GovernanceProjectionReader(authority, source), store)
                .materialize(identity(), scope)

            // Derived state: materializing reads registration and must not advance it.
            val after = authority.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)
            assertThat(after).isEqualTo(before)
            assertThat(after!!.observedVersion.value).isEqualTo(1L)
            // The decision source was read once, through an interface that has no write method.
            assertThat(source.decisionReads).isEqualTo(1)
            // And the version recorded in the snapshot came from that read, not from the caller.
            assertThat(
                store
                    .load(identity())!!
                    .snapshot.provenance.registrationVersion,
            ).isEqualTo(1L)
        }

    // ------------------------------------------------------------------ fixtures

    private fun decision(sequence: Long) =
        RecordedDecision(
            workflowRunId = "run-1",
            kind = DecisionKind.POLICY_DECISION,
            decision = "ALLOW",
            reasonCode = "policy_allowed",
            boundary = DeclaredBoundary(id = "BEFORE_PROVIDER_INVOCATION", registeredStrength = null),
            sourceSequence = sequence,
        )

    /**
     * A source whose read is scripted.
     *
     * The coverage it reports is exactly what a caller supplies, which is how a bounded read and an
     * unclassifiable position are reproduced without contorting the audit store.
     */
    @Suppress("LongParameterList") // explicit named fixture parameters, each defaulted
    private class ScriptedFacts(
        private val records: List<RecordedDecision> = emptyList(),
        private val range: LongRange? = null,
        private val accounted: Long = 0L,
        private val unrecorded: Set<Long> = emptySet(),
        private val unclassified: Set<Long> = emptySet(),
        private val completeness: Completeness = Completeness.COMPLETE,
    ) : GovernanceDecisionFactSource {
        var decisionReads: Int = 0
            private set

        override suspend fun decisionsFor(workflowRunId: String): DecisionFactRead {
            decisionReads++
            return DecisionFactRead(
                records = records,
                coverage =
                    if (range == null) {
                        StreamCoverage()
                    } else {
                        StreamCoverage(
                            verifiedRange = range,
                            accountedPositions = accounted,
                            unrecordedDecisionPositions = unrecorded,
                            unclassifiedPositions = unclassified,
                            observedHeadHash = "hash-${range.last}",
                        )
                    },
            )
        }

        override suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation> = emptyList()
    }
}
