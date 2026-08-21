package dev.tramai.testing

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 6.1 architecture guard: every published provider must be enrolled in
 * the provider compatibility contract.
 *
 * Two properties hold:
 *
 * 1. The eight roadmap providers (pinned allowlist) must each ship a
 *    `*ProviderTckTest` runner. Deleting or renaming a runner breaks the gate.
 * 2. Any provider module that implements [dev.tramai.core.provider.ModelProvider]
 *    in its main source set must have a matching TCK runner. Adding a new
 *    provider without enrolling it in the contract breaks the gate.
 *
 * The runner file IS the reviewed contract matrix: it pins the expected
 * provider id, the exact capability set, and every fixture. A provider can
 * never skip a contract by changing its own capability declaration.
 */
class ProviderTckEnrollmentArchitectureTest {

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "OpenAiCompatibleProviderTckTest",
        "OpenAiProviderTckTest",
        "AzureOpenAiProviderTckTest",
        "AnthropicProviderTckTest",
        "OllamaProviderTckTest",
        "GeminiProviderTckTest",
        "BedrockProviderTckTest",
        "DeepSeekProviderTckTest",
    )

    @Test
    fun `every roadmap provider ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> findRunnerFile(runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned roadmap provider TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a provider from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every ModelProvider implementation is enrolled in the TCK`() {
        val unenrolled = providerModules().filter { module -> !hasRunner(module) }
        assertThat(unenrolled)
            .withFailMessage(
                "Provider modules without a *ProviderTckTest runner: $unenrolled. " +
                    "Adding a published provider without enrolling it in the compatibility contract " +
                    "must make a gate fail (the phrase 'future providers must pass the TCK' is " +
                    "otherwise documentation, not architecture).",
            )
            .isEmpty()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun providerModules(): List<String> {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return emptyList()
        return modules
            .filter { module ->
                // Mock/test providers live in the testing module and are deliberately not published.
                if (module.name == "tramai-testing") return@filter false
                val main = File(module, "src/main/kotlin")
                if (!main.isDirectory) return@filter false
                main.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .any { file -> file.readLines().any(::isProviderImplementation) }
            }
            .map { it.name }
            .sorted()
    }

    /** True when a source line declares a class implementing [dev.tramai.core.provider.ModelProvider]. */
    private fun isProviderImplementation(line: String): Boolean {
        if (!line.contains("class ") && !line.contains("object ")) return false
        if (!line.contains("ModelProvider")) return false
        // Supertype colon is preceded by ')' or whitespace — a constructor
        // parameter like `val provider: ModelProvider` has a word-char prefix.
        return SUPER_TYPE_REGEX.containsMatchIn(line) || line.contains("ModelProvider by")
    }

    private companion object {
        val SUPER_TYPE_REGEX = Regex("""[^A-Za-z0-9_]: ModelProvider""")
    }

    private fun hasRunner(module: String): Boolean {
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        if (!testDir.isDirectory) return false
        return testDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.endsWith("ProviderTckTest.kt") }
            .any()
    }

    private fun findRunnerFile(runnerName: String): File? {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return null
        return modules.asSequence()
            .map { File(it, "src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .firstOrNull { it.isFile && it.name == "$runnerName.kt" }
    }
}
