package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ChangePolicyEvaluator] — the pure-logic change policy engine.
 */
class ChangePolicyEvaluatorTest {

    // --- Production path detection ---

    @Test
    fun `tramai-engine src main is production path`() {
        assertTrue(ChangePolicyEvaluator.isProductionPath("tramai-engine/src/main/kotlin/Engine.kt"))
    }

    @Test
    fun `build-logic src main is production path`() {
        assertTrue(ChangePolicyEvaluator.isProductionPath("build-logic/src/main/kotlin/Scanner.kt"))
    }

    @Test
    fun `test source is not production path`() {
        assertFalse(ChangePolicyEvaluator.isProductionPath("tramai-engine/src/test/kotlin/Test.kt"))
    }

    // --- Runtime production includes all tramai modules ---

    @Test
    fun `spring boot starter is runtime production`() {
        assertTrue(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-spring-boot-starter-sovereign/src/main/kotlin/Starter.kt"))
    }

    @Test
    fun `observability module is runtime production`() {
        assertTrue(ChangePolicyEvaluator.isRuntimeProductionPath(
            "tramai-observability/src/main/kotlin/Observability.kt"))
    }

    @Test
    fun `docs are not runtime production`() {
        assertFalse(ChangePolicyEvaluator.isRuntimeProductionPath("docs/guide.md"))
    }

    // --- Rule 1: production + baseline separation ---

    @Test
    fun `production and baseline change together without explicit class fails`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf(
                "tramai-engine/src/main/kotlin/Engine.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.rule == "production-baseline-separation" })
    }

    @Test
    fun `baseline-migration allows build-logic and baseline together`() {
        val input = ChangePolicyInput(
            changeClass = "baseline-migration",
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue("baseline-migration should allow build-logic + baseline") { result.passed }
    }

    @Test
    fun `baseline-migration still rejects tramai runtime production`() {
        val input = ChangePolicyInput(
            changeClass = "baseline-migration",
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/Scanner.kt",
                "tramai-engine/src/main/kotlin/Engine.kt",
                "config/quality/0.6.0-baseline.json"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.rule == "production-baseline-separation" &&
            it.message.contains("tramai runtime") })
    }

    @Test
    fun `auto-detect never returns baseline-migration`() {
        // Even with analyzer + baseline + deviations changes, auto-detect should
        // never produce 'baseline-migration' — must be explicit
        assertEquals("runtime-behaviour", ChangePolicyEvaluator.detectChangeClass(listOf(
            "build-logic/src/main/kotlin/Scanner.kt",
            "config/quality/0.6.0-baseline.json",
            "config/quality/maintainability-deviations.yml"
        )))
    }

    // --- Rule 2: analyzer + runtime separation ---

    @Test
    fun `analyzer and runtime change together fails`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/KotlinCancellationCatchScanner.kt",
                "tramai-engine/src/main/kotlin/Engine.kt"
            ),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.rule == "analyzer-runtime-separation" })
    }

    @Test
    fun `analyzer change only passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf("build-logic/src/main/kotlin/Scanner.kt"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    @Test
    fun `runtime change only passes`() {
        val input = ChangePolicyInput(
            changeClass = null,
            changedFiles = listOf("tramai-engine/src/main/kotlin/Engine.kt"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = null
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    // --- Rule 3: deviation evidence ---

    private val validDeviationYaml = """
        |deviations:
        |  - id: MQ-0001
        |    metric: constructorParameterCount
        |    scope: ":tramai-engine"
        |    baseline: 32
        |    allowed: 32
        |    reason: "Engine proxy dispatch centralizes many concerns."
        |    acceptedAt: "2026-07-18"
        |    targetPhase: "0.6.1"
        |    owner: "GionaGranchelli"
    """.trimMargin()

    @Test
    fun `valid deviation with all fields passes`() {
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = null,
            currentDeviationsYaml = validDeviationYaml
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    @Test
    fun `deviation missing baseline fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-NO-BASELINE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    allowed: 35
            |    reason: "No baseline provided."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = inputFrom(yaml)
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("baseline") })
    }

    @Test
    fun `deviation missing allowed fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-NO-ALLOWED
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    reason: "No allowed."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = inputFrom(yaml)
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("allowed") })
    }

    @Test
    fun `deviation missing targetPhase fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-NO-PHASE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 10
            |    reason: "No phase."
            |    acceptedAt: "2026-07-26"
            |    owner: "agent"
        """.trimMargin()
        val input = inputFrom(yaml)
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("targetPhase") })
    }

    @Test
    fun `deviation with blank reason fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-BLANK-REASON
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: 10
            |    allowed: 10
            |    reason: ""
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = inputFrom(yaml)
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("blank") && it.message.contains("reason") })
    }

    @Test
    fun `deviation with non-numeric baseline fails`() {
        val yaml = """
            |deviations:
            |  - id: MQ-BAD-BASELINE
            |    metric: someMetric
            |    scope: ":tramai-engine"
            |    baseline: abc
            |    allowed: 10
            |    reason: "Bad baseline."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = inputFrom(yaml)
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("non-numeric baseline") })
    }

    // --- Allowed value increase detection ---

    @Test
    fun `allowed value increase with new justification passes`() {
        val base = """
            |deviations:
            |  - id: MQ-INC
            |    metric: m
            |    scope: ":t"
            |    baseline: 10
            |    allowed: 10
            |    reason: "Old reason."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val current = """
            |deviations:
            |  - id: MQ-INC
            |    metric: m
            |    scope: ":t"
            |    baseline: 10
            |    allowed: 15
            |    reason: "New reason."
            |    acceptedAt: "2026-07-26"
            |    targetPhase: "0.6.2"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = base,
            currentDeviationsYaml = current
        )
        assertTrue(ChangePolicyEvaluator.evaluate(input).passed)
    }

    @Test
    fun `allowed value increase with unchanged metadata fails`() {
        val base = """
            |deviations:
            |  - id: MQ-INC
            |    metric: m
            |    scope: ":t"
            |    baseline: 10
            |    allowed: 10
            |    reason: "Old."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val current = """
            |deviations:
            |  - id: MQ-INC
            |    metric: m
            |    scope: ":t"
            |    baseline: 10
            |    allowed: 100
            |    reason: "Old."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = base,
            currentDeviationsYaml = current
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("unchanged") })
    }

    // --- Removed deviations ---

    @Test
    fun `removed deviation ID is detected`() {
        val base = """
            |deviations:
            |  - id: MQ-0001
            |    metric: m
            |    baseline: 1
            |    allowed: 1
            |    reason: "Original."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
            |  - id: MQ-0002
            |    metric: m
            |    baseline: 2
            |    allowed: 2
            |    reason: "Also original."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val current = """
            |deviations:
            |  - id: MQ-0001
            |    metric: m
            |    baseline: 1
            |    allowed: 1
            |    reason: "Only kept."
            |    acceptedAt: "2026-07-01"
            |    targetPhase: "0.6.1"
            |    owner: "agent"
        """.trimMargin()
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = base,
            currentDeviationsYaml = current
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("MQ-0002") && it.message.contains("removed") })
    }

    // --- Deviation file deletion ---

    @Test
    fun `deletion of deviations file fails`() {
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = validDeviationYaml,
            currentDeviationsYaml = null
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertTrue(result.violations.any { it.message.contains("deleted") })
    }

    // --- YAML parse result tests ---

    @Test
    fun `parseResult returns NotFound for null input`() {
        assertTrue(ChangePolicyEvaluator.parseResult(null) is DeviationParseResult.NotFound)
    }

    @Test
    fun `parseResult returns Invalid for blank input`() {
        assertTrue(ChangePolicyEvaluator.parseResult("") is DeviationParseResult.Invalid)
    }

    @Test
    fun `parseResult returns Invalid for whitespace only`() {
        assertTrue(ChangePolicyEvaluator.parseResult("   \n  \n") is DeviationParseResult.Invalid)
    }

    @Test
    fun `parseResult returns Invalid for malformatted YAML`() {
        assertTrue(ChangePolicyEvaluator.parseResult("{{invalid yaml: [broken") is DeviationParseResult.Invalid)
    }

    @Test
    fun `parseResult returns Invalid for empty deviations key`() {
        assertTrue(ChangePolicyEvaluator.parseResult("deviations: []") is DeviationParseResult.Success)
    }

    @Test
    fun `parseResult returns Invalid for missing deviations key`() {
        assertTrue(ChangePolicyEvaluator.parseResult("someKey: value") is DeviationParseResult.Invalid)
    }

    @Test
    fun `parseResult returns Success for valid YAML`() {
        val result = ChangePolicyEvaluator.parseResult(validDeviationYaml)
        assertTrue(result is DeviationParseResult.Success)
        if (result is DeviationParseResult.Success) {
            assertEquals(1, result.deviations.size)
            assertTrue(result.deviations.containsKey("MQ-0001"))
        }
    }

    // --- Malformed deviation YAML in evaluator ---

    @Test
    fun `malformed deviation YAML causes policy failure`() {
        val input = ChangePolicyInput(
            changeClass = "build-logic",
            changedFiles = listOf("config/quality/maintainability-deviations.yml"),
            baseDeviationsYaml = validDeviationYaml,
            currentDeviationsYaml = "{{broken yaml"
        )
        val result = ChangePolicyEvaluator.evaluate(input)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.message.contains("invalid") || it.message.contains("Invalid") })
    }

    // --- Edge cases ---

    @Test
    fun `empty changed files passes`() {
        assertTrue(ChangePolicyEvaluator.evaluate(ChangePolicyInput(
            null, emptyList(), null, null)).passed)
    }

    @Test
    fun `documentation only change passes`() {
        assertTrue(ChangePolicyEvaluator.evaluate(ChangePolicyInput(
            null, listOf("AGENTS.md", "docs/guide.md"), null, null)).passed)
    }

    @Test
    fun `build-logic only change sets change class correctly`() {
        assertEquals("build-logic", ChangePolicyEvaluator.detectChangeClass(
            listOf("build-logic/src/main/kotlin/Scanner.kt")))
    }

    @Test
    fun `build-logic with docs is still build-logic`() {
        assertEquals("build-logic", ChangePolicyEvaluator.detectChangeClass(
            listOf("build-logic/src/main/kotlin/Scanner.kt", "docs/guide.md")))
    }

    @Test
    fun `runtime with docs is runtime-behaviour`() {
        assertEquals("runtime-behaviour", ChangePolicyEvaluator.detectChangeClass(
            listOf("tramai-engine/src/main/kotlin/Engine.kt", "docs/guide.md")))
    }

    // --- Helpers ---

    /** Build a single-deviation YAML string for testing. */
    private fun singleDeviationYaml(
        baseline: Number = 10,
        allowed: Number = 10,
        reason: String = "\"Test reason.\"",
        acceptedAt: String = "\"2026-07-26\"",
        targetPhase: String = "\"0.6.1\"",
        owner: String = "\"agent\"",
        extraFields: String = ""
    ): String = buildString {
        appendLine("deviations:")
        appendLine("  - id: MQ-TEST")
        appendLine("    metric: testMetric")
        appendLine("    scope: \":test\"")
        appendLine("    baseline: $baseline")
        appendLine("    allowed: $allowed")
        appendLine("    reason: $reason")
        appendLine("    acceptedAt: $acceptedAt")
        appendLine("    targetPhase: $targetPhase")
        appendLine("    owner: $owner")
        if (extraFields.isNotBlank()) {
            appendLine("    $extraFields")
        }
    }.trimEnd()

    /** Build a two-deviation YAML string for base/current comparison tests. */
    private fun multiDeviationYaml(vararg devs: String): String {
        val header = "deviations:\n"
        return header + devs.joinToString("\n") { it.trimStart() }
    }

    private fun inputFrom(currentYaml: String) = ChangePolicyInput(
        changeClass = "build-logic",
        changedFiles = listOf("config/quality/maintainability-deviations.yml"),
        baseDeviationsYaml = null,
        currentDeviationsYaml = currentYaml
    )
}
