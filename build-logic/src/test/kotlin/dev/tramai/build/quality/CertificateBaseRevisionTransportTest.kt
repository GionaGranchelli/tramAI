package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Base-revision transport for the digest-migration certificate ledger (Step 3b).
 *
 * M43/M47 are judged against the certificate ledger **as it exists in the base**, so the base side
 * must be readable at a revision - not only from the working tree. This proves both directions
 * against real repository history, using the same `git show`-into-a-temp-tree path the admissions,
 * enrollments and baseline already use, so one authority snapshot carries the whole base context:
 * population, classifications, enrollments, admissions and certificates.
 *
 * Both SHAs are real and immutable: `9ebe7b44` is the P1M merge that minted the certificate, and
 * `64d05450` is the base P1M was proposed against, which predates the ledger entirely.
 */
class CertificateBaseRevisionTransportTest {
    private val repositoryRoot = repositoryRoot()

    @Test
    fun `the revision that minted the certificate exposes it through the authority snapshot`() {
        val authority = MutationRatchetAuthorityLoader.load(repositoryRoot, P1M_MERGE)

        val certificate = authority.certificates.certificates.single()
        assertEquals(
            authority.admissions.admissions
                .map { it.populationDigest }
                .toSet(),
            setOf(certificate.fromDigest),
        )
    }

    @Test
    fun `a base that predates the certificate ledger exposes no certificates`() {
        val authority = MutationRatchetAuthorityLoader.load(repositoryRoot, PRE_P1M_BASE)

        assertTrue(
            authority.certificates.certificates.isEmpty(),
            "a base predating the ledger must expose no certificates, got " +
                "${authority.certificates.certificates.map { it.fromDigest }}",
        )
        // The same base still carries its authorizations: this isolates the certificate ledger
        // rather than proving that an unrelated empty snapshot is empty.
        assertTrue(authority.admissions.admissions.isNotEmpty())
    }

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

    private companion object {
        /** The P1M merge commit: the first revision whose tree contains the certificate ledger. */
        const val P1M_MERGE = "9ebe7b4430760ac313874750e7c5ac9bbe56ef1e"

        /** The base the P1M transition was proposed against: predates the certificate ledger. */
        const val PRE_P1M_BASE = "64d05450c285ecd9cf3635ca2935da816f649856"
    }
}
