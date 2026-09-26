package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.7.1g1F: the base classification enrollment ceremony (M22-M26) and its M08/M09 backstop.
 *
 * The ratchet rejects candidate-side classification additions (M08/M09) — a PR may not decide that its
 * own new survivor is acceptable. Its documented complement, "adjudicated on master during an enrollment
 * ceremony", had no implementation until this ledger. These discriminators pin both halves of the
 * property: an authorization that exists in the BASE may be consumed by a later transition, and a
 * candidate that fabricates its own authority in the same transition still fails exactly as before.
 *
 * Retention, terminal states and the next-base invariant live in
 * [MutationRatchetEnrollmentLifecycleTest]; ledger parsing lives in
 * [MutationRatchetEnrollmentLedgerTest].
 */
class MutationRatchetEnrollmentCeremonyTest : MutationRatchetEnrollmentTestSupport() {
    // ---------------------------------------------------------------- M22/M26: the ceremony works

    @Test
    fun `M22 exact base-authorized classification enrolls and consumes its authorization`() {
        val x = row("x")
        val authorized = toolLimitation("x")
        val diagnostics =
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = consume(authorized),
            )
        passes(diagnostics)
    }

    @Test
    fun `M26 authorization may be consumed in the same transition that enrolls it`() {
        val x = row("x")
        val authorized = classificationOf("x", classification = "missing-test", reason = "adjudicated")
        // BASE: authorization present, no classification. CANDIDATE: authorization removed, exact
        // classification added — the consumption shape.
        passes(
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = consume(authorized),
            ),
        )
    }

    @Test
    fun `M22 enrollment is generic across the allowed classification vocabulary`() {
        val kinds =
            listOf("missing-test", "equivalent-mutant", "low-risk-implementation-detail", "known-design-ambiguity")
        kinds.forEach { kind ->
            val x = row("x")
            val authorized = classificationOf("x", classification = kind, reason = "adjudicated as $kind")
            passes(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger = consume(authorized),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- M23/M08: self-authorization

    @Test
    fun `M23 a transition that authorizes and enrolls in the same commit fails`() {
        val x = row("x")
        val selfAuthorized = toolLimitation("x", reason = "self-approved")
        val diagnostics =
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger =
                    Ledger(
                        candidateClassifications = classifications(selfAuthorized),
                        candidateEnrollments = enrollments(selfAuthorized),
                    ),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_UNAUTHORIZED), "M23 must fail")
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "self-authorization must not silently authorize the classification",
        )
    }

    @Test
    fun `M08 stays intact - an unrelated candidate classification still fails once the ceremony exists`() {
        val x = row("x")
        val y = row("y")
        val authorized = toolLimitation("x")
        val unrelated = toolLimitation("y", reason = "candidate invented")
        val diagnostics =
            verify(
                base = population(listOf(x, y)),
                candidate = population(listOf(x, y)),
                ledger =
                    Ledger(
                        baseEnrollments = enrollments(authorized),
                        candidateClassifications = classifications(authorized, unrelated),
                    ),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID), "Y must fail")
        assertEquals(
            listOf(identityOf("y", policyFamily, ":engine")),
            failures(diagnostics)
                .filter { it.code == DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID }
                .mapNotNull { it.findingId },
        )
    }

    // ---------------------------------------------------------------- M24: exact payload

    @Test
    fun `M24 classification type differs from the authorization`() {
        val x = row("x")
        val changed = classificationOf("x", classification = "equivalent-mutant", reason = "adjudicated")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger =
                        Ledger(
                            baseEnrollments = enrollments(toolLimitation("x")),
                            candidateClassifications = classifications(changed),
                        ),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_MISMATCH,
            ),
            "M24 must reject a different classification type",
        )
    }

    @Test
    fun `M24 reason issue and targetPhase must match exactly`() {
        val x = row("x")

        fun payload(
            reason: String,
            issue: String? = "I-1",
            targetPhase: String? = "0.7.1",
        ): MutationClassification =
            classificationOf(
                marker = "x",
                classification = "tool-limitation",
                reason = reason,
                issue = issue,
                targetPhase = targetPhase,
            )

        val variants =
            listOf(
                payload(reason = "B"),
                payload(reason = "A", issue = "I-2"),
                payload(reason = "A", targetPhase = "0.7.2"),
                payload(reason = "A", issue = null),
                payload(reason = "A", targetPhase = null),
            )
        variants.forEach { variant ->
            assertTrue(
                hasCode(
                    verify(
                        base = population(listOf(x)),
                        candidate = population(listOf(x)),
                        ledger =
                            Ledger(
                                baseEnrollments = enrollments(payload(reason = "A")),
                                candidateClassifications = classifications(variant),
                            ),
                    ),
                    DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_MISMATCH,
                ),
                "M24 must reject a rewritten payload: $variant",
            )
        }
    }

    // ---------------------------------------------------------------- M25: invalid targets

    @Test
    fun `M25 authorization cannot target an identity absent from the base population`() {
        val authorized = toolLimitation("fresh")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(row("x"))),
                    candidate = population(listOf(row("x"))),
                    ledger = consume(authorized),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
            ),
            "M25 must fail",
        )
    }

    @Test
    fun `M25 authorization cannot preauthorize a newly measured survivor - M06 still holds`() {
        val fresh = row("fresh")
        val authorized = toolLimitation("fresh")
        // Even with an authorization in the base, a NEW NON_KILLED identity cannot enter the authority.
        val diagnostics =
            verify(
                base = population(listOf(row("x"))),
                candidate = population(listOf(row("x"), fresh)),
                ledger = consume(authorized),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID), "M25 must fail")
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR), "M06 must still fail")
    }

    @Test
    fun `M25 authorization cannot target a KILLED identity`() {
        val killed = row("k", status = "KILLED", outcome = "KILLED")
        val authorized = toolLimitation("k")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(killed)),
                    candidate = population(listOf(killed)),
                    ledger = consume(authorized),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
            ),
            "M25 must reject a KILLED target",
        )
    }

    @Test
    fun `M25 authorization cannot target an already classified identity`() {
        val x = row("x")
        val existing = classificationOf("x", classification = "equivalent-mutant", reason = "already approved")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger =
                        Ledger(
                            baseClassifications = classifications(existing),
                            baseEnrollments = enrollments(toolLimitation("x", reason = "second approval")),
                            candidateClassifications = classifications(existing),
                        ),
                ),
                DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID,
            ),
            "M25 must reject an already-classified target",
        )
    }

    @Test
    fun `M25 duplicate authorization ids fail closed`() {
        val x = row("x")
        val diagnostics =
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger =
                    Ledger(
                        baseEnrollments =
                            enrollments(
                                toolLimitation("x", reason = "adjudicated"),
                                classificationOf("x", classification = "missing-test", reason = "contradictory"),
                            ),
                    ),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_ENROLLMENT_INVALID), "duplicates must fail")
    }

    // ---------------------------------------------------------------- M03 retained authority

    @Test
    fun `M03 an enrolled classification is retained byte-identically by later candidates`() {
        val x = row("x")
        val enrolled = toolLimitation("x")
        passes(
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = retained(enrolled),
            ),
        )
    }

    @Test
    fun `M03 an enrolled classification may not be rewritten later`() {
        val x = row("x")
        assertTrue(
            hasCode(
                verify(
                    base = population(listOf(x)),
                    candidate = population(listOf(x)),
                    ledger =
                        Ledger(
                            baseClassifications = classifications(toolLimitation("x")),
                            candidateClassifications = classifications(toolLimitation("x", reason = "reworded later")),
                        ),
                ),
                DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID,
            ),
            "M03 must reject a rewritten enrolled classification",
        )
    }

    // ---------------------------------------------------------------- the full transition proof

    @Test
    fun `ceremony transition BASE0 to BASE1 to BASE2 to CANDIDATE3`() {
        val x = row("x")
        val y = row("y")
        val authorized = toolLimitation("x", reason = "g1E adjudicated")
        val selfApprovedY = toolLimitation("y", reason = "candidate invented")
        val xId = identityOf("x", policyFamily, ":engine")

        // BASE0 -> BASE1 (authorize): candidate adds an authorization for an existing base survivor
        // and enrolls nothing.
        passes(
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = Ledger(candidateEnrollments = enrollments(authorized)),
            ),
        )

        // BASE1 -> BASE2 (consume): the base authorization exists, so the exact classification may be
        // enrolled and the authorization consumed.
        passes(
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x)),
                ledger = consume(authorized),
            ),
        )

        // BASE2 -> CANDIDATE3: X is retained byte-identically while an unrelated new classification for
        // Y is still rejected (M08).
        val diagnostics =
            verify(
                base = population(listOf(x)),
                candidate = population(listOf(x, y)),
                ledger =
                    Ledger(
                        baseClassifications = classifications(authorized),
                        candidateClassifications = classifications(authorized, selfApprovedY),
                    ),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "unrelated candidate self-classification must still fail",
        )
        assertEquals(
            listOf(identityOf("y", policyFamily, ":engine")),
            failures(diagnostics)
                .filter { it.code == DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID }
                .mapNotNull { it.findingId },
        )
        assertTrue(failures(diagnostics).none { it.findingId == xId }, "the enrolled identity must be unaffected")
    }
}
