package dev.tramai.examples.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tramai.security.reconstruction.ReconstructionResult;
import dev.tramai.security.reconstruction.ReconstructionStatus;
import dev.tramai.security.reconstruction.ReconstructionLimitation;
import dev.tramai.security.timeline.AttemptHistoryState;
import dev.tramai.security.timeline.GovernanceSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Epic 0.7.5h — the Java example compiles, runs, and produces the same facts as the Kotlin one.
 *
 * <p>This is the Java-consumer proof for the timeline/reconstruction API: if a Kotlin default argument,
 * a companion-object member or a synthetic accessor is needed here, the API is not Java-friendly and this
 * test stops compiling.
 */
class TimelineReconstructionJavaExampleTest {

    @Test
    void reconstructsRecordedEvidenceThroughTheShippedApi() {
        ReconstructionResult result =
                TimelineReconstructionJavaExample.reconstruct(TimelineReconstructionJavaExample.evidence());

        // One attempt source answered and governance was never asked, so PARTIAL is the honest status.
        assertEquals(ReconstructionStatus.PARTIAL, result.getStatus());
        assertEquals(AttemptHistoryState.RECORDED, result.getTimeline().getHistory());
        assertEquals(2, result.getTimeline().attempts().size());
        assertEquals(GovernanceSection.NotQueried.INSTANCE, result.getTimeline().getGovernance());
        assertTrue(result.getLimitations().contains(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED));
        assertTrue(
                result.getLimitations()
                        .contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED));
        // A failed attempt that reported nothing leaves the run totals unknown, never zero.
        assertEquals(null, result.getTimeline().getUsage().getInputTokens());
        assertEquals(1, result.getTimeline().getUsage().getAttemptsWithoutUsage());
    }

    @Test
    void narrativeCarriesTheFactsAndNoPayloadMaterial() {
        List<String> narrative =
                TimelineReconstructionJavaExample.narrative(
                        TimelineReconstructionJavaExample.reconstruct(
                                TimelineReconstructionJavaExample.evidence()));
        String rendered = String.join("\n", narrative);

        assertTrue(rendered.contains("status PARTIAL"), rendered);
        assertTrue(rendered.contains("input=unknown"), rendered);
        assertTrue(rendered.contains("attempt 0 FAILED"), rendered);
        assertTrue(rendered.contains("attempt 1 SUCCEEDED"), rendered);
        assertTrue(rendered.contains("(not provider-reported)"), rendered);
        // Content-free by type: no prompt, response or payload field exists to render.
        assertFalse(rendered.contains("prompt"), rendered);
        assertFalse(rendered.contains("reasoning"), rendered);
    }
}
