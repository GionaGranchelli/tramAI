package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared synthetic-fixture support for the 10.3c3 ratchet discriminator
 * suites. Every row carries a REAL schema-v2 identity: the stored identity
 * equals [MutationIdentity.stableKey] recomputed over the row's own fields,
 * so the verifier's cryptographic row self-validation (M20) is exercised by
 * every fixture — a synthetic "k1" identity would silently skip the very
 * invariant the ratchet depends on.
 *
 * Abstract on purpose: JUnit must discover @Test methods only in the concrete
 * suites, never here.
 */
abstract class MutationRatchetTestSupport {
    protected val policyFamily = "policy"
    protected val retryFamily = "retry"

    protected companion object {
        const val BASE_SHA = "base-sha"
        const val MUTATOR = "org.pitest.mutationtest.engine.gregor.mutators.MathMutator"
        const val METHOD_DESCRIPTION = "()V"
        const val DESCRIPTION = "replaced int with +1"
        const val BLOCK = 1
        const val INDEX = 7

        fun identityOf(
            marker: String,
            family: String,
            module: String,
        ): String =
            MutationIdentity(
                module = module,
                className = "dev.tramai.$family.Policy",
                method = "apply_$marker",
                methodDescription = METHOD_DESCRIPTION,
                mutator = MUTATOR,
                description = DESCRIPTION,
                block = BLOCK,
                index = INDEX,
            ).stableKey()
    }

    protected val knownStatuses = setOf("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT")

    protected val policyTarget =
        MutationTargetFamily(
            modules = listOf(":engine"),
            targetClasses = listOf("dev.tramai.policy.*"),
            targetTests = listOf("dev.tramai.policy.PolicyTest"),
        )
    protected val retryTarget =
        MutationTargetFamily(
            modules = listOf(":engine"),
            targetClasses = listOf("dev.tramai.retry.*"),
            targetTests = listOf("dev.tramai.retry.RetryTest"),
        )
    protected val baseFamilies = mapOf(policyFamily to policyTarget)

    protected val semantics: MutationAnalyzerSemantics = MutationPopulationAggregator.canonicalSemantics()

    protected fun row(
        marker: String,
        family: String = policyFamily,
        status: String = "SURVIVED",
        outcome: String = "NON_KILLED",
        module: String = ":engine",
    ): MutationOutcome =
        MutationOutcome(
            identity = identityOf(marker, family, module),
            family = family,
            module = module,
            className = "dev.tramai.$family.Policy",
            method = "apply_$marker",
            methodDescription = METHOD_DESCRIPTION,
            mutator = MUTATOR,
            description = DESCRIPTION,
            block = BLOCK,
            index = INDEX,
            sourceFile = "Policy.kt",
            line = 10,
            status = status,
            outcome = outcome,
        )

    protected fun population(
        rows: List<MutationOutcome>,
        families: Map<String, MutationTargetFamily> = baseFamilies,
        measuredCommit: String = "candidate-head",
        analyzer: MutationAnalyzerSemantics = semantics,
    ): MutationPopulationBaseline {
        val killedCount = { familyRows: List<MutationOutcome> -> familyRows.count { it.status == "KILLED" } }
        return MutationPopulationBaseline(
            measuredCommit = measuredCommit,
            analyzer = analyzer,
            byFamily =
                families.keys.associateWith { family ->
                    val familyRows = rows.filter { it.family == family }
                    val killed = killedCount(familyRows)
                    MutationFamilyPopulation(
                        family = family,
                        modules = families.getValue(family).modules.sorted(),
                        totalMutants = familyRows.size,
                        killedMutants = killed,
                        survivedMutants = familyRows.count { it.status == "SURVIVED" },
                        noCoverageMutants = familyRows.count { it.status == "NO_COVERAGE" },
                        timedOutMutants = familyRows.count { it.status == "TIMED_OUT" },
                        errorMutants = familyRows.count { it.status !in knownStatuses },
                        mutationScore = if (familyRows.isEmpty()) 0.0 else 100.0 * killed / familyRows.size,
                    )
                },
            mutants = rows.sortedWith(compareBy<MutationOutcome> { it.family }.thenBy { it.identity }),
        )
    }

    protected fun classificationOf(
        marker: String,
        classification: String = "equivalent-mutant",
        reason: String = "test fixture",
        issue: String? = null,
        targetPhase: String? = null,
    ): MutationClassification =
        MutationClassification(
            id = identityOf(marker, policyFamily, ":engine"),
            classification = classification,
            reason = reason,
            issue = issue,
            targetPhase = targetPhase,
        )

    protected fun classifications(vararg records: MutationClassification): MutationClassifications =
        MutationClassifications(schemaVersion = "1", classifications = records.toList())

    protected fun approvedClassifications(vararg markers: String): MutationClassifications =
        classifications(*markers.map { classificationOf(it) }.toTypedArray())

