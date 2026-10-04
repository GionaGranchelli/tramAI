package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CI wiring for the JUnit test-signature authority.
 *
 * The 0.7.1d review found that a legal-looking `@Test fun x() = runBlocking { ... }` could be
 * silently discarded by JUnit while `verifyJUnitTestSignatures` was reachable only through the
 * local `verifyPr` path, so nothing remote stopped that class of defect. This proves the
 * authority runs in the `quality` job.
 *
 * The assertion is entry-scoped and conjunctive — a matrix entry named
 * `junit-test-signatures` AND `gates: verifyJUnitTestSignatures` inside that same entry — because
 * a workflow-wide search for the task name would also pass if the task were mentioned in some
 * unrelated job or comment.
 */
class JUnitTestSignatureCiWiringTest {
    @Test
    fun `quality job runs the JUnit test-signature authority`() {
        assertJUnitSignatureWiring(ciWorkflow())
    }

    @Test
    fun `discriminator associating a different gate with the entry fails`() {
        val mutated =
            ciWorkflow().replace(
                "gates: verifyJUnitTestSignatures",
                "gates: verifySomethingElse",
            )
        val error = assertFailsWith<AssertionError> { assertJUnitSignatureWiring(mutated) }
        assertTrue(error.message!!.contains("verifyJUnitTestSignatures"))
    }

    @Test
    fun `discriminator dropping the matrix entry fails`() {
        val mutated = ciWorkflow().replace(MATRIX_ENTRY, "")
        val error = assertFailsWith<AssertionError> { assertJUnitSignatureWiring(mutated) }
        assertTrue(error.message!!.contains("junit-test-signatures"))
    }

    @Test
    fun `discriminator moving the gate into another job fails`() {
        val content = ciWorkflow()
        val moved =
            content
                .replace(MATRIX_ENTRY, "")
                .replaceFirst("  contract-tests:", "  contract-tests:\n" + MATRIX_ENTRY.trimEnd())
        val error = assertFailsWith<AssertionError> { assertJUnitSignatureWiring(moved) }
        assertTrue(error.message!!.contains("junit-test-signatures"))
    }

    private fun ciWorkflow(): String {
        val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
        val workflow = File(repoRoot, ".github/workflows/ci.yml")
        assertTrue(workflow.isFile, "CI workflow file must exist: ${workflow.absolutePath}")
        return workflow.readText()
    }

    private fun assertJUnitSignatureWiring(content: String) {
        val entry = extractMatrixEntry(extractJob(content, "quality"), "junit-test-signatures")
        assertTrue(
            entry.contains("gates: verifyJUnitTestSignatures"),
            "quality matrix entry 'junit-test-signatures' must run verifyJUnitTestSignatures, got:\n$entry",
        )
    }

    /** Returns the block of [jobName] up to the next top-level job. */
    private fun extractJob(
        content: String,
        jobName: String,
    ): String {
        val marker = "  $jobName:"
        val start = content.indexOf("\n" + marker)
        assertTrue(start != -1, "ci.yml must declare job '$jobName'")
        val after = content.substring(start + 1 + marker.length)
        val next = Regex("\n  [a-zA-Z0-9_-]+:").find(after)
        val end = next?.range?.first ?: after.length
        return content.substring(start + 1, start + 1 + marker.length + end)
    }

    /** Returns the matrix include-entry named [entryName] up to the next entry. */
    private fun extractMatrixEntry(
        content: String,
        entryName: String,
    ): String {
        val marker = "- name: $entryName"
        val start = content.indexOf(marker)
        assertTrue(start != -1, "matrix must declare entry '$entryName'")
        val after = content.substring(start + marker.length)
        val next = Regex("\n *- name: ").find(after)
        val end = next?.range?.first ?: after.length
        return content.substring(start, start + marker.length + end)
    }

    private companion object {
        const val MATRIX_ENTRY =
            "          - name: junit-test-signatures\n            gates: verifyJUnitTestSignatures\n"
    }
}
