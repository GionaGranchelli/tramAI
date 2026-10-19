package dev.tramai.build.publishing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Semantics of the fixture-publication verifier: proves the rule is correct for the inputs it is handed.
 *
 * The real-task half of the hierarchy (the task loads the repository's generated metadata, and changing
 * that metadata changes the outcome) is the 0.7.4h1 negative control recorded in the task evidence.
 */
class PublishedFixtureLeakVerifierTest {
    @TempDir
    lateinit var temp: File

    /** One generated variant, in the shape Gradle writes into `module.json`. */
    private fun variant(
        name: String,
        files: List<String>,
        usage: String = "java-api",
        category: String? = null,
    ): String {
        val attributes =
            if (category == null) {
                """"attributes":{"org.gradle.usage":"$usage"}"""
            } else {
                """"attributes":{"org.gradle.category":"$category"}"""
            }
        val fileList = files.joinToString(",") { """{"name":"$it"}""" }
        return """{"name":"$name",$attributes,"files":[$fileList]}"""
    }

    private fun metadata(
        module: String,
        variants: List<String>,
    ): File {
        val dir = File(temp, "$module/build/publications/maven").apply { mkdirs() }
        val document = """{"formatVersion":"1.1","variants":[${variants.joinToString(",")}]}"""
        return File(dir, "module.json").apply { writeText(document) }
    }

    private fun verify(
        module: String,
        metadataFile: File,
    ): List<PublishedFixtureLeak> =
        PublishedFixtureLeakVerifier.verify(listOf(module)) { requested ->
            if (requested == module) metadataFile else null
        }

    /** The generated shape of a clean library: main, runtime, javadoc, sources, and nothing else. */
    private val cleanVariants =
        listOf(
            variant("apiElements", listOf("mod-0.7.0.jar"), usage = "java-api"),
            variant("runtimeElements", listOf("mod-0.7.0.jar"), usage = "java-runtime"),
            variant("javadocElements", listOf("mod-0.7.0-javadoc.jar"), category = "documentation"),
            variant("sourcesElements", listOf("mod-0.7.0-sources.jar"), category = "documentation"),
        )

    private val fixtureJar = "tramai-engine-0.7.0-SNAPSHOT-test-fixtures.jar"

    @Test
    fun `a clean publication has no findings`() {
        val leaks = verify("tramai-engine", metadata("tramai-engine", cleanVariants))

        assertEquals(emptyList<PublishedFixtureLeak>(), leaks)
    }

    @Test
    fun `a published test-fixtures variant is reported by module, variant and artifact`() {
        // Exactly the shape 0.7.4h1 found in the repository: java-test-fixtures attaches both variants.
        val variants =
            cleanVariants +
                listOf(
                    variant("testFixturesApiElements", listOf(fixtureJar)),
                    variant("testFixturesRuntimeElements", listOf(fixtureJar), usage = "java-runtime"),
                )

        val leaks = verify("tramai-engine", metadata("tramai-engine", variants))

        assertEquals(2, leaks.size, "both attached variants must be reported: $leaks")
        assertTrue(leaks.all { it.module == "tramai-engine" })
        assertTrue(leaks.all { it.artifact == fixtureJar })
        assertTrue(
            leaks.any { it.variant == "testFixturesApiElements" },
            "the diagnostic must name the variant: $leaks",
        )
        assertTrue(
            leaks.first().describe().contains("tramai-engine") &&
                leaks.first().describe().contains("test-fixtures.jar"),
            "the diagnostic must name the module and the leaked artifact: ${leaks.first().describe()}",
        )
    }

    @Test
    fun `a fixture jar served by a non-fixture variant is still reported`() {
        // The harder leak: the variant name looks legitimate, so only the artifact identifies it.
        val variants =
            cleanVariants.map { variant ->
                if (variant.contains("runtimeElements")) {
                    variant.replace(
                        """{"name":"mod-0.7.0.jar"}""",
                        """{"name":"mod-0.7.0.jar"},{"name":"mod-0.7.0-test-fixtures.jar"}""",
                    )
                } else {
                    variant
                }
            }

        val leaks = verify("tramai-security", metadata("tramai-security", variants))

        assertEquals(1, leaks.size, "the artifact leak must be reported: $leaks")
        assertEquals("runtimeElements", leaks.single().variant)
        assertEquals("mod-0.7.0-test-fixtures.jar", leaks.single().artifact)
        assertTrue(leaks.single().detail.contains("non-fixture variant"))
    }

    @Test
    fun `a module with no generated metadata is a finding, not a pass`() {
        // Fail closed: an unaudited module must never be reported as clean.
        val leaks = PublishedFixtureLeakVerifier.verify(listOf("tramai-engine")) { null }

        assertEquals(1, leaks.size)
        assertEquals("tramai-engine", leaks.single().module)
        assertEquals("<none>", leaks.single().variant)
        assertTrue(leaks.single().detail.contains("no generated publication metadata"))
    }

    @Test
    fun `every publishable module is audited, not just the first`() {
        val clean = metadata("tramai-core", cleanVariants)
        val leaking =
            metadata(
                "tramai-engine",
                cleanVariants + listOf(variant("testFixturesApiElements", listOf(fixtureJar))),
            )

        val leaks =
            PublishedFixtureLeakVerifier.verify(
                listOf("tramai-core", "tramai-engine", "tramai-security"),
            ) { module ->
                when (module) {
                    "tramai-core" -> clean
                    "tramai-engine" -> leaking
                    else -> metadata("tramai-security", cleanVariants)
                }
            }

        assertEquals(1, leaks.size, "only the leaking module may be reported: $leaks")
        assertEquals("tramai-engine", leaks.single().module)
    }
}
