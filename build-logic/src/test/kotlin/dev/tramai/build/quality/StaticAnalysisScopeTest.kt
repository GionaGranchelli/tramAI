package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * P0-F..P0-J: malformed baseline, one-time bootstrap, and source-universe scope.
 */
class StaticAnalysisScopeTest : StaticAnalysisContractTestBase() {

    @Test
    fun `p0-f malformed baseline fails`() {
        val base = baseBranch()
        val xml = File(worktree, "config/detekt/baseline.xml")

        // (a) wrong root element
        xml.writeText(xml.readText().replace("<SmellBaseline>", "<WrongBaseline>"))
        commit("malformed baseline root")
        val runWrongRoot = staticAnalysis(base)
        assertFails(runWrongRoot, "gate with wrong baseline root element")
        assertTrue(
            runWrongRoot.output.contains("DETEKT_BASELINE_MALFORMED"),
            "must cite DETEKT_BASELINE_MALFORMED. Output: ${runWrongRoot.output.take(1200)}",
        )

        // (b) duplicate baseline ID
        git(worktree, "reset", "--hard", base)
        val ids = baselineIds()
        assertTrue(ids.isNotEmpty(), "baseline must not be empty")
        val dup = ids.first()
        xml.writeText(
            xml.readText().replaceFirst("    <ID>${Regex.escape(dup)}</ID>", "    <ID>$dup</ID>\n    <ID>$dup</ID>")
        )
        commit("duplicate baseline ID")
        val runDup = staticAnalysis(base)
        assertFails(runDup, "gate with a duplicate baseline ID")
        assertTrue(
            runDup.output.contains("DETEKT_BASELINE_MALFORMED"),
            "duplicate must cite DETEKT_BASELINE_MALFORMED. Output: ${runDup.output.take(1200)}",
        )
    }

    @Test
    fun `p0-g bootstrap is one-time`() {
        // (a) initial adoption: base (origin/master) has NO Detekt baseline, current
        // has one, change class is build-logic, no runtime changes -> allowed.
        val runAdoption = staticAnalysis("origin/master", changeClass = "build-logic")
        assertPasses(runAdoption, "initial adoption bootstrap")
        assertTrue(
            runAdoption.output.contains("Initial Detekt baseline adoption"),
            "must report the bootstrap. Output: ${runAdoption.output.take(1200)}",
        )

        // (b) the same absence but with a runtime change class -> refused.
        val runAbuse = staticAnalysis("origin/master", changeClass = "runtime-behaviour")
        assertFails(runAbuse, "bootstrap under runtime change class")
        assertTrue(
            runAbuse.output.contains("DETEKT_BASELINE_BOOTSTRAP_ABUSE"),
            "must cite DETEKT_BASELINE_BOOTSTRAP_ABUSE. Output: ${runAbuse.output.take(1200)}",
        )

        // (c) once the base HAS a baseline, deleting/recreating cannot invoke
        // bootstrap: deletion fails closed.
        val base = baseBranch()
        assertTrue(File(worktree, "config/detekt/baseline.xml").delete(), "failed to delete baseline")
        commit("delete baseline")
        val runDelete = staticAnalysis(base)
        assertFails(runDelete, "baseline deletion after adoption")
        assertTrue(
            runDelete.output.contains("DETEKT_BASELINE_DELETED"),
            "deletion must cite DETEKT_BASELINE_DELETED. Output: ${runDelete.output.take(1200)}",
        )
    }

    @Test
    fun `p0-h build-logic source covered`() {
        val base = baseBranch()
        // Deliberately inside the `dev.tramai.build` package path: the source
        // universe must NOT exclude it just because the segment is named `build`.
        writeKt(
            "build-logic/src/main/kotlin/dev/tramai/build/StaticAnalysisProbe.kt",
            probeKt("package dev.tramai.build"),
        )
        commit("add violation under build-logic")
        val run = staticAnalysis(base)
        assertFails(run, "gate with a new violation under build-logic/src")
        assertTrue(
            run.output.contains("non-baselined findings"),
            "build-logic violation must be analyzed. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-i test source covered`() {
        val base = baseBranch()
        writeKt(
            "tramai-core/src/test/kotlin/dev/tramai/core/StaticAnalysisProbeTest.kt",
            probeKt("package dev.tramai.core"),
        )
        commit("add violation under test sources")
        val run = staticAnalysis(base)
        assertFails(run, "gate with a new violation under a test source set")
        assertTrue(
            run.output.contains("non-baselined findings"),
            "test-source violation must be analyzed. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-j generated build output ignored`() {
        val base = baseBranch()
        writeKt(
            "tramai-core/build/generated/StaticAnalysisProbe.kt",
            probeKt("package dev.tramai.generated"),
        )
        commit("add violation under generated build output")
        val run = staticAnalysis(base)
        assertPasses(run, "gate with a violation inside an excluded build/ dir")
    }
}
