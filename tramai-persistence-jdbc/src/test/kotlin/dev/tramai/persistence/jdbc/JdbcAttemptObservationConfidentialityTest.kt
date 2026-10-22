package dev.tramai.persistence.jdbc

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.AttemptRecordingObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Gate 1 of Epic 0.7.5g — the persistence leg of the confidentiality proof, on real PostgreSQL.
 *
 * The engine-level test (`ProducerPathConfidentialityTest`) proves the producer path into the store
 * port; this one takes the *real* recording observer with a real [ModelResponse] and a real provider
 * exception (nothing constructs a [dev.tramai.core.observation.attempt.ModelAttemptObservation] by hand)
 * and proves two things the in-memory store cannot: that no canary is written into any column, and that
 * a read through a fresh store over the same datasource cannot surface one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAttemptObservationConfidentialityTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("attempt_confidentiality")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        JdbcModelAttemptObservationStoreTest.executeSchema(dataSource)
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { postgres.stop() }
    }

    @BeforeEach
    fun clean() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM model_attempt_observations") }
        }
    }

    @Test
    fun `no protected content is persisted into any column or returned by a fresh read`() {
        val responseCanary = "CANARY-RESPONSE-7c3e"
        val failureCanary = "CANARY-FAILURE-9b02"
        val promptCanary = "CANARY-PROMPT-4a91"
        val store = JdbcModelAttemptObservationStore(dataSource)
        val observer = AttemptRecordingObserver(store)

        // A real successful attempt, whose response content is the canary and whose usage is reported.
        val success =
            observe(observer, attempt = 0)
        success.onProviderResponse(
            ModelResponse(
                content = responseCanary,
                modelUsed = "logical-model",
                inputTokens = 12,
                outputTokens = 5,
                thinkingTokens = 3,
            ),
        )
        success.onCallCompleted(parseSuccess = null)

        // A real failed attempt, whose provider exception message is a different canary.
        val failure = observe(observer, attempt = 1)
        failure.onProviderFailure(ProviderException("$failureCanary: upstream said no", retryable = true))
        failure.onCallCompleted(parseSuccess = null)

        // --- non-vacuity: two rows really are in PostgreSQL, with the allowed facts intact.
        val readBack = JdbcModelAttemptObservationStore(dataSource).findByInvocation(INVOCATION)
        val attempts =
            (readBack as dev.tramai.core.observation.attempt.AttemptHistory.Available).attempts
        assertThat(attempts).hasSize(2)
        assertThat(attempts.map { it.outcome.name }).containsExactly("SUCCEEDED", "FAILED")
        assertThat(attempts.map { it.provenance }.distinct()).containsExactly(AttemptProvenance.OBSERVED_IN_PROCESS)
        assertThat(attempts.first().inputTokens).isEqualTo(12)
        assertThat(attempts.first().outputTokens).isEqualTo(5)
        assertThat(attempts.first().thinkingTokens).isEqualTo(3)
        assertThat(attempts.first().reportedModel).isEqualTo("logical-model")
        // The failure is a class name, never the provider's message.
        assertThat(attempts.last().failureCategory).isEqualTo("ProviderException")

        // --- no canary in anything the store hands back.
        val rendered = attempts.toString()
        listOf(promptCanary, responseCanary, failureCanary).forEach { canary ->
            assertThat(rendered)
                .describedAs("canary %s must not appear in a persisted attempt", canary)
                .doesNotContain(canary)
        }

        // --- and no canary in any column value in the table itself, whatever the schema grows into.
        assertThat(rawTableContent()).doesNotContain(promptCanary, responseCanary, failureCanary)
        // The row is really there and readable, so the absence above is not an empty table.
        assertThat(rawTableContent()).contains("logical-model").contains("ProviderException")
    }

    /** Every text-ish column of every row, concatenated: whatever a column holds is visible here. */
    private fun rawTableContent(): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM model_attempt_observations").use(::rowsAsText)
            }
        }

    private fun rowsAsText(rows: java.sql.ResultSet): String {
        val meta = rows.metaData
        val builder = StringBuilder()
        while (rows.next()) {
            (1..meta.columnCount).forEach { column ->
                builder
                    .append(meta.getColumnName(column))
                    .append('=')
                    .append(rows.getString(column))
                    .append('\n')
            }
        }
        return builder.toString()
    }

    private fun observe(
        observer: AttemptRecordingObserver,
        attempt: Int,
    ): OperationObservation {
        val context =
            OperationCallContext(
                serviceInterface = "dev.tramai.persistence.jdbc.ConfidentialityProbe",
                methodName = "call",
                providerId = "primary",
                requestedModel = "logical-model",
                attempt = attempt,
            )
        return observer.recording(
            context,
            observer.onCallStarted(context),
            AttemptIdentity(
                invocationId = INVOCATION,
                workflowRunId = "run-5g-jdbc",
                correlationId = "corr-5g",
                routeIndex = 0,
            ),
        )
    }

    private companion object {
        const val INVOCATION = "inv-5g-jdbc"
    }
}
