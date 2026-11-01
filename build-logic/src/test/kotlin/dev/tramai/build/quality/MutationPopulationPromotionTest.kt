package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * TASK-0.7.1i: a promotion declaration carries an ALREADY-CERTIFIED evidence population across a
 * release promotion boundary.
 *
 * M35 and M45 bind a newly introduced admission/certificate to the authority base it was minted
 * against. A promotion cannot satisfy that binding legitimately - re-minting admissions, rewriting
 * `fromBaseSha` and manufacturing replacement certificates are all forbidden - so it needs exactly
 * one statement: this evidence was not minted here, it is the same certified population, carried
 * across unchanged.
 *
 * These tests are the negative proofs. Each one changes a single field of an otherwise exact
 * declaration, or removes it, and every one of them must still fail.
 */
class MutationPopulationPromotionTest : MutationRatchetTestSupport() {
    private companion object {
        const val CARRIED_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val MINTING_SHA = "1111111111111111111111111111111111111111"
        const val CERTIFICATE_MINTING_SHA = "2222222222222222222222222222222222222222"
        const val OTHER_BASE_SHA = "ffffffffffffffffffffffffffffffffffffffff"
    }

    /** The exact declaration for the fixture below: one carried admission, no certificates. */
    private fun declaration(
        promotionBase: String = BASE_SHA,
        populationDigest: String = CARRIED_DIGEST,
        admissions: Int = 1,
        certificates: Int = 0,
    ) = MutationPopulationPromotion(
        promotionBase = promotionBase,
        populationDigest = populationDigest,
        admissions = admissions,
        certificates = certificates,
    )

    /** An admission minted on an earlier line: it does not bind the base this transition sees. */
    private fun carriedAdmission(digest: String = CARRIED_DIGEST) =
        admission("appearing", populationDigest = digest, fromBaseSha = MINTING_SHA)

    private fun certificate(fromDigest: String = CARRIED_DIGEST) =
        MutationAuthorityDigestCertificate(
            fromAlgorithm = "raw-v1",
            fromDigest = fromDigest,
            toAlgorithm = "authority-v2",
            toDigest = OTHER_DIGEST,
            admissionSetDigest = OTHER_DIGEST,
            fromBaseSha = CERTIFICATE_MINTING_SHA,
            reason = "fixture",
        )

    private fun certificates(vararg records: MutationAuthorityDigestCertificate) =
        MutationAuthorityDigestCertificates(schemaVersion = "1", certificates = records.toList())

    /**
     * The transition under test. Populations are identical across base and candidate so M06 is not
     * in play: the only question is whether the ledger's mint binding still fires.
     */
    private fun verify(
        candidateAdmissions: MutationPopulationAdmissions = noAdmissions,
        candidateCertificates: MutationAuthorityDigestCertificates = MutationAuthorityDigestCertificates.NONE,
        promotion: MutationPopulationPromotion? = null,
    ): List<VerificationDiagnostic> {
        val shared = population(listOf(row("anchor")))
        return MutationRatchetVerifier().verify(
            MutationRatchetAuthority(
                BASE_SHA,
                shared,
                classifications(),
                baseFamilies,
                MutationClassificationEnrollments.NONE,
                noAdmissions,
            ),
            MutationRatchetCandidate(
                shared,
                classifications(),
                baseFamilies,
                MutationClassificationEnrollments.NONE,
                candidateAdmissions,
                candidateCertificates,
                promotion,
            ),
            MutationPopulationAggregator.canonicalSemantics(),
            MutationPopulationEvolution.RECORDED_EVOLUTION,
            evidence(shared),
        )
    }

    // ── the exact declaration is the only thing that carries ──

    @Test
    fun `an exact declaration carries an admission minted on the promotion line`() {
        passes(verify(candidateAdmissions = admissions(carriedAdmission()), promotion = declaration()))
    }

    @Test
    fun `an exact declaration carries the digest-migration certificate`() {
        passes(
            verify(
                candidateCertificates = certificates(certificate()),
                promotion = declaration(admissions = 0, certificates = 1),
            ),
        )
    }

    // ── every other declaration carries nothing ──

