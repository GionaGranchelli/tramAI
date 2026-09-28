package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The population-admission ledger's analyzer semantics are ORDER-SENSITIVE authority: the canonical
 * analyzer records the mutators in the PIT renderer's declaration order, and M16-M19 compare those
 * lists as lists. A ledger that re-orders them describes different semantics, so the loader must
 * preserve what the ledger declares. This is the discriminator for the one-line production rule:
 * sorting anywhere on the load path makes an authorized row unreachable at M33 before admission.
 */
class MutationPopulationAdmissionLoaderTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the loader preserves the ledger's mutator declaration order`() {
        // The canonical PIT renderer order, which is NOT alphabetical: sorting anywhere on the load
        // path re-orders it (CONDITIONALS_BOUNDARY, EMPTY_RETURNS, ...) and describes other semantics.
        val declared = MutationProbeInitScript.PIT_MUTATORS
        assertTrue(declared != declared.sorted(), "this discriminator needs an order that sorting would change")
        writeLedger(declared)

        val loaded = MutationPopulationAdmissionLoader.load(tempDir)

        val admission = loaded.admissions.single()
        assertEquals(declared, admission.analyzer.mutators, "declaration order is part of the authorized semantics")
        assertTrue(admission.analyzer.mutators == declared, "no re-ordering on the load path")
    }

    @Test
    fun `an absent ledger is no authority rather than a failure`() {
        val loaded = MutationPopulationAdmissionLoader.load(tempDir)

        assertEquals(MutationPopulationAdmissions.NONE.admissions, loaded.admissions)
    }

    private fun writeLedger(mutators: List<String>) {
        val lines =
            mutableListOf(
                "schemaVersion: \"1\"",
                "admissions:",
                "  - identity: \"" + "a".repeat(64) + "\"",
                "    status: \"NO_COVERAGE\"",
                "    outcome: \"NON_KILLED\"",
                "    family: \"admission\"",
                "    module: \":subject\"",
                "    analyzer:",
                "      pluginVersion: \"1.19.0\"",
                "      engineVersion: \"1.22.1\"",
                "      mutators:",
            )
        mutators.forEach { lines += "        - \"$it\"" }
        lines += "      timeoutConst: 4000"
        lines += "      timeoutFactor: 1.25"
        lines += "    fromBaseSha: \"" + "b".repeat(40) + "\""
        lines += "    populationDigest: \"" + "c".repeat(64) + "\""
        lines += "    reason: \"loader-order discriminator\""
        val file = File(tempDir, MutationPopulationAdmissionLoader.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
