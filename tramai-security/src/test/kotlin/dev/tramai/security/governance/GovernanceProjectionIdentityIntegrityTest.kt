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
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.Test

/**
 * Owner review round 3 - persisted identity integrity against real PostgreSQL.
 *
 * The projection key includes the audit stream, so a row reached by that key is the row for this scope.
 * Reconstructing the identity from the request without checking the stored columns beside it would report
 * whatever the row holds under the caller's scope, and the digest cannot catch that: it covers the snapshot
 * the materializer built, not the identity stored next to it.
 *
 * Three directions, which is what the review asked for: a corrupted audit-stream identity is refused on
 * load; a strictly newer candidate cannot replace such a row before that refusal happens and leaves it
 * unchanged; and a legitimate newer publish for a consistent scope still succeeds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceProjectionIdentityIntegrityTest {
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
                .withDatabaseName("governance_identity_integrity")
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

    @Test
    fun `a corrupted audit stream identity is refused on load`() =
        runBlocking<Unit> {
            val store = materializedStore(registeredAuthority())
            corruptAuditStream()
            assertThat(storedFingerprint()).contains("other-audit-stream")

            val load = runCatching { store.load(identity()) }

            assertThat(load.isFailure).isTrue()
            assertThat(load.exceptionOrNull()).isInstanceOf(ProjectionCorruptionException::class.java)
            val chain = causeChainOf(load.exceptionOrNull())
            assertThat(chain.any { it.contains("identity") })
                .describedAs("cause chain: $chain")
                .isTrue()
        }

    @Test
    fun `a newer publish cannot overwrite a row with corrupted identity columns`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            corruptAuditStream()
            val corrupted = storedFingerprint()

            // A strictly newer candidate: emitting another decision advances the audit head.
            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            val publish = runCatching { materialize(authority, store) }

            assertThat(publish.isFailure).isTrue()
            assertThat(storedFingerprint()).isEqualTo(corrupted)
            assertThat(runCatching { store.load(identity()) }.isFailure).isTrue()
        }

    @Test
    fun `the refused publish leaves the row unchanged in every identity column`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            val beforeCorruption = storedFingerprint()
            corruptAuditStream()
            val corrupted = storedFingerprint()
            assertThat(corrupted).isNotEqualTo(beforeCorruption)

            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            runCatching { materialize(authority, store) }

            // Still the corrupted row, not one repaired into looking consistent.
            assertThat(storedFingerprint()).isEqualTo(corrupted)
        }

    @Test
    fun `a valid newer publish still succeeds`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = materializedStore(authority)
            val first = storedFingerprint()

            emitPolicy(PolicyDecision.Deny(reason = "newer", reasonCode = "policy_denied"))
            val publish = runCatching { materialize(authority, store) }

            // The identity conditions must not block legitimate replacement by a newer candidate.
            assertThat(publish.isFailure).isFalse()
            assertThat(storedFingerprint()).isNotEqualTo(first)
            assertThat(store.load(identity())).isNotNull()
        }

    private suspend fun materializedStore(authority: WorkloadRegistrationAuthority): JdbcGovernanceProjectionStore {
        emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
        val store = freshStore()
        materialize(authority, store)
        assertThat(storedFingerprint()).isNotEqualTo("no-row")
        return store
    }

    private suspend fun materialize(
        authority: WorkloadRegistrationAuthority,
        store: JdbcGovernanceProjectionStore,
    ) = materializer(authority, store).materialize(identity(), scope, declaredIdentity())

    private fun corruptAuditStream() =
        mutateStored(
            "UPDATE governance_projection_snapshot SET audit_stream_id = 'other-audit-stream' " +
                "WHERE projection_key = '${identity().key}'",
        )

    private fun freshStore(): JdbcGovernanceProjectionStore {
        mutateStored("DELETE FROM governance_projection_snapshot")
        return JdbcGovernanceProjectionStore(dataSource)
    }

    private fun mutateStored(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun storedFingerprint(): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement(STORED_FINGERPRINT_SQL).use { statement ->
                statement.setString(1, identity().key)
                statement.executeQuery().use { rows -> fingerprintOf(rows) }
            }
        }

    private fun fingerprintOf(rows: ResultSet): String {
        if (!rows.next()) return "no-row"
        return listOf(
            rows.getString("workload_id"),
            rows.getString("environment_id"),
            rows.getString("deployment_id"),
            rows.getString("audit_stream_id"),
            rows.getString("workflow_run_id"),
            rows.getLong("audit_head_position").toString(),
            rows.getString("semantic_digest"),
        ).joinToString(separator = "|")
    }

    private fun causeChainOf(failure: Throwable?): List<String> {
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }
        return messages.toList()
    }

    private fun identity(): ProjectionIdentity {
        val deployment = "eu-west-amsterdam-01"
        return ProjectionIdentity("claims", "production", deployment, runId)
    }

    private fun declaredIdentity(): DeclaredRunIdentity =
        DeclaredRunIdentity(
            workloadId = "claims",
            configurationId = "cfg-1",
            configurationVersion = "v1",
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

    private companion object {
        const val STORED_FINGERPRINT_SQL =
            "SELECT workload_id, environment_id, deployment_id, audit_stream_id, workflow_run_id, " +
                "audit_head_position, semantic_digest FROM governance_projection_snapshot " +
                "WHERE projection_key = ?"
    }
}
