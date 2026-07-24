package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestQualityConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private val known = setOf(":core", ":engine")

    @Test
    fun `valid configuration is parsed`() {
        val parsed = TestQualityConfiguration.parse(write(validYaml()), known)
        assertEquals(listOf(":core", ":engine"), parsed.criticalModules)
        assertEquals(listOf(":engine"), parsed.mutation.targetFamilies.getValue("routing").modules)
        assertEquals("reason", parsed.coverage.exclusions.single().reason)
    }

    @Test
    fun `unknown module is rejected`() {
        assertInvalid(validYaml().replace(":engine\"]", ":missing\"]"))
    }

    @Test
    fun `duplicate family is rejected by YAML loader`() {
        assertInvalid(validYaml().replace(
            "    routing:\n      modules: [\":engine\"]",
            "    routing:\n      modules: [\":engine\"]\n    routing:\n      modules: [\":core\"]"
        ))
    }

    @Test
    fun `empty targets are rejected`() {
        assertInvalid(validYaml().replace("modules: [\":engine\"]", "modules: []"))
    }

    @Test
    fun `absolute exclusions are rejected`() {
        assertInvalid(validYaml().replace("**/model/**", "/home/user/model"))
    }

    @Test
    fun `invalid percentages are rejected`() {
        assertInvalid(validYaml().replace("1.0\n  exclusions", "101.0\n  exclusions"))
    }

    @Test
    fun `exclusion without reason is rejected`() {
        assertInvalid(validYaml().replace("reason: \"reason\"", "reason: \"\""))
    }

    private fun assertInvalid(yaml: String) {
        assertFailsWith<GradleException> { TestQualityConfiguration.parse(write(yaml), known) }
    }

    private fun write(content: String) = tempDir.resolve("test-quality.yml").toFile().apply {
        writeText(content)
    }

    private fun validYaml() =
        """
        schemaVersion: "1"
        criticalModules: [":core", ":engine"]
        coverage:
          regressionTolerancePercentagePoints: 1.0
          exclusions:
            - pattern: "**/model/**"
              reason: "reason"
        mutation:
          regressionTolerancePercentagePoints: 1.0
          targetFamilies:
            routing:
              modules: [":engine"]
        """.trimIndent()
}
