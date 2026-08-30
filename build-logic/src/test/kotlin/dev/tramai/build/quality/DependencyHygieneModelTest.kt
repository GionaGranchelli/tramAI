package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
                    "main" to setOf("com.example.UsedService"),
                    "test" to setOf("org.junit.jupiter.api.Test"),
                ),
        )

    private val evidence =
        mapOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to
                JarEvidence(
                    classes = setOf("kotlinx.coroutines.launch", "kotlinx.coroutines.runBlocking"),
                    packages = setOf("kotlinx.coroutines"),
                ),
            "com.example:used-lib" to
                JarEvidence(
                    classes = setOf("com.example.UsedService"),
                    packages = setOf("com.example"),
                ),
            "org.junit.jupiter:junit-jupiter" to
                JarEvidence(
                    classes = setOf("org.junit.jupiter.api.Test"),
                    packages = setOf("org.junit.jupiter.api"),
                ),
        )

    @Test
    fun `unused main dependency fails`() {
        val result = DependencyUsageEvaluator.evaluate(unit, evidence, emptyList())
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("kotlinx-coroutines-core"))
    }

    @Test
    fun `unused test dependency is info only`() {
        val unusedTest =
            unit.copy(
                declared = mapOf("testImplementation" to listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")),
            )
        val result = DependencyUsageEvaluator.evaluate(unusedTest, evidence, emptyList())
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
        val result = DependencyUsageEvaluator.evaluate(unit, evidence, listOf(exemption))
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
        val result = DependencyUsageEvaluator.evaluate(usedOnly, evidence, listOf(exemption))
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
        val result = DependencyUsageEvaluator.evaluate(usedOnly, evidence, listOf(exemption))
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
    fun `runtimeOnly dependency with jar evidence but no static usage fails`() {
        // D4 (10.1c BLOCKER 3): a runtimeOnly coordinate whose jar IS on the
        // runtime classpath evidence but has no main import is a violation —
        // unless exempted (JDBC/ServiceLoader cases live in the catalog).
        val unitWithRuntime =
            unit.copy(
                declared = mapOf("runtimeOnly" to listOf("com.example:driver-lib")),
                importsBySourceSet = mapOf("main" to setOf("org.springframework.context.ApplicationContext")),
            )
        val evidenceWithDriver =
            evidence +
                (
                    "com.example:driver-lib" to
                        JarEvidence(
                            classes = setOf("com.example.driver.Driver"),
                            packages = setOf("com.example.driver"),
                        )
                )
        val result = DependencyUsageEvaluator.evaluate(unitWithRuntime, evidenceWithDriver, emptyList())
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("driver-lib"))
    }

    @Test
    fun `runtimeOnly exemption passes with rationale`() {
        val unitWithRuntime =
            unit.copy(
                declared = mapOf("runtimeOnly" to listOf("com.example:driver-lib")),
                importsBySourceSet = mapOf("main" to setOf("org.springframework.context.ApplicationContext")),
            )
        val evidenceWithDriver =
            evidence +
                (
                    "com.example:driver-lib" to
                        JarEvidence(
                            classes = setOf("com.example.driver.Driver"),
                            packages = setOf("com.example.driver"),
                        )
                )
        val exemption =
            Exemption(
                ":tramai-spring-provider",
                "runtimeOnly",
                "com.example:driver-lib",
                "JDBC driver loaded by class name",
            )
        val result = DependencyUsageEvaluator.evaluate(unitWithRuntime, evidenceWithDriver, listOf(exemption))
        assertTrue(result.violations.isEmpty())
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

    @Test
    fun `absent exemptions file yields no exemptions`() {
        assertTrue(DependencyExemptionsParser.parse(null).isEmpty())
    }

    @Test
    fun `blank exemptions file fails closed`() {
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            DependencyExemptionsParser.parse("   \n  ")
        }
    }

    @Test
    fun `entry missing reason fails closed`() {
        val yaml =
            """
            exemptions:
              - module: ":tramai-server"
                configuration: "runtimeOnly"
                dependency: "org.postgresql:postgresql"
            """.trimIndent()
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            DependencyExemptionsParser.parse(yaml)
        }
    }

    @Test
    fun `duplicate exemption identity fails closed`() {
        val yaml =
            """
            exemptions:
              - module: ":tramai-server"
                configuration: "runtimeOnly"
                dependency: "org.postgresql:postgresql"
                reason: "JDBC driver"
              - module: ":tramai-server"
                configuration: "runtimeOnly"
                dependency: "org.postgresql:postgresql"
                reason: "duplicate"
            """.trimIndent()
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            DependencyExemptionsParser.parse(yaml)
        }
    }

    @Test
    fun `import prefix handles plain wildcard and static imports`() {
        assertEquals("com.example.api.Service", importSymbolOf("import com.example.api.Service"))
        assertEquals("com.example.api.*", importSymbolOf("import com.example.api.*"))
        assertEquals("com.example.api", importSymbolOf("import com.example.api"))
        assertEquals(
            "org.postgresql.Driver.getVersion",
            importSymbolOf("import static org.postgresql.Driver.getVersion"),
        )
        assertEquals("com.example.api.Service", importSymbolOf("  import com.example.api.Service  "))
        // Java imports carry a mandatory trailing semicolon; Kotlin aliases too.
        assertEquals("com.example.api.Service", importSymbolOf("import com.example.api.Service;"))
        assertEquals("com.example.api.*", importSymbolOf("import com.example.api.*;"))
        assertEquals(
            "org.postgresql.Driver.getVersion",
            importSymbolOf("import static org.postgresql.Driver.getVersion;"),
        )
        assertEquals("foo.bar.Type", importSymbolOf("import foo.bar.Type as Alias"))
    }

    @Test
    fun `import prefix ignores non-import lines`() {
        assertNull(importSymbolOf("package com.example"))
        assertNull(importSymbolOf("val x = 1"))
        assertNull(importSymbolOf(""))
        assertNull(importSymbolOf("import a"))
    }

    @Test
    fun `wildcard import counts as usage of the dependency`() {
        // 10.1c review thread: `import foo.bar.*` and Java static imports must
        // count as static usage — otherwise the dependency is falsely flagged.
        val unitWildcard =
            unit.copy(
                importsBySourceSet = mapOf("main" to setOf("com.example.*")),
            )
        val result = DependencyUsageEvaluator.evaluate(unitWildcard, evidence, emptyList())
        // used-lib (package com.example) is used via the wildcard; kotlinx is not.
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("kotlinx-coroutines-core"))
    }

    @Test
    fun `exemption cannot leak across modules`() {
        // D8: a module-scoped exemption must not silence another module's violation.
        val unitB =
            unit.copy(
                modulePath = ":tramai-other-module",
                declared = mapOf("implementation" to listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")),
                importsBySourceSet = mapOf("main" to setOf("org.springframework.context.ApplicationContext")),
            )
        val exemption =
            Exemption(
                ":tramai-spring-provider",
                "implementation",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "runtime provider",
            )
        val result = DependencyUsageEvaluator.evaluate(unitB, evidence, listOf(exemption))
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("kotlinx-coroutines-core"))
    }

    @Test
    fun `sibling artifact sharing a package family cannot justify another`() {
        // 10.1c round-3 review: two-segment package-family evidence let
        // org.springframework.context and org.springframework.jdbc justify each
        // other. Exact-class evidence must flag the genuinely unused sibling.
        val unitCollision =
            DependencyUnitSpec(
                modulePath = ":tramai-collision",
                declared =
                    mapOf(
                        "implementation" to
                            listOf(
                                "org.springframework:spring-context",
                                "org.springframework:spring-jdbc",
                            ),
                    ),
                importsBySourceSet = mapOf("main" to setOf("org.springframework.context.ApplicationContext")),
            )
        val collisionEvidence =
            mapOf(
                "org.springframework:spring-context" to
                    JarEvidence(
                        classes = setOf("org.springframework.context.ApplicationContext"),
                        packages = setOf("org.springframework.context"),
                    ),
                "org.springframework:spring-jdbc" to
                    JarEvidence(
                        classes = setOf("org.springframework.jdbc.core.JdbcTemplate"),
                        packages = setOf("org.springframework.jdbc"),
                    ),
            )
        val result = DependencyUsageEvaluator.evaluate(unitCollision, collisionEvidence, emptyList())
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("spring-jdbc"))
    }

    @Test
    fun `top-level extension function import counts as usage`() {
        // Kotlin top-level functions/properties compile into facade classes, so a
        // direct import (kotlin.reflect.jvm.javaType, jackson.module.kotlin.readValue)
        // has no matching class — its package must still prove usage.
        val unitExt =
            unit.copy(
                importsBySourceSet = mapOf("main" to setOf("kotlinx.coroutines.yield")),
            )
        val result = DependencyUsageEvaluator.evaluate(unitExt, evidence, emptyList())
        // kotlinx package exists in kotlinx-coroutines evidence → used; used-lib not.
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("used-lib"))
    }
}
