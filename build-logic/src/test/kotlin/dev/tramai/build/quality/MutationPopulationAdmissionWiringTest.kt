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
        writeQualityConfig(dir)
        return dir
    }

    /** The authority surface the ratchet task reads; only the population and the ledger vary per case. */
    private fun writeQualityConfig(dir: File) {
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

    /**
     * A ledger entry proposing the appearing identity, written with the canonical analyzer semantics
     * the population carries (M33 compares them, so a subset would stop the transition there).
     */
    private fun admissionLedger(
        identity: String,
        fromBaseSha: String,
        reason: String = "wiring fixture",
    ): String {
        val mutatorLines = semantics.mutators.sorted().joinToString("\n") { "                - \"$it\"" }
        return """
        schemaVersion: "1"
        admissions:
          - identity: "$identity"
            status: "SURVIVED"
            outcome: "NON_KILLED"
            family: "$policyFamily"
            module: ":sample"
            analyzer:
              pluginVersion: "${semantics.pluginVersion}"
              engineVersion: "${semantics.engineVersion}"
              mutators:
$mutatorLines
              timeoutConst: ${semantics.timeoutConst}
              timeoutFactor: ${semantics.timeoutFactor}
            fromBaseSha: "$fromBaseSha"
            populationDigest: "$projectionDigest"
            reason: "$reason"
            """.trimIndent() + "\n"
    }

    /**
     * Commits the base state and leaves the candidate state uncommitted, as a PR working tree is.
     *
     * When [ledger] is given it belongs to the BASE commit — the only place an authorization can be
     * read from, since the base side is resolved with git and never from the working tree.
     */
    private fun commitBase(
        dir: File,
        ledger: String? = null,
    ): String {
        writePopulation(dir, basePopulation())
        if (ledger != null) {
            write(dir, "config/quality/mutation-population-admissions.yml", ledger)
        }
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

    /** The base ledger's own fromBaseSha is mint-time provenance; M35 judges candidate-side mints only. */
    private val neutralBaseSha = "0".repeat(40)

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
        write(
            dir,
            "config/quality/mutation-population-admissions.yml",
            admissionLedger(appearingIdentity, wrongBaseSha),
        )

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

    // ── lifecycle at the real task boundary: MINT → RETAIN → CONSUME ──

    @Test
    fun `a pending authorization retained byte-identically across an unrelated transition passes`() {
        val dir = fixture()
        val ledger = admissionLedger(appearingIdentity, fromBaseSha = neutralBaseSha)
        val baseSha = commitBase(dir, ledger)
        writePopulation(dir, basePopulation())

        // The authorization is still pending: its target has not appeared, so nothing consumes it.
        // Before the candidate ledger was wired into the task this was a hard M37 failure - the task
        // judged the candidate as holding no authorizations and read the pending row as a silent
        // cancellation of base authority.
        val result = runner(dir, baseSha).build()

        assertTrue(
            !result.output.contains("M37"),
            "a byte-identical pending authorization must not be read as a silent removal:\n${result.output}",
        )
    }

    @Test
    fun `a retained authorization rewritten in a bound field fails M36`() {
        val dir = fixture()
        val ledger = admissionLedger(appearingIdentity, fromBaseSha = neutralBaseSha)
        val baseSha = commitBase(dir, ledger)
        writePopulation(dir, basePopulation())
        write(
            dir,
            "config/quality/mutation-population-admissions.yml",
            admissionLedger(appearingIdentity, fromBaseSha = neutralBaseSha, reason = "rewritten after mint"),
        )

        val result = runner(dir, baseSha).buildAndFail()

        assertTrue(result.output.contains("M36"), "expected the immutable-retention failure, got:\n${result.output}")
    }

    @Test
    fun `an authorized row appearing fails closed on analyzer semantics before admission`() {
        val dir = fixture()
        val ledger = admissionLedger(appearingIdentity, fromBaseSha = neutralBaseSha)
        val baseSha = commitBase(dir, ledger)
        writePopulation(dir, candidatePopulation())

        val result = runner(dir, baseSha).buildAndFail()

        // Documents the CURRENT behaviour, including its cause. The admission loader normalizes the
        // ledger's mutators with `.sorted()`, while the canonical population analyzer carries
        // MutationProbeInitScript.PIT_MUTATORS in declaration order; M33 compares those two as
        // order-sensitive lists, so this transition stops at M33 and never reaches M34's fail-closed
        // digest check. That is a pre-existing defect in M30-M39 which the ledger wiring in this PR
        // makes reachable for the first time (with the candidate ledger defaulted to NONE it could
        // never fire at task level). Normalizing either side is an authority-semantics change and is
        // deliberately NOT made here.
        assertTrue(
            result.output.contains("M33"),
            "expected the analyzer-semantics stop before admission, got:\n${result.output}",
        )
    }

    @Test
    fun `removing a pending authorization while the authorized row appears fails M37`() {
        val dir = fixture()
        val ledger = admissionLedger(appearingIdentity, fromBaseSha = neutralBaseSha)
        val baseSha = commitBase(dir, ledger)
        writePopulation(dir, candidatePopulation())
        File(dir, "config/quality/mutation-population-admissions.yml").delete()

        val result = runner(dir, baseSha).buildAndFail()

        assertTrue(
            result.output.contains("M37"),
            "an authorization may not be cancelled silently, got:\n${result.output}",
        )
    }
}
