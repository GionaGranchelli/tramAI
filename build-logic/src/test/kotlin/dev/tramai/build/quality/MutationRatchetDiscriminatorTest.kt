package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 10.3c3 discriminator matrix M01-M20 for the base-authoritative mutation
 * ratchet, plus the critical self-approval test. All tests use small
 * synthetic populations and classifications — NONE of them runs PITest.
 *
 * The verifier is pure and typed: base authority and candidate are both built
 * in memory, so every rule is proven without touching git, files, or Gradle.
 */
class MutationRatchetDiscriminatorTest {
    private val policyFamily = "policy"
    private val retryFamily = "retry"

    private companion object {
        const val BASE_SHA = "base-sha"
    }

    private val policyTarget =
        MutationTargetFamily(
            modules = listOf(":engine"),
            targetClasses = listOf("dev.tramai.policy.*"),
            targetTests = listOf("dev.tramai.policy.PolicyTest"),
        )
    private val retryTarget =
        MutationTargetFamily(
            modules = listOf(":engine"),
            targetClasses = listOf("dev.tramai.retry.*"),
            targetTests = listOf("dev.tramai.retry.RetryTest"),
        )
    private val baseFamilies = mapOf(policyFamily to policyTarget)

    private val semantics =
        MutationAnalyzerSemantics(
            pluginVersion = MutationProbeInitScript.PIT_PLUGIN_VERSION,
            engineVersion = MutationProbeInitScript.PIT_ENGINE_VERSION,
            mutators = MutationProbeInitScript.PIT_MUTATORS,
            timeoutConst = MutationProbeInitScript.TIMEOUT_CONST_MILLIS.toInt(),
            timeoutFactor = MutationProbeInitScript.TIMEOUT_FACTOR,
        )

    private val mutator = "org.pitest.mutationtest.engine.gregor.mutators.MathMutator"

    private fun row(
        id: String,
        family: String = policyFamily,
        outcome: String = "NON_KILLED",
        status: String = "SURVIVED",
        module: String = ":engine",
    ): MutationOutcome =
        MutationOutcome(
            identity = id,
            family = family,
            module = module,
            className = "dev.tramai.$family.Policy",
            method = "apply",
            methodDescription = "()V",
            mutator = mutator,
            description = "replaced int with +1",
            block = 1,
            index = 7,
            sourceFile = "Policy.kt",
            line = 10,
            status = status,
            outcome = outcome,
        )

    private fun population(
        rows: List<MutationOutcome>,
        families: Map<String, MutationTargetFamily> = baseFamilies,
        measuredCommit: String = "candidate-head",
        analyzer: MutationAnalyzerSemantics = semantics,
    ): MutationPopulationBaseline =
        MutationPopulationBaseline(
            measuredCommit = measuredCommit,
            analyzer = analyzer,
            byFamily =
                families.keys.associateWith { family ->
                    val familyRows = rows.filter { it.family == family }
                    MutationFamilyPopulation(
                        family = family,
                        modules = families.getValue(family).modules.sorted(),
                        totalMutants = familyRows.size,
                        killedMutants = familyRows.count { it.outcome == "KILLED" },
                        survivedMutants = familyRows.count { it.status == "SURVIVED" },
                        noCoverageMutants = familyRows.count { it.status == "NO_COVERAGE" },
                        timedOutMutants = familyRows.count { it.status == "TIMED_OUT" },
                        errorMutants = 0,
                        mutationScore =
                            if (familyRows.isEmpty()) {
                                0.0
                            } else {
                                100.0 * familyRows.count { it.outcome == "KILLED" } / familyRows.size
                            },
                    )
                },
            mutants = rows.sortedWith(compareBy<MutationOutcome> { it.family }.thenBy { it.identity }),
        )

