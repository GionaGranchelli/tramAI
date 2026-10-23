package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The digest-migration certificate's authority lifecycle (M45/M46/M47).
 *
 * A certificate is itself base authority, so a strong consumption check would still be worthless if
 * its creation, retention or disappearance were weak. Each rule gets a discriminator in both
 * directions: the property holds for the legitimate transition, and fails for the attack that
 * property exists to defeat.
 *
 * M40-M44 are consumption rules and are not implemented here. The consequence is visible in
 * [M47 removal custody is never satisfied by the mere absence of the certificate]: with no
 * certificate-aware consumption path there is no way to *prove* a valid consumption, so a
 * disappearance always fails. That is the fail-closed half of M47, not an unfinished branch - a
 * disappearance must never become its own evidence.
 */
class MutationAuthorityDigestCertificateCeremonyTest {
    private val baseSha = "ec8b4da59bc9e22711b0cd802417d297f803b4e0"
    private val otherSha = "e6e0d69ed2adfa47c6cdfa4485ef30bc184bb881"
    private val rawV1Digest = "9aebd3202288c82ff006f2db33c95cac0772746fa3c3061569167cd3f45df9b0"
    private val authorityV2Digest = "e6ad01dc1d2966894a6555304bc8ca9a04c8174e3c83ae88760fcfebf1464dad"
    private val admissionSetDigest = "98a9587a1068ec0cd7158a05ba6909c61c522308c272e7fa9cb27d5edd93a79c"

    /** The legitimate certificate: raw-v1 to authority-v2, minted against [baseSha]. */
    private val certificate =
        MutationAuthorityDigestCertificate(
            fromAlgorithm = MutationAuthorityDigestCertificates.ALGORITHM_RAW_V1,
            fromDigest = rawV1Digest,
            toAlgorithm = MutationAuthorityDigestCertificates.ALGORITHM_AUTHORITY_V2,
            toDigest = authorityV2Digest,
            admissionSetDigest = admissionSetDigest,
            fromBaseSha = baseSha,
            reason = "digest migration",
        )

    /** A copy of [certificate] with exactly one bound field replaced. */
    private fun rewritten(
        field: String,
        value: String,
    ): MutationAuthorityDigestCertificate =
        when (field) {
            "fromAlgorithm" -> certificate.copy(fromAlgorithm = value)
            "toAlgorithm" -> certificate.copy(toAlgorithm = value)
            "toDigest" -> certificate.copy(toDigest = value)
            "admissionSetDigest" -> certificate.copy(admissionSetDigest = value)
            "fromBaseSha" -> certificate.copy(fromBaseSha = value)
            "reason" -> certificate.copy(reason = value)
            else -> error("$field is not a field of the enforced payload")
        }

    // ── M45 mint-time base binding (T17) ──

    @Test
    fun `M45 a certificate minted against the authority base is accepted`() {
        val diagnostics = checks(base = ledger(), candidate = ledger(certificate))

        assertEquals(emptyList(), diagnostics, "the legitimate mint must pass")
    }

    @Test
    fun `M45 a certificate minted against a different base is rejected`() {
        val diagnostics = checks(base = ledger(), candidate = ledger(certificate.copy(fromBaseSha = otherSha)))

        val failure = diagnostics.single()
        assertTrue(failure.message.contains("M45"), failure.message)
        assertEquals(rawV1Digest, failure.findingId, "the certificate's source digest identifies it")
    }

    @Test
    fun `M45 binding is enforced at mint only, so a retained certificate survives later merges`() {
        // A pending certificate legitimately outlives intermediate merges: requiring fromBaseSha to
        // track the immediate base would make delayed consumption impossible and would itself violate
        // certificate immutability. If this fails, mint binding has been turned into a retention rule.
        val retained = certificate.copy(fromBaseSha = otherSha)

        val diagnostics = checks(base = ledger(retained), candidate = ledger(retained))

        assertEquals(emptyList(), diagnostics, "anti-replay applies at introduction, not forever")
    }

    // ── M46 retained immutability (T18) ──

    @Test
    fun `M46 a retained certificate rewritten in any enforced field is rejected`() {
        // Driven as a loop so the enforced payload is proven to cover exactly these fields: adding a
        // bound field without classifying it would leave it unprotected and fail this discriminator.
        val rewrites =
            listOf(
                "fromAlgorithm" to "raw-v2",
                "toAlgorithm" to "authority-v3",
                "toDigest" to "f".repeat(64),
                "admissionSetDigest" to "f".repeat(64),
                "fromBaseSha" to otherSha,
                "reason" to "a later, more convenient story",
            )

        for ((field, value) in rewrites) {
            val diagnostics = checks(base = ledger(certificate), candidate = ledger(rewritten(field, value)))
            assertTrue(diagnostics.isNotEmpty(), "rewriting $field must fail M46")
            assertTrue(
                diagnostics.any { it.message.contains("M46") },
                "rewriting $field: ${diagnostics.map { it.message }}",
            )
        }
    }

    @Test
    fun `M46 audit-only metadata may change, and cannot manufacture authority`() {
        val diagnostics =
            checks(
                base = ledger(certificate.copy(authorizedBy = "giona", authorizedAt = "2026-10-01T00:00:00Z")),
                candidate = ledger(certificate.copy(authorizedBy = "someone", authorizedAt = "2027-01-01T00:00:00Z")),
            )

        assertEquals(emptyList(), diagnostics, "who/when carry no enforcement value in either direction")
    }

    // ── M47 removal custody (T19, lifecycle half) ──

    @Test
    fun `M47 a retained certificate is not a removal`() {
        val diagnostics = checks(base = ledger(certificate), candidate = ledger(certificate))

        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun `M47 removal custody is never satisfied by the mere absence of the certificate`() {
        // The exact attack T19 exists to defeat: a transition that simply deletes the certificate and
        // calls the deletion evidence of consumption. With no consumption path implemented, no
        // disappearance can be proven valid, so every disappearance must fail closed.
        val diagnostics = checks(base = ledger(certificate), candidate = ledger())

        val failure = diagnostics.single()
        assertTrue(failure.message.contains("M47"), failure.message)
        assertTrue(failure.message.contains("removed without being consumed"), failure.message)
        assertEquals(rawV1Digest, failure.findingId)
    }

    @Test
    fun `a certificate introduced and removed by the same transition is not a way to consume it`() {
        // Net-zero: nothing is gained, and M45 still judges the introduction against the base. What
        // must never happen is this being read as a consumption, which is M43's job in Step 3.
        val diagnostics = checks(base = ledger(), candidate = ledger(certificate.copy(fromBaseSha = otherSha)))

        assertTrue(diagnostics.single().message.contains("M45"), "the introduction is still judged")
    }

    private fun checks(
        base: MutationAuthorityDigestCertificates,
        candidate: MutationAuthorityDigestCertificates,
    ): List<VerificationDiagnostic> =
        MutationAuthorityDigestCertificateCeremony.checks(base = base, candidate = candidate, baseSha = baseSha)

    private fun ledger(vararg certificates: MutationAuthorityDigestCertificate) =
        MutationAuthorityDigestCertificates(schemaVersion = "1", certificates = certificates.toList())
}
