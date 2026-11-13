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
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4d phase 4 — the durable read model over the production path.
 *
 * The chain is the real one: a decided policy outcome goes through the existing emitter into
 * `AuditStore.appendNext`, is read back by [AuditBackedGovernanceDecisionFactSource] through the
 * `AuditStore` SPI, is folded by [GovernanceProjectionReader] against the real
 * [WorkloadRegistrationAuthority], and is materialized by [GovernanceProjectionMaterializer] into a
 * store backed by a real PostgreSQL instance. Nothing between the decision and the persisted view is a
 * stub.
 *
 * What this class does not do, stated rather than implied: the audit half is an in-memory store. A
 * test that used `JdbcAuditStore` would have to live in `:tramai-persistence-jdbc`, which cannot see
 * the read model — the projection types are internal to this module precisely so that no published API
 * exposes them. The durable audit store has its own real-PostgreSQL TCK (43 tests, recorded in the
 * evidence baseline); the two halves are qualified separately because the module boundary does not
 * permit a single test to hold both.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaterializedReadModelQualificationTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val scope = WorkloadRegistrationFixtures.identity()
    private val runId = "run-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    // Reset per test: this class is PER_CLASS so that one PostgreSQL container serves every case, which
    // means the audit stream must be rebuilt rather than shared. Sharing it silently carried earlier
    // cases' events into later ones, which is exactly the fixture trap that makes a positional
    // assertion measure the wrong thing.
    private lateinit var auditStore: InMemoryAuditStore
    private lateinit var engine: AuditEngine
    private lateinit var emitter: AuditEnginePolicyDecisionAuditEmitter

    @BeforeEach
    fun resetAuditStream() {
        auditStore = InMemoryAuditStore()
        engine = AuditEngine(auditStore, clock = clock)
        emitter = AuditEnginePolicyDecisionAuditEmitter(engine)
    }

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_qualification")
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

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            scope,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

    private fun identity() = ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", runId)

    private fun context() =
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
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), decision)

    private fun materializer(
        authority: WorkloadRegistrationAuthority,
        store: GovernanceProjectionStore,
    ) = GovernanceProjectionMaterializer(
        GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore)),
        store,
    )

    private suspend fun sourceBackedRead(authority: WorkloadRegistrationAuthority) =
        GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore))
            .read(scope, null, runId)
            .decisions
            .single { it.kind == DecisionKind.POLICY_DECISION }

    // ------------------------------------------------------------------ durability and restart

    @Test
    fun `a materialized projection outlives the session that wrote it and matches the sources`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            emitPolicy(PolicyDecision.Allow)

            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            // The writer's connection is closed with the store instance. A component that never saw the
            // write reads the snapshot back, with the reference recomputed from the sources.
            val restarted = JdbcGovernanceProjectionStore(dataSource)
            val restored = restarted.load(identity())!!
            val reference = sourceBackedRead(authority)

            val stored = restored.decision(DecisionKind.POLICY_DECISION)!!
            assertThat(stored.latestDecision).isEqualTo(reference.latest.value?.decision)
            assertThat(stored.latestReasonCode).isEqualTo(reference.latest.value?.reasonCode)
            assertThat(stored.completeness).isEqualTo(reference.completeness)
            assertThat(stored.coverage.verifiedRange).isEqualTo(reference.coverage.verifiedRange)
            assertThat(stored.coverage.observedHeadHash).isEqualTo(reference.coverage.observedHeadHash)
            assertThat(stored.latest.freshness).isEqualTo(reference.latest.freshness)
            assertThat(restored.snapshot.provenance.registrationVersion).isEqualTo(1L)
        }

    // ------------------------------------------------------------------ currency, per source

    @Test
    fun `an advanced audit stream makes the snapshot stale without unmaking its verified range`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val restored = JdbcGovernanceProjectionStore(dataSource).load(identity())!!
            val headBefore = auditStore.latestEvent(runId)!!
            val current =
                restored.assessAgainst(ObservedAuditHead(headBefore.sequenceNumber, headBefore.eventHash), 1L)
            assertThat(current.audit).isEqualTo(SourceCurrency.CURRENT)
            assertThat(current.registration).isEqualTo(SourceCurrency.CURRENT)

            // The runtime appends. The snapshot is now behind, and says so — but what it verified is
            // still what it verified: a new append does not retroactively unmake a historical range.
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            val headAfter = auditStore.latestEvent(runId)!!

            val stale = restored.assessAgainst(ObservedAuditHead(headAfter.sequenceNumber, headAfter.eventHash), 1L)
            assertThat(stale.audit).isEqualTo(SourceCurrency.STALE)
            assertThat(restored.auditCoverageFor(DecisionKind.POLICY_DECISION)!!.verifiedRange).isEqualTo(1L..2L)
            assertThat(restored.snapshot.provenance.auditHeadPosition).isEqualTo(2L)
        }

    @Test
    fun `currency is never claimed without a fresh observation`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val restored = store.load(identity())!!

            // Nothing observed means nothing claimed, for either source, and a registration read alone
            // says nothing about the audit stream.
            val nothing = restored.assessAgainst(null, null)
            assertThat(nothing.audit).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(nothing.registration).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(nothing.anyStale).isFalse

            val registrationOnly = restored.assessAgainst(null, 1L)
            assertThat(registrationOnly.registration).isEqualTo(SourceCurrency.CURRENT)
            assertThat(registrationOnly.audit).isEqualTo(SourceCurrency.UNKNOWN)
        }

    // ------------------------------------------------------------------ rebuild

    @Test
    fun `a rebuild after an append produces a snapshot that covers what it now observed`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val updated = JdbcGovernanceProjectionStore(dataSource).load(identity())!!
            val reference = sourceBackedRead(authority)

            assertThat(updated.snapshot.provenance.auditHeadPosition).isEqualTo(2L)
            assertThat(updated.decision(DecisionKind.POLICY_DECISION)!!.coverage.verifiedRange).isEqualTo(1L..2L)
            assertThat(updated.decision(DecisionKind.POLICY_DECISION)!!.latestDecision)
                .isEqualTo(reference.latest.value?.decision)
            assertThat(updated.completenessFor(DecisionKind.POLICY_DECISION)).isEqualTo(reference.completeness)

            // And rebuilding again changes nothing, because the source facts have not moved.
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.ALREADY_CURRENT)
        }
}
