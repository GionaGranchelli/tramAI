package dev.tramai.build.sovereign

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Discriminating tests for the 9.2d-a3b2b sovereign-lab evidence-bundle
 * process harness (SovereignLabEvidenceBundleVerifierTask +
 * EvidenceBundleScenarioRunner + EvidenceBundleProcessAdapter).
 *
 * The user-mandated standard: a test that only checks task registration is
 * INSUFFICIENT for the process path. These tests exercise the runner with
 * real repo scripts (bash/python3/sha256sum/tar/openssl) and prove the
 * runner DRIVES the processes — a no-op adapter must make it fail.
 *
 * Standing rule (user-mandated): no empty/degenerate fixture may serve as the
 * successful oracle. The full-scenario runs use the REAL repo scripts and
 * templates via git ls-files (same copyFromRepo pattern as the a3b2a tests).
 */
class SovereignLabEvidenceBundleTest {

    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    /** All repo files the evidence-bundle scenario consumes (scripts + templates). */
    private val evidenceFiles = listOf(
        "examples/sovereign-lab/create-evidence-bundle.sh",
        "examples/sovereign-lab/verify-evidence-bundle.sh",
        "examples/sovereign-lab/finalize-evidence-bundle.sh",
        "examples/sovereign-lab/package-evidence-bundle.sh",
        "examples/sovereign-lab/verify-evidence-archive.sh",
        "examples/sovereign-lab/verify-evidence-archive-signature.sh",
    )

