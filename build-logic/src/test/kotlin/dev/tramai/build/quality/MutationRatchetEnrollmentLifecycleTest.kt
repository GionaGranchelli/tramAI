package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 0.7.1g1F: the authorization lifecycle (M27/M28/M29) — what may happen to a base authorization that is
 * not consumed immediately.
 *
 * Review of the first revision found two holes here: an unconsumed authorization could be silently
 * re-authored before a later PR consumed it (M28), and target validity was only ever checked against the
 * base population, so a transition could enroll a classification for a mutant it had just killed or
 * removed — producing a candidate authority its own next verification rejects — while an authorization
 * whose target died was undeletable (M29 plus terminal semantics).
 */
class MutationRatchetEnrollmentLifecycleTest : MutationRatchetEnrollmentTestSupport() {
    // ---------------------------------------------------------------- M27: consumption discipline

    @Test
    fun `M27 authorization removed without enrollment fails closed`() {
        val x = row("x")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger = Ledger(baseEnrollments = enrollments(toolLimitation("x"))),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED,
            ),
            "M27 must fail",
        )
    }

    @Test
    fun `M27 authorization retained after its classification was enrolled fails closed`() {
        val x = row("x")
        val authorized = toolLimitation("x")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger =
                        Ledger(
                            baseEnrollments = enrollments(authorized),
                            candidateClassifications = classifications(authorized),
                            candidateEnrollments = enrollments(authorized),
                        ),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED,
            ),
            "single-use must hold",
        )
    }

    // ---------------------------------------------------------------- M28: retention is immutable

    @Test
    fun `M28 a retained authorization may not be rewritten before it is consumed`() {
        val x = row("x")

        fun payload(
            classification: String = "tool-limitation",
            reason: String = "A",
            issue: String? = "I-1",
            targetPhase: String? = "0.7.1",
        ): MutationClassification =
            classificationOf(
                marker = "x",
                classification = classification,
                reason = reason,
                issue = issue,
                targetPhase = targetPhase,
            )

        val base = payload()
        val rewrites =
            listOf(
                payload(classification = "equivalent-mutant"),
                payload(reason = "B"),
                payload(issue = "I-2"),
                payload(issue = null),
                payload(targetPhase = "0.7.2"),
                payload(targetPhase = null),
            )
        rewrites.forEach { rewritten ->
            assertTrue(
                hasCode(
                    verify(
                        base = population(listOf(x)),
                        candidate = population(listOf(x)),
                        ledger = pending(base, asCarried = rewritten),
                    ),
                    DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_MISMATCH,
                ),
                "M28 must reject a retained authorization rewritten to $rewritten",
            )
        }
    }

    @Test
    fun `M28 an unchanged retained authorization is still pending and passes`() {
        val x = row("x")
        val authorized = toolLimitation("x")
        passes(
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = pending(authorized),
            ),
        )
    }

    // ---------------------------------------------------------------- M27/M29: terminal states

    @Test
    fun `M27 a retained authorization whose target became KILLED is stale`() {
        val killed = row("x", status = "KILLED", outcome = "KILLED")
        val authorized = toolLimitation("x")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(row("x"))),
                    candidate = population(listOf(killed)),
                    ledger = pending(authorized),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED,
            ),
            "a dead target must not keep its authorization",
        )
    }

    @Test
    fun `M27 a retained authorization whose target disappeared is stale`() {
        val authorized = toolLimitation("x")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(row("x"))),
                    candidate = population(listOf(row("y"))),
                    ledger = pending(authorized),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED,
            ),
            "an unmeasured target must not keep its authorization",
        )
    }

    @Test
    fun `M27 an authorization may be cleaned up once its target is KILLED`() {
        val killed = row("x", status = "KILLED", outcome = "KILLED")
        passes(
            verify(
                base = population(listOf(row("x"))),
                candidate = population(listOf(killed)),
                ledger = Ledger(baseEnrollments = enrollments(toolLimitation("x"))),
            ),
        )
    }

    @Test
    fun `M27 cleanup after a disappearance is permitted and M21 governs the disappearance`() {
        val diagnostics =
            verify(
                base = population(listOf(row("x"))),
                candidate = population(listOf(row("y"))),
                ledger = Ledger(baseEnrollments = enrollments(toolLimitation("x"))),
            )
        assertTrue(
            failures(diagnostics).none { it.code == DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_ORPHANED },
            "the ceremony must allow cleanup: ${failures(diagnostics).map { it.code }}",
        )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT),
            "M21 must independently decide whether the disappearance is legitimate",
        )
    }

    @Test
    fun `M29 enrollment for a mutant this transition killed fails`() {
        val killed = row("x", status = "KILLED", outcome = "KILLED")
        val diagnostics =
            verify(
                base = population(listOf(row("x"))),
                candidate = population(listOf(killed)),
                ledger =
                    Ledger(
                        baseEnrollments = enrollments(toolLimitation("x")),
                        candidateClassifications = classifications(toolLimitation("x")),
                    ),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "a classification may not be enrolled onto a dead mutant: ${failures(diagnostics).map { it.code }}",
        )
    }

    @Test
    fun `M29 enrollment for a mutant this transition removed fails`() {
        val diagnostics =
            verify(
                base = population(listOf(row("x"))),
                candidate = population(listOf(row("y"))),
                ledger =
                    Ledger(
                        baseEnrollments = enrollments(toolLimitation("x")),
                        candidateClassifications = classifications(toolLimitation("x")),
                    ),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "a classification may not be enrolled onto an absent mutant: ${failures(diagnostics).map { it.code }}",
        )
    }

    @Test
    fun `M25 a proposed authorization for a mutant already KILLED in the candidate fails`() {
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(row("x"))),
                    candidate = population(listOf(row("x", status = "KILLED", outcome = "KILLED"))),
                    ledger = Ledger(candidateEnrollments = enrollments(toolLimitation("x"))),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
            ),
            "a proposal must target a live survivor in the candidate population too",
        )
    }

    // ---------------------------------------------------------------- the invariant behind all of it

    @Test
    fun `every passing ceremony transition yields a candidate that is valid as the next base`() {
        val authorized = toolLimitation("x", reason = "g1E adjudicated")
        val killed = row("x", status = "KILLED", outcome = "KILLED")

        // authorize (BASE0 -> BASE1): an authorization is added, nothing is enrolled
        assertValidAsNextBase(
            population = population(listOf(row("x"))),
            classifications = classifications(),
            enrollments = enrollments(authorized),
        )
        // pending retention: the authorization is carried unchanged
        assertValidAsNextBase(
            population = population(listOf(row("x"))),
            classifications = classifications(),
            enrollments = enrollments(authorized),
        )
        // consume (BASE1 -> BASE2): the classification is enrolled and the authorization is gone
        assertValidAsNextBase(
            population = population(listOf(row("x"))),
            classifications = classifications(authorized),
            enrollments = MutationClassificationEnrollments.NONE,
        )
        // cleanup: the mutant died and the authorization was removed with it
        assertValidAsNextBase(
            population = population(listOf(killed)),
            classifications = classifications(),
            enrollments = MutationClassificationEnrollments.NONE,
        )
    }
}
