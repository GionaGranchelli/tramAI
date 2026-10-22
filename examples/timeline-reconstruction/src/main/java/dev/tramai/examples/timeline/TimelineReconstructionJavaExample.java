package dev.tramai.examples.timeline;

import dev.tramai.core.observation.attempt.AttemptOutcome;
import dev.tramai.core.observation.attempt.AttemptProvenance;
import dev.tramai.core.observation.attempt.AttemptObservationStore;
import dev.tramai.core.observation.attempt.InMemoryAttemptObservationStore;
import dev.tramai.core.observation.attempt.ModelAttemptObservation;
import dev.tramai.security.reconstruction.EvidenceBackedReconstructor;
import dev.tramai.security.reconstruction.HistoricalConfigurationReference;
import dev.tramai.security.reconstruction.HistoricalConfigurationSource;
import dev.tramai.security.reconstruction.ReconstructionResult;
import dev.tramai.security.timeline.SemanticTimeline;
import dev.tramai.security.timeline.SemanticTimelineQuery;
import dev.tramai.security.timeline.InvocationTimeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The same reference reconstruction as {@link TimelineReconstructionExample}, written in Java.
 *
 * <p>This exists because TramAI claims Java friendliness. Everything below is shipped public API: no
 * Kotlin-only affordance, no coroutine plumbing, no synthetic accessor. If this file stops compiling,
 * the Java-consumer claim has regressed.
 */
public final class TimelineReconstructionJavaExample {

    private static final String RUN_ID = "run-example-001";
    private static final String INVOCATION_ID = "inv-example-001";

    private TimelineReconstructionJavaExample() {}

    /** In-memory evidence: one invocation, one failed attempt, one successful retry. */
    public static AttemptObservationStore evidence() {
        InMemoryAttemptObservationStore store = new InMemoryAttemptObservationStore();
        store.append(attempt(0, AttemptOutcome.FAILED, null, null));
        store.append(attempt(1, AttemptOutcome.SUCCEEDED, 12, 7));
        return store;
    }

    /**
     * The historical reference. A lambda works because the port is a Kotlin {@code fun interface}, and
     * it is the caller's obligation to return what the run actually recorded — never the configuration
     * in force now.
     */
    public static HistoricalConfigurationSource historicalConfiguration() {
        return runId -> new HistoricalConfigurationReference.Recorded(
                null, "policy-17-for-" + runId, null);
    }

    /** The shipped read path: query the store, then reconstruct from what the query returned. */
    public static ReconstructionResult reconstruct(AttemptObservationStore store) {
        SemanticTimelineQuery query = SemanticTimelineQuery.Companion.of(store, null);
        return new EvidenceBackedReconstructor(query, historicalConfiguration())
                .reconstruct(RUN_ID, null);
    }

    /** Renders the reconstruction from typed fields only; unknown totals print as {@code unknown}. */
    public static List<String> narrative(ReconstructionResult result) {
        SemanticTimeline timeline = result.getTimeline();
        List<String> lines = new ArrayList<>();
        lines.add("run " + timeline.getWorkflowRunId());
        lines.add("status " + result.getStatus());
        lines.add("governance " + sectionName(timeline.getGovernance()));
        lines.add("correlation " + timeline.getCorrelation());
        lines.add("history " + timeline.getHistory());

        List<String> limitations = new ArrayList<>();
        result.getLimitations().forEach(limitation -> limitations.add(limitation.name()));
        limitations.sort(String::compareTo);
        lines.add("limitations " + (limitations.isEmpty() ? "none" : String.join(",", limitations)));

        if (timeline.getUsage() == null) {
            lines.add("usage unknown");
        } else {
            lines.add(
                    "usage attempts=" + timeline.getUsage().getAttemptsCounted()
                            + " duplicates=" + timeline.getUsage().getDuplicatesIgnored()
                            + " withoutUsage=" + timeline.getUsage().getAttemptsWithoutUsage()
                            + " input=" + unknownIfNull(timeline.getUsage().getInputTokens())
                            + " output=" + unknownIfNull(timeline.getUsage().getOutputTokens())
                            + " thinking=" + unknownIfNull(timeline.getUsage().getThinkingTokens()));
        }

        for (InvocationTimeline invocation : timeline.getInvocations()) {
            lines.add(
                    "invocation " + invocation.getInvocationId()
                            + " correlation=" + (invocation.getCorrelationId() == null
                                    ? "unresolved"
                                    : invocation.getCorrelationId())
                            + " attempts=" + invocation.getAttempts().size()
                            + " final=" + invocation.getFinalOutcome());
            for (ModelAttemptObservation attempt : invocation.getAttempts()) {
                lines.add(
                        "  attempt " + attempt.getAttempt() + " " + attempt.getOutcome()
                                + " provider=" + attempt.getProviderId()
                                + " requested=" + attempt.getRequestedModel()
                                + " reported=" + (attempt.getReportedModel() == null
                                        ? "unknown"
                                        : attempt.getReportedModel())
                                + " duration=" + attempt.getDurationMillis() + "ms"
                                + " finish=" + (attempt.getFinishReason() == null
                                        ? "none"
                                        : attempt.getFinishReason())
                                + (attempt.getFinishReasonReported() ? "" : " (not provider-reported)"));
            }
        }
        return lines;
    }

    private static String unknownIfNull(final Long value) {
        return value == null ? "unknown" : value.toString();
    }

    private static String sectionName(final Object section) {
        return section.getClass().getSimpleName();
    }

    private static ModelAttemptObservation attempt(
            final int attempt, final AttemptOutcome outcome, final Integer input, final Integer output) {
        Instant startedAt = Instant.parse("2026-10-22T09:00:00Z").plusSeconds(attempt);
        boolean succeeded = outcome == AttemptOutcome.SUCCEEDED;
        return new ModelAttemptObservation(
                INVOCATION_ID,
                attempt,
                RUN_ID,
                "corr-example",
                0,
                "primary",
                "logical-model",
                succeeded ? "logical-model" : null,
                startedAt,
                startedAt.plusMillis(1_000),
                Duration.ofSeconds(1).toMillis(),
                outcome,
                succeeded ? null : "ProviderException",
                succeeded ? "STOP" : null,
                false,
                input,
                output,
                succeeded ? 400 : null,
                AttemptProvenance.OBSERVED_IN_PROCESS);
    }

    /** Prints the Java narrative. Run with the module's `run` task after a `--main-class` override. */
    public static void main(final String[] args) {
        for (String line : narrative(reconstruct(evidence()))) {
            System.out.println(line);
        }
    }
}
