package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 10.3c3 classification-authority discriminator matrix (M03 approval
 * immutability, M04/M05 stale approvals, M08 self-approval, M09 fabrication,
 * M10 orphans, M11 removed approvals) for the base-authoritative mutation
 * ratchet. Outcome/scope/semantics rules (M01/M02/M06/M07/M12-M20) live in
 * [MutationRatchetDiscriminatorTest]. All tests use small synthetic
 * populations and classifications — NONE of them runs PITest.
 */
class MutationRatchetClassificationDiscriminatorTest : MutationRatchetTestSupport() {
    @Test
    fun `M03 approved survivor remaining NON_KILLED passes`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = approvedClassifications("s1"),
            ),
        )
    }

    @Test
    fun `M03 retained classification rewritten classification field fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications =
                    classifications(classificationOf("s1", classification = "low-risk-implementation-detail")),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID, "was rewritten")
    }

    @Test
    fun `M03 retained classification rewritten reason fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(classificationOf("s1", reason = "we do not care anymore")),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID, "was rewritten")
    }

    @Test
    fun `M03 retained classification gaining an issue fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(classificationOf("s1", issue = "ABC-123")),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID, "was rewritten")
    }

    @Test
    fun `M03 retained classification targetPhase change fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(classificationOf("s1", targetPhase = "phase-2")),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID, "was rewritten")
    }

    @Test
    fun `M04 approved survivor killed with classification removed passes`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1", status = "KILLED", outcome = "KILLED")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(),
            ),
        )
    }

    @Test
    fun `M05 approved survivor killed with stale classification fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = approvedClassifications("s1"),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "stale classification on a now-killed mutant must fail",
        )
    }

    @Test
    fun `M08 candidate self-classification of a new survivor fails`() {
        // THE critical test: BASE: A absent; CANDIDATE: A = NON_KILLED and
        // classification includes A => FAIL. A PR cannot approve its own
        // survivor by classifying it.
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("a"),
                ),
            )
        val diagnostics =
            verify(
                basePopulation = base,
                candidatePopulation = candidate,
                candidateClassifications = approvedClassifications("a"),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR))
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "self-classification must itself be rejected",
        )
        assertTrue(failures(diagnostics).any { it.message.contains("self-approval") })
    }

    @Test
    fun `M09 fabricated classification fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                candidatePopulation = candidate,
                candidateClassifications = classifications(classificationOf("never-measured")),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID, "fabricated")
    }

    @Test
    fun `M10 disappeared mutant with retained classification fails`() {
        val base =
            population(
                listOf(
                    row("s1"),
                    row("k1", status = "KILLED", outcome = "KILLED"),
                ),
            )
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = approvedClassifications("s1"),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "classification retained after its mutant disappeared must fail",
        )
    }

    @Test
    fun `M10 disappeared mutant with classification removed passes`() {
        val base =
            population(
                listOf(
                    row("s1"),
                    row("k1", status = "KILLED", outcome = "KILLED"),
                ),
            )
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(),
            ),
        )
    }

    @Test
    fun `M11 removing base classification while survivor remains fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_REMOVED),
            "removing an approval while its survivor remains must fail",
        )
    }

    @Test
    fun `M20 self-inconsistent base classification fails closed`() {
        // Base classification referencing a KILLED mutant is an authority defect.
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("k1"),
                candidatePopulation = candidate,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "a base classification over a killed mutant is not an approval source",
        )
    }
}
