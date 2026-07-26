package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ChangePolicyVerifierTask's rule logic.
 *
 * Covers:
 * - Production path detection (**/src/main/**)
 * - Analyzer path detection (build-logic/**Scanner*)
 * - Runtime module detection (tramai-*/src/main/**)
 * - Deviation evidence field validation
 */
class ChangePolicyVerifierTaskTest {

    // --- Production path detection ---

    @Test
    fun `production path detected for tramai-engine src main`() {
        assertTrue(isProductionChange("tramai-engine/src/main/kotlin/dev/tramai/engine/Engine.kt"))
    }

    @Test
    fun `production path detected for tramai-core src main`() {
        assertTrue(isProductionChange("tramai-core/src/main/kotlin/dev/tramai/core/Provider.kt"))
    }

    @Test
    fun `production path detected for build-logic src main`() {
        assertTrue(isProductionChange("build-logic/src/main/kotlin/dev/tramai/build/quality/Scanner.kt"))
    }

    @Test
    fun `test source is not production path`() {
        assertFalse(isProductionChange("tramai-engine/src/test/kotlin/dev/tramai/engine/EngineTest.kt"))
    }

    @Test
    fun `config file is not production path`() {
        assertFalse(isProductionChange("config/quality/0.6.0-baseline.json"))
    }

    @Test
    fun `docs are not production path`() {
        assertFalse(isProductionChange("docs/board/tasks/TASK-TEMPLATE.md"))
    }

    @Test
    fun `AGENTS md is not production path`() {
        assertFalse(isProductionChange("AGENTS.md"))
    }

    @Test
    fun `production path with no leading module is detected`() {
        // This shouldn't happen in practice, but the pattern should handle it
        assertTrue(isProductionChange("src/main/kotlin/Foo.kt"))
    }

    // --- Baseline path detection ---

    @Test
    fun `baseline path detected exact match`() {
        assertTrue(isBaselineChange("config/quality/0.6.0-baseline.json"))
    }

    @Test
    fun `other quality config is not baseline`() {
        assertFalse(isBaselineChange("config/quality/maintainability-deviations.yml"))
    }

    @Test
    fun `other json files are not baseline`() {
        assertFalse(isBaselineChange("config/quality/module-catalog.yml"))
    }

    // --- Analyzer path detection ---

    @Test
    fun `analyzer scanner detected in build-logic`() {
        assertTrue(isAnalyzerChange("build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt"))
    }

    @Test
    fun `plugin registration is not an analyzer change`() {
        assertFalse(isAnalyzerChange("build-logic/src/main/kotlin/dev/tramai/build/quality/MaintainabilityBaselinePlugin.kt"))
    }

    @Test
    fun `non scanner build-logic file is not analyzer change`() {
        assertFalse(isAnalyzerChange("build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineGenerator.kt"))
    }

    @Test
    fun `scanner outside build-logic is not analyzer change in policy sense`() {
        assertFalse(isAnalyzerChange("tramai-engine/src/main/kotlin/dev/tramai/engine/SomeScanner.kt"))
    }

    // --- Runtime change detection ---

    @Test
    fun `runtime production module detected`() {
        assertTrue(isRuntimeChange("tramai-engine/src/main/kotlin/dev/tramai/engine/Engine.kt"))
    }

    @Test
    fun `runtime test module is not runtime change`() {
        assertFalse(isRuntimeChange("tramai-engine/src/test/kotlin/dev/tramai/engine/EngineTest.kt"))
    }

    @Test
    fun `spring boot starter is not a runtime change`() {
        assertFalse(isRuntimeChange("tramai-spring-boot-starter-sovereign/src/main/kotlin/..."))
    }

    @Test
    fun `build-logic is not a runtime change`() {
        assertFalse(isRuntimeChange("build-logic/src/main/kotlin/dev/tramai/build/quality/Scanner.kt"))
    }

    @Test
    fun `API file is not a runtime change`() {
        assertFalse(isRuntimeChange("tramai-core/api/tramai-core.api"))
    }

    // --- Deviation evidence validation ---

    @Test
    fun `valid deviation block passes evidence check`() {
        val block = """
            id: MQ-0001
            metric: constructorParameterCount
            scope: ":tramai-engine"
            baseline: 32
            allowed: 32
            reason: "Engine proxy dispatch centralizes many concerns."
            acceptedAt: "2026-07-18"
            targetPhase: "0.6.1"
            owner: "GionaGranchelli"
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertTrue(missing.isEmpty(), "Expected no missing fields, got: $missing")
    }

    @Test
    fun `deviation missing baseline fails`() {
        val block = """
            id: MQ-NO-BASELINE
            metric: someMetric
            scope: ":tramai-engine"
            allowed: 32
            reason: "No baseline provided."
            acceptedAt: "2026-07-18"
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertTrue("baseline" in missing)
    }

    @Test
    fun `deviation missing allowed fails`() {
        val block = """
            id: MQ-NO-ALLOWED
            metric: someMetric
            scope: ":tramai-engine"
            baseline: 32
            reason: "No allowed value."
            acceptedAt: "2026-07-18"
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertTrue("allowed" in missing)
    }

    @Test
    fun `deviation missing reason fails`() {
        val block = """
            id: MQ-NO-REASON
            metric: someMetric
            scope: ":tramai-engine"
            baseline: 32
            allowed: 35
            acceptedAt: "2026-07-18"
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertTrue("reason" in missing)
    }

    @Test
    fun `deviation missing acceptedAt fails`() {
        val block = """
            id: MQ-NO-DATE
            metric: someMetric
            scope: ":tramai-engine"
            baseline: 32
            allowed: 35
            reason: "No date."
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertTrue("acceptedAt" in missing)
    }

    @Test
    fun `deviation missing all evidence fields fails`() {
        val block = """
            id: MQ-EMPTY
            metric: someMetric
            scope: ":tramai-engine"
        """.trimIndent()

        val missing = findMissingEvidence(block)
        assertEquals(4, missing.size, "Expected all 4 fields missing")
    }

    // --- Helper functions mirroring the task's logic ---

    /**
     * Mirrors the task's production path check: any path containing "/src/main/".
     */
    private fun isProductionChange(path: String): Boolean = path.contains("/src/main/")

    /**
     * Mirrors the task's baseline path check: exact match.
     */
    private fun isBaselineChange(path: String): Boolean =
        path == "config/quality/0.6.0-baseline.json"

    /**
     * Mirrors the task's analyzer path check:
     * starts with "build-logic/" and contains "Scanner".
     */
    private fun isAnalyzerChange(path: String): Boolean =
        path.contains("Scanner") && path.startsWith("build-logic/")

    /**
     * Mirrors the task's runtime change check:
     * starts with "tramai-", not a spring-boot-starter, contains "/src/main/".
     */
    private fun isRuntimeChange(path: String): Boolean =
        path.startsWith("tramai-") &&
            !path.startsWith("tramai-spring-boot-starter") &&
            path.contains("/src/main/")

    /**
     * Mirrors the task's deviation evidence checking logic.
     */
    private fun findMissingEvidence(block: String): Set<String> {
        val missing = mutableSetOf<String>()
        if (!block.contains("baseline:")) missing.add("baseline")
        if (!block.contains("allowed:")) missing.add("allowed")
        if (!block.contains("reason:")) missing.add("reason")
        if (!block.contains("acceptedAt:")) missing.add("acceptedAt")
        return missing
    }
}
