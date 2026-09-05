package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.io.File

/**
 * Base-authoritative mutation authority (Epic 10.3c3).
 *
 * The mutation population and classifications that JUDGE a candidate PR must
 * come from the PR base / master, never from the candidate being judged. This
 * bundles the base `mutation-baseline.json` (exact population, identity
 * schema v2), the base `mutation-classifications.yml` (approved survivors),
 * and the base mutation slice of `test-quality.yml` (families, target
 * classes, target tests) into one hermetic snapshot.
 *
 * The base SHA is the enforcement authority. `MutationPopulationBaseline.measuredCommit`
 * is provenance only: TramAI squash-merges PRs, so the measurement commit may
 * be a pre-squash PR commit and not an ancestor of final master (true for the
 * 10.3c2 authority, measuredCommit 5856530e).
 */
data class MutationRatchetAuthority(
    val baseSha: String,
    val population: MutationPopulationBaseline,
    val classifications: MutationClassifications,
    val targetFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
)

/**
 * The candidate's committed proposal under review: its own mutation
 * population, classifications, and mutation target configuration. Never the
 * authority for its own regression — the base authority judges it.
 */
data class MutationRatchetCandidate(
    val population: MutationPopulationBaseline,
    val classifications: MutationClassifications,
    val targetFamilies: Map<String, TestQualityConfiguration.MutationTargetFamily>,
)

object MutationRatchetAuthorityLoader {
    /**
     * Resolve the authoritative base SHA, mirroring cancellation safety and
     * critical coverage:
     *
     * - `-PtramaiMutationBaseSha=<sha>` (CI passes pull_request.base.sha /
     *   push event.before) — validated to be a real commit.
     * - absent → `git merge-base HEAD origin/master` (local development).
     *
     * Failure to obtain a valid SHA is a HARD failure. There is deliberately
     * no "base unavailable → use candidate authority" fallback: that would
     * destroy the entire ratchet guarantee (M20).
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
        val result = runGit(rootDir, listOf("merge-base", "HEAD", "origin/master"))
        if (result.exitCode != 0) {
            throw GradleException(
                "Cannot resolve mutation base SHA: pass -PtramaiMutationBaseSha=<sha> " +
                    "(CI: pull_request.base.sha / push before) or ensure origin/master is present. " +
                    "Git exit ${result.exitCode}: ${result.output.trim()}",
            )
        }
        val sha = result.output.trim()
        if (!sha.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException(
                "Cannot resolve mutation base SHA: git merge-base returned " +
                    "'$sha' — pass -PtramaiMutationBaseSha=<sha> or ensure origin/master is present.",
            )
        }
        return sha
    }

    /**
     * Load the base mutation authority at [baseSha] into a hermetic temp tree
     * so the EXISTING parsers run unchanged (including YAML validation). A
     * missing file at the base SHA is a HARD failure — the ratchet has no
     * authority to compare against and must fail closed (M20).
     */
    fun load(
        rootDir: File,
        baseSha: String,
    ): MutationRatchetAuthority {
        val tempDir =
            java.nio.file.Files
                .createTempDirectory("tramai-mutation-base-")
                .toFile()
        try {
            val qualityDir = File(tempDir, "config/quality").apply { mkdirs() }
            for (rel in MUTATION_SOURCE_FILES) {
                val result = runGit(rootDir, listOf("show", "$baseSha:$rel"))
                if (result.exitCode != 0) {
                    throw GradleException(
                        "Mutation base authority missing $rel at $baseSha: " +
                            "git exit ${result.exitCode}: ${result.output.trim()}",
                    )
                }
                File(qualityDir, rel.substringAfterLast('/')).writeText(result.output, Charsets.UTF_8)
            }
            val baselineFile = File(qualityDir, "mutation-baseline.json")
            val population = readPopulation(baselineFile, baseSha)
            val classifications = MutationClassificationLoader.load(tempDir)
            val configuration = TestQualityConfiguration.load(tempDir)
            return MutationRatchetAuthority(
                baseSha = baseSha,
                population = population,
                classifications = classifications,
                targetFamilies = configuration.mutation.targetFamilies,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun readPopulation(
        baselineFile: File,
        baseSha: String,
    ): MutationPopulationBaseline =
        try {
            ReportNormalizer.readJson(baselineFile, MutationPopulationBaseline::class.java)
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw GradleException("Base mutation-baseline.json at $baseSha is malformed: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw GradleException("Base mutation-baseline.json at $baseSha is malformed: ${e.message}", e)
        }

    private fun requireCommitExists(
        rootDir: File,
        sha: String,
    ) {
        val result = runGit(rootDir, listOf("cat-file", "-e", "$sha^{commit}"))
        if (result.exitCode != 0) {
            throw GradleException(
                "Mutation base SHA '$sha' is not a valid commit: git exit ${result.exitCode}: ${result.output.trim()}",
            )
        }
    }

    private data class GitResult(
        val output: String,
        val exitCode: Int,
    )

    private fun runGit(
        rootDir: File,
        args: List<String>,
    ): GitResult =
        ProcessBuilder(listOf("git") + args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                GitResult(output, exitCode)
            }

    private val MUTATION_SOURCE_FILES =
        listOf(
            "config/quality/mutation-baseline.json",
            "config/quality/mutation-classifications.yml",
            "config/quality/test-quality.yml",
            "config/quality/module-catalog.yml",
        )
}
