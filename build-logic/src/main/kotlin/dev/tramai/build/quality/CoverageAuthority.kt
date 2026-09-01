package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.io.File

/**
 * Base-authoritative coverage policy snapshot.
 *
 * 10.3b: the coverage policy that JUDGES a candidate PR must come from the PR
 * base / master, never from the candidate being judged. This bundles the base
 * `test-quality.yml` (critical modules, tolerance, exclusions) with the base
 * `coverage-baseline.json` (committed measurement evidence).
 *
 * The base SHA is the enforcement authority. `CoverageData.measuredCommit` is
 * provenance only: TramAI squash-merges PRs, so a measurement commit may be a
 * pre-squash PR commit and not an ancestor of final master (already true for
 * the 10.3a baseline, measuredCommit = 15f4ffb6 on the #356 branch).
 */
data class CoverageAuthority(
    val baseSha: String,
    val configuration: TestQualityConfiguration,
    val baseline: CoverageData,
)

object CoverageAuthorityLoader {
    /**
     * Resolve the authoritative base SHA, mirroring cancellation safety:
     *
     * - `-PtramaiCoverageBaseSha=<sha>` (CI passes pull_request.base.sha /
     *   push event.before) — validated to be a real commit.
     * - absent → `git merge-base HEAD origin/master` (local development).
     *
     * Failure to obtain a valid SHA is a HARD failure. There is deliberately
     * no "base unavailable → use candidate baseline" fallback: that would
     * destroy the entire guarantee.
     */
    fun resolveBaseSha(
        rootDir: File,
        explicitBaseSha: String?,
    ): String {
        val explicit = explicitBaseSha?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            requireCommitExists(rootDir, explicit)
            return explicit
        }
        val result =
            runGit(
                rootDir,
                listOf("merge-base", "HEAD", "origin/master"),
            )
        val sha = result.trim()
        if (result.contains("fatal:") || !sha.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException(
                "Cannot resolve coverage base SHA: pass -PtramaiCoverageBaseSha=<sha> " +
                    "(CI: pull_request.base.sha / push before) or ensure origin/master is present. " +
                    "Git output: ${result.trim()}",
            )
        }
        return sha
    }

    /**
     * Load the base test-quality.yml + module catalog + coverage-baseline.json
     * at [baseSha] into a hermetic temp tree so the EXISTING parsers run
     * unchanged (including catalog validation).
     */
    fun load(
        rootDir: File,
        baseSha: String,
    ): CoverageAuthority {
        val tempDir =
            java.nio.file.Files
                .createTempDirectory("tramai-coverage-base-")
                .toFile()
        try {
            val qualityDir = File(tempDir, "config/quality").apply { mkdirs() }
            for (rel in listOf("test-quality.yml", "module-catalog.yml")) {
                val content =
                    runGit(rootDir, listOf("show", "$baseSha:config/quality/$rel"))
                if (content.contains("fatal:")) {
                    throw GradleException(
                        "Coverage base authority missing config/quality/$rel at $baseSha: ${content.trim()}",
                    )
                }
                File(qualityDir, rel).writeText(content, Charsets.UTF_8)
            }
            val baselineContent =
                runGit(rootDir, listOf("show", "$baseSha:config/quality/coverage-baseline.json"))
            if (baselineContent.contains("fatal:")) {
                throw GradleException(
                    "Coverage base authority missing config/quality/coverage-baseline.json at $baseSha: " +
                        baselineContent.trim(),
                )
            }
            val baselineFile = File(qualityDir, "coverage-baseline.json")
            baselineFile.writeText(baselineContent, Charsets.UTF_8)
            return CoverageAuthority(
                baseSha = baseSha,
                configuration = TestQualityConfiguration.load(tempDir),
                baseline = ReportNormalizer.readJson(baselineFile, CoverageData::class.java),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun requireCommitExists(
        rootDir: File,
        sha: String,
    ) {
        val result = runGit(rootDir, listOf("cat-file", "-e", "$sha^{commit}"))
        if (result.contains("fatal:")) {
            throw GradleException("Coverage base SHA '$sha' is not a valid commit: ${result.trim()}")
        }
    }

    private fun runGit(
        rootDir: File,
        args: List<String>,
    ): String =
        ProcessBuilder(listOf("git") + args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output
            }
}
