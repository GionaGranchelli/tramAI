package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-task custody transport for the certificate lifecycle (Step 3b, M44-M47).
 *
 * The committed certificate ledger is loaded through the real loader and driven through the
 * certificate ceremony with the facts the verifier itself produces, so these are the real rules over
 * real base authority rather than fixtures. The last case is the one the whole ordering exists for:
 * a certificate may disappear only because a consumption was **independently proven** in the same
 * transition - never because it was absent.
 */
class CertificateCustodyTransportTest {
    private val repositoryRoot = repositoryRoot()
    private val base = MutationAuthorityDigestCertificateLoader.load(repositoryRoot)
    private val admissions = MutationPopulationAdmissionLoader.load(repositoryRoot)
    private val real = base.certificates.single()

    private fun checks(
        candidate: MutationAuthorityDigestCertificates,
        validConsumptions: Set<CertificateConsumption.Valid> = emptySet(),
    ) = MutationAuthorityDigestCertificateCeremony.checks(
        base = base,
        candidate = candidate,
        baseSha = base.certificates.single().fromBaseSha,
        validConsumptions = validConsumptions,
    )

    /** The facts the verifier produces for this base: same production site, same inputs. */
    private fun facts() = certifiedConsumptions(base, admissions, base.certificates.single().toDigest)

    @Test
    fun `the committed certificate survives an unchanged transition`() {
        val diagnostics = checks(base)

        assertTrue(diagnostics.isEmpty(), diagnostics.joinToString { it.message })
    }

    @Test
    fun `the committed certificate cannot be dropped without a proven consumption`() {
        val diagnostics = checks(MutationAuthorityDigestCertificates.NONE)

        assertTrue(diagnostics.single().message.contains("M47"), diagnostics.single().message)
    }

    @Test
    fun `dropping it is permitted once the consumption is proven from the real ledgers`() {
        val diagnostics = checks(MutationAuthorityDigestCertificates.NONE, facts())

        assertTrue(diagnostics.isEmpty(), diagnostics.joinToString { it.message })
    }

    @Test
    fun `the committed ledger really does prove a consumption for its own target digest`() {
        assertEquals(1, facts().size)
    }

    // ── The remaining lifecycle discriminators, over the real committed ledger ──

    @Test
    fun `T18 a retained certificate whose enforced payload was rewritten fails M46`() {
        val rewritten =
            MutationAuthorityDigestCertificate(
                fromAlgorithm = real.fromAlgorithm,
                fromDigest = real.fromDigest,
                toAlgorithm = real.toAlgorithm,
                toDigest = real.toDigest,
                admissionSetDigest = real.admissionSetDigest,
                fromBaseSha = real.fromBaseSha,
                reason = "rewritten after it was minted",
            )

        val diagnostics = checks(MutationAuthorityDigestCertificates("1", listOf(rewritten)))

        assertTrue(diagnostics.single().message.contains("M46"), diagnostics.single().message)
    }

    @Test
    fun `T17 a certificate introduced against another base fails M45`() {
        val foreign = foreignCertificate(fromDigest = "2".repeat(64), fromBaseSha = "b".repeat(40))

        val diagnostics =
            MutationAuthorityDigestCertificateCeremony.checks(
                base = base,
                candidate = MutationAuthorityDigestCertificates("1", listOf(real, foreign)),
                baseSha = real.fromBaseSha,
            )

        assertTrue(diagnostics.single().message.contains("M45"), diagnostics.single().message)
    }

    @Test
    fun `T14 a certificate absent from the base cannot be consumed, however it is cited`() {
        val foreign = foreignCertificate(fromDigest = "3".repeat(64), fromBaseSha = real.fromBaseSha)

        val verdict =
            verifyCertificateConsumption(
                certificate = foreign,
                baseCertificates = base,
                citedAdmissionPopulationDigest = foreign.fromDigest,
                baseAdmissionIdentities = admissions.admissions.map { it.identity },
                freshAuthorityProjectionHash = foreign.toDigest,
            )

        assertTrue(verdict is CertificateConsumption.Invalid, "expected a refusal, got $verdict")
        assertTrue(
            (verdict as CertificateConsumption.Invalid).diagnostic.message.contains("M43"),
            verdict.diagnostic.message,
        )
    }

    /** A certificate with the real one's shape but a different provenance or source digest. */
    private fun foreignCertificate(
        fromDigest: String,
        fromBaseSha: String,
    ) = MutationAuthorityDigestCertificate(
        fromAlgorithm = real.fromAlgorithm,
        fromDigest = fromDigest,
        toAlgorithm = real.toAlgorithm,
        toDigest = real.toDigest,
        admissionSetDigest = real.admissionSetDigest,
        fromBaseSha = fromBaseSha,
        reason = "foreign certificate",
    )

    /**
     * The repository root, found by walking up to the `gradlew` marker (the idiom the other
     * real-task tests use) rather than assuming a fixed depth below it.
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
