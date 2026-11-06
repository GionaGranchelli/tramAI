package dev.tramai.build.ci

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Proves that the required governance gates are effectively executed by the real CI workflows (0.7.4h2).
 *
 * Registered in 0.7.4h2a but deliberately aggregated into nothing: it is activated by the workflow-only
 * change that enrolls the gates and invokes it, so the preparatory change can be green on its own base while
 * the enforcement is still real once it lands. Running it before that enrolment fails for the intended
 * reason, which is the point of the task.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifyCiGateEnrollmentTask : DefaultTask() {
    @get:Input
    abstract val requiredGates: ListProperty<String>

    @get:Input
    abstract val requiredBranch: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workflowFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val files = workflowFiles.files.filter { it.isFile }
        if (files.isEmpty()) {
            throw GradleException(
                "verifyCiGateEnrollment: no workflow files were found, so no gate could be audited. " +
                    "An empty workflow set is a finding, not a pass.",
            )
        }
        val workflows = files.associate { it.name to it.readText() }
        val findings =
            CiGateEnrollmentVerifier.verify(
                workflows = workflows,
                requiredGates = requiredGates.get(),
                requiredBranch = requiredBranch.get(),
            )
        if (findings.isNotEmpty()) {
            val detail = findings.joinToString(separator = "\n") { "  - ${it.describe()}" }
            throw GradleException(
                "verifyCiGateEnrollment: ${findings.size} required gate(s) are not effectively executed by " +
                    "CI:\n$detail\n" +
                    "A gate that is absent, conditional, non-fatal or unreachable from the integration " +
                    "branch is not enforcement. Enroll it in a job that runs for pull requests targeting " +
                    "'${requiredBranch.get()}' and let its failure fail the check.",
            )
        }
        logger.lifecycle(
            "verifyCiGateEnrollment: ${requiredGates.get().size} required gate(s) audited across " +
                "${files.size} workflow file(s); each is executed unconditionally by a job that runs for pull " +
                "requests targeting '${requiredBranch.get()}' and uploads diagnostics on failure.",
        )
    }
}