    @Test
    fun `no declaration leaves the mint binding in force`() {
        val diagnostics = verify(candidateAdmissions = admissions(carriedAdmission()))
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a declaration for another promotion base carries nothing`() {
        val diagnostics =
            verify(
                candidateAdmissions = admissions(carriedAdmission()),
                promotion = declaration(promotionBase = OTHER_BASE_SHA),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a declaration naming another population digest carries nothing`() {
        val diagnostics =
            verify(
                candidateAdmissions = admissions(carriedAdmission()),
                promotion = declaration(populationDigest = OTHER_DIGEST),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a declaration whose admission count does not describe the carried set carries nothing`() {
        // The real promotion names 67; 68 is the off-by-one this rule exists to reject, and the same
        // mismatch one row up or down is rejected here.
        val tooMany =
            verify(
                candidateAdmissions = admissions(carriedAdmission()),
                promotion = declaration(admissions = 2),
            )
        assertFailsWith(tooMany, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
        val tooFew =
            verify(
                candidateAdmissions = admissions(carriedAdmission()),
                promotion = declaration(admissions = 0),
            )
        assertFailsWith(tooFew, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a declaration whose certificate count does not describe the carried set carries nothing`() {
        // One certificate present, declaration says zero: not carried, so M45 still fires.
        val zero =
            verify(
                candidateCertificates = certificates(certificate()),
                promotion = declaration(admissions = 0, certificates = 0),
            )
        assertFailsWith(zero, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID, "M45")
        // No certificate present, declaration says two: the whole declaration is void, so the
        // admission it names is not carried either.
        val two =
            verify(
                candidateAdmissions = admissions(carriedAdmission()),
                promotion = declaration(admissions = 1, certificates = 2),
            )
        assertFailsWith(two, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a newly minted admission carrying another digest is not carried`() {
        // The declaration names the certified population; a row whose digest is not that population
        // was minted here and is bound to this base like any other new authorization.
        val diagnostics =
            verify(
                candidateAdmissions = admissions(carriedAdmission(digest = OTHER_DIGEST)),
                promotion = declaration(),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_ADMISSION_INVALID, "fromBaseSha")
    }

    @Test
    fun `a newly introduced certificate carrying another digest is not carried`() {
        val diagnostics =
            verify(
                candidateCertificates = certificates(certificate(fromDigest = OTHER_DIGEST)),
                promotion = declaration(admissions = 0, certificates = 1),
            )
        assertFailsWith(diagnostics, DiagnosticCode.MUTATION_RATCHET_AUTHORITY_INVALID, "M45")
    }

    // ── the declaration itself: fail-closed parsing ──

    @Test
    fun `an absent declaration file is no promotion`() {
        val root = Files.createTempDirectory("promotion-absent").toFile()
        assertEquals(null, MutationPopulationPromotionLoader.load(root))
    }

    @Test
    fun `a declaration of the wrong shape is a hard failure, not an absent promotion`() {
        // A malformed ledger must not silently degrade into "no promotion was needed": the counts are
        // part of the declaration's meaning, so a missing one is invalid rather than zero. Structural
        // faults surface as GradleException and canonicality faults as IllegalArgumentException, the
        // same split the population-admissions loader uses - both are hard build failures.
        val missingCount =
            ledger(
                """
                schemaVersion: "1"
                promotions:
                  - promotionBase: "$MINTING_SHA"
                    populationDigest: "$CARRIED_DIGEST"
                    admissions: 67
                """.trimIndent(),
            )
        assertThrows<GradleException> { MutationPopulationPromotionLoader.load(missingCount) }

        val badDigest =
            ledger(
                """
                schemaVersion: "1"
                promotions:
                  - promotionBase: "$MINTING_SHA"
                    populationDigest: "not-a-digest"
                    admissions: 67
                    certificates: 1
                """.trimIndent(),
            )
        assertThrows<IllegalArgumentException> { MutationPopulationPromotionLoader.load(badDigest) }
    }

    @Test
    fun `a well-formed declaration loads exactly what it states`() {
        val root =
            ledger(
                """
                schemaVersion: "1"
                promotions:
                  - promotionBase: "$MINTING_SHA"
                    populationDigest: "$CARRIED_DIGEST"
                    admissions: 67
                    certificates: 1
                """.trimIndent(),
            )
        assertEquals(
            MutationPopulationPromotion(
                promotionBase = MINTING_SHA,
                populationDigest = CARRIED_DIGEST,
                admissions = 67,
                certificates = 1,
            ),
            MutationPopulationPromotionLoader.load(root),
        )
    }

    private fun ledger(contents: String): File {
        val root = Files.createTempDirectory("promotion-ledger").toFile()
        val file = File(root, MutationPopulationPromotionLoader.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText(contents)
        return root
    }
}
