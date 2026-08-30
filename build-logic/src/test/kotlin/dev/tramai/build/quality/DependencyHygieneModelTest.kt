package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the dependency-hygiene gate (Epic 10.1c, D-series).
 * Pure logic only — no Gradle, no git, no jars.
 *
 * Invariant: no unused direct dependency declared for main compilation/runtime
 * semantics, except explicitly documented non-static usages.
 */
class DependencyHygieneModelTest {

    private val unit =
        DependencyUnitSpec(
            modulePath = ":tramai-spring-provider",
            declared =
                mapOf(
                    "implementation" to listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core", "com.example:used-lib"),
                    "testImplementation" to listOf("org.junit.jupiter:junit-jupiter"),
                ),
            importsBySourceSet =
                mapOf(
                    "main" to setOf("com.example"),
                    "test" to setOf("org.junit"),
                ),
        )

    private val packages =
        mapOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to setOf("kotlinx.coroutines"),
            "com.example:used-lib" to setOf("com.example"),
            "org.junit.jupiter:junit-jupiter" to setOf("org.junit"),
        )

    @Test
    fun `unused main dependency fails`() {
        val result = DependencyUsageEvaluator.evaluate(unit, packages, emptyList())
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("kotlinx-coroutines-core"))
    }

    @Test
    fun `unused test dependency is info only`() {
        val unusedTest =
            unit.copy(
                declared = mapOf("testImplementation" to listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")),
            )
        val result = DependencyUsageEvaluator.evaluate(unusedTest, packages, emptyList())
        assertTrue(result.violations.isEmpty())
        assertTrue(result.info.any { it.contains("test-scope") })
    }

    @Test
    fun `exemption silences a genuine runtime-only usage`() {
        val exemption =
            Exemption(
                module = ":tramai-spring-provider",
                configuration = "implementation",
                dependency = "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                reason = "runtime provider via Spring auto-configuration",
            )
        val result = DependencyUsageEvaluator.evaluate(unit, packages, listOf(exemption))
        assertTrue(result.violations.isEmpty())
        assertTrue(result.info.any { it.contains("exempted") })
    }

    @Test
    fun `stale exemption for now-used dependency fails`() {
        // fixture: only used-lib declared (used), exemption claims it was reflection-only
        val usedOnly =
            unit.copy(
                declared = mapOf("implementation" to listOf("com.example:used-lib")),
            )
        val exemption =
            Exemption(
                module = ":tramai-spring-provider",
                configuration = "implementation",
                dependency = "com.example:used-lib",
                reason = "was reflection-only",
            )
        val result = DependencyUsageEvaluator.evaluate(usedOnly, packages, listOf(exemption))
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("now statically used"))
    }

    @Test
    fun `stale exemption for undeclared dependency fails`() {
        val usedOnly =
            unit.copy(
                declared = mapOf("implementation" to listOf("com.example:used-lib")),
            )
        val exemption =
            Exemption(
                module = ":tramai-spring-provider",
                configuration = "implementation",
                dependency = "com.example:removed-lib",
                reason = "historical",
            )
        val result = DependencyUsageEvaluator.evaluate(usedOnly, packages, listOf(exemption))
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("not a declared dependency"))
    }

    @Test
    fun `coordinate without classes on classpath is info not violation`() {
        val bomOnly =
            unit.copy(
                declared = mapOf("implementation" to listOf("org.springframework.boot:spring-boot-bom")),
            )
        val result = DependencyUsageEvaluator.evaluate(bomOnly, emptyMap(), emptyList())
        assertTrue(result.violations.isEmpty())
        assertTrue(result.info.any { it.contains("BOM/platform") })
    }

    @Test
    fun `exemptions yaml parses`() {
        val yaml =
            """
            exemptions:
              - module: ":tramai-server"
                configuration: "runtimeOnly"
                dependency: "org.postgresql:postgresql"
                reason: "JDBC driver loaded by class name"
            """.trimIndent()
        val parsed = DependencyExemptionsParser.parse(yaml)
        assertEquals(1, parsed.size)
        assertEquals("org.postgresql:postgresql", parsed.single().dependency)
        assertTrue(parsed.single().reason.contains("JDBC"))
    }

    @Test
    fun `malformed exemptions yaml fails closed`() {
        val bad = "exemptions: [unclosed"
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            DependencyExemptionsParser.parse(bad)
        }
    }
}
