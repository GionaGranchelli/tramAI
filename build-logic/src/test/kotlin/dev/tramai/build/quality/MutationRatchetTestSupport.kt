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
        executable: MutationAnalyzerSemantics = basePopulation.analyzer,
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(BASE_SHA, basePopulation, baseClassifications, baseFamilies),
            MutationRatchetCandidate(candidatePopulation, candidateClassifications, baseFamilies),
            executable,
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
        )

    protected fun failures(diagnostics: List<VerificationDiagnostic>): List<VerificationDiagnostic> =
        diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }

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
