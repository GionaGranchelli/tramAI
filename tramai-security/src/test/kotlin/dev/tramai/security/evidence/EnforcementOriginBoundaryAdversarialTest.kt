package dev.tramai.security.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 0.7.6g2 Adversarial qualification for G2-14: Enforcement Origin Attestation Boundary.
 *
 * Verifies that:
 * 1. Self-asserted strength (`boundary.enforcementStrength`) is refused before derivation.
 * 2. Self-asserted origin (`boundary.origin`) is refused before derivation.
 * 3. Declared provenance without attester is capped strictly at `DECLARED` strength and `declared` origin.
 * 4. Claiming `attested` origin is refused during validation because no runtime attester exists (Slice 2 deferred).
 * 5. Claiming registered entitlement (e.g. `AUTHORITATIVE`) with declared origin is refused during validation.
 */
class EnforcementOriginBoundaryAdversarialTest {
    private val enforcementPoint = "BEFORE_TOOL_EXECUTION"
    private val registeredBoundaryId = RuntimeBoundaryProvenance.SPRING_AI_TOOL_CALLBACK

    @Test
    fun `G2-14 - self-asserted enforcement strength in event metadata is refused`() {
        assertThatThrownBy {
            RuntimeBoundaryProvenance.provenanceFor(
                eventMetadata =
                    mapOf(
                        RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                        RuntimeBoundaryProvenance.STRENGTH_KEY to "AUTHORITATIVE",
                    ),
                enforcementPoint = enforcementPoint,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("self-asserted claim")
    }

    @Test
    fun `G2-14 - self-asserted enforcement origin in event metadata is refused`() {
        assertThatThrownBy {
            RuntimeBoundaryProvenance.provenanceFor(
                eventMetadata =
                    mapOf(
                        RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                        RuntimeBoundaryProvenance.ORIGIN_KEY to "attested",
                    ),
                enforcementPoint = enforcementPoint,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("self-asserted origin")
    }

    @Test
    fun `G2-14 - declared provenance without attester is capped at DECLARED strength and declared origin`() {
        val provenance =
            RuntimeBoundaryProvenance.provenanceFor(
                eventMetadata =
                    mapOf(
                        RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                    ),
                enforcementPoint = enforcementPoint,
            )

        assertThat(provenance[RuntimeBoundaryProvenance.ID_KEY]).isEqualTo(registeredBoundaryId)
        assertThat(provenance[RuntimeBoundaryProvenance.STRENGTH_KEY])
            .isEqualTo(RuntimeBoundaryProvenance.DECLARED)
        assertThat(provenance[RuntimeBoundaryProvenance.ORIGIN_KEY])
            .isEqualTo(RuntimeBoundaryProvenance.ORIGIN_DECLARED)
    }

    @Test
    fun `G2-14 - record claiming attested origin is refused during validation because Slice 2 has no attester`() {
        assertThatThrownBy {
            RuntimeBoundaryProvenance.validate(
                enforcementPoint = enforcementPoint,
                metadata =
                    mapOf(
                        RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                        RuntimeBoundaryProvenance.STRENGTH_KEY to RuntimeBoundaryProvenance.DECLARED,
                        RuntimeBoundaryProvenance.ORIGIN_KEY to RuntimeBoundaryProvenance.ORIGIN_ATTESTED,
                    ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("has no attester in this runtime")
    }

    @Test
    fun `G2-14 - record claiming registered entitlement AUTHORITATIVE with declared origin is refused`() {
        assertThatThrownBy {
            RuntimeBoundaryProvenance.validate(
                enforcementPoint = enforcementPoint,
                metadata =
                    mapOf(
                        RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                        RuntimeBoundaryProvenance.STRENGTH_KEY to RuntimeBoundaryProvenance.AUTHORITATIVE,
                        RuntimeBoundaryProvenance.ORIGIN_KEY to RuntimeBoundaryProvenance.ORIGIN_DECLARED,
                    ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("carries declared origin and cannot claim 'AUTHORITATIVE'")
    }

    @Test
    fun `G2-14 positive control - valid DECLARED provenance with declared origin passes validation`() {
        RuntimeBoundaryProvenance.validate(
            enforcementPoint = enforcementPoint,
            metadata =
                mapOf(
                    RuntimeBoundaryProvenance.ID_KEY to registeredBoundaryId,
                    RuntimeBoundaryProvenance.STRENGTH_KEY to RuntimeBoundaryProvenance.DECLARED,
                    RuntimeBoundaryProvenance.ORIGIN_KEY to RuntimeBoundaryProvenance.ORIGIN_DECLARED,
                ),
        )
    }
}
