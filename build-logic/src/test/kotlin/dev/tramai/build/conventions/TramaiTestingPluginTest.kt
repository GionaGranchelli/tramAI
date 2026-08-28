package dev.tramai.build.conventions

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit behavioral contract for the 9.2c-b `tramai.testing` convention.
 *
 * The plugin owns exactly the three-dependency test baseline (JUnit BOM as
 * platform, AssertJ, Kotlin test/JUnit5) added to `testImplementation` only,
 * reading coordinates from the root version catalog. It must never add
 * production dependencies, never add `junit-jupiter` implicitly, and be safe
 * under plugin-order and double-application.
 *
 * T1 and T3 inspect the declared direct-dependency model (via the fixture's
 * `printDirectDeps` task) rather than parsing the human-readable dependency
 * report, so a stray fourth dependency or a lost `platform(...)` wrapper
 * cannot slip through.
 */
class TramaiTestingPluginTest {

    @TempDir
    lateinit var tempDir: File

    private fun writeFile(base: File, relativePath: String, content: String) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    private fun fixture(dir: File, extraPlugins: String = "", extraDeps: String = ""): File {
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sample\"\n")
        // the plugin reads coordinates from the version catalog — the fixture
        // must provide one so the catalog path is exercised honestly
        writeFile(
            dir,
            "gradle/libs.versions.toml",
            """
            [versions]
            junit = "5.12.2"
            assertj = "3.27.7"
            kotlin = "2.3.0"
            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
            kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5", version.ref = "kotlin" }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            import org.gradle.api.artifacts.ModuleDependency
            import org.gradle.api.attributes.Category

            plugins {
                `java`
                id("tramai.testing")
                $extraPlugins
            }
            repositories { mavenCentral() }
            $extraDeps

            // dumps the declared direct dependencies of testImplementation —
            // group:name plus the dependency category attribute
            tasks.register("printDirectDeps") {
                doLast {
                    configurations.testImplementation.get().dependencies
                        .sortedBy { "${'$'}{it.group}:${'$'}{it.name}" }
                        .forEach { d ->
                            val category = (d as? ModuleDependency)
                                ?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name ?: "none"
                            println("DIRECTDEP ${'$'}{d.group}:${'$'}{d.name} category=${'$'}category")
                        }
                }
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun resolvedDeps(dir: File): String =
        runner(dir, "dependencies", "--configuration", "testRuntimeClasspath").build().output

    private fun directDeps(dir: File): List<String> =
        runner(dir, "printDirectDeps").build().output
            .lineSequence()
            .filter { it.startsWith("DIRECTDEP ") }
            .map { it.removePrefix("DIRECTDEP ") }
            .toList()

    // ── T1: exactly the three expected test dependencies ─────────────────────

    @Test
    fun `T1 adds exactly junit-bom assertj and kotlin-test-junit5`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        val deps = directDeps(dir)
        val coords = deps.map { it.substringBefore(" category=") }.sorted()
        assertEquals(
            listOf(
                "org.assertj:assertj-core",
                "org.jetbrains.kotlin:kotlin-test-junit5",
                "org.junit:junit-bom",
            ),
            coords,
            "direct testImplementation deps must be exactly the trio: $deps",
        )
    }

    // ── T2: scopes are testImplementation only ───────────────────────────────

    @Test
    fun `T2 dependencies are added only to testImplementation`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        val testOut = runner(dir, "dependencies", "--configuration", "testImplementation").build().output
        assertTrue(testOut.contains("junit-bom"), "junit-bom missing from testImplementation")
        assertTrue(testOut.contains("assertj"), "assertj missing from testImplementation")
        assertTrue(testOut.contains("kotlin-test"), "kotlin-test missing from testImplementation")
        val mainOut = runner(dir, "dependencies", "--configuration", "runtimeClasspath").build().output
        assertFalse(mainOut.contains("junit-bom"), "junit-bom leaked to main runtime classpath")
        assertFalse(mainOut.contains("assertj"), "assertj leaked to main runtime classpath")
        assertFalse(mainOut.contains("kotlin-test"), "kotlin-test leaked to main runtime classpath")
    }

    // ── T3: JUnit BOM is a platform dependency, not a plain jar ──────────────

    @Test
    fun `T3 junit-bom is a platform dependency`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        val bomLine = directDeps(dir).first { it.startsWith("org.junit:junit-bom") }
        assertTrue(
            bomLine.contains("category=platform"),
            "junit-bom must carry the platform category attribute: $bomLine",
        )
    }

    // ── T4: no junit-jupiter added as a direct dependency ────────────────────

    @Test
    fun `T4 does not add junit-jupiter implicitly`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        // T1 proves the direct deps are exactly the trio; here we additionally
        // prove no org.junit.jupiter coordinate is declared directly. The
        // engine on the runtime classpath comes transitively from
        // kotlin-test-junit5 (which is how JUnit5 tests run) — that is
        // intended, not plugin-added.
        val direct = directDeps(dir).map { it.substringBefore(" category=") }
        assertTrue(
            direct.none { it.startsWith("org.junit.jupiter:") },
            "plugin must not add any junit-jupiter dependency directly: $direct",
        )
        val out = resolvedDeps(dir)
        assertTrue(
            out.contains("org.jetbrains.kotlin:kotlin-test-junit5:"),
            "sanity: kotlin-test-junit5 present on runtime classpath",
        )
    }

    // ── T5: no production implementation/api dependencies ────────────────────

    @Test
    fun `T5 adds no production dependencies`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        val implOut = runner(dir, "dependencies", "--configuration", "implementation").build().output
        assertFalse(implOut.contains("junit-bom"), "junit-bom leaked to implementation")
        assertFalse(implOut.contains("assertj"), "assertj leaked to implementation")
        assertFalse(implOut.contains("kotlin-test"), "kotlin-test leaked to implementation")
        val apiOut = runner(dir, "dependencies", "--configuration", "api").build().output
        assertFalse(apiOut.contains("junit-bom") && !apiOut.contains("No dependencies"), "junit-bom leaked to api")
        assertFalse(apiOut.contains("assertj"), "assertj leaked to api")
        assertFalse(apiOut.contains("kotlin-test"), "kotlin-test leaked to api")
    }

    // ── T6: plugin application order is safe ─────────────────────────────────

    @Test
    fun `T6 works under both plugin orders`() {
        val orderA = File(tempDir, "orderA").apply { mkdirs() }
        fixture(orderA, extraPlugins = """id("org.jetbrains.kotlin.jvm") version "2.3.0"""").let {
            assertTrue(resolvedDeps(it).contains("assertj-core"), "order A failed")
        }
        val orderB = File(tempDir, "orderB").apply { mkdirs() }
        fixture(orderB).let { }
        // overwrite with reversed plugin order (catalog already present from fixture)
        writeFile(
            orderB,
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "2.3.0"
                id("tramai.testing")
                `java`
            }
            repositories { mavenCentral() }
            """.trimIndent(),
        )
        assertTrue(resolvedDeps(orderB).contains("assertj-core"), "order B failed")
    }

    // ── T7: double application does not duplicate semantics ──────────────────

    @Test
    fun `T7 applying twice does not duplicate dependencies`() {
        val dir = fixture(File(tempDir, "fixture").apply { mkdirs() })
        // overwrite: request the plugin twice (plugins block + explicit apply)
        // Gradle dedupes plugin application; the trio must appear exactly once.
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                `java`
                id("tramai.testing")
            }
            repositories { mavenCentral() }
            apply(plugin = "tramai.testing")
            """.trimIndent(),
        )
        val out = runner(dir, "dependencies", "--configuration", "testImplementation").build().output
        // count occurrences of the assertj coordinate in the resolved graph
        val occurrences = Regex("org\\.assertj:assertj-core:").findAll(out).count()
        assertEquals(1, occurrences, "assertj must appear exactly once despite double application")
    }
}
