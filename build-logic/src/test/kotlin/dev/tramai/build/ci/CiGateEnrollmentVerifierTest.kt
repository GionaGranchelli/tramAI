package dev.tramai.build.ci

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the CI gate-enrollment verifier (0.7.4h2a).
 *
 * Everything is driven through controlled workflow fixtures, so this class is green on the repository as it
 * stands and the enforcement becomes real only when the workflow-only change enrolls the gates and invokes the
 * task. Each negative control applies exactly one mutation and asserts that the diagnostic names the specific
 * gate, because "CI is not enrolled" is not actionable on its own.
 *
 * Fixtures are built from mutations rather than from a wide parameter list: a test states the one thing it
 * changes, and the builder states what a compliant workflow looks like once.
 */
class CiGateEnrollmentVerifierTest {
    private val gates =
        listOf("verifyModuleMatrixDrift", "verifyModuleDocContract", "verifyNoPublishedTestFixtures")

    private val allGates = gates.joinToString(" ")

    private fun compliant(): String = mutated()

    private fun mutated(vararg mutations: String): String {
        val changes = mutations.toList()
        val steps = changes.valueAfter("steps=") ?: allGates
        val masking = changes.valueAfter("mask=") ?: ""
        val stepCondition = changes.valueAfter("step-if=")
        val jobCondition = changes.valueAfter("job-if=")
        val continuesOnError = changes.firstOrNull { it.startsWith("continue-on-error=") }
        val withArtifact = changes.none { it == "no-artifact" || it == "artifact-in-other-job" }
        val artifactCondition =
            if (changes.any { it == "artifact-if-success" }) "success()" else "always()"
        val branches =
            if (changes.any { it == "wrong-branch" }) "[master]" else "[release/0.7.0, master]"
        val extraRun = changes.valueAfter("run-extra=")
        val artifactInOtherJob = changes.any { it == "artifact-in-other-job" }

        val lines = mutableListOf("name: ci", "on:", "  pull_request:", "    branches: $branches", "")
        lines += "jobs:"
        lines += "  quality:"
        lines += "    runs-on: ubuntu-latest"
        jobCondition?.let { lines += "    if: $it" }
        if (continuesOnError == "continue-on-error=job") lines += "    continue-on-error: true"
        lines += "    steps:"
        lines += "      - uses: actions/checkout@v4"
        lines += "      - name: Quality gates"
        stepCondition?.let { lines += "        if: $it" }
        if (continuesOnError == "continue-on-error=step") lines += "        continue-on-error: true"
        if (extraRun == null) {
            lines += "        run: ./gradlew $steps --no-daemon $masking"
        } else {
            lines += "        run: |"
            lines += "          ./gradlew $steps --no-daemon $masking"
            lines += "          $extraRun"
        }
        if (artifactInOtherJob) {
            lines += "  diagnostics:"
            lines += "    runs-on: ubuntu-latest"
            lines += "    steps:"
            lines += "      - name: Upload diagnostics elsewhere"
            lines += "        if: always()"
            lines += "        uses: actions/upload-artifact@v4"
            lines += "        with:"
            lines += "          name: reports"
            lines += "          path: build/reports"
        }
        if (withArtifact) {
            lines += "      - name: Upload diagnostics"
            lines += "        if: $artifactCondition"
            lines += "        uses: actions/upload-artifact@v4"
            lines += "        with:"
            lines += "          name: reports"
            lines += "          path: build/reports"
        }
        return lines.joinToString("\n")
    }

    private fun List<String>.valueAfter(prefix: String): String? {
        val match = firstOrNull { it.startsWith(prefix) }
        return match?.removePrefix(prefix)
    }

    private fun findings(
        workflow: String,
        name: String = "ci.yml",
    ): List<String> =
        CiGateEnrollmentVerifier
            .verify(
                workflows = mapOf(name to workflow),
                requiredGates = gates,
                requiredBranch = "release/0.7.0",
            ).map { it.describe() }

    private fun rejectedGates(workflow: String): Set<String> =
        CiGateEnrollmentVerifier
            .verify(
                workflows = mapOf("ci.yml" to workflow),
                requiredGates = gates,
                requiredBranch = "release/0.7.0",
            ).map { it.gate }
            .toSet()

    private fun assertRejectsOnly(
        workflow: String,
        gate: String,
        expectedText: String,
    ) {
        val reported = findings(workflow)
        assertTrue(
            reported.any { it.contains(gate) && it.contains(expectedText) },
            "expected '$gate' to be rejected with '$expectedText', got: $reported",
        )
        assertEquals(setOf(gate), rejectedGates(workflow), "only '$gate' may be rejected, got: $reported")
    }

    private fun assertAllRejectedWith(
        workflow: String,
        expectedText: String,
    ) {
        val reported = findings(workflow)
        assertEquals(gates.toSet(), rejectedGates(workflow), "every gate must be rejected, got: $reported")
        assertTrue(reported.all { it.contains(expectedText) }, "expected '$expectedText' in: $reported")
    }

    @Test
    fun `a workflow that runs every gate unconditionally is accepted`() {
        assertEquals(emptyList<String>(), findings(compliant()))
    }

    @Test
    fun `a gate whose command is removed is rejected and named`() {
        assertRejectsOnly(
            mutated("steps=verifyModuleDocContract verifyNoPublishedTestFixtures"),
            gate = "verifyModuleMatrixDrift",
            expectedText = "no workflow step executes it",
        )
    }

    @Test
    fun `a disabled step is rejected for every gate it carried`() {
        assertAllRejectedWith(mutated("step-if=false"), expectedText = "step that runs it is conditional")
    }

    @Test
    fun `a step condition that evaluates false is rejected`() {
        assertAllRejectedWith(mutated("step-if=\${{ false }}"), expectedText = "step that runs it is conditional")
    }