    protected fun verify(
        basePopulation: MutationPopulationBaseline,
        baseClassifications: MutationClassifications = classifications(),
        candidatePopulation: MutationPopulationBaseline,
        candidateClassifications: MutationClassifications = classifications(),
        executable: MutationAnalyzerSemantics = MutationPopulationAggregator.canonicalSemantics(),
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, baseClassifications, baseFamilies),
            MutationRatchetCandidate(candidatePopulation, candidateClassifications, baseFamilies),
            executable,
        )

    protected fun verify(
        basePopulation: MutationPopulationBaseline,
        candidatePopulation: MutationPopulationBaseline,
        evolution: MutationPopulationEvolution,
        evolutionRecords: MutationEvolutionRecords,
        evolutionProof: MutationPopulationEvolutionProof? =
            if (evolution == MutationPopulationEvolution.RECORDED_EVOLUTION) {
                MutationPopulationEvolutionProof.exactComparison(candidatePopulation, candidatePopulation).proof
            } else {
                null
            },
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, classifications(), baseFamilies),
            MutationRatchetCandidate(candidatePopulation, classifications(), baseFamilies),
            MutationPopulationAggregator.canonicalSemantics(),
            evolution,
            MutationEvolutionEvidence(evolutionRecords, evolutionProof),
        )

    protected fun verify(
        basePopulation: MutationPopulationBaseline,
        baseFamilies: Map<String, MutationTargetFamily>,
        candidatePopulation: MutationPopulationBaseline,
        candidateFamilies: Map<String, MutationTargetFamily>,
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, classifications(), baseFamilies),
            MutationRatchetCandidate(candidatePopulation, classifications(), candidateFamilies),
            MutationPopulationAggregator.canonicalSemantics(),
        )

    protected fun failures(diagnostics: List<VerificationDiagnostic>): List<VerificationDiagnostic> =
        diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }

    /**
     * The canonical projection digest the verifier itself computes for a population (the M21
     * exact-comparison proof). Tests must never invent a digest: the admission ceremony compares
     * against this value, so using it is what proves the trust direction.
     */
    protected fun digestOf(population: MutationPopulationBaseline): String =
        MutationPopulationEvolutionProof.exactComparison(population, population).proof!!.projectionHash

    /** The verifier's own trusted measurement evidence for a population (the M21 exact comparison). */
    protected fun evidence(population: MutationPopulationBaseline): MutationEvolutionEvidence =
        evidence(population, MutationEvolutionRecords("1", emptyList()))

    protected fun evidence(
        population: MutationPopulationBaseline,
        records: MutationEvolutionRecords,
    ): MutationEvolutionEvidence =
        MutationEvolutionEvidence(
            records,
            MutationPopulationEvolutionProof.exactComparison(population, population).proof,
        )

    /**
     * A population admission for [marker]'s canonical identity. The row fields use the same identity
     * arithmetic as [population], so an authorization and the row it admits cannot silently disagree;
     * tests vary one bound field at a time with `copy(...)`.
     */
    protected fun admission(
        marker: String,
        populationDigest: String,
        fromBaseSha: String = BASE_SHA,
        status: String = "SURVIVED",
        analyzer: MutationAnalyzerSemantics = semantics,
    ): MutationPopulationAdmission =
        MutationPopulationAdmission(
            identity = identityOf(marker, policyFamily, ":engine"),
            status = status,
            outcome = "NON_KILLED",
            family = policyFamily,
            module = ":engine",
            analyzer = analyzer,
            fromBaseSha = fromBaseSha,
            populationDigest = populationDigest,
            reason = "adjudicated: admitted as part of this measured population transition",
            authorizedBy = "fixture",
            authorizedAt = "1970-01-01T00:00:00Z",
        )

    protected fun admissions(vararg records: MutationPopulationAdmission): MutationPopulationAdmissions =
        MutationPopulationAdmissions(schemaVersion = "1", admissions = records.toList())

    /**
     * Verify a population-admission transition. [freshPopulation] is the population whose canonical
     * projection the verifier trusts as its own fresh measurement - by default the candidate, which
     * is what a legal P2 commits. `null` means no trusted measurement proof exists, and admission
     * must then fail closed.
     */
    protected fun verifyAdmission(
        basePopulation: MutationPopulationBaseline,
        candidatePopulation: MutationPopulationBaseline,
        baseAdmissions: MutationPopulationAdmissions = MutationPopulationAdmissions.NONE,
        candidateAdmissions: MutationPopulationAdmissions = MutationPopulationAdmissions.NONE,
        evidence: MutationEvolutionEvidence = evidence(candidatePopulation),
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(
                BASE_SHA,
                basePopulation,
                classifications(),
                baseFamilies,
                MutationClassificationEnrollments.NONE,
                baseAdmissions,
            ),
            MutationRatchetCandidate(
                candidatePopulation,
                classifications(),
                baseFamilies,
                MutationClassificationEnrollments.NONE,
                candidateAdmissions,
            ),
            MutationPopulationAggregator.canonicalSemantics(),
            // A transition carrying a trusted measurement proof is an evolution transition; without
            // one it may not change the population at all (M21 FORBID), and admission fails closed.
            if (evidence.proof == null) {
                MutationPopulationEvolution.FORBID
            } else {
                MutationPopulationEvolution.RECORDED_EVOLUTION
            },
            evidence,
        )

    protected fun evolutionRecord(marker: String): MutationEvolutionRecord =
        MutationEvolutionRecord(
            id = identityOf(marker, policyFamily, ":engine"),
            fromBaseSha = BASE_SHA,
            reason = "source mutation removed",
            issue = "ISSUE-1",
            targetPhase = "0.7.1",
        )

    protected fun hasCode(
        diagnostics: List<VerificationDiagnostic>,
        code: DiagnosticCode,
    ): Boolean = failures(diagnostics).any { it.code == code }

    protected fun passes(diagnostics: List<VerificationDiagnostic>) {
        assertEquals(emptyList(), failures(diagnostics).map { "${it.code}: ${it.message}" })
    }

    protected fun assertFailsWith(
        diagnostics: List<VerificationDiagnostic>,
        code: DiagnosticCode,
        message: String,
    ) {
        assertTrue(hasCode(diagnostics, code), "expected $code for: $message")
        assertTrue(
            failures(diagnostics).any { it.message.contains(message) },
            "expected a diagnostic mentioning '$message' but got: ${failures(diagnostics).map { it.message }}",
        )
    }
}
