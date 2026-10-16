package dev.tramai.security.governance

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4d — the durable read model against a real PostgreSQL instance.
 *
 * The production store is qualified here rather than against an in-memory stand-in: transactional
 * publish, crash-safe replacement and ordering under concurrent writers only exist in the database, so
 * a test double would qualify a different implementation from the one that ships.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcGovernanceProjectionStoreTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_read_model")
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

    // ------------------------------------------------------------------ durability

    @Test
    fun `a published snapshot is readable by a store instance that did not write it`() =
        runBlocking<Unit> {
            val store = freshStore()
            val snapshot = snapshot()
            assertThat(store.publish(snapshot)).isEqualTo(MaterializationOutcome.PUBLISHED)

            // A different instance with its own connection: nothing process-local is being read back.
            val reloaded = JdbcGovernanceProjectionStore(dataSource).load(identity())

            assertThat(reloaded).isNotNull
            assertThat(reloaded!!.snapshot.semanticDigest).isEqualTo(snapshot.semanticDigest)
            assertThat(reloaded.snapshot.provenance.auditHeadPosition).isEqualTo(5L)
            assertThat(reloaded.snapshot.provenance.auditHeadHash).isEqualTo("hash-5")
        }

    @Test
    fun `an identity that was never published loads as nothing stored`() =
        runBlocking<Unit> {
            assertThat(freshStore().load(ProjectionIdentity("other", "env-1", "dep-1", "run-9", "stream-9"))).isNull()
        }

    @Test
    fun `raw-stream coverage survives persistence`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())

            val reloaded = JdbcGovernanceProjectionStore(dataSource).load(identity())!!
            val policy = reloaded.decision(DecisionKind.POLICY_DECISION)!!

            assertThat(policy.coverage.verifiedRange).isEqualTo(1L..5L)
            assertThat(policy.coverage.observedHeadHash).isEqualTo("hash-5")
            assertThat(policy.coverage.accountedPositions).isEqualTo(5L)
            assertThat(policy.coverage.classificationRevision).isEqualTo(GOVERNANCE_CLASSIFICATION_REVISION)
        }

    @Test
    fun `decision-record coverage survives persistence separately from the raw range`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())

            val policy = store.load(identity())!!.decision(DecisionKind.POLICY_DECISION)!!

            // A hole in the record set, reported alongside the range that contained it.
            assertThat(policy.completeness).isEqualTo(Completeness.GAPS_PRESENT)
            assertThat(policy.coverage.unrecordedDecisionPositions).containsExactly(4L)
            assertThat(policy.coverage.unclassifiedPositions).isEmpty()
            assertThat(policy.latestDecision).isEqualTo("ALLOW")
            assertThat(policy.latestReasonCode).isEqualTo("policy_allowed")
        }

    @Test
    fun `an unclassified position round-trips without being turned into a gap`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(
                snapshot(
                    completeness = Completeness.UNKNOWN,
                    unrecorded = emptySet(),
                    unclassified = setOf(2L),
                ),
            )

            val policy = store.load(identity())!!.decision(DecisionKind.POLICY_DECISION)!!

            assertThat(policy.coverage.unclassifiedPositions).containsExactly(2L)
            assertThat(policy.coverage.unrecordedDecisionPositions).isEmpty()
            assertThat(policy.completeness).isEqualTo(Completeness.UNKNOWN)
        }

    @Test
    fun `a stored decision claim is withheld when the classification revision moved on`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(classificationRevision = "governance-classification.0"))

            val reloaded = store.load(identity())!!

            // Which stream was verified is a fact about the stream and survives; the decision claim was
            // made under a vocabulary this build does not have and is not reported as if it were.
            assertThat(reloaded.auditCoverageFor(DecisionKind.POLICY_DECISION)!!.verifiedRange).isEqualTo(1L..5L)
            assertThat(reloaded.completenessFor(DecisionKind.POLICY_DECISION)).isEqualTo(Completeness.UNKNOWN)
            assertThat(reloaded.classificationRevisionIsCurrent).isFalse()
        }

    // ------------------------------------------------------------------ idempotence and ordering

    @Test
    fun `applying the same snapshot twice changes nothing`() =
        runBlocking<Unit> {
            val store = freshStore()
            val snapshot = snapshot()

            assertThat(store.publish(snapshot)).isEqualTo(MaterializationOutcome.PUBLISHED)
            assertThat(store.publish(snapshot)).isEqualTo(MaterializationOutcome.ALREADY_CURRENT)

            assertThat(rowCount("governance_projection_snapshot")).isEqualTo(1)
            assertThat(rowCount("governance_projection_decision")).isEqualTo(1)
            assertThat(rowCount("governance_projection_fact")).isEqualTo(3)
        }

    @Test
    fun `a stale candidate cannot overwrite a newer snapshot`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(headPosition = 5L, headHash = "hash-5"))

            val outcome = store.publish(snapshot(headPosition = 3L, headHash = "hash-3", decision = "DENY"))

            assertThat(outcome).isEqualTo(MaterializationOutcome.REJECTED_STALE)
            val stored = store.load(identity())!!
            assertThat(stored.snapshot.provenance.auditHeadPosition).isEqualTo(5L)
            assertThat(stored.decision(DecisionKind.POLICY_DECISION)!!.latestDecision).isEqualTo("ALLOW")
        }

    @Test
    fun `a different head hash at the same position is a conflict, not an overwrite`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(headPosition = 5L, headHash = "hash-5"))

            val outcome = store.publish(snapshot(headPosition = 5L, headHash = "hash-5-diverged", decision = "DENY"))

            assertThat(outcome).isEqualTo(MaterializationOutcome.REJECTED_CONFLICT)
            assertThat(
                store
                    .load(identity())!!
                    .snapshot.provenance.auditHeadHash,
            ).isEqualTo("hash-5")
        }

    @Test
    fun `a newer registration version at the same audit head is published`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(registrationVersion = 1L))

            // Registration is the secondary key: audit evidence is unchanged, and registration moving
            // forward is not a regression.
            assertThat(store.publish(snapshot(registrationVersion = 2L))).isEqualTo(MaterializationOutcome.PUBLISHED)
            assertThat(
                store
                    .load(identity())!!
                    .snapshot.provenance.registrationVersion,
            ).isEqualTo(2L)
        }

    @Test
    fun `an older registration version at the same audit head is refused`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(registrationVersion = 2L))

            assertThat(store.publish(snapshot(registrationVersion = 1L)))
                .isEqualTo(MaterializationOutcome.REJECTED_STALE)
        }

    // ------------------------------------------------------------------ concurrency and recovery

    @Test
    fun `concurrent publishers converge on the newer snapshot`() =
        runBlocking<Unit> {
            val store = freshStore()
            val newer = snapshot(headPosition = 5L, headHash = "hash-5")
            val older = snapshot(headPosition = 3L, headHash = "hash-3", decision = "DENY")

            val pool = Executors.newFixedThreadPool(2)
            val newerOutcome: MaterializationOutcome
            val olderOutcome: MaterializationOutcome
            try {
                val a = pool.submit<MaterializationOutcome> { runBlocking { store.publish(newer) } }
                val b = pool.submit<MaterializationOutcome> { runBlocking { store.publish(older) } }
                newerOutcome = a.get(30, TimeUnit.SECONDS)
                olderOutcome = b.get(30, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            // What is deterministic is the snapshot the store converges on, not the pair of reports. A
            // losing candidate that wins the insert race is published and then correctly superseded, so
            // it may report PUBLISHED; it may never report having replaced the newer one, and the newer
            // one may never report having been refused. Either way the stored head is the newer
            // candidate: a stale snapshot cannot end up persisted because its writer finished later.
            assertThat(newerOutcome).isEqualTo(MaterializationOutcome.PUBLISHED)
            assertThat(olderOutcome).isIn(MaterializationOutcome.PUBLISHED, MaterializationOutcome.REJECTED_STALE)

            val stored = store.load(identity())!!
            assertThat(stored.snapshot.provenance.auditHeadPosition).isEqualTo(5L)
            assertThat(stored.snapshot.provenance.auditHeadHash).isEqualTo("hash-5")
            // And the race duplicated nothing.
            assertThat(rowCount("governance_projection_snapshot")).isEqualTo(1)
            assertThat(rowCount("governance_projection_decision")).isEqualTo(1)
            assertThat(rowCount("governance_projection_fact")).isEqualTo(3)
        }

    @Test
    fun `a publish interrupted between the header and its rows leaves the previous snapshot whole`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot(headPosition = 5L, headHash = "hash-5"))

            // Fault injection at the connection, not the store: the header upsert and the child-row
            // deletes succeed, then the next statement fails. Half a snapshot would be visible to a
            // reader if the transaction were not doing its job.
            val failing = JdbcGovernanceProjectionStore(FailingAfterStatementsDataSource(dataSource, 3))

            assertThatThrownBy { runBlocking { failing.publish(snapshot(headPosition = 7L, headHash = "hash-7")) } }
                .isInstanceOf(GovernanceProjectionStoreException::class.java)

            val stored = store.load(identity())!!
            assertThat(stored.snapshot.provenance.auditHeadPosition).isEqualTo(5L)
            assertThat(stored.snapshot.provenance.auditHeadHash).isEqualTo("hash-5")
            assertThat(rowCount("governance_projection_decision")).isEqualTo(1)
        }

    // ------------------------------------------------------------------ refusal of bad stored rows

    @Test
    fun `a stored format this build does not write is refused, not upgraded`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())
            mutateStored("UPDATE governance_projection_snapshot SET format_version = 'governance-read-model.v99'")

            assertThatThrownBy { runBlocking { store.load(identity()) } }
                .isInstanceOf(UnsupportedProjectionFormatException::class.java)
        }

    @Test
    fun `stored content that contradicts its own digest is refused`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())
            mutateStored("UPDATE governance_projection_snapshot SET semantic_digest = 'deadbeef'")

            assertThatThrownBy { runBlocking { store.load(identity()) } }
                .isInstanceOf(ProjectionCorruptionException::class.java)
        }

    @Test
    fun `a stored fact that violates the authority model is refused rather than reported`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())
            // A fact reporting no value while claiming to be current at a version: read back, this
            // would be an authoritative-looking fact with nothing behind it.
            mutateStored("UPDATE governance_projection_fact SET value_present = false")

            assertThatThrownBy { runBlocking { store.load(identity()) } }
                .isInstanceOf(ProjectionCorruptionException::class.java)
        }

    @Test
    fun `a decision row whose positions fall outside its verified range is refused`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())
            mutateStored("UPDATE governance_projection_decision SET unrecorded_positions = '9'")

            assertThatThrownBy { runBlocking { store.load(identity()) } }
                .isInstanceOf(ProjectionCorruptionException::class.java)
        }

    @Test
    fun `a read-only consumer cannot reach the write path`() =
        runBlocking<Unit> {
            val store = freshStore()
            store.publish(snapshot())

            val consumer: GovernanceProjectionReadModel = store.readOnly()

            assertThat(consumer.load(identity())).isNotNull
            assertThat(consumer::class.java.methods.map { it.name }).doesNotContain("publish")
        }

    // ------------------------------------------------------------------ fixtures

    private fun rowCount(table: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun mutateStored(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun identity() = ProjectionIdentity("wl-1", "env-1", "dep-1", "run-1", "stream-1")

    @Suppress("LongParameterList") // explicit named fixture parameters, each defaulted
    private fun snapshot(
        headPosition: Long = 5L,
        headHash: String = "hash-5",
        registrationVersion: Long = 1L,
        decision: String = "ALLOW",
        completeness: Completeness = Completeness.GAPS_PRESENT,
        unrecorded: Set<Long> = setOf(headPosition - 1),
        unclassified: Set<Long> = emptySet(),
        classificationRevision: String = GOVERNANCE_CLASSIFICATION_REVISION,
    ): GovernanceProjectionSnapshot =
        GovernanceProjectionSnapshot(
            formatVersion = GOVERNANCE_READ_MODEL_FORMAT_VERSION,
            identity = identity(),
            registration = registrationFact(),
            declaredRunIdentity = declaredFact(),
            telemetry = telemetryFact(),
            decisions =
                listOf(
                    SnapshotDecision(
                        kind = DecisionKind.POLICY_DECISION,
                        completeness = completeness,
                        coverage =
                            SnapshotCoverage(
                                verifiedRange = 1L..headPosition,
                                observedHeadHash = headHash,
                                accountedPositions = headPosition,
                                unrecordedDecisionPositions = unrecorded,
                                unclassifiedPositions = unclassified,
                                classificationRevision = classificationRevision,
                            ),
                        latest = decisionFact(),
                        latestDecision = decision,
                        latestReasonCode = "policy_allowed",
                        latestBoundaryId = "BEFORE_PROVIDER_INVOCATION",
                        latestBoundaryStrength = null,
                        duplicatesIgnored = 0,
                        staleIgnored = 0,
                    ),
                ),
            provenance =
                SourceProvenance(
                    registrationVersion = registrationVersion,
                    auditHeadPosition = headPosition,
                    auditHeadHash = headHash,
                    classificationRevision = classificationRevision,
                ),
        )

    private fun registrationFact() =
        SnapshotFact(
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = FactFreshness.CURRENT_AT_VERSION,
            established = "the registration authority reports this workload at the version named",
            mustNotInfer = listOf("registration is not a governance decision"),
            valuePresent = true,
            observedVersion = 1L,
        )

    private fun declaredFact() =
        SnapshotFact(
            authority = FactAuthority.PRODUCER_DECLARED,
            freshness = FactFreshness.HISTORICAL,
            established = "a producer declared this identity for the run; it is not authenticated",
            mustNotInfer = listOf("declared identity is not authenticated identity"),
            valuePresent = true,
            sourceVersion = 1L,
        )

    private fun telemetryFact() =
        SnapshotFact(
            authority = FactAuthority.TELEMETRY,
            freshness = FactFreshness.HISTORICAL,
            established = "an audit stream is evidence, not telemetry; the read reported no observations",
            mustNotInfer = listOf("a non-authoritative observation cannot establish a governance fact"),
            valuePresent = true,
            sourceVersion = 5L,
        )

    private fun decisionFact() =
        SnapshotFact(
            authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
            freshness = FactFreshness.HISTORICAL,
            established = "a recorded policy decision at a source position",
            mustNotInfer = listOf("a policy ALLOW is authorization, not completion"),
            valuePresent = true,
            sourceVersion = 5L,
        )
}

/**
 * A datasource whose connections fail from the Nth prepared statement on.
 *
 * Fault injection between the statements of one publish, which is where a partial write would become
 * visible if the transaction boundary were missing.
 */
private class FailingAfterStatementsDataSource(
    private val delegate: DataSource,
    private val failFromStatement: Int,
) : DataSource {
    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = wrap(delegate.getConnection(username, password))

    private fun wrap(connection: Connection): Connection {
        val counter = intArrayOf(0)
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "prepareStatement") {
                    counter[0]++
                    if (counter[0] >= failFromStatement) {
                        throw java.sql.SQLException("injected failure at statement ${counter[0]}")
                    }
                }
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            },
        ) as Connection
    }

    override fun getLogWriter() = delegate.logWriter

    override fun setLogWriter(out: java.io.PrintWriter) = delegate.setLogWriter(out)

    override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)

    override fun getLoginTimeout() = delegate.loginTimeout

    override fun getParentLogger() = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
}