    @Test
    fun `a disabled job is rejected`() {
        assertAllRejectedWith(mutated("job-if=\${{ false }}"), expectedText = "job is conditional")
    }

    @Test
    fun `continue-on-error on the step is rejected`() {
        assertAllRejectedWith(
            mutated("continue-on-error=step"),
            expectedText = "continue-on-error, so a failure does not fail the check",
        )
    }

    @Test
    fun `continue-on-error on the job is rejected`() {
        assertAllRejectedWith(
            mutated("continue-on-error=job"),
            expectedText = "continue-on-error, so a failure does not fail the check",
        )
    }

    @Test
    fun `a masked exit status is rejected`() {
        assertAllRejectedWith(mutated("mask=|| true"), expectedText = "masks its exit status")
    }

    @Test
    fun `a set plus e shell escape is rejected`() {
        assertAllRejectedWith(mutated("mask=; set +e;"), expectedText = "masks its exit status")
    }

    @Test
    fun `a workflow that never runs for the integration branch is rejected`() {
        assertAllRejectedWith(mutated("wrong-branch"), expectedText = "does not run for pull_request events")
    }

    @Test
    fun `a workflow without an always artifact upload is rejected`() {
        assertAllRejectedWith(mutated("no-artifact"), expectedText = "no 'always()' failure-artifact upload")
    }

    @Test
    fun `an artifact upload without always does not count`() {
        assertAllRejectedWith(
            mutated("artifact-if-success"),
            expectedText = "no 'always()' failure-artifact upload",
        )
    }

    @Test
    fun `an unrelated gate name is not accepted as the required gate`() {
        assertEquals(
            gates.toSet(),
            rejectedGates(mutated("steps=verifyModuleMatrixDrift2 verifyModuleDocContractX")),
            "substring matches must not satisfy the contract",
        )
    }

    /** The shape the real ci.yml uses: the gate list arrives through the matrix, not in the step text. */
    private fun matrixWorkflow(referencesMatrix: Boolean): String {
        val delegate = "./gradlew \"\${GRADLE_ARGS[@]}\""
        val invocation = if (referencesMatrix) delegate else "./gradlew spotlessCheck"
        return """
            name: ci
            on:
              pull_request:

            jobs:
              quality:
                runs-on: ubuntu-latest
                strategy:
                  fail-fast: false
                  matrix:
                    include:
                      - name: governance-integration
                        gates: verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures
                steps:
                  - name: Quality gates (${'$'}{{ matrix.name }})
                    run: |
                      GRADLE_ARGS=(${'$'}{{ matrix.gates }} --no-daemon)
                      $invocation
                  - name: Upload quality reports
                    if: always()
                    uses: actions/upload-artifact@v4
                    with:
                      name: reports
                      path: build/reports
            """.trimIndent()
    }

    @Test
    fun `a gate supplied through the matrix is recognized as executed`() {
        assertEquals(emptyList<String>(), findings(matrixWorkflow(referencesMatrix = true)))
    }

    @Test
    fun `a matrix value that never reaches the command does not count`() {
        assertEquals(gates.toSet(), rejectedGates(matrixWorkflow(referencesMatrix = false)))
    }

    @Test
    fun `a gate named in an echo is not executed`() {
        assertRejectsOnly(
            mutated(
                "steps=verifyModuleDocContract verifyNoPublishedTestFixtures",
                "run-extra=echo \"verifyModuleMatrixDrift\"",
            ),
            gate = "verifyModuleMatrixDrift",
            expectedText = "no workflow step executes it",
        )
    }

    @Test
    fun `a gate named in a comment is not executed`() {
        assertRejectsOnly(
            mutated(
                "steps=verifyModuleDocContract verifyNoPublishedTestFixtures",
                "run-extra=# ./gradlew verifyModuleMatrixDrift",
            ),
            gate = "verifyModuleMatrixDrift",
            expectedText = "no workflow step executes it",
        )
    }

    @Test
    fun `a gate whose arguments are echoed to a non-gradle command is not executed`() {
        assertRejectsOnly(
            mutated(
                "steps=verifyModuleDocContract verifyNoPublishedTestFixtures",
                "run-extra=env ./gradlew-echo verifyModuleMatrixDrift",
            ),
            gate = "verifyModuleMatrixDrift",
            expectedText = "no workflow step executes it",
        )
    }

    @Test
    fun `an artifact upload in an unrelated job does not satisfy the contract`() {
        assertAllRejectedWith(
            mutated("artifact-in-other-job"),
            expectedText = "no 'always()' failure-artifact upload",
        )
    }

    @Test
    fun `or-exit-zero masks the exit status`() {
        assertAllRejectedWith(mutated("mask=|| exit 0"), expectedText = "masks its exit status")
    }

    @Test
    fun `a bare semicolon exit zero is not treated as masking`() {
        // GitHub runs a failing step under `bash -e`, which aborts at the gradlew failure before `exit 0`
        // executes. Restricting this would be a false positive, so it is accepted and stated explicitly.
        assertEquals(emptyList<String>(), findings(mutated("mask=; exit 0")))
    }

    @Test
    fun `a gate enrolled in a second workflow is still enforced`() {
        val reported =
            CiGateEnrollmentVerifier.verify(
                workflows =
                    mapOf(
                        "ci.yml" to mutated("steps=verifyModuleMatrixDrift verifyModuleDocContract"),
                        "publication.yml" to mutated("steps=verifyNoPublishedTestFixtures"),
                    ),
                requiredGates = gates,
                requiredBranch = "release/0.7.0",
            )

        assertEquals(emptyList<String>(), reported.map { it.describe() })
    }
}
