package dev.tramai.testing

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1a architecture guard: every concrete [dev.tramai.core.approval.ApprovalStore]
 * implementation must be enrolled in the shared ApprovalStore compatibility
 * contract (tramai-testing testFixtures).
 *
 * Two properties hold:
 *
 * 1. The three roadmap implementations (pinned allowlist) must each ship a
 *    `<Implementation>TckTest` runner. Deleting or renaming a runner breaks
 *    the gate.
 * 2. **Every concrete `ApprovalStore` implementation** in any module's main
 *    source set must have a runner named after it (`<Store>TckTest`), in the
 *    same module. Adding a store without a runner fails the gate — the phrase
 *    "future stores must pass the TCK" is otherwise documentation, not
 *    architecture.
 *
 * The runner file IS the reviewed contract matrix: it wires the store to the
 * shared [dev.tramai.testing.persistence.approval.ApprovalStoreTck].
 */
class ApprovalStoreTckEnrollmentArchitectureTest {

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryApprovalStoreTckTest",
        "FileApprovalStoreTckTest",
        "JdbcApprovalStoreTckTest",
    )

    @Test
    fun `every roadmap ApprovalStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> findRunnerFile(runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned ApprovalStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every ApprovalStore implementation has a runner named after it in its module`() {
        val unenrolled = storeModules().flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !hasRunner(module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "ApprovalStore implementations without a <Store>TckTest runner in the same module: $unenrolled. " +
                    "Adding an ApprovalStore without enrolling it in the compatibility contract " +
                    "must make a gate fail.",
            )
            .isEmpty()
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun storeModules(): List<Pair<String, List<String>>> {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return emptyList()
        return modules
            .mapNotNull { module ->
                if (module.name == "tramai-testing") return@mapNotNull null
                val main = File(module, "src/main/kotlin")
                if (!main.isDirectory) return@mapNotNull null
                val implementations = main.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> storeImplementations(file).asSequence() }
                    .distinct()
                    .sorted()
                    .toList()
                if (implementations.isEmpty()) null else module.name to implementations
            }
            .toList()
    }

    /**
     * Concrete [dev.tramai.core.approval.ApprovalStore] implementations declared in [file].
     * Mirrors the provider-TCK scanner: the supertype section is everything
     * after the first top-level `:` (depth-aware), so a constructor parameter
     * like `class X(val store: ApprovalStore, ...)` never counts as a supertype,
     * while `class X(...) : ApprovalStore` (multi-line or not) is caught.
     */
    private fun storeImplementations(file: File): List<String> {
        val text = file.readText()
        return CLASS_HEADER.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1]
            val header = match.groupValues[2]
            val supertype = supertypeSection(header)
            // A body-less declaration (no '{' of its own) makes the non-greedy
            // header regex bleed into the next declaration; a supertype section
            // containing declaration keywords or a doc comment is such a false
            // positive (e.g. exception classes with implicit bodies).
            val overSpanned = DECLARATION_KEYWORD.containsMatchIn(supertype)
            if (!overSpanned && supertype.contains("ApprovalStore")) name else null
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

    private fun hasRunner(module: String, storeName: String): Boolean {
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        if (!testDir.isDirectory) return false
        return testDir.walkTopDown()
            .any { it.isFile && it.name == "${storeName}TckTest.kt" }
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

        /** Declaration keywords that must never appear inside a real supertype list. */
        val DECLARATION_KEYWORD = Regex("""(fun |class |interface |object |/\*\*)""")
    }
}
