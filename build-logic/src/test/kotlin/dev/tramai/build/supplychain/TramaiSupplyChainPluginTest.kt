package dev.tramai.build.supplychain

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discriminating TestKit tests for the 9.2d-b2 slice A extraction:
 * prepareCycloneDxBom moved from the root build script into the
 * tramai.supply-chain convention plugin.
 *
 * The Socratic discriminator: perturb the declared BOM input and prove the
 * extracted task consumes THAT input (the declared @InputFile), not a
 * rediscovered historical root path.
 */
class TramaiSupplyChainPluginTest {
    @TempDir
    lateinit var tempDir: File

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun fixture(
        bomContent: String,
        writeBom: Boolean = true,
    ): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"supply-chain-fixture\"\n")
        // The real build applies org.cyclonedx.bom which provides
        // cyclonedxBom; register a sentinel that writes the BOM under the
        // DECLARED input path, so the fixture can perturb it.
        val bomContentLiteral = "\"\"\"" + bomContent + "\"\"\""
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.supply-chain") }
            tasks.register("cyclonedxBom") {
                doLast {
                    ${if (writeBom) {
                "val bom = layout.buildDirectory.file(\"reports/cyclonedx/bom.json\").get().asFile\n                    bom.parentFile.mkdirs()\n                    bom.writeText($bomContentLiteral)"
            } else {
                "logger.lifecycle(\"fixture cyclonedxBom ran (no BOM written)\")"
            }}
                }
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--no-build-cache", "--stacktrace")
            .withPluginClasspath()

    @Test
    fun `prepareCycloneDxBom copies the declared BOM input and writes a digest`() {
        val dir = fixture("""{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}""")
        val result = runner(dir, "prepareCycloneDxBom").build()

        assertTrue(result.output.contains("SBOM generated:"), "copy marker required")
        val sbom = File(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.json")
        assertTrue(sbom.isFile, "SBOM must be copied under build/supply-chain/sbom/")
        assertEquals("""{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}""", sbom.readText())
        val digestFile = File(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        assertTrue(digestFile.isFile, "SHA-256 digest must be written")
        assertContains(digestFile.readText(), "sha256:")
    }

    @Test
    fun `SBOM authority mutation - task consumes the declared input, not a hardcoded path`() {
        val dir = fixture("""{"bomFormat":"CycloneDX","version":1,"content":"FIRST"}""")
        runner(dir, "prepareCycloneDxBom").build()

        // Perturb the DECLARED input: the next run must reflect the new
        // content in the copied SBOM — proving the task reads the @Internal
        // declared input property, not a rediscovered historical root path.
        // -x cyclonedxBom keeps the sentinel from re-writing FIRST.
        writeFile(
            dir,
            "build/reports/cyclonedx/bom.json",
            """{"bomFormat":"CycloneDX","version":1,"content":"SECOND"}""",
        )
        runner(dir, "prepareCycloneDxBom", "--rerun-tasks", "-x", "cyclonedxBom").build()

        val sbom = File(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.json")
        assertEquals("""{"bomFormat":"CycloneDX","version":1,"content":"SECOND"}""", sbom.readText())
        val digestFile = File(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        val firstDigest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("""{"bomFormat":"CycloneDX","version":1,"content":"FIRST"}""".toByteArray())
                .joinToString("") { "%02x".format(it) }
        val secondDigest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("""{"bomFormat":"CycloneDX","version":1,"content":"SECOND"}""".toByteArray())
                .joinToString("") { "%02x".format(it) }
        assertContains(digestFile.readText(), secondDigest)
        assertTrue(firstDigest != secondDigest, "mutated input must change the digest")
    }

    @Test
    fun `prepareCycloneDxBom warns and skips when the BOM input is absent`() {
        val dir = fixture("unused", writeBom = false)
        // The sentinel cyclonedxBom writes nothing: the historical root
        // behavior warns and skips (no failure). The typed task must preserve
        // that fail-soft diagnostic contract.
        val result = runner(dir, "prepareCycloneDxBom").build()
        assertTrue(
            result.output.contains("skipping SBOM copy"),
            "fail-soft warn marker required, got: ${result.output.take(800)}",
        )
    }

    @Test
    fun `prepareCycloneDxBom re-runs when outputs already exist and the BOM changed`() {
        // Regression for the Copilot finding: with @OutputDirectory and an
        // @Internal source (deliberate, fail-soft), Gradle's up-to-date check
        // would skip the task once outputs exist — even after cyclonedxBom
        // regenerates the BOM. The historical root closure always re-ran.
        val dir = fixture("""{"bomFormat":"CycloneDX","version":1,"content":"FIRST"}""")
        runner(dir, "prepareCycloneDxBom").build()
        val sbom = File(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.json")
        assertTrue(sbom.isFile)

        writeFile(
            dir,
            "build/reports/cyclonedx/bom.json",
            """{"bomFormat":"CycloneDX","version":1,"content":"SECOND"}""",
        )
        // No --rerun-tasks: the task must execute on its own (outputs exist).
        // -x cyclonedxBom keeps the sentinel from re-writing FIRST.
        val result = runner(dir, "prepareCycloneDxBom", "-x", "cyclonedxBom").build()
        assertTrue(result.output.contains("SBOM generated:"), "task must re-run, not be UP-TO-DATE")
        assertEquals("""{"bomFormat":"CycloneDX","version":1,"content":"SECOND"}""", sbom.readText())
    }

    @Test
    fun `supply-chain plugin is root-only - no prepareCycloneDxBom in subprojects`() {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"multi\"\ninclude(\":sub\")\n")
        writeFile(dir, "build.gradle.kts", "")
        writeFile(
            dir,
            "sub/build.gradle.kts",
            """
            plugins { id("tramai.supply-chain") }
            """.trimIndent(),
        )
        val result = runner(dir, "sub:tasks", "--all").build()
        assertTrue(
            !result.output.contains("prepareCycloneDxBom"),
            "plugin must no-op on subprojects (root-only guard), got: ${result.output.take(800)}",
        )
    }

    @Test
    fun `root build script no longer carries the CycloneDX copy implementation`() {
        val prop =
            System.getProperty("tramai.repositoryRoot")
                ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")
        val rootBuildScript = File(prop, "build.gradle.kts").readText()

        for (marker in listOf(
            "tasks.register(\"prepareCycloneDxBom\")",
            "tramai-cyclonedx-sbom.sha256",
            "MessageDigest.getInstance(\"SHA-256\")",
        )) {
            assertTrue(
                !rootBuildScript.contains(marker),
                "root build.gradle.kts must not contain CycloneDX implementation marker: $marker",
            )
        }
    }
}
