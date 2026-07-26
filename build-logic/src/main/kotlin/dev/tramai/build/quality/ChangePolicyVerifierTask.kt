package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Internal
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.io.File

/**
 * Verifies that the current branch's change set conforms to the repository's
 * change-policy rules.
 *
 * Registered as "verifyChangePolicy" in MaintainabilityBaselinePlugin.
 *
 * Rules enforced:
 *  1. Production code and canonical baseline must not change together
 *     (**/src/main/** ∧ config/quality/0.6.0-baseline.json)
 *  2. Analyzer code (build-logic/**Scanner*) and runtime production modules
 *     must not change together
 *  3. New or modified deviations must carry structured evidence
 *     (baseline, allowed, reason, acceptedAt)
 *
 * This task FAILS CLOSED: if the git diff cannot be determined, the build
 * fails rather than silently passing.
 */
abstract class ChangePolicyVerifierTask : DefaultTask() {

    @get:Input
    abstract val baseRef: Property<String>

    @get:Internal
    lateinit var projectRoot: File
        private set

    init {
        baseRef.convention("origin/master")
        group = "maintainability"
        description = "Enforces change-policy rules: forbidden path combinations and deviation evidence"
    }

    @TaskAction
    fun verify() {
        projectRoot = project.rootDir

        val result = getChangedFiles()
        val changedFiles = result.files
        val diffErrors = result.errors

        if (diffErrors.isNotEmpty()) {
            throw GradleException(
                "verifyChangePolicy FAILED: could not determine changed files against ${baseRef.get()}.\n" +
                    "  Reason: ${diffErrors.joinToString("; ")}\n" +
                    "  This gate fails closed — the diff must be available to evaluate change policy.\n" +
                    "  Ensure the repository has fetch-depth > 0 and the base ref is present."
            )
        }

        if (changedFiles.isEmpty()) {
            logger.lifecycle("verifyChangePolicy: no changes detected against ${baseRef.get()}")
            return
        }

        val failures = mutableListOf<String>()

        // Rule 1: production + canonical baseline
        // Production follows the multi-module pattern: tramai-*/src/main/**, build-logic/src/main/**
        val productionChanged = changedFiles.any { it.contains("/src/main/") }
        val baselineChanged = changedFiles.any { it == "config/quality/0.6.0-baseline.json" }
        if (productionChanged && baselineChanged) {
            failures.add(
                "POLICY: Production source (**/src/main/**) and canonical baseline " +
                    "(config/quality/0.6.0-baseline.json) must not change together.\n" +
                    "  If the baseline needs updating, submit a separate PR with type 'baseline-migration'.\n" +
                    "  Changed production paths: ${changedFiles.filter { it.contains("/src/main/") }.joinToString(", ")}"
            )
        }

        // Rule 2: analyzer + remediated runtime together
        val analyzerChanged = changedFiles.any { it.contains("Scanner") && it.startsWith("build-logic/") }
        val runtimeChanged = changedFiles.any {
            it.startsWith("tramai-") &&
                !it.startsWith("tramai-spring-boot-starter") &&
                it.contains("/src/main/")
        }
        if (analyzerChanged && runtimeChanged) {
            failures.add(
                "POLICY: Analyzer code (build-logic/**Scanner*) and runtime production modules " +
                    "must not change in the same PR.\n" +
                    "  Submit separate PRs: one for tooling changes, one for runtime remediation.\n" +
                    "  Changed analyzer: ${changedFiles.filter { it.contains("Scanner") && it.startsWith("build-logic/") }.joinToString(", ")}\n" +
                    "  Changed runtime: ${changedFiles.filter { it.startsWith("tramai-") && it.contains("/src/main/") }.joinToString(", ")}"
            )
        }

        // Rule 3: deviation ceiling increase without evidence
        val deviationsChanged = changedFiles.any { it == "config/quality/maintainability-deviations.yml" }
        if (deviationsChanged) {
            val deviationFile = File(projectRoot, "config/quality/maintainability-deviations.yml")
            if (deviationFile.isFile) {
                val content = deviationFile.readText()
                // Split on deviation block markers
                val deviationBlocks = content.split("  - id:")
                // Skip the first split (header), check the rest
                for (i in 1 until deviationBlocks.size) {
                    val block = deviationBlocks[i]
                    val hasBaseline = block.contains("baseline:")
                    val hasAllowed = block.contains("allowed:")
                    val hasReason = block.contains("reason:")
                    val hasAcceptedAt = block.contains("acceptedAt:")
                    if (!hasBaseline || !hasAllowed || !hasReason || !hasAcceptedAt) {
                        val idMatch = Regex("id:\\s*(\\S+)").find(block)
                        val id = idMatch?.groupValues?.getOrNull(1) ?: "unknown"
                        val missing = listOfNotNull(
                            "baseline" to hasBaseline,
                            "allowed" to hasAllowed,
                            "reason" to hasReason,
                            "acceptedAt" to hasAcceptedAt
                        ).filter { !it.second }.joinToString(", ") { it.first }
                        failures.add(
                            "POLICY: Deviation $id is missing required evidence fields: $missing.\n" +
                                "  Every deviation must include: baseline, allowed, reason, acceptedAt."
                        )
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Change policy verification FAILED (${failures.size} violation(s)):\n\n" +
                    failures.joinToString("\n---\n") +
                    "\n\nSee AGENTS.md and the task template for allowed change types."
            )
        }

        logger.lifecycle("verifyChangePolicy PASSED — ${changedFiles.size} file(s) changed, no policy violations.")
    }

    /**
     * Returns the list of changed files and any errors encountered.
     * FAILS CLOSED: errors are returned to the caller instead of silently
     * returning an empty set.
     */
    private fun getChangedFiles(): DiffResult {
        val base = baseRef.get()
        return try {
            val process = ProcessBuilder(
                "git", "diff", "--name-only", "$base...HEAD"
            )
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                // git diff returns non-zero when the merge-base can't be found
                // (e.g. shallow clone, base ref missing). Try fallback to HEAD~1.
                val fallbackProcess = ProcessBuilder(
                    "git", "diff", "--name-only", "HEAD~1...HEAD"
                )
                    .directory(projectRoot)
                    .redirectErrorStream(true)
                    .start()
                val fallbackOutput = fallbackProcess.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val fallbackExit = fallbackProcess.waitFor()

                if (fallbackExit == 0) {
                    DiffResult(fallbackOutput.lines().filter { it.isNotBlank() }, emptyList())
                } else {
                    DiffResult(
                        emptyList(),
                        listOf(
                            "git diff --name-only $base...HEAD exited $exitCode",
                            "git diff --name-only HEAD~1...HEAD also failed (exit $fallbackExit)"
                        )
                    )
                }
            } else {
                DiffResult(output.lines().filter { it.isNotBlank() }, emptyList())
            }
        } catch (e: Exception) {
            DiffResult(emptyList(), listOf("Exception running git diff: ${e.message}"))
        }
    }

    private data class DiffResult(
        val files: List<String>,
        val errors: List<String>
    )
}
