package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The digest-migration certificate ledger's shape contract, and the one value a future mint must be
 * able to reproduce exactly.
 *
 * The loader validates *shape* only: mint-time base binding (M45), retained immutability (M46) and
 * removal custody (M47) are transition properties and are tested in
 * [MutationAuthorityDigestCertificateCeremonyTest]. What is tested here is that a partially
 * specified certificate - indistinguishable from one describing a different migration - is a hard
 * failure rather than a silently weaker authority.
 */
class MutationAuthorityDigestCertificateLoaderTest {
    @TempDir
    lateinit var tempDir: File

    private val fromDigest = "9aebd3202288c82ff006f2db33c95cac0772746fa3c3061569167cd3f45df9b0"
    private val toDigest = "e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad"
    private val admissionSetDigest = "98a9587a1068ec0cd7158a05ba6909c61c522308c272e7fa9cb27d5edd93a79c"
    private val fromBaseSha = "ec8b4da59bc9e22711b0cd802417d297f803b4e0"

    @Test
    fun `an absent certificate ledger is no migration authority rather than a failure`() {
        val loaded = MutationAuthorityDigestCertificateLoader.load(tempDir)

        assertEquals(MutationAuthorityDigestCertificates.NONE.certificates, loaded.certificates)
        assertEquals("1", loaded.schemaVersion)
    }

    @Test
    fun `a fully specified certificate round-trips, audit metadata included`() {
        writeLedger(auditedEntry("giona", "2026-10-01T00:00:00Z"))

        val certificate =
            MutationAuthorityDigestCertificateLoader
                .load(tempDir)
                .certificates
                .single()

        assertEquals("raw-v1", certificate.fromAlgorithm)
        assertEquals(fromDigest, certificate.fromDigest)
        assertEquals("authority-v2", certificate.toAlgorithm)
        assertEquals(toDigest, certificate.toDigest)
        assertEquals(admissionSetDigest, certificate.admissionSetDigest)
        assertEquals(fromBaseSha, certificate.fromBaseSha)
        assertEquals("test reason", certificate.reason)
        assertEquals("giona", certificate.authorizedBy)
    }

    @Test
    fun `the admission-set digest recipe reproduces the committed 67-admission set`() {
        // The recipe is part of the contract, not an implementation detail: whoever mints the P1M
        // certificate must be able to reproduce this value exactly from the authorization set the
        // certificate is meant to bound, and M42 later compares against it.
        val admissions = MutationPopulationAdmissionLoader.load(repositoryRoot()).admissions

        assertEquals(67, admissions.size, "the committed P1 authorization set")
        assertEquals(
            admissionSetDigest,
            MutationAuthorityDigestCertificates.admissionSetDigest(admissions.map { it.identity }),
            "sorted identities joined with a newline and a trailing newline, SHA-256",
        )
    }

