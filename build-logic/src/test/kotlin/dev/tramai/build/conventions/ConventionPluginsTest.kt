package dev.tramai.build.conventions

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TestKit behavioral contract for the 9.2c convention plugins. Fixtures
 * register plugins via the `plugins { id("...") }` block exactly like
 * production modules; assertions prove real execution outcomes (compiled
 * bytecode targets, produced artifacts, executed tests), not task names.
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

    private fun kotlinLibraryFixture(dir: File, pluginOrder: List<String>) {
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sample\"\n")
        val pluginsBlock = pluginOrder.joinToString("\n") { "    $it" }
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
            $pluginsBlock
            }
            repositories { mavenCentral() }
            version = "1.0"
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
            }
            """.trimIndent(),
        )
        writeFile(dir, "src/main/kotlin/Sample.kt", "class Sample\n")
        writeFile(dir, "src/main/java/SampleJava.java", "public class SampleJava {}\n")
    }

    /** major version of a .class file (bytes 6-7 big-endian). */
    private fun classMajorVersion(classFile: File): Int {
        val bytes = classFile.readBytes()
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }

    // ── K1: base-plugin order independence ───────────────────────────────────

    @Test
    fun `K1 kotlin-library configures under both base-plugin orders`() {
        // convention first, then Kotlin, then java-library
        val orderA = File(tempDir, "orderA").apply { mkdirs() }
        kotlinLibraryFixture(
            orderA,
            listOf(
                """id("tramai.kotlin-library")""",
                """id("org.jetbrains.kotlin.jvm") version "2.3.0"""",
                "`java-library`",
            ),
        )
        val resultA = runner(orderA, "compileKotlin", "compileJava").build()
        assertEquals(TaskOutcome.SUCCESS, resultA.task(":compileKotlin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, resultA.task(":compileJava")!!.outcome)

        // bases first, convention last
        val orderB = File(tempDir, "orderB").apply { mkdirs() }
        kotlinLibraryFixture(
            orderB,
            listOf(
                "`java-library`",
                """id("org.jetbrains.kotlin.jvm") version "2.3.0"""",
                """id("tramai.kotlin-library")""",
            ),
        )
        val resultB = runner(orderB, "compileKotlin", "compileJava").build()
        assertEquals(TaskOutcome.SUCCESS, resultB.task(":compileKotlin")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, resultB.task(":compileJava")!!.outcome)
    }

    // ── K2: JVM 21 for Kotlin and Java ───────────────────────────────────────

    @Test
    fun `K2 kotlin-library compiles Kotlin and Java to JVM 21`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        kotlinLibraryFixture(
            dir,
            listOf(
                "`java-library`",
                """id("org.jetbrains.kotlin.jvm") version "2.3.0"""",
                """id("tramai.kotlin-library")""",
            ),
        )

        val result = runner(dir, "classes").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":classes")!!.outcome)

        val kotlinClass = File(dir, "build/classes/kotlin/main/Sample.class")
        val javaClass = File(dir, "build/classes/java/main/SampleJava.class")
        assertTrue(kotlinClass.isFile, "Kotlin class must be compiled")
        assertTrue(javaClass.isFile, "Java class must be compiled")
        assertEquals(65, classMajorVersion(kotlinClass), "Kotlin class must target JVM 21 (major 65)")
        assertEquals(65, classMajorVersion(javaClass), "Java class must target JVM 21 (major 65)")
    }

    // ── K3: sources artifact ─────────────────────────────────────────────────

    @Test
    fun `K3 kotlin-library sourcesJar contains Kotlin and Java sources`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        kotlinLibraryFixture(
            dir,
            listOf(
                "`java-library`",
                """id("org.jetbrains.kotlin.jvm") version "2.3.0"""",
                """id("tramai.kotlin-library")""",
            ),
        )

        val result = runner(dir, "sourcesJar").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":sourcesJar")!!.outcome)
        val jar = File(dir, "build/libs/sample-1.0-sources.jar")
        assertTrue(jar.isFile, "sources jar must be produced: ${jar.absolutePath}")
        val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue(entries.any { it.endsWith("Sample.kt") }, "sources jar must contain Sample.kt: $entries")
        assertTrue(entries.any { it.endsWith("SampleJava.java") }, "sources jar must contain SampleJava.java: $entries")
    }

    // ── K4: JUnit Platform actually executes a test ─────────────────────────

    @Test
    fun `K4 kotlin-library runs a real JUnit 5 test`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        kotlinLibraryFixture(
            dir,
            listOf(
                "`java-library`",
                """id("org.jetbrains.kotlin.jvm") version "2.3.0"""",
                """id("tramai.kotlin-library")""",
            ),
        )
        // add a real Jupiter test + JUnit 5 deps
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
            version = "1.0"
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "src/test/kotlin/SampleTest.kt",
            """
            import org.junit.jupiter.api.Test
            import kotlin.test.assertEquals

            class SampleTest {
                @Test
                fun sampleWorks() {
                    assertEquals(2, 1 + 1)
                }
            }
            """.trimIndent(),
        )

        val result = runner(dir, "test").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")!!.outcome)
        // prove the test actually executed, not just that the task succeeded
        val results = File(dir, "build/test-results/test")
        assertTrue(results.isDirectory, "test results must be written")
        val xml = results.listFiles()?.firstOrNull { it.name.endsWith(".xml") }
        assertTrue(xml != null, "JUnit XML result must exist")
        assertTrue(xml!!.readText().contains("sampleWorks"), "SampleTest must have executed: ${xml.readText().take(500)}")
    }

    // ── K5: test fixtures jar contains the fixture class ────────────────────

    @Test
    fun `K5 test-fixtures produces a jar with the fixture class`() {
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
            version = "1.0"
            """.trimIndent(),
        )
        writeFile(dir, "src/main/java/Sample.java", "public class Sample {}\n")
        writeFile(dir, "src/testFixtures/java/SampleFixture.java", "public class SampleFixture {}\n")

        val result = runner(dir, "testFixturesJar").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":testFixturesJar")!!.outcome)
        val jar = File(dir, "build/libs/sample-1.0-test-fixtures.jar")
        assertTrue(jar.isFile, "test-fixtures jar must be produced: ${jar.absolutePath}")
        val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue(entries.any { it.endsWith("SampleFixture.class") }, "fixtures jar must contain SampleFixture.class: $entries")
    }
}
