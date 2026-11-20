package dev.tramai.persistence.jdbc

import dev.tramai.core.observation.attempt.AttemptAppendOutcome
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource

/**
 * Epic 0.7.5b — durable attempt observations in PostgreSQL.
 *
 * Proves the record survives the process that wrote it: every read here goes through a fresh store
 * over the same datasource, never through an in-process cache.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcModelAttemptObservationStoreTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("attempt_observation")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        executeSchema(dataSource)
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

    private fun store(): JdbcModelAttemptObservationStore = JdbcModelAttemptObservationStore(dataSource)

    private fun attempts(history: AttemptHistory): List<ModelAttemptObservation> =
        when (history) {
            is AttemptHistory.Available -> history.attempts
            AttemptHistory.Uninstrumented -> error("a durable store never reports Uninstrumented")
        }

    private fun observation(
        invocationId: String = "inv-1",
        attempt: Int = 0,
        outcome: AttemptOutcome = AttemptOutcome.SUCCEEDED,
        workflowRunId: String? = "run-1",
        thinkingTokens: Int? = 3,
    ) = ModelAttemptObservation(
        invocationId = invocationId,
        attempt = attempt,
        workflowRunId = workflowRunId,
        correlationId = "corr-1",
        routeIndex = 0,
        providerId = "primary",
        requestedModel = "logical-model",
        reportedModel = if (outcome == AttemptOutcome.SUCCEEDED) "actual-model" else null,
        startedAt = Instant.parse("2026-03-01T00:00:00Z"),
        completedAt = Instant.parse("2026-03-01T00:00:01.250Z"),
        durationMillis = 1250,
        outcome = outcome,
        failureCategory = if (outcome == AttemptOutcome.FAILED) "ProviderException" else null,
        finishReason = "STOP",
        finishReasonReported = false,
        inputTokens = if (outcome == AttemptOutcome.FAILED) null else 10,
        outputTokens = if (outcome == AttemptOutcome.FAILED) null else 5,
        thinkingTokens = if (outcome == AttemptOutcome.FAILED) null else thinkingTokens,
        provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
    )

    @Test
    fun `an attempt written by one process is readable by another with every field intact`() {
        assertThat(store().append(observation())).isEqualTo(AttemptAppendOutcome.RECORDED)

        val recorded = attempts(store().findByInvocation("inv-1")).single()
        assertThat(recorded.invocationId).isEqualTo("inv-1")
        assertThat(recorded.attempt).isZero()
        assertThat(recorded.workflowRunId).isEqualTo("run-1")
        assertThat(recorded.correlationId).isEqualTo("corr-1")
        assertThat(recorded.routeIndex).isZero()
        assertThat(recorded.providerId).isEqualTo("primary")
        assertThat(recorded.requestedModel).isEqualTo("logical-model")
        assertThat(recorded.reportedModel).isEqualTo("actual-model")
        assertThat(recorded.startedAt).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"))
        assertThat(recorded.completedAt).isEqualTo(Instant.parse("2026-03-01T00:00:01.250Z"))
        assertThat(recorded.durationMillis).isEqualTo(1250)
        assertThat(recorded.outcome).isEqualTo(AttemptOutcome.SUCCEEDED)
        assertThat(recorded.finishReason).isEqualTo("STOP")
        assertThat(recorded.finishReasonReported).isFalse()
        assertThat(recorded.provenance).isEqualTo(AttemptProvenance.OBSERVED_IN_PROCESS)
        // Usage is stored exactly as reported, per field; no total is stored or derived.
        assertThat(recorded.inputTokens).isEqualTo(10)
        assertThat(recorded.outputTokens).isEqualTo(5)
        assertThat(recorded.thinkingTokens).isEqualTo(3)
    }

    @Test
    fun `unknown values survive the round trip as unknown`() {
        store().append(observation(thinkingTokens = null))
        val recorded = attempts(store().findByInvocation("inv-1")).single()
        assertThat(recorded.thinkingTokens).isNull()

        store().append(observation(invocationId = "inv-2", outcome = AttemptOutcome.FAILED))
        val failed = attempts(store().findByInvocation("inv-2")).single()
        assertThat(failed.inputTokens).isNull()
        assertThat(failed.outputTokens).isNull()
        assertThat(failed.thinkingTokens).isNull()
        assertThat(failed.reportedModel).isNull()
        assertThat(failed.failureCategory).isEqualTo("ProviderException")
    }

    @Test
    fun `a duplicate write is idempotent and never duplicates the attempt`() {
        val store = store()
        assertThat(store.append(observation())).isEqualTo(AttemptAppendOutcome.RECORDED)
        assertThat(store.append(observation())).isEqualTo(AttemptAppendOutcome.ALREADY_RECORDED)
        assertThat(attempts(store.findByInvocation("inv-1"))).hasSize(1)
    }

    @Test
    fun `attempts of one invocation are ordered and distinct attempts are both kept`() {
        val store = store()
        store.append(observation(attempt = 1, outcome = AttemptOutcome.SUCCEEDED))
        store.append(observation(attempt = 0, outcome = AttemptOutcome.FAILED))

        val recorded = attempts(store.findByInvocation("inv-1"))
        assertThat(recorded.map { it.attempt }).containsExactly(0, 1)
        assertThat(recorded.map { it.outcome }).containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED)
    }

    @Test
    fun `attempts are readable by governed run and an unknown identity returns no rows`() {
        val store = store()
        store.append(observation(invocationId = "inv-1", attempt = 0))
        store.append(observation(invocationId = "inv-2", attempt = 0))

        assertThat(attempts(store.findByWorkflowRun("run-1"))).hasSize(2)
        assertThat(attempts(store.findByWorkflowRun("run-unknown"))).isEmpty()
        assertThat(attempts(store.findByInvocation("inv-unknown"))).isEmpty()
    }

    @Test
    fun `an attempt with no governed run is still stored and is not returned by a run query`() {
        val store = store()
        store.append(observation(workflowRunId = null))

        assertThat(attempts(store.findByInvocation("inv-1")).single().workflowRunId).isNull()
        assertThat(attempts(store.findByWorkflowRun("run-1"))).isEmpty()
    }

    companion object {
        internal fun executeSchema(dataSource: DataSource) {
            dataSource.connection.use { conn ->
                val migrations =
                    listOf(
                        "V1__sovereign_persistence.sql",
                        "V2__approval_continuations.sql",
                        "V3__audit_events_hardening.sql",
                        "V4__audit_outbox_hardening.sql",
                        "V5__worker_leases_hardening.sql",
                        "V6__approval_resume_credential_custody.sql",
                        "V7__approval_continuations_resume_retry.sql",
                        "V8__control_plane_workload_registration.sql",
                        "V9__model_attempt_observations.sql",
                    )
                for (migration in migrations) {
                    val sql =
                        JdbcModelAttemptObservationStoreTest::class.java.classLoader
                            .getResource("tramai/persistence/jdbc/postgres/$migration")
                            ?.readText()
                            ?: error("Schema SQL resource not found: $migration")
                    conn.createStatement().use { it.execute(sql) }
                }
            }
        }
    }
}