    @Test
    fun `the admission-set digest is order independent and set dependent`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)

        assertEquals(
            MutationAuthorityDigestCertificates.admissionSetDigest(listOf(a, b)),
            MutationAuthorityDigestCertificates.admissionSetDigest(listOf(b, a)),
        )
        assertTrue(
            MutationAuthorityDigestCertificates.admissionSetDigest(listOf(a, b)) !=
                MutationAuthorityDigestCertificates.admissionSetDigest(listOf(a)),
            "bounding a different set must change the digest, otherwise the certificate is not bounded",
        )
    }

    @Test
    fun `a malformed from digest is a hard failure, not a weaker authority`() {
        failingLoad(entry(mapOf("fromDigest" to "not-a-digest")))
    }

    @Test
    fun `a short fromBaseSha is a hard failure`() {
        val failure = failingLoad(entry(mapOf("fromBaseSha" to "ec8b4da5")))

        assertTrue(failure.message!!.contains("fromBaseSha"), failure.message)
    }

    @Test
    fun `an unknown digest semantics is a hard failure`() {
        val failure = failingLoad(entry(mapOf("toAlgorithm" to "authority-v3")))

        assertTrue(failure.message!!.contains("known digest semantics"), failure.message)
    }

    @Test
    fun `a missing reason is a hard failure`() {
        val failure = failingLoad(entry(mapOf("reason" to null)))

        assertTrue(failure.message!!.contains("reason"), failure.message)
    }

    @Test
    fun `two certificates claiming the same source digest contradict each other and fail`() {
        val failure = failingLoad(entry() + entry(mapOf("toDigest" to "f".repeat(64))))

        assertTrue(failure.message!!.contains("duplicate fromDigest"), failure.message)
    }

    @Test
    fun `a certificate that migrates a digest to itself fails, because it certifies nothing`() {
        val failure = failingLoad(entry(mapOf("toAlgorithm" to "raw-v1")))

        assertTrue(failure.message!!.contains("certifies nothing"), failure.message)
    }

    @Test
    fun `an absent schemaVersion is a hard failure`() {
        val file = File(tempDir, MutationAuthorityDigestCertificateLoader.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText("certificates:\n" + entry(), Charsets.UTF_8)

        val failure = assertFailsWith<GradleException> { MutationAuthorityDigestCertificateLoader.load(tempDir) }

        assertTrue(failure.message!!.contains("schemaVersion"), failure.message)
    }

    @Test
    fun `the enforced payload excludes audit metadata and includes every bound field`() {
        val certificate =
            MutationAuthorityDigestCertificate(
                fromAlgorithm = "raw-v1",
                fromDigest = fromDigest,
                toAlgorithm = "authority-v2",
                toDigest = toDigest,
                admissionSetDigest = admissionSetDigest,
                fromBaseSha = fromBaseSha,
                reason = "test reason",
                authorizedBy = "someone",
                authorizedAt = "sometime",
            )

        assertEquals(
            listOf("raw-v1", fromDigest, "authority-v2", toDigest, admissionSetDigest, fromBaseSha, "test reason"),
            certificate.enforcedPayload(),
        )
        assertEquals(
            certificate.enforcedPayload(),
            certificate.copy(authorizedBy = "another", authorizedAt = "elsewhere").enforcedPayload(),
            "audit metadata alone can never manufacture or break authority",
        )
    }

    /** Writes [entries] as the ledger, then asserts that loading it fails, returning the failure. */
    private fun failingLoad(entries: String): GradleException {
        writeLedger(entries)
        return assertFailsWith { MutationAuthorityDigestCertificateLoader.load(tempDir) }
    }

    /**
     * The repository root, found by walking up to the `gradlew` marker (the same idiom
     * `CanonicalProbeFunctionalTest` uses) rather than assuming this test starts a fixed number of
     * levels below it. This test is deliberately pinned against the real committed admission ledger,
     * so a brittle path guess would fail for a reason that has nothing to do with the recipe.
     */
    private fun repositoryRoot(): File {
        var candidate = File(System.getProperty("user.dir"))
        while (candidate.parentFile != null && !File(candidate, "gradlew").isFile) {
            candidate = candidate.parentFile!!
        }
        check(File(candidate, "gradlew").isFile) {
            "no repository root (directory containing gradlew) above ${System.getProperty("user.dir")}"
        }
        return candidate
    }

    private fun auditedEntry(
        authorizedBy: String,
        authorizedAt: String,
    ): String =
        entry().replace(
            "    reason: \"test reason\"",
            "    authorizedBy: \"$authorizedBy\"\n    authorizedAt: \"$authorizedAt\"\n    reason: \"test reason\"",
        )

    /**
     * The canonical certificate YAML, with selected fields replaced.
     *
     * Malformed-input cases need raw values that a typed certificate cannot represent, so overrides
     * are raw strings (or null to omit a field) rather than certificate fields.
     */
    private fun entry(overrides: Map<String, String?> = emptyMap()): String {
        val fields =
            linkedMapOf<String, String?>(
                "fromAlgorithm" to "raw-v1",
                "fromDigest" to fromDigest,
                "toAlgorithm" to "authority-v2",
                "toDigest" to toDigest,
                "admissionSetDigest" to admissionSetDigest,
                "fromBaseSha" to fromBaseSha,
                "reason" to "test reason",
            )
        fields.putAll(overrides)
        val lines =
            fields.entries
                .filter { it.value != null }
                .map { "    ${it.key}: \"${it.value}\"" }
        return "  - " + lines.first().trimStart() + "\n" + lines.drop(1).joinToString("") { "$it\n" }
    }

    private fun writeLedger(entries: String) {
        val file = File(tempDir, MutationAuthorityDigestCertificateLoader.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText("schemaVersion: \"1\"\ncertificates:\n$entries", Charsets.UTF_8)
    }
}
