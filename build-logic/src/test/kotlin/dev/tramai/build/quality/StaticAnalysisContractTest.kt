package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * P0-A..P0-E: ratchet semantics of the static-analysis gate against the
 * committed baseline. Each test builds its own git history from the branch
 * head in a disposable worktree and runs the REAL `verifyStaticAnalysis` task.
 */
class StaticAnalysisContractTest : StaticAnalysisContractTestBase() {
    @Test
    fun `p0-a existing baselined finding passes`() {
        val base = baseBranch()
        val run = staticAnalysis(base)
        assertPasses(run, "gate with unchanged baseline and no new findings")
        assertTrue(run.output.contains("baseline OK"), "expected baseline-OK verdict. Output: ${run.output.take(1200)}")
    }

    @Test
    fun `p0-b new finding fails`() {
        val base = baseBranch()
        writeKt("tramai-core/src/main/kotlin/dev/tramai/core/StaticAnalysisProbe.kt", probeKt("package dev.tramai.core"))
        commit("add new violation")
        val run = staticAnalysis(base)
        assertFails(run, "gate with a new non-baselined finding")
        assertTrue(
            run.output.contains("non-baselined findings"),
            "failure must cite non-baselined findings. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-c code cleanup passes`() {
        val base = baseBranch()
        // Deleting a baselined source file removes its findings and creates none.
        val target = File(worktree, "tramai-core/src/main/kotlin/dev/tramai/core/annotations/Operation.kt")
        assertTrue(target.isFile, "expected baselined file $target to exist")
        assertTrue(target.delete(), "failed to delete $target")
        commit("fix baselined finding")
        val run = staticAnalysis(base)
        assertPasses(run, "gate after removing a baselined finding")
    }

    @Test
    fun `p0-d baseline growth fails`() {
        val base = baseBranch()
        // A new violation AND an unauthorized new baseline entry: growth must fail
        // with DETEKT_BASELINE_GROWTH before Detekt even runs.
        writeKt("tramai-core/src/main/kotlin/dev/tramai/core/StaticAnalysisProbe.kt", probeKt("package dev.tramai.core"))
        val xml = File(worktree, "config/detekt/baseline.xml")
        val fakeEntry = "MagicNumber:StaticAnalysisProbe.kt\$probeMagic"
        if (baselineIds().none { it == fakeEntry }) {
            xml.writeText(
                xml
                    .readText()
                    .replace("  </CurrentIssues>", "    <ID>$fakeEntry</ID>\n  </CurrentIssues>"),
            )
        }
        commit("add violation and grow baseline")
        val run = staticAnalysis(base)
        assertFails(run, "gate with unauthorized baseline growth")
        assertTrue(
            run.output.contains("DETEKT_BASELINE_GROWTH"),
            "failure must cite DETEKT_BASELINE_GROWTH. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `p0-e baseline shrink passes`() {
        val base = baseBranch()
        // Faithful shrink: delete a source file whose ONLY finding is baselined,
        // then drop its single entry. The source must be a test file (the gate
        // does not compile test sources, so the deletion cannot break the build)
        // and its leaf name must be unique in the worktree (baseline IDs are
        // leaf-only, so an ambiguous leaf would delete the wrong file).
        val ids = baselineIds()
        assertTrue(ids.isNotEmpty(), "baseline must not be empty")
        val byLeaf =
            ids.groupBy {
                it
                    .split(":")
                    .drop(1)
                    .joinToString(":")
                    .substringBefore('$')
            }
        val single =
            byLeaf.entries.firstOrNull { entry ->
                entry.value.size == 1 && testSourcesFor(entry.key).size == 1
            }
        assertTrue(single != null, "expected a single-finding test source in the baseline")
        val (leaf, id) = single!!.key to single.value.single()
        assertTrue(testSourcesFor(leaf).single().delete(), "failed to delete $leaf")
        val xml = File(worktree, "config/detekt/baseline.xml")
        xml.writeText(xml.readText().replace("    <ID>$id</ID>\n", ""))
        commit("pay down one baseline entry (delete single-finding test source)")
        val run = staticAnalysis(base)
        assertPasses(run, "gate with a baseline removal")
        assertTrue(
            run.output.contains("1 removed"),
            "expected a 1-removed verdict. Output: ${run.output.take(1200)}",
        )
    }

    private fun testSourcesFor(leaf: String): List<File> =
        worktree
            .walkTopDown()
            .filter { it.isFile && it.name == leaf && it.path.contains("/src/test/") }
            .toList()
}
