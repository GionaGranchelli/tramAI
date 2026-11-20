package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.7.1g1F: the authorization ledger file itself. An absent ledger means no authorizations (the most
 * restrictive state), a malformed one fails closed, and the classification vocabulary is the shared one —
 * not a second list that could drift from `mutation-classifications.yml`.
 */
class MutationRatchetEnrollmentLedgerTest : MutationRatchetEnrollmentTestSupport() {
    // ---------------------------------------------------------------- the ledger file itself

    @Test
    fun `absent ledger means no authorizations`() {
        assertEquals(0, MutationClassificationEnrollmentLoader.load(tempDir).enrollments.size)
    }

    @Test
    fun `ledger rejects an unknown classification name using the shared vocabulary`() {
        writeLedger(
            """
            schemaVersion: "1"
            enrollments:
              - id: "${identityOf("x", policyFamily, ":engine")}"
                classification: "sounds-fine-honestly"
                reason: "not in the allowed vocabulary"
            """.trimIndent(),
        )
        val failure = runCatching { MutationClassificationEnrollmentLoader.load(tempDir) }.exceptionOrNull()
        assertTrue(failure is GradleException, "expected GradleException, got $failure")
        assertTrue(failure.message!!.contains("not allowed"), "unexpected message: ${failure.message}")
    }

    @Test
    fun `ledger rejects duplicate authorization ids`() {
        val id = identityOf("x", policyFamily, ":engine")
        writeLedger(
            """
            schemaVersion: "1"
            enrollments:
              - id: "$id"
                classification: "tool-limitation"
                reason: "first"
              - id: "$id"
                classification: "missing-test"
                reason: "contradictory duplicate"
            """.trimIndent(),
        )
        val failure = runCatching { MutationClassificationEnrollmentLoader.load(tempDir) }.exceptionOrNull()
        assertTrue(failure is GradleException, "expected GradleException, got $failure")
        assertTrue(failure.message!!.contains("duplicate"), "unexpected message: ${failure.message}")
    }

    @Test
    fun `ledger parses an exact authorization payload`() {
        val id = identityOf("x", policyFamily, ":engine")
        writeLedger(
            """
            schemaVersion: "1"
            enrollments:
              - id: "$id"
                classification: "tool-limitation"
                reason: >-
                  Deterministic PIT timeout on a compiler-generated suspension check.
                issue: "g1G1"
                targetPhase: "0.7.1"
            """.trimIndent(),
        )
        val loaded = MutationClassificationEnrollmentLoader.load(tempDir)
        assertEquals(1, loaded.enrollments.size)
        val authorized = loaded.byIdentity().getValue(id)
        assertEquals("tool-limitation", authorized.classification)
        assertEquals("g1G1", authorized.issue)
        assertEquals("0.7.1", authorized.targetPhase)
        assertTrue(authorized.reason.startsWith("Deterministic PIT timeout"), "reason must survive folding")
    }
}
