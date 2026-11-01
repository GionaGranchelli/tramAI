package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-task authority transport for certificate consumption (Step 3b, T7 first).
 *
 * Per `build-logic/AGENTS.md` the rule-level tests prove semantics; this proves the transport: the
 * **real** committed certificate ledger and the **real** committed admission ledger are loaded
 * through the **real** loaders and fed to the real consumption verifier. Changing either committed
 * file changes this outcome, which is the property a fixture can never establish.
 *
 * This is also the load path P1M reported as unwired: until now nothing loaded the canonical
 * certificate ledger from the repository root.
 */
class CertificateLedgerTransportTest {
    private val repositoryRoot = repositoryRoot()
    private val certificates = MutationAuthorityDigestCertificateLoader.load(repositoryRoot)
    private val admissions = MutationPopulationAdmissionLoader.load(repositoryRoot)

    private fun facts(
        certificate: MutationAuthorityDigestCertificate,
        freshAuthorityProjectionHash: String = certificate.toDigest,
    ) = verifyCertificateConsumption(
        certificate = certificate,
        baseCertificates = certificates,
        citedAdmissionPopulationDigest = certificate.fromDigest,
        baseAdmissionIdentities = admissions.admissions.map { it.identity },
        freshAuthorityProjectionHash = freshAuthorityProjectionHash,
    )

    @Test
    fun `the canonical certificate ledger loads through the real loader`() {
        assertEquals(1, certificates.certificates.size)
    }

    @Test
    fun `the committed certificate binds exactly the committed admission set`() {
        val certificate = certificates.certificates.single()

        assertEquals(
            MutationAuthorityDigestCertificates.admissionSetDigest(admissions.admissions.map { it.identity }),
            certificate.admissionSetDigest,
        )
    }

    @Test
    fun `the committed certificate translates the digest the committed admissions are bound to`() {
        val certificate = certificates.certificates.single()

        assertEquals(
            setOf(certificate.fromDigest),
            admissions.admissions.map { it.populationDigest }.toSet(),
        )
    }

    @Test
    fun `real base authority produces a valid consumption`() {
        val result = facts(certificates.certificates.single())

        assertTrue(result is CertificateConsumption.Valid, "expected a valid consumption, got $result")
    }

    @Test
    fun `a certificate absent from the real base ledger cannot be consumed`() {
        val real = certificates.certificates.single()
        val absent =
            MutationAuthorityDigestCertificate(
                fromAlgorithm = real.fromAlgorithm,
                fromDigest = "0".repeat(64),
                toAlgorithm = real.toAlgorithm,
                toDigest = real.toDigest,
                admissionSetDigest = real.admissionSetDigest,
                fromBaseSha = real.fromBaseSha,
                reason = real.reason,
            )

        val result = facts(absent)

        assertTrue(result is CertificateConsumption.Invalid, "expected a refusal, got $result")
        assertTrue(
            (result as CertificateConsumption.Invalid).diagnostic.message.contains("M43"),
            result.diagnostic.message,
        )
    }

    @Test
    fun `real base authority refuses a target digest this transition did not measure`() {
        val result = facts(certificates.certificates.single(), freshAuthorityProjectionHash = "0".repeat(64))

        assertTrue(result is CertificateConsumption.Invalid, "expected a refusal, got $result")
        assertTrue(
            (result as CertificateConsumption.Invalid).diagnostic.message.contains("M40"),
            result.diagnostic.message,
        )
    }

    /**
     * The repository root, found by walking up to the `gradlew` marker (the same idiom
     * `CanonicalProbeFunctionalTest` and the Step-2 loader test use) rather than assuming this test
     * starts a fixed number of levels below it.
     */
    private fun repositoryRoot(): File {
        var candidate = File(System.getProperty("user.dir"))
        while (candidate.parentFile != null && !File(candidate, "gradlew").isFile) {
            candidate = candidate.parentFile!!
        }
        check(File(candidate, "gradlew").isFile) {
            "no repository root (gradlew marker) found above ${System.getProperty("user.dir")}"
        }
        return candidate
    }
}
