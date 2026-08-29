package dev.tramai.build.quality

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Shared infrastructure for the Epic 10.1b durable static-analysis contract
 * suite.
 *
 * Runs against the REAL root `build.gradle.kts` (the current checkout's
 * committed state): each concrete class creates a disposable git worktree of
 * this repository, builds a dynamic git history (baseline present/absent at
 * different base refs, new violations, malformed baselines), and asserts the
 * `verifyStaticAnalysis` gate's semantics. The worktree is disposable and
 * never commits deliberate violations into the real tree.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName::class)
abstract class StaticAnalysisContractTestBase {
    private lateinit var repoRoot: File
    protected lateinit var worktree: File
    private lateinit var baseHead: String

    protected data class Run(
        val exit: Int,
        val output: String,
    )

    @BeforeAll
    fun setUpWorktree() {
        val prop =
            System.getProperty("tramai.repositoryRoot")
                ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")
        repoRoot = File(prop)
        check(File(repoRoot, "build.gradle.kts").isFile) { "repo root lacks build.gradle.kts: $repoRoot" }
        worktree = File(File(System.getProperty("java.io.tmpdir")), "static-analysis-wt-${System.nanoTime()}")
        git(repoRoot, "worktree", "prune")
        git(repoRoot, "worktree", "add", "--detach", worktree.absolutePath, "HEAD")
        // Hermetic: dynamic commits must not depend on machine-global Git identity.
        git(worktree, "config", "user.name", "TramAI Test")
        git(worktree, "config", "user.email", "tramai-test@invalid")
        baseHead = git(worktree, "rev-parse", "HEAD").trim()
        check(File(worktree, "build.gradle.kts").readText().contains("tramai.static-analysis")) {
            "worktree build.gradle.kts must apply the static-analysis plugin (is the branch up to date?)"
        }
        check(File(worktree, "config/detekt/baseline.xml").isFile) {
            "worktree must carry the committed Detekt baseline"
        }
    }

    @AfterAll
    fun tearDownWorktree() {
        if (::worktree.isInitialized && worktree.isDirectory) {
            runCatching { git(repoRoot, "worktree", "remove", "--force", worktree.absolutePath) }
            if (worktree.isDirectory) {
                worktree.deleteRecursively()
            }
            runCatching { git(repoRoot, "worktree", "prune") }
        }
    }

    @BeforeEach
    fun resetHistory() {
        // Each test builds its own history from the pristine branch head.
        git(worktree, "reset", "--hard", baseHead)
    }

    protected fun git(
        dir: File,
        vararg args: String,
    ): String = runProcess(dir, "git", *args)

    private fun runProcess(
        dir: File,
        vararg command: String,
    ): String {
        val proc =
            ProcessBuilder(*command)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        check(proc.waitFor(5, TimeUnit.MINUTES)) { "`${command.joinToString(" ")}` timed out" }
        check(proc.exitValue() == 0) { "`${command.joinToString(" ")}` failed (exit=${proc.exitValue()}): ${output.take(2000)}" }
        return output
    }

    protected fun gradle(vararg args: String): Run {
        val proc =
            ProcessBuilder(File(worktree, "gradlew").absolutePath, *args)
                .directory(worktree)
                .redirectErrorStream(true)
                .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        check(proc.waitFor(6, TimeUnit.MINUTES)) { "gradle ${args.joinToString(" ")} timed out" }
        return Run(proc.exitValue(), output)
    }

    /**
     * Runs gradle but returns as soon as [needle] appears in the stream (bounded
     * at 90s), destroying the process. Used for `--dry-run` task-graph proofs whose
     * full graph may hang in the stripped-network test environment.
     */
    protected fun gradleUntil(
        needle: String,
        vararg args: String,
    ): Run {
        val proc =
            ProcessBuilder(File(worktree, "gradlew").absolutePath, *args)
                .directory(worktree)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                output.append(reader.read().toChar())
            }
            if (output.contains(needle)) {
                proc.destroy()
                proc.waitFor(10, TimeUnit.SECONDS)
                unlockGit()
                return Run(0, output.toString())
            }
            Thread.sleep(50)
        }
        proc.destroy()
        unlockGit()
        return Run(proc.exitValue(), output.toString())
    }

    private fun unlockGit() {
        // A destroyed gradle client may leave the worktree's git index locked.
        val dotGit = File(worktree, ".git")
        val gitDir = if (dotGit.isFile) dotGit.readText().removePrefix("gitdir: ").trim() else dotGit.absolutePath
        File(gitDir, "index.lock").delete()
    }

    protected fun writeKt(
        relativePath: String,
        content: String,
    ) {
        val f = File(worktree, relativePath)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    protected fun commit(message: String) {
        git(worktree, "add", "-A")
        git(worktree, "commit", "-qm", message)
    }

    protected fun head(): String = git(worktree, "rev-parse", "HEAD").trim()

    /** Branch pointing at the current worktree HEAD (baseline present). */
    protected fun baseBranch(): String {
        git(worktree, "branch", "-f", "base", head())
        return "base"
    }

    /** Runs the canonical gate against the given exact base ref. */
    protected fun staticAnalysis(
        baseRef: String,
        changeClass: String = "build-logic",
    ): Run =
        gradle(
            "--no-build-cache",
            "verifyStaticAnalysis",
            "-PtramaiStaticAnalysisBaseRef=$baseRef",
            "-PchangeClass=$changeClass",
        )

    /** A file that deterministically produces a MagicNumber finding. */
    protected fun probeKt(packageLine: String): String = "$packageLine\nval probeMagic = 42\n"

    protected fun assertFails(
        run: Run,
        what: String,
    ) {
        assertTrue(run.exit != 0, "$what should FAIL but exited 0. Output: ${run.output.take(1500)}")
    }

    protected fun assertPasses(
        run: Run,
        what: String,
    ) {
        assertTrue(run.exit == 0, "$what should PASS but exited ${run.exit}. Output: ${run.output.take(1500)}")
    }

    protected fun baselineIds(): List<String> {
        val xml = File(worktree, "config/detekt/baseline.xml").readText()
        return Regex("<ID>([^<]*)</ID>").findAll(xml).map { it.groupValues[1] }.toList()
    }
}