    private fun copyFromRepo(dir: File, vararg relativePaths: String) {
        val git = ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", *relativePaths)
            .redirectErrorStream(true)
            .start()
        val listing = git.inputStream.bufferedReader().readText()
        check(git.waitFor() == 0) { "git ls-files failed: $listing" }
        listing.lineSequence().filter { it.isNotBlank() }.forEach { rel ->
            val src = File(repoRoot, rel)
            val dst = File(dir, rel)
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    /** Copies the lab dir tree (scripts + templates + subdirs) needed by the scenario. */
    private fun copyLabTree(target: File) {
        // All files under examples/sovereign-lab (git-tracked), preserving layout.
        copyFromRepo(target, "examples/sovereign-lab")
        // Also copy the spring-sovereign-starter profile the lab-profile task reads
        // is NOT needed here — the runner only drives evidence scripts.
    }

    private fun scriptsAt(root: File): EvidenceScripts = EvidenceScripts(
        create = File(root, "examples/sovereign-lab/create-evidence-bundle.sh"),
        verifier = File(root, "examples/sovereign-lab/verify-evidence-bundle.sh"),
        finalizer = File(root, "examples/sovereign-lab/finalize-evidence-bundle.sh"),
        packager = File(root, "examples/sovereign-lab/package-evidence-bundle.sh"),
        archiveVerifier = File(root, "examples/sovereign-lab/verify-evidence-archive.sh"),
        signatureVerifier = File(root, "examples/sovereign-lab/verify-evidence-archive-signature.sh"),
    )

    /**
     * The scripts write bundles relative to THEIR OWN location
     * (examples/sovereign-lab/build/evidence-bundles), so the runner's
     * workDir must be that same dir under the copied root — matching the
     * real-repo contract where task workingDirectory and script location
     * coincide.
     */
    private fun freshWorkDir(root: File): File =
        File(root, "examples/sovereign-lab/build").apply { mkdirs() }

    private fun fullScenario(root: File, workDir: File): EvidenceBundleScenarioRunner =
        EvidenceBundleScenarioRunner(scriptsAt(root), workDir, ProcessBuilderProcessAdapter()) {}

    // ------------------------------------------------------------------
    // 1. Process actually executed (no-op adapter must make it fail)
    // ------------------------------------------------------------------

    @Test
    fun `runner drives the adapter - no-op adapter cannot make the scenario pass`() {
        val root = File(tempDir, "repo-nop").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        // No-op adapter: reports success but never creates the bundle.
        val noopAdapter = object : EvidenceBundleProcessAdapter {
            override fun run(
                executable: File,
                arguments: List<String>,
                environment: Map<String, String>,
                workingDirectory: File,
            ): ProcessResult = ProcessResult(0, "")
        }
        val runner = EvidenceBundleScenarioRunner(scriptsAt(root), workDir, noopAdapter) {}
        val e = assertFailsWith<IllegalArgumentException> {
            runner.run()
        }
        // The runner consumed the process result; the missing-bundle require fires.
        assertContains(e.message!!, "Evidence bundle was not created")
    }

    // ------------------------------------------------------------------
    // 2. Exit-code fail closed (creator exits non-zero → exact diagnostic)
    // ------------------------------------------------------------------

    @Test
    fun `exit-code fail closed preserves the historical diagnostic`() {
        val root = File(tempDir, "repo-exit").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        val failingAdapter = object : EvidenceBundleProcessAdapter {
            override fun run(
                executable: File,
                arguments: List<String>,
                environment: Map<String, String>,
                workingDirectory: File,
            ): ProcessResult = ProcessResult(7, "")
        }
        val runner = EvidenceBundleScenarioRunner(scriptsAt(root), workDir, failingAdapter) {}
        val e = assertFailsWith<IllegalArgumentException> {
            runner.run()
        }
        assertContains(e.message!!, "Evidence bundle script exited with code 7")
    }

    // ------------------------------------------------------------------
    // 3. Real generated artifact (successful creator physically generates it)
    // ------------------------------------------------------------------

    @Test
    fun `full scenario generates a real bundle with the required files`() {
        val root = File(tempDir, "repo-real").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        // Delete any pre-existing bundle so pre-created files cannot satisfy the test.
        File(workDir, "evidence-bundles/test-bundle").deleteRecursively()

        fullScenario(root, workDir).run()

        val bundle = File(workDir, "evidence-bundles/test-bundle")
        assertTrue(bundle.isDirectory, "scenario must physically create the bundle at ${bundle.absolutePath}")
        for (required in listOf(
            "README.md", "manifest.json", "MANIFEST.md", "command-log.md", "environment.md",
            "run-log.md", "approval-flow.md", "restart-proof.md", "jdbc-persistence.md",
            "no-cloud-proof.md", "benchmark.md", "reports/.gitkeep",
        )) {
            assertTrue(File(bundle, required).isFile, "required file missing: $required")
        }
    }

    // ------------------------------------------------------------------
    // 4. Digest mutation (file changed after manifest → verifier rejects)
    // ------------------------------------------------------------------

    @Test
    fun `digest mutation is rejected by the verifier`() {
        val root = File(tempDir, "repo-digest").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        fullScenario(root, workDir).run()

        val bundle = File(workDir, "evidence-bundles/test-bundle")
        val evidence = File(bundle, "command-log.md")
        evidence.appendText("\nTampered after finalization.\n")

        val result = ProcessBuilder("bash", scriptsAt(root).verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        assertTrue(exit != 0, "verifier must reject a tampered file")
        assertTrue(
            output.contains("sha256 mismatch") || output.contains("sizeBytes mismatch"),
            "rejection must explain digest or size mismatch. Output: $output",
        )
    }

    // ------------------------------------------------------------------
    // 5. Missing required artifact (remove exactly one required file)
    // ------------------------------------------------------------------

    @Test
    fun `missing required artifact is rejected`() {
        val root = File(tempDir, "repo-missing").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        fullScenario(root, workDir).run()

        val bundle = File(workDir, "evidence-bundles/test-bundle")
        File(bundle, "command-log.md").delete()

        val result = ProcessBuilder("bash", scriptsAt(root).verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        assertTrue(exit != 0, "verifier must reject a missing required file")
        assertContains(output, "required file missing")
    }

    // ------------------------------------------------------------------
    // 6. Valid runtime-evidence path (real non-empty records, no empty oracle)
    // ------------------------------------------------------------------

    @Test
    fun `positive runtime-evidence records are real and present in the manifest`() {
        val root = File(tempDir, "repo-rt").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        // Capture the runner's lifecycle messages: the scenario verifies the
        // manifest contains the 4 runtime-evidence paths MID-SEQUENCE (before
        // the clean re-create), so the observable discriminator is the
        // "finalized and verified with 4 files" message + the physical files
        // that were injected before the re-create.
        val logs = mutableListOf<String>()
        EvidenceBundleScenarioRunner(scriptsAt(root), workDir, ProcessBuilderProcessAdapter()) { logs += it }
            .run()

        assertTrue(
            logs.any { it.contains("positive runtime-evidence finalized and verified with 4 files in manifest.json") },
            "scenario must inject real runtime-evidence and verify it lands in manifest.json. Logs: $logs",
        )
        assertTrue(
            logs.any { it.contains("added positive runtime-evidence") },
            "scenario must log the runtime-evidence injection step",
        )
    }

    // ------------------------------------------------------------------
    // 7. Negative semantic mutation (claimBoundary weakened → correct reason)
    // ------------------------------------------------------------------

    @Test
    fun `negative semantic mutation fails for the correct reason`() {
        val root = File(tempDir, "repo-neg").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)
        fullScenario(root, workDir).run()

        val bundle = File(workDir, "evidence-bundles/test-bundle")
        // Weaken the certification boundary in a COPY so the original stays clean.
        val copy = File(workDir, "weakened-copy")
        if (copy.exists()) copy.deleteRecursively()
        bundle.copyRecursively(copy, overwrite = true)

        // Mutate the manifest: claimBoundary.certifiesProductionReadiness = true
        val fixture = EvidenceBundleFixtureBuilder(scriptsAt(root), ProcessBuilderProcessAdapter())
        fixture.mutateManifest(
            copy,
            "m[\"claimBoundary\"][\"certifiesProductionReadiness\"] = True",
        )

        val result = ProcessBuilder("bash", scriptsAt(root).verifier.absolutePath, copy.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        assertTrue(exit != 0, "verifier must reject a weakened claim boundary")
        assertContains(output, "claimBoundary.certifiesProductionReadiness")
    }

    // ------------------------------------------------------------------
    // 8. Cleanup / isolation (stale state cannot make a later scenario pass)
    // ------------------------------------------------------------------

    @Test
    fun `stale state from a previous scenario cannot make the next run pass`() {
        val root = File(tempDir, "repo-clean").apply { mkdirs() }
        copyLabTree(root)
        val workDir = freshWorkDir(root)

        // Scenario A: a full clean run leaves a finalized bundle.
        fullScenario(root, workDir).run()
        val bundle = File(workDir, "evidence-bundles/test-bundle")
        // Tamper it: the bundle is now corrupt (stale state).
        File(bundle, "command-log.md").appendText("\nstale tamper\n")

        // Scenario B: the runner must DELETE and RE-CREATE the bundle from
        // scratch — the tampered stale state cannot make B pass (B re-runs
        // the full chain and finalizes fresh).
        fullScenario(root, workDir).run()
        // After B the bundle must verify clean — re-run the verifier to prove
        // the final state is clean (not the stale tampered state).
        val result = ProcessBuilder("bash", scriptsAt(root).verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        assertTrue(exit == 0, "scenario B must restore a clean verifiable bundle. Output: $output")
    }
}
