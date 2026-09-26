package dev.tramai.build.quality

import dev.tramai.build.quality.MutationPopulationAdmissionCeremony.AdmissionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M30-M39: the population-admission ceremony (0.7.1g1G3).
 *
 * This suite exists to prove one narrow exception to M06 and nothing wider:
 *
 * - an appearing candidate-only NON_KILLED identity is admitted **only** when the *base* ledger
 *   authorizes its exact row, under the exact analyzer semantics, for the exact canonical
 *   fresh-population digest;
 * - an unauthorized appearing survivor still fails M06, so `authorized X + unauthorized Y` fails
 *   **for Y** - the discriminator that separates "explicit authority for one adjudicated identity"
 *   from "turn M06 off for this transition";
 * - authority is temporal and base-derived: a transition can never mint the authority it consumes;
 * - the SHA binds the mint, not the consumption, so a pending authorization survives intermediate
 *   merges and is still consumed many commits later;
 * - the authorization is single-use, immutable while pending, and removable as cleanup once
 *   obsolete - but an obsolete authorization never forces an admission.
 *
 * Identities are real schema-v2 identities recomputed from the row's own fields
 * ([MutationRatchetTestSupport]), and the digest used in every fixture is the one the verifier
 * itself computes ([digestOf]), never an invented constant.
 */
class MutationPopulationAdmissionCeremonyTest : MutationRatchetTestSupport() {
    private val anchor = row("anchor", status = "KILLED", outcome = "KILLED")

    private fun accepted(diagnostics: List<VerificationDiagnostic>): Boolean =
        diagnostics.any {
            it.severity == DiagnosticSeverity.ACCEPTED &&
                it.code == DiagnosticCode.MUTATION_RATCHET_ADMISSION_ACCEPTED
        }

    // ── row 1: the admitted transition ──

