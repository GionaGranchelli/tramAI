package dev.tramai.security.governance

import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.Test

/**
 * Two adversarial proofs the existing PostgreSQL suites do not make.
 *
 * 1. Provenance tampering (Q2). A direct UPDATE of `audit_head_position`, `audit_head_hash` or
 *    `registration_version` on the stored row is refused on load, and — this is the invariant that
 *    matters — a rebuild does not paper over it: the tampered row is still there afterwards and the
 *    load still fails. Which guard fired is asserted, not narrated: these three columns are part of
 *    the snapshot's canonical form, so the digest comparison in `loadWithin` is the mechanism.
 *    The tests also state where that invariant stops: it holds for a candidate whose ordering pair
 *    does not exceed the corrupted row's, and a strictly newer candidate replaces the row wholesale
 *    for the two columns that are not in the upsert predicate. That boundary is asserted, not
 *    glossed over, because "corruption cannot be repaired" is only true of the case where the
 *    corruption claims to be newer.
 *
 * 2. Read-only proof at the database level (Q5). The existing read-only proofs assert the API
 *    surface; these count and reject SQL verbs through a [DataSource] wrapper around every
 *    [GovernanceQueryResult] state, and compare actual row counts and row contents in all three
 *    tables before and after. The wrapper throws on a write statement, so a write is impossible to
 *    miss rather than merely uncounted; a negative control shows the wrapper is not vacuous.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceCorruptionPreservationTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val scope = WorkloadRegistrationFixtures.identity()
    private val runId = "run-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    private lateinit var auditStore: InMemoryAuditStore
    private lateinit var emitter: AuditEnginePolicyDecisionAuditEmitter

    @BeforeEach
    fun resetAuditStream() {
        auditStore = InMemoryAuditStore()
        emitter = AuditEnginePolicyDecisionAuditEmitter(AuditEngine(auditStore, clock = clock))
    }

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_corruption_preservation")
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

    // ------------------------------------------- Q2: tampered provenance is refused, not repaired

    @Test
    fun `a tampered audit head position survives a newer publish instead of being repaired`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            val pristine = provenance()
            assertThat(pristine.first).isEqualTo("1")

            // An attacker raising the stored head is the direction that matters: the row now claims
            // to be newer than anything a rebuild can produce from the real sources.
            tamper("SET audit_head_position = 500")
            val corrupted = provenance()
            assertThat(corrupted).isNotEqualTo(pristine)

            // Guard that fired: the digest comparison in loadWithin. audit_head_position is part of
            // canonicalForm(), so the rebuilt snapshot cannot reproduce the stored digest.
            // Message observed: "stored projection content does not match its recorded digest".
            val refusal = runCatching { store.load(identity()) }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(ProjectionCorruptionException::class.java)
            assertThat(refusal?.message)
                .isEqualTo("stored projection content does not match its recorded digest")
            assertThat(GovernanceQueryService.jdbc(dataSource).query(request()))
                .isInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)

            // A strictly newer candidate: one more decision advances the real audit head to 2.
            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            val outcome = materializer(authority, store).materialize(identity(), scope)

            // (2, 1) > (500, 1) is false, so the conditional upsert writes nothing and publish
            // reports the candidate as stale. It does not "fix" the row, and it does not adopt the
            // tampered ordering as its own either.
            assertThat(outcome).isEqualTo(MaterializationOutcome.REJECTED_STALE)
            assertThat(provenance()).isEqualTo(corrupted)
            assertThat(runCatching { store.load(identity()) }.isFailure).isTrue()
        }

    @Test
    fun `a tampered audit head hash is refused and a same-source rebuild does not repair it`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            val pristine = provenance()

            tamper("SET audit_head_hash = 'tampered-head-hash'")
            val corrupted = provenance()
            assertThat(corrupted).isNotEqualTo(pristine)

            // Same guard as above: the hash is in the canonical form, so the digest catches it.
            // Message observed: "stored projection content does not match its recorded digest".
            val refusal = runCatching { store.load(identity()) }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(ProjectionCorruptionException::class.java)
            assertThat(refusal?.message)
                .isEqualTo("stored projection content does not match its recorded digest")
            assertThat(GovernanceQueryService.jdbc(dataSource).query(request()))
                .isInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)

            // A rebuild from unchanged sources is not greater in the ordering pair, and publish
            // compares the *stored digest column* rather than re-deriving the stored content, so it
            // reports already-current and writes nothing. The tampered hash therefore survives.
            // ponytail: this is also why ALREADY_CURRENT and a refusing read can coexist for one row;
            // tightening it would mean re-deriving the stored digest on the publish path.
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.ALREADY_CURRENT)
            assertThat(provenance()).isEqualTo(corrupted)
            assertThat(runCatching { store.load(identity()) }.isFailure).isTrue()

            // Boundary asserted rather than assumed: audit_head_hash is not part of the upsert
            // predicate, so a candidate that is genuinely newer in the ordering pair replaces the
            // whole row. That is a complete re-derivation from the sources, not a repair of the
            // tampered bytes, and the corruption ends here.
            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)
            // The replacement carries the head hash of the position it was read at, so it is not the
            // pristine value either - it is a re-derived one. What matters is that the tampered bytes
            // are gone and the row loads.
            assertThat(provenance().second).isNotEqualTo("tampered-head-hash")
            assertThat(provenance().second).isNotEqualTo(pristine.second)
            assertThat(provenance().first).isEqualTo("2")
            assertThat(store.load(identity())).isNotNull
        }

    @Test
    fun `a tampered registration version is refused and a rebuild at the same head does not repair it`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            val pristine = provenance()
            assertThat(pristine.third).isEqualTo("1")

            tamper("SET registration_version = 500")
            val corrupted = provenance()
            assertThat(corrupted).isNotEqualTo(pristine)

            // Message observed: "stored projection content does not match its recorded digest".
            val refusal = runCatching { store.load(identity()) }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(ProjectionCorruptionException::class.java)
            assertThat(refusal?.message)
                .isEqualTo("stored projection content does not match its recorded digest")
            assertThat(GovernanceQueryService.jdbc(dataSource).query(request()))
                .isInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)

            // (1, 1) > (1, 500) is false: the tampered version dominates the pair for a candidate
            // built from the same audit head, so the row is not replaced.
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.REJECTED_STALE)
            assertThat(provenance()).isEqualTo(corrupted)
            assertThat(runCatching { store.load(identity()) }.isFailure).isTrue()

            // Boundary: the pair is compared lexicographically, so a newer audit head wins outright
            // and the row is replaced wholesale — again a re-derivation, not a patch of the tampered
            // value. Asserted here so the invariant is stated with its actual limit.
            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)
            assertThat(provenance().third).isEqualTo(pristine.third)
            assertThat(store.load(identity())).isNotNull
        }

    // ------------------------------------------- Q5: zero writes, proven at the database level

    @Test
    fun `a Found query issues no write and leaves every stored row byte-identical`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            storedSnapshot { materializer(authority, it).materialize(identity(), scope) }
            assertQueryIsReadOnly(expected = "Found")
        }

    @Test
    fun `a NotMaterialized query issues no write and leaves every stored row byte-identical`() {
        clearStored()
        assertQueryIsReadOnly(expected = "NotMaterialized")
    }

    @Test
    fun `an UnsupportedFormat query issues no write and leaves the tampered row byte-identical`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            storedSnapshot { materializer(authority, it).materialize(identity(), scope) }
            // A format this build does not write: refused, and certainly not "upgraded" by a read.
            tamper("SET format_version = 'governance-read-model.v0'")
            assertQueryIsReadOnly(expected = "UnsupportedFormat")
        }

    @Test
    fun `an InvalidStoredProjection query issues no write and leaves the tampered row byte-identical`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            storedSnapshot { materializer(authority, it).materialize(identity(), scope) }
            tamper("SET semantic_digest = 'tampered-digest'")
            assertQueryIsReadOnly(expected = "InvalidStoredProjection")
        }

    @Test
    fun `the write guard is not vacuous - a publish through it is refused`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            clearStored()
            val guard = WriteRejectingDataSource(dataSource)

            val failure =
                runCatching {
                    materializer(authority, JdbcGovernanceProjectionStore(guard)).materialize(identity(), scope)
                }.exceptionOrNull()

            assertThat(failure).isNotNull
            assertThat(causeChainOf(failure)).anyMatch { it.contains("write statement refused (INSERT)") }
            assertThat(guard.writeStatements).isGreaterThan(0)
            assertThat(tableState().first()).startsWith("governance_projection_snapshot|0|")
        }

    /**
     * Runs one query through a datasource that refuses writes, over whatever the caller seeded, and
     * proves three things: the expected result state, that no write statement was issued (the guard
     * throws, so a write would surface as a failure rather than a count), and that all three tables
     * hold the same rows with the same bytes afterwards.
     */
    private fun assertQueryIsReadOnly(expected: String) {
        val before = tableState()
        val guard = WriteRejectingDataSource(dataSource)

        val result = GovernanceQueryService.jdbc(guard).query(request())
        val after = tableState()

        assertThat(result::class.java.simpleName).isEqualTo(expected)
        assertThat(guard.writeStatements)
            .describedAs("write statements issued by a $expected query")
            .isZero()
        assertThat(guard.statementsSeen)
            .describedAs("statements the $expected query did issue")
            .isGreaterThan(0)
        assertThat(after).describedAs("stored rows after a $expected query").isEqualTo(before)
    }

    // ------------------------------------------- fixtures

    private fun identity() = ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", runId)

    private fun request() =
        GovernanceQueryRequest(
            workloadId = "claims",
            environmentId = "production",
            deploymentId = "eu-west-amsterdam-01",
            workflowRunId = runId,
        )

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            scope,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

    private fun emitContext() =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
            workflowId = "claims",
            workflowRunId = runId,
            correlationId = "corr-1",
            actorId = "system.anonymous",
            providerId = "ollama",
            modelName = "mistral",
            toolName = null,
            policyVersion = "v17",
        )

    private suspend fun emitPolicy(decision: PolicyDecision) =
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, emitContext(), decision)

    private fun materializer(
        authority: WorkloadRegistrationAuthority,
        store: GovernanceProjectionStore,
    ) = GovernanceProjectionMaterializer(
        GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore)),
        store,
    )

    private suspend fun materializedStore(authority: WorkloadRegistrationAuthority): JdbcGovernanceProjectionStore {
        emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
        val store = clearStored()
        assertThat(materializer(authority, store).materialize(identity(), scope))
            .isEqualTo(MaterializationOutcome.PUBLISHED)
        return store
    }

    private suspend fun storedSnapshot(seed: suspend (JdbcGovernanceProjectionStore) -> Unit) {
        val store = clearStored()
        seed(store)
    }

    private fun clearStored(): JdbcGovernanceProjectionStore {
        mutateStored("DELETE FROM governance_projection_snapshot")
        return JdbcGovernanceProjectionStore(dataSource)
    }

    /** One statement; returns the rows it touched. */
    private fun mutateStored(sql: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(sql)
            }
        }

    /** The tamper as written in the tests: `SET <column> = <value>` on the seeded row. */
    private fun tamper(setClause: String) {
        val touched =
            mutateStored(
                "UPDATE governance_projection_snapshot $setClause WHERE projection_key = '${identity().key}'",
            )
        assertThat(touched).describedAs("rows the tamper touched: $setClause").isEqualTo(1)
    }

    /** The three tampered columns, as stored. */
    private fun provenance(): Triple<String, String, String> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT audit_head_position, audit_head_hash, registration_version " +
                        "FROM governance_projection_snapshot WHERE projection_key = ?",
                ).use { statement ->
                    statement.setString(1, identity().key)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "the seeded row is gone" }
                        Triple(
                            rows.getLong("audit_head_position").toString(),
                            rows.getString("audit_head_hash"),
                            rows.getLong("registration_version").toString(),
                        )
                    }
                }
        }

    /**
     * Row count and a digest of the full text of every row, per table.
     *
     * `t::text` renders the whole row, so this compares contents rather than only counts: an
     * UPDATE that left the count alone still changes the digest.
     */
    private fun tableState(): List<String> =
        TABLES.map { table ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*), md5(coalesce(string_agg(t::text, chr(10) ORDER BY t::text), '')) " +
                                "FROM $table t",
                        ).use { rows ->
                            rows.next()
                            "$table|${rows.getLong(1)}|${rows.getString(2)}"
                        }
                }
            }
        }

    private fun causeChainOf(failure: Throwable?): List<String> = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()

    private companion object {
        val TABLES =
            listOf(
                "governance_projection_snapshot",
                "governance_projection_fact",
                "governance_projection_decision",
            )
    }
}

