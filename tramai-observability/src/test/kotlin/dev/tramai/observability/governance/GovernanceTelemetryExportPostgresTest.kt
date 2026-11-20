package dev.tramai.observability.governance

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.testing.GovernanceProjectionTestSeeder
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4f - the end-to-end proof: a persisted PostgreSQL projection, read through the public
 * [GovernanceQueryService.jdbc] port, mapped by [GovernanceExposure.from] and inspected in a real OpenTelemetry
 * export.
 *
 * The projection is written by the real materializer through a test-only seeder in `:tramai-security`, so the
 * row the store reads carries a digest the store accepts. A hand-inserted row would be refused as
 * [GovernanceQueryResult.InvalidStoredProjection] and would prove nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceTelemetryExportPostgresTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection
    private lateinit var provider: SdkTracerProvider
    private lateinit var telemetry: GovernanceTelemetryExporter
    private val spanExporter = InMemorySpanExporter.create()

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_telemetry")
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

        runBlocking { GovernanceProjectionTestSeeder.seedDeniedPolicyDecision(dataSource) }

        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build()
        telemetry = GovernanceTelemetryExporter(provider.get("governance-telemetry-postgres-test"))
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { provider.close() }
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    @Test
    fun `a persisted projection is queried and exported with its real classifications`() {
        val result =
            GovernanceQueryService.jdbc(dataSource = dataSource).query(
                GovernanceQueryRequest(
                    workloadId = GovernanceProjectionTestSeeder.WORKLOAD_ID,
                    environmentId = GovernanceProjectionTestSeeder.ENVIRONMENT_ID,
                    deploymentId = GovernanceProjectionTestSeeder.DEPLOYMENT_ID,
                    workflowRunId = GovernanceProjectionTestSeeder.WORKFLOW_RUN_ID,
                ),
            )

        assertThat(result).isInstanceOf(GovernanceQueryResult.Found::class.java)

        telemetry.export(GovernanceExposure.from(result))

        val span = spanExporter.finishedSpanItems.single()
        val rendered = render(span)

        // One state event plus one event per recorded decision family: the seeded policy decision plus the
        // families the projection reports as NOT_OBSERVED. An unobserved family is reported, not dropped -
        // silence would read as "nothing happened" when what it means is "nothing was observed".
        assertThat(span.events).hasSize(4)
        span.events.forEach { event ->
            assertThat(
                event.attributes
                    .asMap()
                    .keys
                    .map { it.key },
            ).contains(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE.name)
        }

        assertThat(rendered).contains(GovernanceExposureState.EVIDENCE_AVAILABLE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        // A decision the authoritative audit trail recorded exports as POLICY_AUTHORIZATION_RECORD, never as
        // AUTHORITATIVE_PERSISTED: the record establishes what was decided, not that anything executed or was
        // prevented. Persisted evidence does not become enforcement by being exported.
        assertThat(rendered).contains("POLICY_AUTHORIZATION_RECORD")
        assertThat(rendered).doesNotContain("AUTHORITATIVE_PERSISTED")
        // The reason code was withheld at the query boundary; the code text never reaches telemetry.
        assertThat(rendered).contains("WITHHELD")
        // Exactly one family carries a recorded authorization; the others claim no authorization at all.
        val authorities =
            span.events.drop(1).map { event ->
                event.attributes[AttributeKey.stringKey(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)]
            }
        assertThat(authorities.count { it == "POLICY_AUTHORIZATION_RECORD" }).isEqualTo(1)
        assertThat(authorities.count { it == "AUTHORITATIVE_PERSISTED" }).isZero()
        // The recorded decision category survives.
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_RECORDED.name)
        assertThat(rendered).doesNotContain(GovernanceProjectionTestSeeder.REASON_CODE)

        // No fresh observation ports are attached to this query, so neither source can claim currency. A
        // persisted row alone is UNKNOWN on both, never CURRENT.
        assertThat(rendered).contains(SourceCurrency.UNKNOWN.name)
        assertThat(rendered).doesNotContain(SourceCurrency.CURRENT.name)
        assertThat(rendered).doesNotContain(SourceCurrency.STALE.name)
    }

    private fun render(span: SpanData): String =
        span.events
            .flatMap { event ->
                event.attributes
                    .asMap()
                    .entries
                    .map { "${it.key.key}=${it.value}" }
            }.joinToString(separator = "\n")
}
