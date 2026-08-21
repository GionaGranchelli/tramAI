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
 * 2. **Every concrete `ModelProvider` implementation** in a provider module's
 *    main source set must have a runner named after it (`<Provider>TckTest`),
 *    in the same module. The mapping is implementation → runner, not
 *    module → any runner: adding a second provider to an already-enrolled
 *    module without a runner of its own fails the gate, as does a multiline
 *    class declaration, a renamed provider, or a deleted runner.
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
    fun `every ModelProvider implementation has a runner named after it in its module`() {
        val unenrolled = providerModules().flatMap { (module, implementations) ->
            implementations
                .filter { providerName -> !hasRunner(module, providerName) }
                .map { provider -> "$module/$provider" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "Provider implementations without a <Provider>TckTest runner in the same module: $unenrolled. " +
                    "Adding a published provider without enrolling it in the compatibility contract " +
                    "must make a gate fail (the phrase 'future providers must pass the TCK' is " +
                    "otherwise documentation, not architecture).",
            )
            .isEmpty()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun providerModules(): List<Pair<String, List<String>>> {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return emptyList()
        return modules
            .mapNotNull { module ->
                // Mock/test providers live in the testing module and are deliberately not published.
                if (module.name == "tramai-testing") return@mapNotNull null
                val main = File(module, "src/main/kotlin")
                if (!main.isDirectory) return@mapNotNull null
                val implementations = main.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> providerImplementations(file).asSequence() }
                    .distinct()
                    .sorted()
                    .toList()
                if (implementations.isEmpty()) null else module.name to implementations
            }
            .toList()
    }

    /**
     * Concrete [dev.tramai.core.provider.ModelProvider] implementations declared in [file].
     *
     * A class/object implements ModelProvider when its header (text between the
     * name and the first `{`) lists ModelProvider among its supertypes. The
     * supertype section is everything after the FIRST top-level `:` (outside
     * parentheses) — depth-aware so a constructor parameter like
     * `class X(val provider: ModelProvider, ...)` never counts as a supertype,
     * while `class X(...) : ModelProvider, SomeBase(...)` (multi-line or not)
     * is caught even though the base constructor's `)` comes after ModelProvider.
     */
    private fun providerImplementations(file: File): List<String> {
        val text = file.readText()
        return CLASS_HEADER.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1]
            val header = match.groupValues[2]
            if (supertypeSection(header).contains("ModelProvider")) name else null
        }.toList()
    }

    /** Everything after the first top-level `:` in a class header (the supertype list). */
    private fun supertypeSection(header: String): String {
        var depth = 0
        for ((index, ch) in header.withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> depth--
                ':' -> if (depth == 0) return header.substring(index + 1)
            }
        }
        return ""
    }

    private fun hasRunner(module: String, providerName: String): Boolean {
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        if (!testDir.isDirectory) return false
        return testDir.walkTopDown()
            .any { it.isFile && it.name == "${providerName}TckTest.kt" }
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

    private companion object {
        /** Class/object name + header up to the first `{`, spanning newlines. */
        val CLASS_HEADER = Regex("""(?s)(?:class|object)\s+(\w+)(.*?)\{""")
    }
}
