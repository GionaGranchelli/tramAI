package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Verifies that the current branch's change set conforms to the repository's
 * change-policy rules.
 *
 * Rules enforced (delegated to [ChangePolicyEvaluator]):
 *  1. Production code and canonical baseline must not change together
 *     (unless -PchangeClass=baseline-migration)
 *  2. Analyzer/tooling code and runtime production modules must not change together
 *  3. New or modified deviations must carry full structured evidence
 *     (baseline, allowed, reason, acceptedAt, targetPhase, owner)
 *
 * This task FAILS CLOSED: if the git diff cannot be determined, the build
 * fails rather than silently passing.
 *
 * Configuration-cache compatible: no Task.project access at execution time;
 * all state is declared as task inputs.
 */
abstract class ChangePolicyVerifierTask : DefaultTask() {

    @get:Internal
    abstract val rootDir: Property<File>

    @get:Input
    abstract val baseRef: Property<String>

    @get:Input
    @get:Optional
    abstract val changeClass: Property<String>

    @get:Input
    @get:Optional
    abstract val deviationBaseRef: Property<String>

    init {
        baseRef.convention("origin/master")
        deviationBaseRef.convention("origin/master")
        group = "maintainability"
        description = "Enforces change-policy rules: forbidden path combinations and deviation evidence"
    }

    @TaskAction
    fun verify() {
        val rootDir = rootDir.get()

        // --- Collect all changed files (committed + staged + unstaged + untracked) ---
        val allChanged = collectChangedFiles(rootDir)
        if (allChanged == null) {
            throw GradleException(
                "verifyChangePolicy FAILED: could not determine changed files.\n" +
                    "  This gate fails closed — the diff must be available to evaluate policy.\n" +
                    "  Ensure git is available and the repository is not in a detached HEAD state."
            )
        }

        if (allChanged.isEmpty()) {
            logger.lifecycle("verifyChangePolicy: no changes detected (clean tree, no branch commits)")
            return
        }

        // Warn if there are uncommitted changes — the agent should commit before CI
        val hasUncommitted = hasUncommittedChanges(rootDir)
        if (hasUncommitted) {
            logger.warn(
                "verifyChangePolicy: WARNING — working tree has uncommitted changes.\n" +
                    "  Policy evaluation includes staged, unstaged, and untracked files.\n" +
                    "  However, CI evaluates only committed branch changes.\n" +
                    "  Commit before pushing to avoid CI vs local mismatch."
            )
        }

        // --- Read base and current deviation YAML ---
        val baseDeviationsYaml = readFileFromGit(rootDir, deviationBaseRef.get(),
            "config/quality/maintainability-deviations.yml")
        val currentDeviationsFile = File(rootDir, "config/quality/maintainability-deviations.yml")
        val currentDeviationsYaml = if (currentDeviationsFile.isFile) {
            currentDeviationsFile.readText()
        } else {
            null // file was deleted
        }

        // --- Evaluate ---
        val declaredClass = changeClass.orNull
        val input = ChangePolicyInput(
            changeClass = declaredClass,
            changedFiles = allChanged,
            baseDeviationsYaml = baseDeviationsYaml,
            currentDeviationsYaml = currentDeviationsYaml
        )
        val result = ChangePolicyEvaluator.evaluate(input)

        if (result.violations.isNotEmpty()) {
            throw GradleException(
                "Change policy verification FAILED (${result.violations.size} violation(s)):\n\n" +
                    result.violations.joinToString("\n---\n") { it.formatted() } +
                    "\n\nTo override the detected change class, pass -PchangeClass=<class>.\n" +
                    "Supported values: runtime-behaviour, build-logic, baseline-migration.\n" +
                    "See AGENTS.md and docs/board/tasks/TASK-TEMPLATE.md for allowed change types."
            )
        }

        logger.lifecycle("verifyChangePolicy PASSED — ${allChanged.size} changed file(s), change class: ${declaredClass ?: ChangePolicyEvaluator.detectChangeClass(allChanged)}, no policy violations.")
    }

    // --- File collection ---

    /**
     * Collects all changed files from the union of:
     *  - committed branch changes (baseRef...HEAD)
     *  - staged changes (git diff --cached)
     *  - unstaged changes (git diff)
     *  - untracked files (git ls-files --others)
     *
     * Returns null on failure (fail closed). Returns empty list for clean state.
     */
    private fun collectChangedFiles(rootDir: File): List<String>? {
        val all = mutableSetOf<String>()

        // 1. Committed branch changes
        val base = baseRef.get()
        val branchResult = runGit(rootDir, "diff", "--name-only", "$base...HEAD")
        if (branchResult == null) return null
        all.addAll(branchResult)

        // 2. Staged changes
        val stagedResult = runGit(rootDir, "diff", "--cached", "--name-only")
        if (stagedResult == null) return null
        all.addAll(stagedResult)

        // 3. Unstaged changes
        val unstagedResult = runGit(rootDir, "diff", "--name-only")
        if (unstagedResult == null) return null
        all.addAll(unstagedResult)

        // 4. Untracked files
        val untrackedResult = runGit(rootDir, "ls-files", "--others", "--exclude-standard")
        if (untrackedResult == null) return null
        all.addAll(untrackedResult)

        return all.filter { it.isNotBlank() }.sorted()
    }

    private fun hasUncommittedChanges(rootDir: File): Boolean {
        val status = runGit(rootDir, "status", "--porcelain") ?: return false
        return status.any { it.isNotBlank() }
    }

    private fun readFileFromGit(rootDir: File, ref: String, path: String): String? {
        val result = runGit(rootDir, "show", "$ref:$path")
        // git show returns non-zero exit if the file doesn't exist at that ref
        return result?.joinToString("\n")
    }

    private fun runGit(rootDir: File, vararg args: String): List<String>? {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("git ${args.joinToString(" ")} failed (exit=$exitCode): ${output.take(200)}")
                null
            } else {
                output.lines().filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            logger.warn("git ${args.joinToString(" ")} exception: ${e.message}")
            null
        }
    }
}
