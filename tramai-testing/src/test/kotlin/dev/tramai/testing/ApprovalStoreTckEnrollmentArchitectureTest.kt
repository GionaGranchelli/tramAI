package dev.tramai.testing

import java.io.File
import java.nio.file.Files
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
 *    same module, that actually extends [dev.tramai.testing.persistence.approval.ApprovalStoreTck].
 *    Adding a store without a runner fails the gate — the phrase
 *    "future stores must pass the TCK" is otherwise documentation, not
 *    architecture. A file named `<Store>TckTest.kt` that does not subclass
 *    the TCK does not count. The scanner is source-shape based (class/object
 *    declarations with or without bodies, single-line or multiline, with or
 *    without visibility/`open` modifiers) — not a type resolver.
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
    fun `every ApprovalStore implementation has a valid TCK runner in its module`() {
        val unenrolled = storeModules().flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !hasValidRunner(module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "ApprovalStore implementations without a <Store>TckTest runner extending " +
                    "ApprovalStoreTck in the same module: $unenrolled. " +
                    "Adding an ApprovalStore without enrolling it in the compatibility contract " +
                    "must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner itself ──────────────────────────

    @Test
    fun `body-less ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            class RedisApprovalStore(private val delegate: ApprovalStore) : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(storeImplementations(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `multiline ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            class MultiLineApprovalStore(
                private val clock: java.time.Clock,
            ) : ApprovalStore {
                override suspend fun create(request: ApprovalRequest): ApprovalRequest = request
            }
            """.trimIndent(),
        )
        assertThat(storeImplementations(file)).containsExactly("MultiLineApprovalStore")
    }

    @Test
    fun `multiline body-less ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            class RedisApprovalStore(
                private val delegate: ApprovalStore,
            ) : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(storeImplementations(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `modifier-prefixed and colon-continued body-less implementations are detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            internal class RedisApprovalStore(
                private val delegate: ApprovalStore,
            )
                : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(storeImplementations(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `runner file must actually subclass ApprovalStoreTck`() {
        val fake = tempSourceFile("class RedisApprovalStoreTckTest")
        val real = tempSourceFile("class RedisApprovalStoreTckTest : ApprovalStoreTck() { }")
        assertThat(runnerSubclassesTck(fake, "RedisApprovalStore")).isFalse()
        assertThat(runnerSubclassesTck(real, "RedisApprovalStore")).isTrue()
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
        return classHeaders(text).mapNotNull { (name, header) ->
            val supertype = supertypeSection(header)
            // A body-less declaration (no '{' of its own) makes the non-greedy
            // header regex bleed into the next declaration; a supertype section
            // containing declaration keywords or a doc comment is such a false
            // positive (e.g. exception classes with implicit bodies).
            val overSpanned = DECLARATION_KEYWORD.containsMatchIn(supertype)
            // Word-boundary match on the interface name so a supertype like
            // `ApprovalStoreException` (whose name merely contains the word)
            // never counts as an implementation.
            if (!overSpanned && APPROVAL_STORE_TYPE.containsMatchIn(supertype)) name else null
        }.distinct().toList()
    }

    /**
     * Class/object name + header, as a union of two shapes:
     * - with a body: header runs to the first `{` (may span lines);
     * - body-less (e.g. `class X : ApprovalStore by delegate`): the header
     *   runs to the first `{` or to the end of the declaration. A body-less
     *   declaration may span lines when parameters are parenthesized, so the
     *   line walk continues while paren depth is positive or the line ends
     *   with `(` / `,`.
     */
    private fun classHeaders(text: String): List<Pair<String, String>> {
        val withBody = Regex("""(?s)(?:class|object)\s+(\w+)(.*?)\{""")
            .findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }
        val bodyless = bodylessHeaders(text)
        return (withBody + bodyless).toList()
    }

    private fun bodylessHeaders(text: String): List<Pair<String, String>> {
        val lines = text.lines()
        // Unanchored so visibility / open modifiers (`internal class X ...`)
        // still match.
        val declaration = Regex("""(?:class|object)\s+(\w+)(.*)$""")
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val decl = declaration.find(lines[i])
            if (decl == null) {
                i++
                continue
            }
            val name = decl.groupValues[1]
            val header = StringBuilder(decl.groupValues[2])
            var depth = decl.groupValues[2].count { it == '(' } - decl.groupValues[2].count { it == ')' }
            var j = i
            while (true) {
                val nextLine = lines.getOrNull(j + 1)
                val continues = depth > 0 ||
                    header.trimEnd().endsWith("(") ||
                    header.trimEnd().endsWith(",") ||
                    // `)\n    : ApprovalStore by delegate` — supertype on its
                    // own line.
                    (nextLine != null && nextLine.trimStart().startsWith(":"))
                if (!continues) break
                j++
                if (j >= lines.size) break
                val next = lines[j]
                header.append("\n").append(next)
                depth += next.count { it == '(' } - next.count { it == ')' }
            }
            result.add(name to header.toString())
            i = j + 1
        }
        return result
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

    private fun hasValidRunner(module: String, storeName: String): Boolean {
        val runner = findRunnerFileInModule(module, storeName) ?: return false
        return runnerSubclassesTck(runner, storeName)
    }

    private fun findRunnerFileInModule(module: String, storeName: String): File? {
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        if (!testDir.isDirectory) return null
        return testDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "${storeName}TckTest.kt" }
    }

    /**
     * A `<Store>TckTest` file only counts as enrollment if its class actually
     * extends [dev.tramai.testing.persistence.approval.ApprovalStoreTck]. A
     * same-named file with an unrelated class would otherwise satisfy the
     * gate while executing zero contract tests.
     */
    private fun runnerSubclassesTck(runnerFile: File, storeName: String): Boolean =
        Regex("""(?s)class\s+${storeName}TckTest\b[^{]*:\s*ApprovalStoreTck\b""")
            .containsMatchIn(runnerFile.readText())

    private fun findRunnerFile(runnerName: String): File? {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return null
        return modules.asSequence()
            .map { File(it, "src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .firstOrNull { it.isFile && it.name == "$runnerName.kt" }
    }

    private fun tempSourceFile(content: String): File {
        val dir = Files.createTempDirectory("tck-enrollment-probe-").toFile()
        dir.deleteOnExit()
        return File(dir, "Probe.kt").apply { writeText(content) }
    }

    private companion object {
        /** The interface every enrolled store must extend. */
        val APPROVAL_STORE_TYPE = Regex("""\bApprovalStore\b""")

        /** Declaration keywords that must never appear inside a real supertype list. */
        val DECLARATION_KEYWORD = Regex("""(fun |class |interface |object |/\*\*)""")
    }
}
