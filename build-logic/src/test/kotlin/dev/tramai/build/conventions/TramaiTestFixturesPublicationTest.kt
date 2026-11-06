package dev.tramai.build.conventions

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit regression for the test-fixtures publication boundary (0.7.4h1).
 *
 * The point of these tests is that the guarantee survives without anyone re-running the negative
 * control by hand: a real project with real fixtures is published and its **generated** `module.json`
 * is inspected. One test proves the leak is real when the convention is absent (otherwise "no fixture
 * variants" could mean the fixture never produced any), and the others prove the convention removes
 * them for both plugin orders — the ordering matters because the exclusion is registered from a
 * `withPlugin("maven-publish")` callback.
 *
 * `java-test-fixtures` and `maven-publish` are published by Gradle itself, so these fixtures need no
 * network access; only `tramai.test-fixtures` comes from the plugin classpath.
 */
class TramaiTestFixturesPublicationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `without the convention a fixture module publishes its fixture variant`() {
        val metadata = generatedMetadata(project(withConvention = false, publishBeforeConvention = false))

        assertContains(metadata, "testFixturesApiElements")
        assertContains(metadata, "testFixturesRuntimeElements")
        assertContains(metadata, "-test-fixtures.jar")
    }

    @Test
    fun `the convention removes the fixture variants when maven-publish is applied after it`() {
        val metadata = generatedMetadata(project(withConvention = true, publishBeforeConvention = false))

        assertNoFixturePublication(metadata)
    }

    @Test
    fun `the convention removes the fixture variants when maven-publish is applied before it`() {
        val metadata = generatedMetadata(project(withConvention = true, publishBeforeConvention = true))

        assertNoFixturePublication(metadata)
    }

    /** Ordinary publication must survive the exclusion, which is the half a leak-only check would miss. */
    private fun assertNoFixturePublication(metadata: String) {
        assertFalse(metadata.contains("testFixtures", ignoreCase = true), "leaked fixture variant: $metadata")
        assertFalse(metadata.contains("test-fixtures.jar"), "leaked fixture artifact: $metadata")
        assertContains(metadata, "apiElements")
        assertContains(metadata, "sourcesElements")
    }

    private fun project(
        withConvention: Boolean,
        publishBeforeConvention: Boolean,
    ): File {
        val dir = File(tempDir, "fixture-$withConvention-$publishBeforeConvention")
        dir.mkdirs()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        writeFixtureSources(dir)
        File(dir, "build.gradle.kts").writeText(
            buildScript(pluginBlock(withConvention, publishBeforeConvention)),
        )
        return dir
    }

    private fun writeFixtureSources(dir: File) {
        File(dir, "src/main/kotlin/Library.kt").apply {
            parentFile.mkdirs()
            writeText("class Library\n")
        }
        File(dir, "src/testFixtures/kotlin/FixtureSupport.kt").apply {
            parentFile.mkdirs()
            writeText("class FixtureSupport\n")
        }
    }

    /**
     * The convention applies `java-test-fixtures` itself; the control applies it directly, so both sides
     * create the same fixture source set. The ordering case applies `maven-publish` dynamically, which is
     * what a module does when it wires publishing later in its script.
     */
    private fun pluginBlock(
        withConvention: Boolean,
        publishBeforeConvention: Boolean,
    ): String =
        when {
            !withConvention -> {
                """
                plugins {
                    id("java-library")
                    id("java-test-fixtures")
                    id("maven-publish")
                }
                """.trimIndent()
            }

            publishBeforeConvention -> {
                """
                plugins {
                    id("java-library")
                    id("maven-publish")
                    id("tramai.test-fixtures")
                }
                """.trimIndent()
            }

            else -> {
                """
                plugins {
                    id("java-library")
                    id("tramai.test-fixtures")
                }
                apply(plugin = "maven-publish")
                """.trimIndent()
            }
        }

    /**
     * Typed through `configure<PublishingExtension>`: the type-safe `publishing { }` accessor is not
     * generated when the plugin is applied dynamically, which one of the orderings below does.
     */
    private fun buildScript(pluginLines: String): String =
        """
        import org.gradle.api.plugins.JavaPluginExtension
        import org.gradle.api.publish.PublishingExtension
        import org.gradle.api.publish.maven.MavenPublication

        $pluginLines

        group = "dev.tramai.fixture"
        version = "1.0.0"

        // A real library publishes sources too; without this the fixture would have no sources variant
        // to prove the convention leaves ordinary publication intact.
        extensions.configure<JavaPluginExtension> { withSourcesJar() }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
        """.trimIndent()

    private fun generatedMetadata(dir: File): String {
        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments("generateMetadataFileForMavenPublication", "--stacktrace")
                .withPluginClasspath()
                .build()

        assertTrue(
            result.output.contains("BUILD SUCCESSFUL"),
            "the fixture project must publish its metadata, otherwise the assertion is vacuous: ${result.output}",
        )
        return File(dir, "build/publications/maven/module.json").readText()
    }
}
