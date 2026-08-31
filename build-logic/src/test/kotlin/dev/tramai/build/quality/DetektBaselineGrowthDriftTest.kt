package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 10.1d: DetektBaselineGrowthVerifier drift diagnosis. When a baseline
 * migration adds and removes IDs for the SAME entity (signature text changed
 * by the ktlint_official formatting ratchet), the verdict must surface the
 * suspects so the migration is not mistaken for genuine debt movement.
 */
class DetektBaselineGrowthDriftTest {
    private fun baseline(vararg ids: String): String =
        """
        <SmellBaseline>
          <ManuallySuppressedIssues/>
          <CurrentIssues>
        ${ids.joinToString("\n") { "    <ID>$it</ID>" }}
          </CurrentIssues>
        </SmellBaseline>
        """.trimIndent()

    @Test
    fun `same entity added and removed is flagged as drift suspect`() {
        val oldSig = "NestedBlockDepth:AnthropicProvider.kt\$AnthropicProvider\$parse(\n    old: String,\n)"
        val newSig =
            "NestedBlockDepth:AnthropicProvider.kt\$AnthropicProvider\$parse(\n" +
                "    old: String,\n    new: String,\n)"
        val base = baseline(oldSig)
        val current = baseline(newSig)
        val verdict =
            DetektBaselineGrowthVerifier.verify(
                DetektGrowthInput(
                    baseBaselineXml = base,
                    currentBaselineXml = current,
                    changeClass = "baseline-migration",
                    runtimeSourceChanged = false,
                ),
            )
        assertTrue(verdict.passed, verdict.message)
        assertTrue(verdict.message.contains("Formatting-ratchet ID drift suspected"))
    }

    @Test
    fun `unrelated addition and removal are not drift suspects`() {
        val base = baseline("NestedBlockDepth:FileA.kt\$A\$old(")
        val current = baseline("NestedBlockDepth:FileB.kt\$B\$new(")
        val verdict =
            DetektBaselineGrowthVerifier.verify(
                DetektGrowthInput(
                    baseBaselineXml = base,
                    currentBaselineXml = current,
                    changeClass = "baseline-migration",
                    runtimeSourceChanged = false,
                ),
            )
        assertTrue(verdict.passed, verdict.message)
        assertTrue(!verdict.message.contains("drift suspected"))
    }

    @Test
    fun `growth without migration still fails`() {
        val base = baseline("NestedBlockDepth:FileA.kt\$A\$old(")
        val current = baseline("NestedBlockDepth:FileA.kt\$A\$old(", "NestedBlockDepth:FileA.kt\$A\$new(")
        val verdict =
            DetektBaselineGrowthVerifier.verify(
                DetektGrowthInput(
                    baseBaselineXml = base,
                    currentBaselineXml = current,
                    changeClass = null,
                    runtimeSourceChanged = false,
                ),
            )
        assertEquals(DetektGrowthVerdict.GROWTH, verdict.code)
    }
}
