package dev.tramai.examples.timeline

import dev.tramai.core.observation.attempt.AttemptObservationStore
import dev.tramai.core.observation.attempt.AttemptOutcome
import dev.tramai.core.observation.attempt.AttemptProvenance
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore
import dev.tramai.core.observation.attempt.ModelAttemptObservation
import dev.tramai.security.reconstruction.EvidenceBackedReconstructor
import dev.tramai.security.reconstruction.HistoricalConfigurationReference
import dev.tramai.security.reconstruction.HistoricalConfigurationSource
import dev.tramai.security.reconstruction.ReconstructionResult
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.SemanticTimelineQuery
import java.time.Instant

/**
 * Reference example for Epic 0.7.5 — read a run's recorded evidence and reconstruct it.
 *
 * Two things are worth copying from this file:
 *
 * 1. The reconstructor takes its historical configuration from a
 *    [HistoricalConfigurationSource], never from the caller and never from the configuration in force
 *    now. Here the source is a plain lambda over persisted evidence; in a real deployment it reads the
 *    run's own recorded snapshot. Whether that implementation is honest is a property of the
 *    implementation, not of this interface — which is why every result carries
 *    `HISTORICAL_CONFIGURATION_UNVERIFIED` when a reference is supplied.
 * 2. The narrative is rendered from typed fields only. Prompts, responses, tool payloads and reasoning
 *    are not expressible in these types, so no redaction step exists to forget.
 */
object TimelineReconstructionExample {
    const val RUN_ID: String = "run-example-001"
    private const val INVOCATION_ID: String = "inv-example-001"
    private const val THINKING_TOKENS: Int = 400
    private const val DURATION_MILLIS: Long = 1_000

    /**
     * The observations this example stands in for what the engine records: one invocation, a failed
     * first attempt and a successful retry. Exposed separately from [evidence] so a durable-store
     * example (or a test) can write exactly these rows.
     */
    fun recordedAttempts(runId: String = RUN_ID): List<ModelAttemptObservation> =
        listOf(
            attempt(runId, 0, AttemptOutcome.FAILED, input = null, output = null),
            attempt(runId, 1, AttemptOutcome.SUCCEEDED, input = 12, output = 7),
        )

    /** The same evidence in the in-memory store. */
    fun evidence(runId: String = RUN_ID): AttemptObservationStore =
        InMemoryAttemptObservationStore().apply { recordedAttempts(runId).forEach(::append) }

    /** The historical reference this run recorded. `NotRecorded` would be equally valid — and honest. */
    fun historicalConfiguration(): HistoricalConfigurationSource =
        HistoricalConfigurationSource { runId ->
            HistoricalConfigurationReference.Recorded(policyVersion = "policy-17-for-$runId")
        }

    /** The shipped read path: query the store, then reconstruct from what the query returned. */
    fun reconstruct(
        store: AttemptObservationStore,
        source: HistoricalConfigurationSource = historicalConfiguration(),
    ): ReconstructionResult {
        val query = SemanticTimelineQuery.of(store, governance = null)
        return EvidenceBackedReconstructor(query, source).reconstruct(RUN_ID, null)
    }

    /**
     * Renders a reconstruction as lines a human can read. Everything here comes from typed fields; the
     * `null` totals are printed as `unknown` and never as `0`.
     */
    fun narrative(result: ReconstructionResult): List<String> {
        val timeline = result.timeline
        val usage = timeline.usage
        return buildList {
            add("run ${timeline.workflowRunId}")
            add("status ${result.status}")
            add("governance ${sectionName(timeline.governance)}")
            add("correlation ${timeline.correlation}")
            add("history ${timeline.history}")
            add(
                "limitations " +
                    result.limitations
                        .map { it.name }
                        .sorted()
                        .joinToString(",")
                        .ifEmpty { "none" },
            )
            add(
                if (usage == null) {
                    "usage unknown"
                } else {
                    "usage attempts=${usage.attemptsCounted} duplicates=${usage.duplicatesIgnored} " +
                        "withoutUsage=${usage.attemptsWithoutUsage} " +
                        "input=${usage.inputTokens ?: "unknown"} " +
                        "output=${usage.outputTokens ?: "unknown"} " +
                        "thinking=${usage.thinkingTokens ?: "unknown"}"
                },
            )
            timeline.invocations.forEach { invocation ->
                add(
                    "invocation ${invocation.invocationId} " +
                        "correlation=${invocation.correlationId ?: "unresolved"} " +
                        "attempts=${invocation.attempts.size} final=${invocation.finalOutcome}",
                )
                invocation.attempts.forEach { attempt ->
                    add(
                        "  attempt ${attempt.attempt} ${attempt.outcome} " +
                            "provider=${attempt.providerId} " +
                            "requested=${attempt.requestedModel} " +
                            "reported=${attempt.reportedModel ?: "unknown"} " +
                            "duration=${attempt.durationMillis}ms " +
                            "finish=${attempt.finishReason ?: "none"}" +
                            if (attempt.finishReasonReported) "" else " (not provider-reported)",
                    )
                }
            }
        }
    }

    /** Exhaustive over the sealed section type: a new state cannot be silently rendered as "other". */
    private fun sectionName(section: GovernanceSection): String =
        when (section) {
            GovernanceSection.NotQueried -> "NOT_QUERIED"
            GovernanceSection.NotConfigured -> "NOT_CONFIGURED"
            GovernanceSection.NotMaterialized -> "NOT_MATERIALIZED"
            is GovernanceSection.UnsupportedFormat -> "UNSUPPORTED_FORMAT(${section.reason})"
            is GovernanceSection.InvalidStoredProjection -> "INVALID_STORED_PROJECTION(${section.reason})"
            is GovernanceSection.Snapshot -> "SNAPSHOT(decisions=${section.view.decisions.size})"
        }

    private fun attempt(
        runId: String,
        attempt: Int,
        outcome: AttemptOutcome,
        input: Int?,
        output: Int?,
    ): ModelAttemptObservation {
        val startedAt = Instant.parse("2026-10-22T09:00:00Z").plusSeconds(attempt.toLong())
        return ModelAttemptObservation(
            invocationId = INVOCATION_ID,
            attempt = attempt,
            workflowRunId = runId,
            correlationId = "corr-example",
            routeIndex = 0,
            providerId = "primary",
            requestedModel = "logical-model",
            reportedModel = if (outcome == AttemptOutcome.SUCCEEDED) "logical-model" else null,
            startedAt = startedAt,
            completedAt = startedAt.plusMillis(DURATION_MILLIS),
            durationMillis = DURATION_MILLIS,
            outcome = outcome,
            failureCategory = if (outcome == AttemptOutcome.FAILED) "ProviderException" else null,
            finishReason = if (outcome == AttemptOutcome.SUCCEEDED) "STOP" else null,
            finishReasonReported = false,
            inputTokens = input,
            outputTokens = output,
            thinkingTokens = if (outcome == AttemptOutcome.SUCCEEDED) THINKING_TOKENS else null,
            provenance = AttemptProvenance.OBSERVED_IN_PROCESS,
        )
    }
}

/** Prints the reference narrative. Run with `./gradlew :examples:timeline-reconstruction:run`. */
fun main() {
    val result = TimelineReconstructionExample.reconstruct(TimelineReconstructionExample.evidence())
    TimelineReconstructionExample.narrative(result).forEach(::println)
}
