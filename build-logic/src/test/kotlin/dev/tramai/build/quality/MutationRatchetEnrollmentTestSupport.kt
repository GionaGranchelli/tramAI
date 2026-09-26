package dev.tramai.build.quality

import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Shared fixtures for the classification enrollment ceremony suites (0.7.1g1F). No test methods live
 * here: [MutationRatchetEnrollmentCeremonyTest] covers the rules that let a base authorization enroll a
 * classification, and [MutationRatchetEnrollmentLifecycleTest] covers retention, terminal states and the
 * "a passing transition must be a valid next base" invariant.
 */
abstract class MutationRatchetEnrollmentTestSupport : MutationRatchetTestSupport() {
    @TempDir
    lateinit var tempDir: File

    /** The optional half of a transition; everything omitted keeps its pre-ceremony default. */
    protected data class Ledger(
        val baseClassifications: MutationClassifications? = null,
        val baseEnrollments: MutationClassificationEnrollments = MutationClassificationEnrollments.NONE,
        val candidateClassifications: MutationClassifications? = null,
        val candidateEnrollments: MutationClassificationEnrollments = MutationClassificationEnrollments.NONE,
    )

    protected fun enrollments(vararg records: MutationClassification): MutationClassificationEnrollments =
        MutationClassificationEnrollments(
            schemaVersion = "1",
            enrollments = records.map { MutationClassificationEnrollment(it) },
        )

    protected fun verify(
        base: MutationPopulationBaseline,
        candidate: MutationPopulationBaseline,
        ledger: Ledger = Ledger(),
    ): List<VerificationDiagnostic> =
        MutationRatchetVerifier().verify(
            MutationRatchetAuthority(
                baseSha = BASE_SHA,
                population = base,
                classifications = ledger.baseClassifications ?: classifications(),
                targetFamilies = baseFamilies,
                enrollments = ledger.baseEnrollments,
            ),
            MutationRatchetCandidate(
                population = candidate,
                classifications = ledger.candidateClassifications ?: classifications(),
                targetFamilies = baseFamilies,
                enrollments = ledger.candidateEnrollments,
            ),
            MutationPopulationAggregator.canonicalSemantics(),
        )

    protected fun consume(record: MutationClassification): Ledger =
        Ledger(baseEnrollments = enrollments(record), candidateClassifications = classifications(record))

    /**
     * The pending shape: the base authorization is carried into the candidate ledger unchanged (or, with
     * [asCarried], rewritten — which M28 must reject).
     */
    protected fun pending(
        record: MutationClassification,
        asCarried: MutationClassification = record,
    ): Ledger = Ledger(baseEnrollments = enrollments(record), candidateEnrollments = enrollments(asCarried))

    /** The retained shape: the record is already base authority on both sides of the transition. */
    protected fun retained(record: MutationClassification): Ledger =
        Ledger(baseClassifications = classifications(record), candidateClassifications = classifications(record))

    protected fun toolLimitation(
        marker: String,
        reason: String = "adjudicated",
    ): MutationClassification = classificationOf(marker, classification = "tool-limitation", reason = reason)

    /**
     * The property the two reviewed blockers violated: a transition that passes must produce an
     * authority that its own next verification accepts. The identity transition here is exactly the shape
     * CI runs, so this fails if a passing transition can poison the next base.
     */
    protected fun assertValidAsNextBase(
        population: MutationPopulationBaseline,
        classifications: MutationClassifications,
        enrollments: MutationClassificationEnrollments,
    ) {
        val next =
            MutationRatchetAuthority(
                baseSha = BASE_SHA,
                population = population,
                classifications = classifications,
                targetFamilies = baseFamilies,
                enrollments = enrollments,
            )
        passes(
            MutationRatchetVerifier().verify(
                next,
                MutationRatchetCandidate(population, classifications, baseFamilies, enrollments),
                MutationPopulationAggregator.canonicalSemantics(),
            ),
        )
    }

    protected fun writeLedger(content: String) {
        val file = File(tempDir, MutationClassificationEnrollmentLoader.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }
}
