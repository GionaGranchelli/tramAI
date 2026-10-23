package dev.tramai.build.quality

import dev.tramai.build.quality.CertificateConsumption.Valid
import dev.tramai.build.quality.MutationAuthorityDigestCertificates.Companion.ALGORITHM_AUTHORITY_V2
import dev.tramai.build.quality.MutationAuthorityDigestCertificates.Companion.ALGORITHM_RAW_V1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T14, T15, T16 and T19 at the rule level: consumption is a fact produced from base authority, and
 * the lifecycle rules act only on facts the verifier attributed.
 *
 * Every `Valid` fact below is obtained by running the real verifier over a real base ledger - none is
 * hand-constructed - so these tests exercise the same route a Gradle call site will use.
 */
class MutationAuthorityDigestCertificateConsumptionTest {
    private val raw = "1".repeat(64)
    private val otherRaw = "7".repeat(64)
    private val authority = "2".repeat(64)
    private val baseSha = "a".repeat(40)
    private val identities = listOf("id-a", "id-b")
    private val setDigest = MutationAuthorityDigestCertificates.admissionSetDigest(identities)

    private fun certificate(
        fromDigest: String = raw,
        fromAlgorithm: String = ALGORITHM_RAW_V1,
        admissionSetDigest: String = setDigest,
        reason: String = "test certificate",
    ) = MutationAuthorityDigestCertificate(
        fromAlgorithm = fromAlgorithm,
        fromDigest = fromDigest,
        toAlgorithm = ALGORITHM_AUTHORITY_V2,
        toDigest = authority,
        admissionSetDigest = admissionSetDigest,
        fromBaseSha = baseSha,
        reason = reason,
    )

    private fun ledger(vararg certificates: MutationAuthorityDigestCertificate) =
        MutationAuthorityDigestCertificates(schemaVersion = "1", certificates = certificates.toList())

    private fun verify(
        certificate: MutationAuthorityDigestCertificate = certificate(),
        base: MutationAuthorityDigestCertificates = ledger(certificate()),
        citedAdmissionPopulationDigest: String = raw,
        baseAdmissionIdentities: List<String> = identities,
        freshAuthorityProjectionHash: String = authority,
    ) = verifyCertificateConsumption(
        certificate = certificate,
        baseCertificates = base,
        citedAdmissionPopulationDigest = citedAdmissionPopulationDigest,
        baseAdmissionIdentities = baseAdmissionIdentities,
        freshAuthorityProjectionHash = freshAuthorityProjectionHash,
    )

    private fun validFact(certificate: MutationAuthorityDigestCertificate = certificate()): Valid {
        val result =
            verify(
                certificate = certificate,
                base = ledger(certificate),
                citedAdmissionPopulationDigest = certificate.fromDigest,
            )
        assertTrue(result is CertificateConsumption.Valid, "expected a valid consumption, got $result")
        return result as CertificateConsumption.Valid
    }

    private fun refusal(
        certificate: MutationAuthorityDigestCertificate = certificate(),
        base: MutationAuthorityDigestCertificates = ledger(certificate),
        citedAdmissionPopulationDigest: String = raw,
        baseAdmissionIdentities: List<String> = identities,
        freshAuthorityProjectionHash: String = authority,
    ): VerificationDiagnostic {
        val result =
            verify(
                certificate = certificate,
                base = base,
                citedAdmissionPopulationDigest = citedAdmissionPopulationDigest,
                baseAdmissionIdentities = baseAdmissionIdentities,
                freshAuthorityProjectionHash = freshAuthorityProjectionHash,
            )
        assertTrue(result is CertificateConsumption.Invalid, "expected a refusal, got $result")
        return (result as CertificateConsumption.Invalid).diagnostic
    }

    @Test
    fun `a certificate satisfying every predicate yields the attributed fact`() {
        val fact = validFact()

        assertEquals(raw, fact.fromDigest)
        assertEquals(authority, fact.toDigest)
        assertEquals(setDigest, fact.admissionSetDigest)
    }

    @Test
    fun `M43 a certificate absent from the base cannot be consumed`() {
        val diagnostic = refusal(base = MutationAuthorityDigestCertificates.NONE)

        assertTrue(diagnostic.message.contains("M43"), diagnostic.message)
        assertEquals(raw, diagnostic.findingId)
    }

    @Test
    fun `M43 a candidate-minted certificate cannot become a consumption fact`() {
        val diagnostic = refusal(base = ledger(certificate(fromDigest = otherRaw)))

        assertTrue(diagnostic.message.contains("M43"), diagnostic.message)
    }

    @Test
    fun `M43 a certificate whose base payload was rewritten cannot be consumed`() {
        val diagnostic = refusal(base = ledger(certificate(reason = "rewritten in the candidate")))

        assertTrue(diagnostic.message.contains("M43"), diagnostic.message)
    }

    @Test
    fun `M40 a certificate whose target digest is not this measurement's projection is refused`() {
        val diagnostic = refusal(freshAuthorityProjectionHash = "3".repeat(64))

        assertTrue(diagnostic.message.contains("M40"), diagnostic.message)
    }

    @Test
    fun `M41 a certificate for a different source digest than the cited admission is refused`() {
        val diagnostic = refusal(citedAdmissionPopulationDigest = otherRaw)

        assertTrue(diagnostic.message.contains("M41"), diagnostic.message)
    }

    @Test
    fun `M41 a certificate declaring a different migration pair is refused`() {
        val diagnostic = refusal(certificate = certificate(fromAlgorithm = ALGORITHM_AUTHORITY_V2))

        assertTrue(diagnostic.message.contains("M41"), diagnostic.message)
    }

    @Test
    fun `M42 a certificate bounding a different admission set is refused`() {
        val diagnostic = refusal(certificate = certificate(admissionSetDigest = "5".repeat(64)))

        assertTrue(diagnostic.message.contains("M42"), diagnostic.message)
    }

    @Test
    fun `M42 a certificate that also covers an unauthorized identity is refused`() {
        val diagnostic = refusal(baseAdmissionIdentities = identities + "id-c")

        assertTrue(diagnostic.message.contains("M42"), diagnostic.message)
    }

    @Test
    fun `T16 M44 a consumed certificate retained in the candidate fails`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(certificate()),
                baseSha = baseSha,
                validConsumptions = setOf(validFact()),
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
    fun `T19 M47 removal is permitted by the fact for that exact certificate`() {
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(),
                baseSha = baseSha,
                validConsumptions = setOf(validFact()),
            )

        assertTrue(diagnostics.isEmpty(), diagnostics.joinToString { it.message })
    }

    @Test
    fun `T19 M47 removal is not permitted by a fact proving a different certificate`() {
        val other = certificate(fromDigest = otherRaw)
        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = ledger(certificate()),
                candidate = ledger(),
                baseSha = baseSha,
                validConsumptions = setOf(validFact(other)),
            )

        assertTrue(diagnostics.single().message.contains("M47"), diagnostics.single().message)
    }
}
