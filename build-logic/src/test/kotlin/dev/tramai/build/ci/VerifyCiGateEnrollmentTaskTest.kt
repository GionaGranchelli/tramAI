package dev.tramai.build.ci

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Real-task tests for `verifyCiGateEnrollment` (0.7.4h2a).
 *
 * `CiGateEnrollmentVerifierTest` proves the rule is correct for the inputs it is handed. This class proves the
 * *task* actually loads workflow files from disk and fails closed — the second half of the hierarchy the
 * repository's AGENTS.md requires, because a correct verifier wired to a task that never reads its inputs is
 * still an ineffective gate.
 */
class VerifyCiGateEnrollmentTaskTest {
    @TempDir
    lateinit var tempDir: File

    private fun taskOver(workflowDir: File): VerifyCiGateEnrollmentTask {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        val task = project.tasks.register("verifyCiGateEnrollment", VerifyCiGateEnrollmentTask::class.java).get()
        task.requiredGates.set(
            listOf("verifyModuleMatrixDrift", "verifyModuleDocContract", "verifyNoPublishedTestFixtures"),
        )
        task.requiredBranch.set("release/0.7.0")
        val workflows = project.fileTree(workflowDir)
        workflows.include("*.yml")
        workflows.include("*.yaml")
        task.workflowFiles.from(workflows)
        return task
    }

    private fun workflowDir(vararg files: Pair<String, String>): File =
        File(tempDir, "workflows").apply {
            mkdirs()
            files.forEach { (name, content) -> File(this, name).writeText(content) }
        }

    private fun compliantWorkflow(): String =
        """
        name: ci
        on:
          pull_request:

        jobs:
          governance:
            runs-on: ubuntu-latest
            steps:
              - name: Governance integration gates
                run: |
                  ./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures --no-daemon
              - name: Upload quality reports
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: reports
                  path: build/reports
        """.trimIndent()

    @Test
    fun `a compliant workflow on disk is accepted`() {
        taskOver(workflowDir("ci.yml" to compliantWorkflow())).verify()
    }

    @Test
    fun `no workflow files fail closed`() {
        val failure = assertFailsWith<GradleException> { taskOver(workflowDir()).verify() }

        assertContains(failure.message.orEmpty(), "no workflow files were found")
    }

    @Test
    fun `an unreadable workflow file fails closed and names the gates`() {
        val failure =
            assertFailsWith<GradleException> {
                taskOver(workflowDir("ci.yml" to "jobs: [this is not valid")).verify()
            }

        assertContains(failure.message.orEmpty(), "verifyModuleMatrixDrift")
        assertContains(failure.message.orEmpty(), "no workflow step executes it")
    }

    @Test
    fun `a workflow that drops one gate fails closed and names that gate only`() {
        val missing = compliantWorkflow().replace("verifyModuleDocContract ", "")

        val failure = assertFailsWith<GradleException> { taskOver(workflowDir("ci.yml" to missing)).verify() }

        assertContains(failure.message.orEmpty(), "verifyModuleDocContract")
        assertContains(failure.message.orEmpty(), "1 required gate(s)")
    }

    @Test
    fun `a step that only runs on push fails closed`() {
        val conditional =
            compliantWorkflow().replace(
                "      - name: Governance integration gates",
                "      - name: Governance integration gates\n" +
                    "        if: " + "${'$'}" + "{{ github.event_name == 'push' }}",
            )

        val failure = assertFailsWith<GradleException> { taskOver(workflowDir("ci.yml" to conditional)).verify() }

        assertContains(failure.message.orEmpty(), "not proven to run for pull requests")
        assertContains(failure.message.orEmpty(), "3 required gate(s)")
    }

    @Test
    fun `the task reads every workflow file it is given`() {
        val split =
            compliantWorkflow().replace(
                "verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures",
                "verifyModuleMatrixDrift verifyModuleDocContract",
            )
        val other =
            compliantWorkflow().replace(
                "./gradlew verifyModuleMatrixDrift verifyModuleDocContract verifyNoPublishedTestFixtures",
                "./gradlew verifyNoPublishedTestFixtures",
            )

        taskOver(workflowDir("ci.yml" to split, "publication.yml" to other)).verify()
    }
}
