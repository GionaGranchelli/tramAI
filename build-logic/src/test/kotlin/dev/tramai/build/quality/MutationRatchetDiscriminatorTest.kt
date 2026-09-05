package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 10.3c3 discriminator matrix M01-M20 (outcome, scope and semantics half) for
 * the base-authoritative mutation ratchet. Classification-authority rules
 * (M03-M05, M08-M11) live in [MutationRatchetClassificationDiscriminatorTest].
 * All tests use small synthetic populations and classifications — NONE of
 * them runs PITest.
 */
class MutationRatchetDiscriminatorTest : MutationRatchetTestSupport() {
    // ── M01 / M02: killed-mutant regression vs stability ──

    @Test
    fun `M01 base KILLED to candidate NON_KILLED fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_REGRESSION), "M01 must fail")
        assertTrue(failures(diagnostics).any { it.message.contains("apply_k1") })
    }

    @Test
    fun `M02 base KILLED to candidate KILLED passes`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        passes(verify(basePopulation = base, candidatePopulation = candidate))
    }

    // ── M06 / M07: new identities ──

    @Test
    fun `M06 new NON_KILLED identity fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("n1"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR), "new survivor must fail")
        assertTrue(failures(diagnostics).any { it.message.contains("apply_n1") })
    }

    @Test
    fun `M07 new KILLED identity passes`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("k2", status = "KILLED", outcome = "KILLED"),
                ),
            )
        passes(verify(basePopulation = base, candidatePopulation = candidate))
    }

    @Test
    fun `M07 forged kill with raw SURVIVED status fails closed`() {
        // A hand-edited candidate cannot store status=SURVIVED with
        // outcome=KILLED to sneak a new mutant past M07.
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("k2", status = "SURVIVED", outcome = "KILLED"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME, "contradicts the canonical")
    }

    @Test
    fun `M13 unknown raw status fails closed`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k2", status = "NON_VIABLE", outcome = "NON_KILLED")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME, "cannot be canonicalized")
    }

    @Test
    fun `M20 forged identity fails closed`() {
        // A row whose stored identity does not equal the SHA-256 over its own
        // fields cannot be ratcheted: the identity is the trust anchor.
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED").copy(identity = "0".repeat(64))),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID, "self-inconsistent")
    }

    // ── M12 / M13: structural integrity, fail closed ──

    @Test
    fun `M12 duplicate identity fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("k1", status = "KILLED", outcome = "KILLED"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_DUPLICATE_IDENTITY), "duplicate must fail")
    }

    @Test
    fun `M13 non-canonical outcome fails closed`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1", outcome = "SURVIVED")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_UNKNOWN_OUTCOME),
            "non-canonical outcome must fail closed",
        )
    }

    // ── M14 / M15: measured-scope narrowing ──

    @Test
    fun `M14 family narrowing fails`() {
        val twoFamilyTargets = mapOf(policyFamily to policyTarget, retryFamily to retryTarget)
        val base =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("k2", family = retryFamily, status = "KILLED", outcome = "KILLED"),
                ),
                families = twoFamilyTargets,
            )
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                families = baseFamilies,
            )
        val diagnostics =
            verify(
                basePopulation = base,
                baseFamilies = twoFamilyTargets,
                candidatePopulation = candidate,
                candidateFamilies = baseFamilies,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_FAMILY_NARROWING),
            "dropping a base family must fail",
        )
    }

    @Test
    fun `M14 module narrowing inside a family fails`() {
        val wideTarget = policyTarget.copy(modules = listOf(":engine", ":security"))
        val base =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")), families = baseFamilies)
        val diagnostics =
            verify(
                basePopulation = base,
                baseFamilies = mapOf(policyFamily to wideTarget),
                candidatePopulation = candidate,
                candidateFamilies = baseFamilies,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_FAMILY_NARROWING),
            "removing a module from a family must fail",
        )
    }

    @Test
    fun `M15 target-class narrowing fails`() {
        val wideTarget =
            policyTarget.copy(targetClasses = listOf("dev.tramai.policy.*", "dev.tramai.policy.deep.*"))
        val base =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseFamilies = mapOf(policyFamily to wideTarget),
                candidatePopulation = candidate,
                candidateFamilies = baseFamilies,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT),
            "target-class narrowing must fail",
        )
    }

    @Test
    fun `M15 target-test narrowing fails`() {
        val wideTarget =
            policyTarget.copy(targetTests = listOf("dev.tramai.policy.PolicyTest", "dev.tramai.policy.PolicyDeepTest"))
        val base =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseFamilies = mapOf(policyFamily to wideTarget),
                candidatePopulation = candidate,
                candidateFamilies = baseFamilies,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_TARGET_DRIFT),
            "target-test narrowing must fail",
        )
    }

    // ── M16 / M17 / M18: analyzer semantics drift ──

    @Test
    fun `M16 plugin engine semantics drift fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                analyzer = semantics.copy(pluginVersion = "9.9.9"),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "PIT version drift must fail")
    }

    @Test
    fun `M17 mutator drift fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                analyzer = semantics.copy(mutators = semantics.mutators + "EXTRA_MUTATOR"),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "mutator drift must fail")
    }

    @Test
    fun `M18 timeout drift fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
                analyzer = semantics.copy(timeoutConst = 9_999),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "timeout drift must fail")
    }

    @Test
    fun `M16 executable PIT renderer drift fails even when metadata matches base`() {
        // Blocker 3: base == candidate metadata, but the executable renderer
        // (MutationProbeInitScript) drifted -> the gate must still fail.
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                candidatePopulation = candidate,
                executable = semantics.copy(engineVersion = "9.9.9"),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT, "executable PIT renderer")
    }

    // ── M19 / M20: schema and input integrity ──

    @Test
    fun `M19 identity schema drift fails`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(listOf(row("k1", status = "KILLED", outcome = "KILLED"))).copy(identitySchemaVersion = "3")
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SCHEMA_DRIFT),
            "identity-schema drift must fail",
        )
    }

    @Test
    fun `M20 empty authority fails closed`() {
        val base = population(rows = emptyList())
        val candidate = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "an empty base population must fail closed, never vacate the authority",
        )
    }

    @Test
    fun `M20 blank identity row fails closed`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED").copy(identity = ""),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "a malformed row with blank identity must fail closed",
        )
    }

    @Test
    fun `M20 vacuous configured family fails closed`() {
        // Candidate retains the configured family in its target config but
        // carries zero rows for it — byFamily must never go vacuous.
        val twoFamilyTargets = mapOf(policyFamily to policyTarget, retryFamily to retryTarget)
        val base =
            population(
                listOf(
                    row("k1", status = "KILLED", outcome = "KILLED"),
                    row("k2", family = retryFamily, status = "KILLED", outcome = "KILLED"),
                ),
                families = twoFamilyTargets,
            )
        val candidate =
            population(listOf(row("k1", status = "KILLED", outcome = "KILLED")), families = twoFamilyTargets)
        val diagnostics =
            verify(
                basePopulation = base,
                baseFamilies = twoFamilyTargets,
                candidatePopulation = candidate,
                candidateFamilies = twoFamilyTargets,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "a configured family emptied of rows must fail closed",
        )
        assertTrue(failures(diagnostics).any { it.message.contains("vacuous") })
    }

    @Test
    fun `M20 tampered byFamily metrics fail closed`() {
        val base = population(listOf(row("k1", status = "KILLED", outcome = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", status = "KILLED", outcome = "KILLED")),
            ).copy(
                byFamily =
                    mapOf(
                        policyFamily to
                            MutationFamilyPopulation(
                                family = policyFamily,
                                modules = listOf(":engine"),
                                totalMutants = 1,
                                killedMutants = 999,
                                survivedMutants = 0,
                                noCoverageMutants = 0,
                                timedOutMutants = 0,
                                errorMutants = 0,
                                mutationScore = 999.0,
                            ),
                    ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID, "byFamily")
    }

    // ── master identity: the committed authority must pass its own ratchet ──

    @Test
    fun `identical base and candidate authorities pass`() {
        val rows =
            listOf(
                row("s1"),
                row("k1", status = "KILLED", outcome = "KILLED"),
                row("u1"),
            )
        val base = population(rows, measuredCommit = "base")
        val candidate = population(rows, measuredCommit = "candidate-head")
        passes(
            verify(
                basePopulation = base,
                baseClassifications = approvedClassifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = approvedClassifications("s1"),
            ),
        )
    }
}