    @Test
    fun `exact base authorized appearing row is admitted and M06 does not fire`() {
        val base = population(listOf(anchor))
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(candidate))),
                candidatePopulation = candidate,
                candidateAdmissions = MutationPopulationAdmissions.NONE,
            )
        passes(diagnostics)
        assertTrue(accepted(diagnostics), "expected an M30 accepted note, got: $diagnostics")
        assertFalse(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR),
            "M06 must not fire for an authorized identity",
        )
    }

    // ── row 3: M06 unchanged when nothing is authorized ──

    @Test
    fun `appearing survivor without any authorization still fails M06`() {
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                candidatePopulation = population(listOf(anchor, row("target"))),
            )
        assertFailsWith(
            diagnostics,
            DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR,
            "NEW NON_KILLED identity absent from the base authority",
        )
    }

    // ── row 2: same-transition self-authorization ──

    @Test
    fun `candidate that authorizes and admits in the same transition fails M31`() {
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions = MutationPopulationAdmissions.NONE,
                candidatePopulation = candidate,
                candidateAdmissions = admissions(admission("target", populationDigest = digestOf(candidate))),
            )
        assertFailsWith(
            diagnostics,
            DiagnosticCode.MUTATION_RATCHET_ADMISSION_UNAUTHORIZED,
            "authorized only by this transition",
        )
        assertFalse(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR),
            "the more precise M31 must replace M06 for a self-authorized identity",
        )
    }

    // ── rows 4-6: identity alone is not authority ──

    @Test
    fun `authorized identity with a different raw status fails M32`() {
        val candidate = population(listOf(anchor, row("target", status = "NO_COVERAGE")))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions =
                    admissions(
                        admission("target", status = "SURVIVED", populationDigest = digestOf(candidate)),
                    ),
                candidatePopulation = candidate,
            )
        assertFailsWith(
            diagnostics,
            DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
            "does not match the row authorized in the base",
        )
    }

    @Test
    fun `authorized row measured under different analyzer semantics fails M33`() {
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions =
                    admissions(
                        admission(
                            "target",
                            analyzer = semantics.copy(pluginVersion = "0.0.0-other"),
                            populationDigest = digestOf(candidate),
                        ),
                    ),
                candidatePopulation = candidate,
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH, "different analyzer semantics")
    }

    @Test
    fun `authorized row with a different population digest fails M34`() {
        val base = population(listOf(anchor))
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(base))),
                candidatePopulation = candidate,
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH, "complete measured population")
    }

    @Test
    fun `admission fails closed when no trusted fresh measurement proof exists`() {
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(candidate))),
                candidatePopulation = candidate,
                evidence = MutationEvolutionEvidence(),
            )
        assertFailsWith(
            diagnostics,
            DiagnosticCode.MUTATION_RATCHET_ADMISSION_MISMATCH,
            "no proof of the canonical fresh measurement",
        )
    }

    // ── row 8: THE discriminator ──

    @Test
    fun `authorized X plus unauthorized Y fails for Y and never rejects X`() {
        val base = population(listOf(anchor))
        val candidate = population(listOf(anchor, row("target"), row("other")))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(candidate))),
                candidatePopulation = candidate,
            )
        val failures = failures(diagnostics)
        assertEquals(1, failures.size, "exactly one failure expected, got: ${failures.map { it.message }}")
        assertEquals(identityOf("other", policyFamily, ":engine"), failures.single().findingId)
        assertEquals(DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR, failures.single().code)
        assertFalse(
            failures.any { it.findingId == identityOf("target", policyFamily, ":engine") },
            "X must not be rejected",
        )
    }

    // ── row 9: improvements still pass ──

    @Test
    fun `newly killed identity passes through M07`() {
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                candidatePopulation =
                    population(listOf(anchor, row("newkilled", status = "KILLED", outcome = "KILLED"))),
            )
        passes(diagnostics)
    }

    // ── the SHA table: mint-time binding, never consumption-time ──

    @Test
    fun `new authorization bound to another base SHA fails at mint`() {
        val candidate = population(listOf(anchor))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                candidatePopulation = candidate,
                candidateAdmissions =
                    admissions(
                        admission(
                            "future",
                            fromBaseSha = "0000000000000000000000000000000000000000",
                            populationDigest = digestOf(candidate),
                        ),
                    ),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `pending authorization survives intermediate merges and is consumable much later`() {
        // The authorization was minted against an older authority base; the current base is
        // several merges ahead. That is the expected state, not an error - requiring the recorded
        // SHA to track the immediate base would make delayed consumption impossible.
        val mintingSha = "1111111111111111111111111111111111111111"
        val minted =
            admission("target", fromBaseSha = mintingSha, populationDigest = digestOf(population(listOf(anchor))))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions = admissions(minted),
                candidatePopulation = population(listOf(anchor)),
                candidateAdmissions = admissions(minted),
            )
        passes(diagnostics)
    }

    @Test
    fun `rewriting only fromBaseSha while pending fails M36`() {
        val base = population(listOf(anchor))
        val minted =
            admission(
                "target",
                fromBaseSha = "1111111111111111111111111111111111111111",
                populationDigest = digestOf(base),
            )
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(minted),
                candidatePopulation = base,
                candidateAdmissions = admissions(minted.copy(fromBaseSha = BASE_SHA)),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "immutable")
    }

    // ── structural proof: complete-payload immutability, audit fields excluded ──

    @Test
    fun `every bound field independently rejects a retained rewrite and audit fields do not`() {
        val base = population(listOf(anchor))
        val minted = admission("target", populationDigest = digestOf(base))
        val rewrites =
            listOf(
                "status" to minted.copy(status = "TIMED_OUT"),
                "outcome" to minted.copy(outcome = "KILLED"),
                "family" to minted.copy(family = retryFamily),
                "module" to minted.copy(module = ":other"),
                "analyzer" to minted.copy(analyzer = minted.analyzer.copy(engineVersion = "0.0.0")),
                "fromBaseSha" to minted.copy(fromBaseSha = "3333333333333333333333333333333333333333"),
                "populationDigest" to minted.copy(populationDigest = "4".repeat(64)),
                "reason" to minted.copy(reason = "re-authored after the decision"),
                "issue" to minted.copy(issue = "ISSUE-2"),
                "targetPhase" to minted.copy(targetPhase = "0.8.0"),
            )
        for ((field, rewritten) in rewrites) {
            val diagnostics =
                verifyAdmission(
                    basePopulation = base,
                    baseAdmissions = admissions(minted),
                    candidatePopulation = base,
                    candidateAdmissions = admissions(rewritten),
                )
            assertTrue(
                hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID),
                "rewriting $field must fail",
            )
        }
        val auditOnly =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(minted),
                candidatePopulation = base,
                candidateAdmissions = admissions(minted.copy(authorizedBy = "someone-else", authorizedAt = "later")),
            )
        passes(auditOnly)
    }

    // ── lifecycle: removal, single use, obsolescence ──

    @Test
    fun `removing a pending authorization without consuming it fails M37`() {
        val base = population(listOf(anchor))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(base))),
                candidatePopulation = base,
                candidateAdmissions = MutationPopulationAdmissions.NONE,
            )
        assertFailsWith(
            diagnostics,
            DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID,
            "removed without being consumed",
        )
    }

    @Test
    fun `retaining a consumed authorization fails M38`() {
        val candidate = population(listOf(anchor, row("target")))
        val minted = admission("target", populationDigest = digestOf(candidate))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor)),
                baseAdmissions = admissions(minted),
                candidatePopulation = candidate,
                candidateAdmissions = admissions(minted),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_RETAINED, "single-use")
        assertTrue(accepted(diagnostics), "the row itself is still authorized; only the retention is wrong")
    }

    @Test
    fun `obsolete authorization whose target is now killed can be cleaned up`() {
        val base = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(base))),
                candidatePopulation = population(listOf(anchor, row("target", status = "KILLED", outcome = "KILLED"))),
                candidateAdmissions = MutationPopulationAdmissions.NONE,
            )
        passes(diagnostics)
    }

    @Test
    fun `retained obsolete authorization warns and admits nothing`() {
        val base = population(listOf(anchor, row("target")))
        val minted = admission("target", populationDigest = digestOf(base))
        val diagnostics =
            verifyAdmission(
                basePopulation = base,
                baseAdmissions = admissions(minted),
                candidatePopulation = population(listOf(anchor, row("target", status = "KILLED", outcome = "KILLED"))),
                candidateAdmissions = admissions(minted),
            )
        passes(diagnostics)
        assertTrue(
            diagnostics.any { it.severity == DiagnosticSeverity.WARNING && it.message.contains("M39") },
            "expected an M39 cleanup advisory, got: $diagnostics",
        )
        assertFalse(accepted(diagnostics), "an obsolete authorization must never force an admission")
    }

    // ── structural proof: next-base invariant ──

    @Test
    fun `a passing mint and a passing consumption are each valid as the next base`() {
        val p1Base = population(listOf(anchor))
        val target = row("target")
        val p2Population = population(listOf(anchor, target))
        val minted = admission("target", populationDigest = digestOf(p2Population))

        val mintDiagnostics =
            verifyAdmission(
                basePopulation = p1Base,
                baseAdmissions = MutationPopulationAdmissions.NONE,
                candidatePopulation = p1Base,
                candidateAdmissions = admissions(minted),
            )
        passes(mintDiagnostics)

        val consumeDiagnostics =
            verifyAdmission(
                basePopulation = p1Base,
                baseAdmissions = admissions(minted),
                candidatePopulation = p2Population,
                candidateAdmissions = MutationPopulationAdmissions.NONE,
            )
        passes(consumeDiagnostics)

        // The consumed state must itself be a legal base: same populations, ledger empty.
        val quietDiagnostics =
            verifyAdmission(
                basePopulation = p2Population,
                candidatePopulation = p2Population,
            )
        passes(quietDiagnostics)
    }

    // ── row 11: composition with the existing M21 removal authority ──

    @Test
    fun `a valid M21 removal and a valid admission compose in one transition`() {
        val doomed = row("doomed")
        val candidate = population(listOf(anchor, row("target")))
        val diagnostics =
            verifyAdmission(
                basePopulation = population(listOf(anchor, doomed)),
                baseAdmissions = admissions(admission("target", populationDigest = digestOf(candidate))),
                candidatePopulation = candidate,
                candidateAdmissions = MutationPopulationAdmissions.NONE,
                evidence = evidence(candidate, MutationEvolutionRecords("1", listOf(evolutionRecord("doomed")))),
            )
        passes(diagnostics)
    }

    // ── the shared verdict is public API of the ceremony: a null authorization is M06, verbatim ──

    @Test
    fun `appearance verdict keeps the M06 wording for an unauthorized identity`() {
        val verdict =
            MutationPopulationAdmissionCeremony.appearanceVerdict(
                baseAdmission = null,
                candidateAdmission = null,
                mutant = row("target"),
                candidateAnalyzer = semantics,
                freshProjectionHash = "0".repeat(64),
            )
        val rejected = verdict as? AdmissionVerdict.Rejected
        assertNotNull(rejected)
        assertEquals(DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR, rejected.code)
        assertTrue(rejected.message.startsWith("M06:"))
    }
}
