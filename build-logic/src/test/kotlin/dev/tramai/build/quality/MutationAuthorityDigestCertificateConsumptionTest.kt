package dev.tramai.build.quality

import dev.tramai.build.quality.MutationAuthorityDigestCertificates.Companion.ALGORITHM_AUTHORITY_V2
import dev.tramai.build.quality.MutationAuthorityDigestCertificates.Companion.ALGORITHM_RAW_V1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T14, T15, T16 and T19 at the rule level: certificate consumption establishes a positive fact, and
 * the lifecycle rules act on that fact rather than on a certificate's disappearance.
 */
class MutationAuthorityDigestCertificateConsumptionTest {
    private val raw = "1".repeat(64)
    private val authority = "2".repeat(64)
    private val baseSha = "a".repeat(40)
    private val identities = listOf("id-a", "id-b")
    private val setDigest = MutationAuthorityDigestCertificates.admissionSetDigest(identities)

    private fun certificate(
        fromDigest: String = raw,
        toDigest: String = authority,
        fromAlgorithm: String = ALGORITHM_RAW_V1,
        toAlgorithm: String = ALGORITHM_AUTHORITY_V2,
        admissionSetDigest: String = setDigest,
    ) = MutationAuthorityDigestCertificate(
        fromAlgorithm = fromAlgorithm,
        fromDigest = fromDigest,
        toAlgorithm = toAlgorithm,
        toDigest = toDigest,
        admissionSetDigest = admissionSetDigest,
        fromBaseSha = baseSha,
        reason = "test certificate",
    )

    private fun ledger(vararg certificates: MutationAuthorityDigestCertificate) =
        MutationAuthorityDigestCertificates(schemaVersion = "1", certificates = certificates.toList())

    private fun verify(
        certificate: MutationAuthorityDigestCertificate = certificate(),
        fromBase: Boolean = true,
        freshAuthorityProjectionHash: String = authority,
        citedPopulationDigest: String = raw,
        base: List<String> = identities,
    ) = MutationAuthorityDigestCertificateConsumption.verify(
        certificate = certificate,
        certificateFromBase = fromBase,
        freshAuthorityProjectionHash = freshAuthorityProjectionHash,
        citedPopulationDigest = citedPopulationDigest,
        baseAdmissionIdentities = base,
    )

    @Test
    fun `a certificate satisfying every predicate establishes the consumption fact`() {
        assertNull(verify())
    }

    @Test
    fun `M43 a candidate-minted certificate cannot enter the consumption fact set`() {
        val diagnostic = assertNotNull(verify(fromBase = false))

        assertTrue(diagnostic.message.contains("M43"), diagnostic.message)
        assertEquals(raw, diagnostic.findingId)
    }

    @Test
    fun `M40 a certificate whose target digest is not this measurement's projection is refused`() {
        val diagnostic = assertNotNull(verify(freshAuthorityProjectionHash = "3".repeat(64)))

        assertTrue(diagnostic.message.contains("M40"), diagnostic.message)
    }

    @Test
    fun `M41 a certificate for a different source digest than the cited admission is refused`() {
        val diagnostic = assertNotNull(verify(certificate = certificate(fromDigest = "4".repeat(64))))

        assertTrue(diagnostic.message.contains("M41"), diagnostic.message)
    }

    @Test
    fun `M41 a certificate declaring a different migration pair is refused`() {
        val diagnostic =
            assertNotNull(verify(certificate = certificate(fromAlgorithm = ALGORITHM_AUTHORITY_V2)))

        assertTrue(diagnostic.message.contains("M41"), diagnostic.message)
    }

    @Test
    fun `M42 a certificate bounding a different admission set is refused`() {
        val diagnostic =
            assertNotNull(verify(certificate = certificate(admissionSetDigest = "5".repeat(64))))

        assertTrue(diagnostic.message.contains("M42"), diagnostic.message)
    }

    @Test
    fun `M42 a certificate that also covers an unauthorized identity is refused`() {
        val diagnostic = assertNotNull(verify(base = identities + "id-c"))

        assertTrue(diagnostic.message.contains("M42"), diagnostic.message)
    }

    @Test
    fun `T16 M44 a consumed certificate retained in the candidate fails`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(certificate()),
                baseSha = baseSha,
                validConsumptions = setOf(raw),
            )

        assertTrue(diagnostics.single().message.contains("M44"), diagnostics.single().message)
    }

    @Test
    fun `T19 M47 a removed certificate with no established consumption still fails`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(),
                baseSha = baseSha,
            )

        assertTrue(diagnostics.single().message.contains("M47"), diagnostics.single().message)
    }

    @Test
    fun `T19 M47 removal is permitted only by a consumption established in the same transition`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(),
                baseSha = baseSha,
                validConsumptions = setOf(raw),
            )

        assertTrue(diagnostics.isEmpty(), diagnostics.joinToString { it.message })
    }

    @Test
    fun `T19 M47 removal is not permitted by a consumption established for a different digest`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(),
                baseSha = baseSha,
                validConsumptions = setOf("6".repeat(64)),
            )

        assertTrue(diagnostics.single().message.contains("M47"), diagnostics.single().message)
    }
}
