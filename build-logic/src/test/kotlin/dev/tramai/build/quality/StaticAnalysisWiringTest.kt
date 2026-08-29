package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * P0-K..P0-O: aggregate-gate wiring, exact-base authority, fail-closed tool
 * failure, and the formatting-separation invariant.
 */
class StaticAnalysisWiringTest : StaticAnalysisContractTestBase() {

    @Test
    fun `p0-k check owns gate`() {
        val run = gradleUntil(":verifyStaticAnalysis", "--no-build-cache", "check", "--dry-run")
        assertTrue(
            run.output.contains(":verifyStaticAnalysis"),
            "check's task graph must contain :verifyStaticAnalysis. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-l verifyPr owns gate`() {
        val run = gradleUntil(":verifyStaticAnalysis", "--no-build-cache", "verifyPr", "--dry-run")
        assertTrue(
            run.output.contains(":verifyStaticAnalysis"),
            "verifyPr's task graph must contain :verifyStaticAnalysis. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-m exact supplied base is authoritative`() {
        // baseA = branch head (baseline with N entries). baseB = one entry removed.
        val baseA = baseBranch()
        val ids = baselineIds()
        assertTrue(ids.size > 1, "baseline must have more than one entry")
        val xml = File(worktree, "config/detekt/baseline.xml")
        xml.writeText(
            xml.readText().replaceFirst("    <ID>${ids.first()}</ID>", "")
        )
        commit("baseB baseline")
        val baseB = "baseB"
        git(worktree, "branch", "-f", baseB, head())

        // Restore the pristine baseline into the working tree: current now has N
        // entries — identical to baseA, one MORE than baseB.
        git(worktree, "checkout", baseA, "--", "config/detekt/baseline.xml")

        val runA = staticAnalysis(baseA)
        assertPasses(runA, "gate against baseA (identical baseline)")
        val runB = staticAnalysis(baseB)
        assertFails(runB, "gate against baseB (current has one entry baseB lacks)")
        assertTrue(
            runB.output.contains("DETEKT_BASELINE_GROWTH"),
            "baseB comparison must cite DETEKT_BASELINE_GROWTH. Output: ${runB.output.take(1500)}",
        )
    }

    @Test
    fun `p0-n detekt failure fails closed`() {
        val base = baseBranch()
        // Invalid config must make the tool fail — never be read as "0 findings".
        File(worktree, "config/detekt/detekt.yml").writeText("not: [valid yaml")
        commit("corrupt detekt config")
        val run = staticAnalysis(base)
        assertFails(run, "gate when Detekt cannot load its config")
        assertTrue(
            run.output.contains("verifyStaticAnalysis: Detekt reported") ||
                run.output.contains("Detekt") && run.exit != 0,
            "tool failure must surface as a task failure. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-o formatting remains separate`() {
        val config = File(worktree, "config/detekt/detekt.yml").readText()
        // No active `formatting:` ruleset — Spotless/KtLint is the sole formatter.
        // (The word "detekt-formatting" appears only in the documented header
        // policy, never as a ruleset section.)
        assertTrue(
            !Regex("(?m)^formatting:").containsMatchIn(config),
            "detekt.yml must not enable a formatting ruleset",
        )
        val rootBuild = File(worktree, "build.gradle.kts").readText()
        assertTrue(
            !rootBuild.contains("detekt-formatting"),
            "build.gradle.kts must not apply detekt-formatting",
        )
    }
}
