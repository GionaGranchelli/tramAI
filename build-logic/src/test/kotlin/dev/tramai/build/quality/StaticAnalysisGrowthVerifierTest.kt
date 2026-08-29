package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for the Detekt baseline-growth contract (section G) and the
 * one-time bootstrap discriminator (section I). No Gradle, no git.
 */
class StaticAnalysisGrowthVerifierTest {

    private fun verifier() = DetektBaselineGrowthVerifier

    private val idFoo = "MaxLineLength:Foo.kt\$foo\$aVeryLongLine"
    private val idBar = "MagicNumber:Bar.kt\$42"

    private val sampleXml =
        "<?xml version=\"1.0\" ?>\n" +
            "<SmellBaseline>\n" +
            "  <ManuallySuppressedIssues/>\n" +
            "  <CurrentIssues>\n" +
            "    <ID>$idFoo</ID>\n" +
            "    <ID>$idBar</ID>\n" +
            "  </CurrentIssues>\n" +
            "</SmellBaseline>"

    private fun input(
        base: String? = sampleXml,
        current: String? = sampleXml,
        changeClass: String? = "build-logic",
        runtimeChanged: Boolean = false,
    ) = DetektGrowthInput(base, current, changeClass, runtimeChanged)

    @Test
    fun `unchanged baseline passes`() {
        assertTrue(verifier().verify(input()).passed)
    }

    @Test
    fun `removed entry passes and reports removal`() {
        val current = sampleXml.replace("    <ID>$idFoo</ID>\n", "")
        val verdict = verifier().verify(input(current = current))
        assertTrue(verdict.passed)
        assertEquals(listOf(idFoo), verdict.removed)
    }

    @Test
    fun `added entry fails with growth unless migration`() {
        val newId = "MagicNumber:New.kt\$7"
        val current = sampleXml.replace("  </CurrentIssues>", "    <ID>$newId</ID>\n  </CurrentIssues>")

        val ordinary = verifier().verify(input(current = current, changeClass = "build-logic"))
        assertFalse(ordinary.passed)
        assertEquals(DetektGrowthVerdict.GROWTH, ordinary.code)
        assertTrue(ordinary.message.contains(newId))

        // runtime-behaviour PRs may not grow the baseline either
        val runtime = verifier().verify(input(current = current, changeClass = "runtime-behaviour"))
        assertFalse(runtime.passed)
        assertEquals(DetektGrowthVerdict.GROWTH, runtime.code)

        // baseline-migration PRs may grow it
        val migration = verifier().verify(input(current = current, changeClass = "baseline-migration"))
        assertTrue(migration.passed)
    }

    @Test
    fun `deleted baseline fails`() {
        val verdict = verifier().verify(input(current = null))
        assertFalse(verdict.passed)
        assertEquals(DetektGrowthVerdict.DELETED, verdict.code)
    }

    @Test
    fun `emptied baseline fails`() {
        val emptied =
            "<?xml version=\"1.0\" ?>\n" +
                "<SmellBaseline>\n" +
                "  <ManuallySuppressedIssues/>\n" +
                "  <CurrentIssues/>\n" +
                "</SmellBaseline>"
        val verdict = verifier().verify(input(current = emptied))
        assertFalse(verdict.passed)
        assertEquals(DetektGrowthVerdict.EMPTIED, verdict.code)
    }

    @Test
    fun `malformed baseline fails`() {
        val wrongRoot =
            "<?xml version=\"1.0\" ?>\n" +
                "<WrongBaseline>\n" +
                "  <CurrentIssues/>\n" +
                "</WrongBaseline>"
        assertEquals(DetektGrowthVerdict.MALFORMED, verifier().verify(input(current = wrongRoot)).code)

        val duplicate =
            sampleXml.replace(
                "    <ID>$idBar</ID>",
                "    <ID>$idBar</ID>\n    <ID>$idBar</ID>"
            )
        assertEquals(DetektGrowthVerdict.MALFORMED, verifier().verify(input(current = duplicate)).code)

        val notXml = "this is not xml <<<"
        assertEquals(DetektGrowthVerdict.MALFORMED, verifier().verify(input(current = notXml)).code)

        // malformed at the BASE also fails
        assertEquals(DetektGrowthVerdict.MALFORMED, verifier().verify(input(base = notXml)).code)
    }

    @Test
    fun `bootstrap allowed only for initial adoption`() {
        // base absent + current present + non-runtime class + no runtime changes -> allowed
        val adoption = verifier().verify(input(base = null, changeClass = "build-logic"))
        assertTrue(adoption.passed)
        assertEquals(2, adoption.currentTotal)

        // runtime change class -> abuse
        val runtimeClass = verifier().verify(input(base = null, changeClass = "runtime-behaviour"))
        assertFalse(runtimeClass.passed)
        assertEquals(DetektGrowthVerdict.BOOTSTRAP_ABUSE, runtimeClass.code)

        // runtime source changed -> abuse even under build-logic
        val runtimeChanged =
            verifier().verify(input(base = null, changeClass = "build-logic", runtimeChanged = true))
        assertFalse(runtimeChanged.passed)
        assertEquals(DetektGrowthVerdict.BOOTSTRAP_ABUSE, runtimeChanged.code)
    }

    @Test
    fun `both absent passes trivially`() {
        assertTrue(verifier().verify(input(base = null, current = null)).passed)
    }

    @Test
    fun `identity is content not position`() {
        // Reordering entries is not a change; the comparison uses the ID set.
        val reordered =
            "<?xml version=\"1.0\" ?>\n" +
                "<SmellBaseline>\n" +
                "  <ManuallySuppressedIssues/>\n" +
                "  <CurrentIssues>\n" +
                "    <ID>$idBar</ID>\n" +
                "    <ID>$idFoo</ID>\n" +
                "  </CurrentIssues>\n" +
                "</SmellBaseline>"
        assertTrue(verifier().verify(input(current = reordered)).passed)
    }
}