    private fun classifications(vararg ids: String): MutationClassifications =
        MutationClassifications(
            schemaVersion = "1",
            classifications =
                ids.map { id ->
                    MutationClassification(
                        id = id,
                        classification = "equivalent-mutant",
                        reason = "test fixture",
                    )
                },
        )

    private fun verify(
        basePopulation: MutationPopulationBaseline,
        baseClassifications: MutationClassifications = classifications(),
        candidatePopulation: MutationPopulationBaseline,
        candidateClassifications: MutationClassifications = classifications(),
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, baseClassifications, baseFamilies),
            MutationRatchetCandidate(candidatePopulation, candidateClassifications, baseFamilies),
        )

    private fun verify(
        basePopulation: MutationPopulationBaseline,
        baseFamilies: Map<String, MutationTargetFamily>,
        candidatePopulation: MutationPopulationBaseline,
        candidateFamilies: Map<String, MutationTargetFamily>,
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, classifications(), baseFamilies),
            MutationRatchetCandidate(candidatePopulation, classifications(), candidateFamilies),
        )

    private fun failures(diagnostics: List<VerificationDiagnostic>): List<VerificationDiagnostic> =
        diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }

    private fun hasCode(
        diagnostics: List<VerificationDiagnostic>,
        code: DiagnosticCode,
    ): Boolean = failures(diagnostics).any { it.code == code }

    private fun passes(diagnostics: List<VerificationDiagnostic>) {
        assertEquals(emptyList(), failures(diagnostics).map { "${it.code}: ${it.message}" })
    }

    // ── M01 / M02: killed-mutant regression vs stability ──

    @Test
    fun `M01 base KILLED to candidate NON_KILLED fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate = population(listOf(row("k1", outcome = "NON_KILLED")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_REGRESSION), "M01 must fail")
    }

    @Test
    fun `M02 base KILLED to candidate KILLED passes`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        passes(verify(basePopulation = base, candidatePopulation = candidate))
    }

    // ── M03 / M04 / M05: approved survivors ──

    @Test
    fun `M03 approved survivor remaining NON_KILLED passes`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications("s1"),
            ),
        )
    }

    @Test
    fun `M04 approved survivor killed with classification removed passes`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1", outcome = "KILLED", status = "KILLED")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(),
            ),
        )
    }

    @Test
    fun `M05 approved survivor killed with stale classification fails`() {
        val base = population(listOf(row("s1")))
        val candidate = population(listOf(row("s1", outcome = "KILLED", status = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications("s1"),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "stale classification on a now-killed mutant must fail",
        )
    }

    // ── M06 / M07: new identities ──

    @Test
    fun `M06 new NON_KILLED identity fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", outcome = "KILLED", status = "KILLED"),
                    row("n1"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR), "new survivor must fail")
        assertTrue(failures(diagnostics).any { it.message.contains("n1") })
    }

    @Test
    fun `M07 new KILLED identity passes`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", outcome = "KILLED", status = "KILLED"),
                    row("k2", outcome = "KILLED", status = "KILLED"),
                ),
            )
        passes(verify(basePopulation = base, candidatePopulation = candidate))
    }

    // ── M08 / M09 / M10 / M11: classification authority ──

    @Test
    fun `M08 candidate self-classification of a new survivor fails`() {
        // THE critical test: BASE: A absent; CANDIDATE: A = NON_KILLED and
        // classification includes A => FAIL. A PR cannot approve its own
        // survivor by classifying it.
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", outcome = "KILLED", status = "KILLED"),
                    row("a", outcome = "NON_KILLED"),
                ),
            )
        val diagnostics =
            verify(
                basePopulation = base,
                candidatePopulation = candidate,
                candidateClassifications = classifications("a"),
            )
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_NEW_SURVIVOR))
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "self-classification must itself be rejected",
        )
        assertTrue(failures(diagnostics).any { it.message.contains("a") })
    }

    @Test
    fun `M09 fabricated classification fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                candidatePopulation = candidate,
                candidateClassifications = classifications("never-measured"),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_INVALID),
            "fabricated classification must fail",
        )
        assertTrue(failures(diagnostics).any { it.message.contains("fabricated") })
    }

    @Test
    fun `M10 disappeared mutant with retained classification fails`() {
        val base =
            population(
                listOf(
                    row("s1"),
                    row("k1", outcome = "KILLED", status = "KILLED"),
                ),
            )
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications("s1"),
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
                    row("k1", outcome = "KILLED", status = "KILLED"),
                ),
            )
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        passes(
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
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
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications(),
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_CLASSIFICATION_REMOVED),
            "removing an approval while its survivor remains must fail",
        )
    }

    // ── M12 / M13: structural integrity, fail closed ──

    @Test
    fun `M12 duplicate identity fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("k1", outcome = "KILLED", status = "KILLED"),
                    row("k1", outcome = "KILLED", status = "KILLED"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_DUPLICATE_IDENTITY), "duplicate must fail")
    }

    @Test
    fun `M13 unknown outcome fails closed`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
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
                    row("k1", outcome = "KILLED", status = "KILLED"),
                    row("k2", family = retryFamily, outcome = "KILLED", status = "KILLED"),
                ),
                families = twoFamilyTargets,
            )
        val candidate =
            population(
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
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
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")), families = baseFamilies)
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
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
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
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                families = mapOf(policyFamily to wideTarget),
            )
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
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
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                analyzer = semantics.copy(pluginVersion = "9.9.9"),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "PIT version drift must fail")
    }

    @Test
    fun `M17 mutator drift fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                analyzer = semantics.copy(mutators = semantics.mutators + "EXTRA_MUTATOR"),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "mutator drift must fail")
    }

    @Test
    fun `M18 timeout drift fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(row("k1", outcome = "KILLED", status = "KILLED")),
                analyzer = semantics.copy(timeoutConst = 9_999),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SEMANTICS_DRIFT), "timeout drift must fail")
    }

    // ── M19 / M20: schema and input integrity ──

    @Test
    fun `M19 identity schema drift fails`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(listOf(row("k1", outcome = "KILLED", status = "KILLED"))).copy(identitySchemaVersion = "3")
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_SCHEMA_DRIFT),
            "identity-schema drift must fail",
        )
    }

    @Test
    fun `M20 empty authority fails closed`() {
        val base = population(rows = emptyList())
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "an empty base population must fail closed, never vacate the authority",
        )
    }

    @Test
    fun `M20 blank identity row fails closed`() {
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate =
            population(
                listOf(
                    row("", outcome = "KILLED", status = "KILLED"),
                    row("k1", outcome = "KILLED", status = "KILLED"),
                ),
            )
        val diagnostics = verify(basePopulation = base, candidatePopulation = candidate)
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "a malformed row with blank identity must fail closed",
        )
    }

    @Test
    fun `M20 self-inconsistent base classification fails closed`() {
        // Base classification referencing a KILLED mutant is an authority defect.
        val base = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val candidate = population(listOf(row("k1", outcome = "KILLED", status = "KILLED")))
        val diagnostics =
            verify(
                basePopulation = base,
                baseClassifications = classifications("k1"),
                candidatePopulation = candidate,
            )
        assertTrue(
            hasCode(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID),
            "a base classification over a killed mutant is not an approval source",
        )
    }

    // ── master identity: the committed authority must pass its own ratchet ──

    @Test
    fun `identical base and candidate authorities pass`() {
        val rows =
            listOf(
                row("s1"),
                row("k1", outcome = "KILLED", status = "KILLED"),
                row("u1"),
            )
        val base = population(rows, measuredCommit = "base")
        val candidate = population(rows, measuredCommit = "candidate-head")
        passes(
            verify(
                basePopulation = base,
                baseClassifications = classifications("s1"),
                candidatePopulation = candidate,
                candidateClassifications = classifications("s1"),
            ),
        )
    }
}
