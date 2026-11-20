package dev.tramai.persistence.jdbc

import dev.tramai.core.observation.attempt.AttemptAppendOutcome
import dev.tramai.core.observation.attempt.AttemptHistory
import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * PostgreSQL [AttemptObservationStore] (Epic 0.7.5b).
 *
 * One row per provider attempt, keyed by `(invocation_id, attempt)`. Appends are idempotent through
 * the primary key, so a repeated terminal callback cannot create a second attempt.
 *
 * Rows are observations, not authority: nothing here is proof that a governance decision was
 * enforced, and no column of this store can hold prompt, response, tool or reasoning content.
 */
class JdbcModelAttemptObservationStore(
    private val dataSource: DataSource,
) : AttemptObservationStore {
    override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO model_attempt_observations (
                        invocation_id, attempt, workflow_run_id, correlation_id, route_index,
                        provider_id, requested_model, reported_model, started_at, completed_at,
                        duration_millis, outcome, failure_category, finish_reason,
                        finish_reason_reported, input_tokens, output_tokens, thinking_tokens,
                        provenance
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (invocation_id, attempt) DO NOTHING
                    """.trimIndent(),
                ).use { stmt ->
                    var index = 1
                    stmt.setString(index++, observation.invocationId)
                    stmt.setInt(index++, observation.attempt)
                    stmt.setString(index++, observation.workflowRunId)
                    stmt.setString(index++, observation.correlationId)
                    stmt.setNullableInt(index++, observation.routeIndex)
                    stmt.setString(index++, observation.providerId)
                    stmt.setString(index++, observation.requestedModel)
                    stmt.setString(index++, observation.reportedModel)
                    stmt.setTimestamp(index++, Timestamp.from(observation.startedAt))
                    stmt.setTimestamp(index++, Timestamp.from(observation.completedAt))
                    stmt.setLong(index++, observation.durationMillis)
                    stmt.setString(index++, observation.outcome.name)
                    stmt.setString(index++, observation.failureCategory)
                    stmt.setString(index++, observation.finishReason)
                    stmt.setBoolean(index++, observation.finishReasonReported)
                    stmt.setNullableInt(index++, observation.inputTokens)
                    stmt.setNullableInt(index++, observation.outputTokens)
                    stmt.setNullableInt(index++, observation.thinkingTokens)
                    stmt.setString(index, observation.provenance.name)
                    if (stmt.executeUpdate() == 0) {
                        AttemptAppendOutcome.ALREADY_RECORDED
                    } else {
                        AttemptAppendOutcome.RECORDED
                    }
                }
        }

    override fun findByWorkflowRun(workflowRunId: String): AttemptHistory = read("workflow_run_id = ?", workflowRunId)

    override fun findByInvocation(invocationId: String): AttemptHistory = read("invocation_id = ?", invocationId)

    private fun read(
        predicate: String,
        value: String,
    ): AttemptHistory =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT * FROM model_attempt_observations
                    WHERE $predicate
                    ORDER BY started_at, attempt
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, value)
                    stmt.executeQuery().use { rows ->
                        AttemptHistory.Available(buildList { while (rows.next()) add(rows.toObservation()) })
                    }
                }
        }

    private fun ResultSet.toObservation(): ModelAttemptObservation =
        ModelAttemptObservation(
            invocationId = getString("invocation_id"),
            attempt = getInt("attempt"),
            workflowRunId = getString("workflow_run_id"),
            correlationId = getString("correlation_id"),
            routeIndex = getNullableInt("route_index"),
            providerId = getString("provider_id"),
            requestedModel = getString("requested_model"),
            reportedModel = getString("reported_model"),
            startedAt = getTimestamp("started_at").toInstant(),
            completedAt = getTimestamp("completed_at").toInstant(),
            durationMillis = getLong("duration_millis"),
            outcome = AttemptOutcome.valueOf(getString("outcome")),
            failureCategory = getString("failure_category"),
            finishReason = getString("finish_reason"),
            finishReasonReported = getBoolean("finish_reason_reported"),
            inputTokens = getNullableInt("input_tokens"),
            outputTokens = getNullableInt("output_tokens"),
            thinkingTokens = getNullableInt("thinking_tokens"),
            provenance = AttemptProvenance.valueOf(getString("provenance")),
        )

    private fun ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun java.sql.PreparedStatement.setNullableInt(
        index: Int,
        value: Int?,
    ) {
        if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
    }
}
