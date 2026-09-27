package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task-level discriminators for the population-admission ledger wiring (0.7.1g1G4z).
 *
 * The M30-M39 ceremony was proved at verifier level; what these tests prove is the boundary the
 * verifier cannot: that `verifyMutationRatchet` — the actual governance task — loads the
 * repository's own `config/quality/mutation-population-admissions.yml` into the candidate it judges.
 *
 * Every case changes one real repository file and observes the real task:
 *
 * - the ledger absent: an appearing survivor still fails M06 (non-vacuous baseline);
 * - the ledger present with an authorization minted against a different base: M35 fires, which is
 *   only possible if the task actually read the candidate ledger. If the candidate ledger were
 *   silently defaulted to "none", this case would fail with M06 alone and M35 could never fire;
 * - the ledger present but malformed: the task fails closed on the loader error instead of
 *   degrading to "no authorizations".
 */
class MutationPopulationAdmissionWiringTest : MutationRatchetTestSupport() {
    @TempDir
    lateinit var tempDir: File

    private val sampleFamily =
        MutationTargetFamily(
            modules = listOf(":sample"),
            targetClasses = listOf("dev.tramai.policy.*"),
            targetTests = listOf("dev.tramai.policy.PolicyTest"),
        )

    private val sampleFamilies = mapOf(policyFamily to sampleFamily)

    private val appearingMarker = "appearing"

    private fun basePopulation() =
        population(
            listOf(row("anchor", module = ":sample", status = "KILLED", outcome = "KILLED")),
            families = sampleFamilies,
            measuredCommit = "base-head",
        )

    private fun candidatePopulation() =
        population(
            listOf(
                row("anchor", module = ":sample", status = "KILLED", outcome = "KILLED"),
                row(appearingMarker, module = ":sample"),
            ),
            families = sampleFamilies,
            measuredCommit = "candidate-head",
        )

    private fun fixture(): File {
        val dir = File(tempDir, "admission-wiring").apply { mkdirs() }
        write(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "admission-wiring"
            include(":sample")
            """.trimIndent(),
        )
        write(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            """.trimIndent(),
        )
        write(dir, "sample/build.gradle.kts", "plugins { `java-library` }")
        write(
            dir,
            "config/quality/module-catalog.yml",
            """
            schemaVersion: "3"
            dependencyPolicies:
              testing:
                allowedLayers: [testing-support]
            entryDefaults:
              internal: &internal
                layer: "testing-support"
                maturity: "internal"
                publishability: "internal"
                apiStability: "excluded"
                visibility: "internal"
                owner: "testing"
                dependencyPolicy: "testing"
                releaseInclusion: "internal_only"
                rationale: "Provides a TestKit fixture module."
            modules:
              - path: ":sample"
                <<: *internal
            """.trimIndent(),
        )
        write(
            dir,
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":sample"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                $policyFamily:
                  modules: [":sample"]
                  targetClasses: ["dev.tramai.policy.*"]
                  targetTests: ["dev.tramai.policy.PolicyTest"]
            """.trimIndent(),
        )
        write(dir, "config/quality/mutation-classifications.yml", "schemaVersion: \"1\"\nclassifications: []\n")
        write(dir, "config/quality/mutation-evolution.yml", "schemaVersion: \"1\"\nrecords: []\n")
        return dir
    }

    private fun write(
        dir: File,
        path: String,
        content: String,
    ) {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun writePopulation(
        dir: File,
        population: MutationPopulationBaseline,
    ) {
        ReportNormalizer.writeJson(population, File(dir, "config/quality/mutation-baseline.json"))
    }

    private fun admissionLedger(
        identity: String,
        fromBaseSha: String,
    ): String =
        """
        schemaVersion: "1"
        admissions:
          - identity: "$identity"
            status: "SURVIVED"
            outcome: "NON_KILLED"
            family: "$policyFamily"
            module: ":sample"
            analyzer:
              pluginVersion: "1.19.0"
              engineVersion: "1.22.1"
              mutators:
                - "$MUTATOR"
              timeoutConst: 4000
              timeoutFactor: 1.25
            fromBaseSha: "$fromBaseSha"
            populationDigest: "$projectionDigest"
            reason: "wiring fixture"
        """.trimIndent() + "\n"

    /** Commits the base state and leaves the candidate state uncommitted, as a PR working tree is. */
    private fun commitBase(dir: File): String {
        writePopulation(dir, basePopulation())
        git(dir, "init")
        git(dir, "add", "-A")
        git(dir, "-c", "user.email=fixture@test", "-c", "user.name=fixture", "commit", "-m", "base")
        val sha = git(dir, "rev-parse", "HEAD").trim()
        assertTrue(sha.matches(Regex("[0-9a-f]{40}")), "fixture base SHA must be a real commit, got '$sha'")
        return sha
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")} failed: $output")
        return output
    }

    private fun runner(
        dir: File,
        baseSha: String,
    ) = GradleRunner
        .create()
        .withProjectDir(dir)
        .withArguments("verifyMutationRatchet", "-PtramaiMutationBaseSha=$baseSha", "--stacktrace")
        .withPluginClasspath()

    private val appearingIdentity = row(appearingMarker, module = ":sample").identity

    private val wrongBaseSha = "1".repeat(40)

    /** Structural only: the loader never validates that a digest corresponds to a real campaign. */
    private val projectionDigest = "a".repeat(64)

    @Test
    fun `an appearing survivor with no candidate ledger still fails M06`() {
        val dir = fixture()
        val baseSha = commitBase(dir)
        writePopulation(dir, candidatePopulation())

        val result = runner(dir, baseSha).buildAndFail()

        val output = result.output
        assertTrue(output.contains("M06"), "expected the M06 new-survivor failure, got:\n$output")
        assertTrue(
            !output.contains("M35"),
            "M35 must not fire when the candidate ledger is absent:\n$output",
        )
    }

    @Test
    fun `the candidate ledger is read by the real task and a wrong fromBaseSha fails M35`() {
        val dir = fixture()
        val baseSha = commitBase(dir)
        writePopulation(dir, candidatePopulation())
        write(dir, "config/quality/mutation-population-admissions.yml", admissionLedger(appearingIdentity, wrongBaseSha))

        val result = runner(dir, baseSha).buildAndFail()

        assertTrue(
            result.output.contains("M35"),
            "expected the M35 fromBaseSha binding failure, which is only reachable when the task loads " +
                "the candidate ledger, got:\n${result.output}",
        )
    }

    @Test
    fun `a malformed candidate ledger fails the real task closed`() {
        val dir = fixture()
        val baseSha = commitBase(dir)
        writePopulation(dir, candidatePopulation())
        write(
            dir,
            "config/quality/mutation-population-admissions.yml",
            "schemaVersion: \"1\"\nadmissions:\n  - identity: \"$appearingIdentity\"\n",
        )

        val result = runner(dir, baseSha).buildAndFail()

        assertTrue(
            result.output.contains("mutation-population-admissions.yml"),
            "expected the loader's own hard failure for the malformed ledger, got:\n${result.output}",
        )
    }
}
