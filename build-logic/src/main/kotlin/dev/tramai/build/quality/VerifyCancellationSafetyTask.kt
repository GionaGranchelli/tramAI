package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Scans all production source for broad catches in suspend-capable code and
 * rejects newly introduced critical/high findings and risk worsenings (Epic
 * 10.1d gate). Typed so it is configuration-cache compatible and participates
 * in the normal developer `check` lifecycle (9.2d-b3: C5 = 0).
 *
 * PR mode compares against `tramaiCancellationBaseSha`; local dev mode
 * auto-resolves the merge base against origin/master. The current-tree scan
 * consumes the declared [scanInputs] directly (declared input = execution
 * authority); the base-side scan materializes a worktree at the base SHA,
 * which is itself the declared authority for that immutable tree. No Gradle
 * project model access at execution time.
 */
abstract class VerifyCancellationSafetyTask : DefaultTask() {
    companion object {
        private const val SHA_ABBREV_LENGTH = 8
    }

    /** Repository root; the git worktree commands are rooted here. */
    @get:Internal
    abstract val rootDir: DirectoryProperty

    /** Production sources + settings + catalog actually scanned by the task. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scanInputs: ConfigurableFileCollection

    /** PR base SHA for comparison mode; absent = local dev mode (merge-base). */
    @get:Optional
    @get:Input
    abstract val baseSha: Property<String>

    /** Git executable; default resolved from PATH. */
    @get:Internal
    abstract val gitExecutable: Property<String>

    init {
        gitExecutable.convention("git")
    }

    @TaskAction
    fun verify() {
        val repoRoot = rootDir.get().asFile

        // The declared candidate inputs are the execution authority: the
        // current-tree scan consumes scanInputs directly (module identity
        // derived from each file's location), never rediscovering the tree
        // through MeasurementContext.
        val scopedModules =
            scanInputs.files
                .mapNotNull { moduleOf(repoRoot, it) }
                .filterNot { it.startsWith(":examples:") }
                .toSet()
        val scopedFindings =
            scanInputs.files
                .asSequence()
                .filter { it.isFile && it.extension == "kt" }
                .mapNotNull { file ->
                    val module = moduleOf(repoRoot, file) ?: return@mapNotNull null
                    if (module !in scopedModules) return@mapNotNull null
                    val relativePath = ReportNormalizer.repoRelativePath(file, repoRoot)
                    KotlinCancellationCatchScanner.scan(file.readText(), module, relativePath)
                }.flatten()
                .toList()

        val configuredBaseSha = baseSha.orNull

        if (configuredBaseSha != null) {
            // ── PR mode: compare against base SHA ──
            val baseCatches = scanWorktree(repoRoot, configuredBaseSha, scopedModules)
            compareAndReport(
                baseCatches,
                scopedFindings,
                "verifyCancellationSafety PASSED: no new critical/high findings " +
                    "or risk worsenings against base SHA.",
            )
        } else {
            // ── Local dev mode: auto-resolve merge base against origin/master ──
            val resolveOutput = git(repoRoot, "merge-base", "HEAD", "origin/master")
            val baseShaLocal = resolveOutput.trim()
            if (baseShaLocal.isEmpty()) {
                throw GradleException(
                    "verifyCancellationSafety requires -PtramaiCancellationBaseSha " +
                        "when origin/master is not available.\n" +
                        "Usage: ./gradlew verifyCancellationSafety -PtramaiCancellationBaseSha=<sha>\n" +
                        "In CI this is auto-wired. Locally, use the base branch SHA.",
                )
            }

            println(
                "verifyCancellationSafety: auto-resolved merge base against " +
                    "origin/master = ${baseShaLocal.take(SHA_ABBREV_LENGTH)}",
            )

            val localBaseCatches = scanWorktree(repoRoot, baseShaLocal, scopedModules)
            compareAndReport(
                localBaseCatches,
                scopedFindings,
                "verifyCancellationSafety PASSED: ${scopedFindings.size} findings " +
                    "in scoped modules, no new critical/high findings or risk " +
                    "worsenings against origin/master.",
            )
        }
    }

    /**
     * Derives the module path (":a:b") for a declared candidate file from its
     * location relative to the repository root, using the conventional
     * "<module>/src/..." layout. Returns null for files outside any module
     * source tree (e.g. settings.gradle.kts, catalog).
     */
    private fun moduleOf(
        repoRoot: File,
        file: File,
    ): String? {
        val relative = file.toRelativeString(repoRoot).replace(File.separatorChar, '/')
        val srcSegment = "src"
        val segments = relative.split("/")
        val srcIndex = segments.indexOf(srcSegment)
        if (srcIndex <= 0) return null
        return ":" + segments.subList(0, srcIndex).joinToString(":")
    }

    private fun compareAndReport(
        baseCatches: List<CancellationCatchFinding>,
        scopedFindings: List<CancellationCatchFinding>,
        passMessage: String,
    ) {
        val delta = CancellationDeltaComparator.compare(baseCatches, scopedFindings)

        if (delta.newCriticalHigh.isNotEmpty() || delta.worsened.isNotEmpty()) {
            throw GradleException(delta.diagnostics.joinToString("\n"))
        }

        println(delta.diagnostics.joinToString("\n"))
        println(passMessage)
    }

    private fun scanWorktree(
        repoRoot: File,
        baseSha: String,
        scopedModules: Set<String>,
    ): List<CancellationCatchFinding> {
        val worktreeDir =
            java.nio.file.Files
                .createTempDirectory("tramai-base-${baseSha.take(SHA_ABBREV_LENGTH)}-")
                .toFile()
        var worktreeCreated = false

        try {
            git(repoRoot, "worktree", "add", worktreeDir.absolutePath, baseSha, "--detach")
            worktreeCreated = true

            val baseCtx = MeasurementContext.fromDirectory(worktreeDir)
            val baseInventory = CancellationCatchInventory(baseCtx)
            val baseAllFindings = baseInventory.inventory()
            return baseAllFindings.filter { it.module in scopedModules }
        } finally {
            if (worktreeCreated) {
                git(repoRoot, "worktree", "remove", "--force", worktreeDir.absolutePath)
                git(repoRoot, "worktree", "prune")
            }
            worktreeDir.deleteRecursively()
        }
    }

    private fun git(
        repoRoot: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf(gitExecutable.get()) + args)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("git ${args.joinToString(" ")} failed (exit $exit): $output")
        return output
    }
}
