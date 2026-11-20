package dev.tramai.examples.timeline

import dev.tramai.security.reconstruction.ReconstructionLimitation
import dev.tramai.security.reconstruction.ReconstructionStatus
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.CorrelationEvidence
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.ObservedUsage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 0.7.5h — the Kotlin example is executable and its narrative is populated from real facts.
 *
 * A reference example that prints nothing, or prints the wrong thing, is worse than no example. These
 * assertions are the example's own acceptance test.
 */
class TimelineReconstructionExampleTest {
    @Test
    fun `the example reconstructs a recorded run and its narrative carries the facts`() {
        val result = TimelineReconstructionExample.reconstruct(TimelineReconstructionExample.evidence())
        val narrative = TimelineReconstructionExample.narrative(result)

        // No governance scope and no governance query: the attempt source answered, the governance
        // source did not, so the honest status is PARTIAL — not SUPPORTED.
        assertThat(result.status).isEqualTo(ReconstructionStatus.PARTIAL)
        assertThat(result.limitations).contains(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)
        assertThat(result.timeline.history).isEqualTo(AttemptHistoryState.RECORDED)
        assertThat(result.timeline.attempts()).hasSize(2)
        assertThat(
            result.timeline.invocations
                .single()
                .finalOutcome.name,
        ).isEqualTo("SUCCEEDED")

        // Exact accounting: the failed attempt reported nothing, so no component is a run total...
        // ... except those both attempts reported. Only the second reported anything at all.
        assertThat(result.timeline.usage).isEqualTo(
            ObservedUsage(
                attemptsCounted = 2,
                duplicatesIgnored = 0,
                attemptsWithoutUsage = 1,
                inputTokens = null,
                outputTokens = null,
                thinkingTokens = null,
            ),
        )

        // The narrative is populated (not vacuous) and states the unknowns as unknown, never as zero.
        assertThat(narrative).allSatisfy { assertThat(it).isNotBlank() }
        val rendered = narrative.joinToString("\n")
        assertThat(rendered).contains("run ${TimelineReconstructionExample.RUN_ID}")
        assertThat(rendered).contains("status PARTIAL")
        assertThat(rendered).contains("input=unknown").contains("output=unknown")
        assertThat(rendered).contains("attempts=2 duplicates=0 withoutUsage=1")
        assertThat(rendered).contains("  attempt 0 FAILED")
        assertThat(rendered).contains("  attempt 1 SUCCEEDED")
        assertThat(rendered).contains("(not provider-reported)")

        // Governance was never consulted, and the narrative says so rather than implying nothing happened.
        assertThat(result.timeline.governance).isEqualTo(GovernanceSection.NotQueried)
        assertThat(result.timeline.correlation).isEqualTo(CorrelationEvidence.NONE)

        // A supplied reference is source-declared, never authenticated — the result says so.
        assertThat(result.limitations)
            .contains(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED)
    }

    @Test
    fun `the narrative contains no prompt, response or payload material`() {
        val result = TimelineReconstructionExample.reconstruct(TimelineReconstructionExample.evidence())
        val rendered = TimelineReconstructionExample.narrative(result).joinToString("\n")

        // The types cannot carry content, so the strongest available assertion is that the rendering
        // exposes only recorded metadata — no field names that could hold protected material.
        assertThat(rendered).doesNotContain("prompt", "content", "arguments", "reasoning", "response body")
        assertThat(rendered).contains("logical-model").contains("primary")
    }
}
