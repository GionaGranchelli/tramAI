package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition.Approve
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.engine.approval.ApprovalAttributionCorruptionException
import dev.tramai.engine.approval.ApprovalAttributionKeys
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.GovernedApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreFixtures
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.assertFailsWith

/**
 * 0.7.1d1: GovernedJdbcApprovalStore must satisfy the same ApprovalStore compatibility contract as
 * the store it composes, and must persist governed attribution on the durable approval row.
 *
 * The governed capability delegates into the same `approvals` row, so the released contract has to
 * hold through the wrapper: same datasource, same schema, per-case isolation owned here.
 *
 * The governed attribution discriminators live in this class rather than in a second class so that
 * one PostgreSQL container, one datasource and one schema serve them: each additional container
 * class costs a container startup inside the suite's CI time budget.
 *
 * The attribution snapshot must survive the approval's whole lifecycle — not merely creation — which
 * is why the lifecycle case asserts the reserved values again after an approve and after a
 * consumption, and why the raw JSONB is inspected rather than only the decoded value: the metadata
 * record is typed with `ignoreUnknown = true`, so a flat reserved-key write would be silently erased
 * by the first `parse → copy → write` round trip.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedJdbcApprovalStoreTckTest : ApprovalStoreTck() {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governed_approval_tck")
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
        val schemaSql =
            javaClass.classLoader
                .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
                ?.readText()
                ?: error("Schema SQL resource not found")
        setupConnection.createStatement().use { it.execute(schemaSql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override val harness =
        object : ApprovalStoreTckHarness {
            override fun createStore(clock: MutableClock): ApprovalStore {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { it.execute("DELETE FROM approvals") }
                }
                return GovernedJdbcApprovalStore(JdbcApprovalStore(dataSource, clock = clock))
            }
        }

    @Test
    fun `a governed approval keeps its attribution snapshot across approval and consumption`() =
        runBlocking<Unit> {
            val store = governedStore()
            val request = ApprovalStoreFixtures.pending("gov-lifecycle", t0, expiry)
            val attribution = ApprovalRunAttribution.Governed(identity(request.binding.workflowRunId))

            store.createGovernedApproval(request, attribution)
            assertThat(store.attributionOf("gov-lifecycle")).isEqualTo(attribution)

            // The typed metadata record is parsed and copied on every transition: this is where a
            // flat reserved-key encoding would have been dropped.
            val decide = Approve(decidedBy = "approver-1")
            val decided = store.transition("gov-lifecycle", 0L, decide)
            assertThat(store.attributionOf("gov-lifecycle")).isEqualTo(attribution)

            store.consumeApprovedOrReplay(
                "gov-lifecycle",
                decided.version,
                request.binding.approvalTokenDigest,
                consumedBy = "consumer-1",
            )
            assertThat(store.attributionOf("gov-lifecycle")).isEqualTo(attribution)

            // Byte-level proof, independent of the codec: the five reserved keys are still on the row.
            assertThat(rawAttribution("gov-lifecycle"))
                .isEqualTo(
                    mapOf(
                        ApprovalAttributionKeys.WORKLOAD to "claims-triage",
                        ApprovalAttributionKeys.CONFIGURATION to "claims-triage-prod",
                        ApprovalAttributionKeys.CONFIGURATION_VERSION to "v7",
                        ApprovalAttributionKeys.ENVIRONMENT to "prod-eu",
                        ApprovalAttributionKeys.DEPLOYMENT to "deploy-42",
                    ),
                )
        }

    @Test
    fun `a legacy approval stores no reserved keys and decodes as un-attributed`() =
        runBlocking<Unit> {
            val store = governedStore()
            val request = ApprovalStoreFixtures.pending("legacy-1", t0, expiry)

            store.create(request)

            assertThat(store.attributionOf("legacy-1")).isEqualTo(ApprovalRunAttribution.Ungoverned)
            assertThat(rawMetadata("legacy-1")).doesNotContain("approval.identity.")
        }

    @Test
    fun `a governed creation describing another run than its binding is rejected and writes nothing`() =
        runBlocking<Unit> {
            val store = governedStore()
            val request = ApprovalStoreFixtures.pending("mismatch-1", t0, expiry)
            val otherRun = identity("run-somewhere-else")

            assertFailsWith<IllegalArgumentException> {
                store.createGovernedApproval(request, ApprovalRunAttribution.Governed(otherRun))
            }

            assertThat(store.get("mismatch-1")).isNull()
        }

    @Test
    fun `a partially attributed row is corruption rather than a legacy approval`() =
        runBlocking<Unit> {
            val store = governedStore()
            val request = ApprovalStoreFixtures.pending("partial-1", t0, expiry)
            val governed = ApprovalRunAttribution.Governed(identity(request.binding.workflowRunId))

            store.createGovernedApproval(request, governed)

            // Simulate a torn/foreign write: drop exactly one reserved key from the JSONB document.
            setupConnection
                .prepareStatement(
                    "UPDATE approvals SET sanitized_metadata = sanitized_metadata #- " +
                        "'{attribution,${ApprovalAttributionKeys.DEPLOYMENT}}' WHERE approval_id = ?",
                ).use { stmt ->
                    stmt.setString(1, "partial-1")
                    stmt.executeUpdate()
                }

            assertFailsWith<ApprovalAttributionCorruptionException> { store.attributionOf("partial-1") }
        }

    @Test
    fun `attribution of an unknown approval is not found rather than un-attributed`() =
        runBlocking<Unit> {
            val store = governedStore()

            assertFailsWith<ApprovalStoreNotFoundException> { store.attributionOf("no-such-approval") }
        }

    /** The governed capability over a store on this class's container. */
    private fun governedStore(): GovernedApprovalStore = GovernedJdbcApprovalStore(JdbcApprovalStore(dataSource, clock))

    private fun identity(runId: String): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims-triage"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-triage-prod"),
                            version = ConfigurationVersion("v7"),
                        ),
                    environmentId = EnvironmentId("prod-eu"),
                    deploymentId = DeploymentId("deploy-42"),
                ),
            runId = RunId(runId),
        )

    private fun rawMetadata(approvalId: String): String {
        val sql = "SELECT sanitized_metadata::text FROM approvals WHERE approval_id = ?"
        return setupConnection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "no approval row for $approvalId" }
                rs.getString(1)
            }
        }
    }

    private fun rawAttribution(approvalId: String): Map<String, String> {
        val node = ObjectMapper().readTree(rawMetadata(approvalId)).get("attribution")
        return ApprovalAttributionKeys.ALL.associateWith { key -> node.get(key).asText() }
    }
}