/**
 * A datasource whose connections refuse any statement that is not a read.
 *
 * The wrapper throws on INSERT/UPDATE/DELETE/TRUNCATE/ALTER/DROP/CREATE/MERGE instead of only
 * counting them, so a write cannot be missed between two counting assertions: it turns into a
 * failure at the statement. The count is still kept for the assertion message, and the negative
 * control in the suite proves the guard fires on a real write rather than being vacuous.
 */
private class WriteRejectingDataSource(
    private val delegate: DataSource,
) : DataSource {
    var statementsSeen = 0
        private set
    var writeStatements = 0
        private set

    override fun getConnection(): Connection = guard(delegate.connection)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = guard(delegate.getConnection(username, password))

    private fun guard(connection: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement" || method.name == "prepareCall") {
                inspect(args?.firstOrNull() as? String)
            }
            val result = invokeOn(connection, method, args)
            if (method.name == "createStatement") guardStatement(result as Statement) else result
        } as Connection

    private fun guardStatement(statement: Statement): Statement =
        Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, args ->
            if (method.name.startsWith("execute") || method.name == "addBatch") {
                (args?.firstOrNull() as? String)?.let(::inspect)
            }
            invokeOn(statement, method, args)
        } as Statement

    private fun inspect(sql: String?) {
        val text = sql ?: return
        statementsSeen++
        val verb =
            text
                .trim()
                .split(Regex("\\s+"), limit = 2)
                .first()
                .uppercase()
        if (verb in WRITE_VERBS) {
            writeStatements++
            throw SQLException("write statement refused ($verb): a governance query must not write")
        }
    }

    private fun invokeOn(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    override fun getLogWriter() = delegate.logWriter

    override fun setLogWriter(out: java.io.PrintWriter) = delegate.setLogWriter(out)

    override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)

    override fun getLoginTimeout() = delegate.loginTimeout

    override fun getParentLogger() = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    private companion object {
        val WRITE_VERBS =
            setOf("INSERT", "UPDATE", "DELETE", "TRUNCATE", "ALTER", "DROP", "CREATE", "MERGE")
    }
}
