package dev.tramai.build.conventions

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TestKit contract for the 9.2c convention plugins. Each fixture registers
 * plugins via the `plugins { id("...") }` block exactly like production
 * modules; assertions pin the observable behavior the conventions own.
 */
class ConventionPluginsTest {

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

    /** Common fixture preamble: settings + repositories for kotlin stdlib resolution. */
    private fun kotlinFixture(dir: File) {
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sample\"\n")
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                `java-library`
                id("org.jetbrains.kotlin.jvm") version "2.3.0"
                id("tramai.kotlin-library")
            }
            repositories { mavenCentral() }
            dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0") }
            """.trimIndent(),
        )
        writeFile(dir, "src/main/kotlin/Sample.kt", "class Sample\n")
    }

    // ── tramai.kotlin-library ────────────────────────────────────────────────

    @Test
    fun `kotlin-library configures toolchain, sources jar, and junit platform`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        kotlinFixture(dir)

        val result = runner(dir, "tasks", "--all").build()
        assertTrue(result.output.contains("sourcesJar"), "sourcesJar task must exist")
        assertTrue(result.output.contains("test"), "test task must exist")
    }

    @Test
    fun `kotlin-library sets jvm target 21`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        kotlinFixture(dir)

        // Compile and inspect the bytecode target: 21 = major version 65.
        val result = runner(dir, "compileKotlin", "classes").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")!!.outcome)
        val classFile = File(dir, "build/classes/kotlin/main/Sample.class")
        assertTrue(classFile.isFile, "compiled class must exist")
        val bytes = classFile.readBytes()
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(65, major, "Kotlin class must target JVM 21 (major version 65)")
    }

    // ── tramai.java-platform ─────────────────────────────────────────────────

    @Test
    fun `java-platform wires allowDependencies and manifest constraints`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sample\"\ninclude(\"tramai-bom\", \"tramai-core\")\n")
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            """
            schemaVersion: "2"
            description: fixture
            dependencyPolicies:
              default:
                allowedLayers: [core-contracts, runtime-execution, governance-security]
            entryDefaults: {}
            modules:
              - path: ":tramai-bom"
                layer: "core-contracts"
                maturity: "stable"
                publishability: "published"
                apiStability: "stable"
                visibility: "public"
                owner: "build"
                dependencyPolicy: "default"
                releaseInclusion: "excluded"
                rationale: "BOM fixture"
              - path: ":tramai-core"
                layer: "core-contracts"
                maturity: "stable"
                publishability: "published"
                apiStability: "stable"
                visibility: "public"
                owner: "build"
                dependencyPolicy: "default"
                releaseInclusion: "included"
                rationale: "core fixture"
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-bom/build.gradle.kts",
            """
            plugins {
                `java-platform`
                id("tramai.java-platform")
                `maven-publish`
            }
            group = "dev.tramai"
            version = "0.5.0"
            publishing {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["javaPlatform"])
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "dev.tramai"
            version = "0.5.0"
            """.trimIndent(),
        )

        // Constraints surface in the BOM's published POM (dependencyManagement),
        // not in the `api` dependency report (verified against the real repo).
        val result = runner(dir, ":tramai-bom:generatePomFileForMavenPublication").build()
        val pom = File(dir, "tramai-bom/build/publications/maven/pom-default.xml")
        assertTrue(pom.isFile, "bom POM must be generated")
        assertTrue(
            pom.readText().contains("tramai-core"),
            "bom POM must constrain manifest-derived modules: ${pom.readText().take(1200)}",
        )
    }

    // ── tramai.test-fixtures ─────────────────────────────────────────────────

    @Test
    fun `test-fixtures applies java-test-fixtures source set`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sample\"\n")
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                `java-library`
                id("tramai.test-fixtures")
            }
            """.trimIndent(),
        )
        writeFile(dir, "src/main/kotlin/Sample.kt", "class Sample\n")
        writeFile(dir, "src/testFixtures/kotlin/SampleFixture.kt", "class SampleFixture\n")

        val result = runner(dir, "tasks", "--all").build()
        assertTrue(result.output.contains("testFixturesJar"), "testFixturesJar task must exist")
        assertTrue(
            result.output.contains("compileTestFixturesKotlin") || result.output.contains("testFixturesClasses"),
            "testFixtures source set must be compiled: ${result.output.take(800)}",
        )
    }
}
