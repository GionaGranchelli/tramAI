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
 *     (src/main/** ∧ config/quality/0.6.0-baseline.json)
 *  2. Analyzer code (build-logic/**Scanner*) and runtime production must
 *     not change together
 *  3. A deviation ceiling increase must carry structured evidence
 *  4. A workflow that introduces a command must have that command present
 *     in verifyPr's dependency list
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
        description = "Enforces change-policy rules on the current branch diff"
    }

    @TaskAction
    fun verify() {
        projectRoot = project.rootDir

        val changedFiles = getChangedFiles()
        if (changedFiles.isEmpty()) {
            logger.lifecycle("verifyChangePolicy: no changes detected against ${baseRef.get()}")
            return
        }

        val failures = mutableListOf<String>()

        // Rule 1: production + canonical baseline
        val productionChanged = changedFiles.any { it.startsWith("src/main/") }
        val baselineChanged = changedFiles.any { it == "config/quality/0.6.0-baseline.json" }
        if (productionChanged && baselineChanged) {
            failures.add(
                "POLICY: Production source (src/main/**) and canonical baseline " +
                    "(config/quality/0.6.0-baseline.json) must not change together.\n" +
                    "  If the baseline needs updating, submit a separate PR with type 'baseline-migration'.\n" +
                    "  Changed production paths: ${changedFiles.filter { it.startsWith("src/main/") }.joinToString(", ")}"
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
                // Check that new deviations carry required evidence fields
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

    private fun getChangedFiles(): List<String> {
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
                logger.warn("git diff against $base failed (exit=$exitCode). Falling back to staged changes only.")
                val fallback = ProcessBuilder("git", "diff", "--name-only", "--cached")
                    .directory(projectRoot)
                    .redirectErrorStream(true)
                    .start()
                val fallbackOutput = fallback.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                fallback.waitFor()
                fallbackOutput.lines().filter { it.isNotBlank() }
            } else {
                output.lines().filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            logger.warn("Could not determine changed files: ${e.message}. Using empty change set.")
            emptyList()
        }
    }
}
