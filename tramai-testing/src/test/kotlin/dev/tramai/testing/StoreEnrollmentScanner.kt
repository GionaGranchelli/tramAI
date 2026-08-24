package dev.tramai.testing

import java.io.File

/**
 * Source-shape scanner shared by the store-family enrollment architecture
 * tests (#267 ApprovalStore, #269 ApprovalContinuationStore). Recognizes
 * class/object declarations with or without bodies, single-line or
 * multiline, and with or without visibility/`open` modifiers — not a type
 * resolver. A `<Store>TckTest` file only counts as enrollment when it
 * actually extends the family's shared TCK.
 */
class StoreEnrollmentScanner(
    private val interfaceName: String,
    private val tckName: String,
) {

    private val interfaceType = Regex("""\b$interfaceName\b""")

    /** Modules (name -> detected implementation class names) whose main source set declares the interface. */
    fun storeModules(repoRoot: File): List<Pair<String, List<String>>> {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return emptyList()
        return modules
            .mapNotNull { module ->
                if (module.name == "tramai-testing") return@mapNotNull null
                val main = File(module, "src/main/kotlin")
                if (!main.isDirectory) return@mapNotNull null
                val implementations = main.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file -> implementationsIn(file).asSequence() }
                    .distinct()
                    .sorted()
                    .toList()
                if (implementations.isEmpty()) null else module.name to implementations
            }
            .toList()
    }

    /**
     * Concrete implementations of the family interface declared in [file].
     * Mirrors the provider-TCK scanner: the supertype section is everything
     * after the first top-level `:` (depth-aware), so a constructor parameter
     * like `class X(val store: ApprovalStore, ...)` never counts as a
     * supertype, while `class X(...) : ApprovalStore` (multi-line or not) is
     * caught. `private` declarations are skipped: a private class cannot be a
     * publishable store-family member (nothing outside its file can
     * instantiate it or enroll it in a runner), e.g. the supervisor's
     * private lease-fencing decorator.
     */
    fun implementationsIn(file: File): List<String> {
        val text = file.readText()
        return classHeaders(text).mapNotNull { (name, header) ->
            val supertype = supertypeSection(header)
            // A body-less declaration (no '{' of its own) makes the non-greedy
            // header regex bleed into the next declaration; a supertype section
            // containing declaration keywords or a doc comment is such a false
            // positive (e.g. exception classes with implicit bodies).
            val overSpanned = DECLARATION_KEYWORD.containsMatchIn(supertype)
            val isPrivate = Regex("""(?m)^\s*private\s+(?:class|object)\s+$name\b""").containsMatchIn(text)
            // Word-boundary match on the interface name so a supertype like
            // `ApprovalContinuationStoreException` (whose name merely
            // contains the words) never counts as an implementation.
            if (!overSpanned && !isPrivate && interfaceType.containsMatchIn(supertype)) name else null
        }.distinct().toList()
    }

    fun hasValidRunner(repoRoot: File, module: String, storeName: String): Boolean {
        val runner = findRunnerFileInModule(repoRoot, module, storeName) ?: return false
        return runnerSubclassesTck(runner, storeName)
    }

    fun findRunnerFileInModule(repoRoot: File, module: String, storeName: String): File? {
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        if (!testDir.isDirectory) return null
        return testDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "${storeName}TckTest.kt" }
    }

    fun findRunnerFile(repoRoot: File, runnerName: String): File? {
        val modules = repoRoot.listFiles { file -> file.isDirectory && file.name.startsWith("tramai-") }
            ?: return null
        return modules.asSequence()
            .map { File(it, "src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().asSequence() }
            .firstOrNull { it.isFile && it.name == "$runnerName.kt" }
    }

    /**
     * A `<Store>TckTest` file only counts as enrollment if its class actually
     * extends the family's shared TCK. A same-named file with an unrelated
     * class would otherwise satisfy the gate while executing zero contract
     * tests.
     */
    fun runnerSubclassesTck(runnerFile: File, storeName: String): Boolean =
        Regex("""(?s)class\s+${storeName}TckTest\b[^{]*:\s*$tckName\b""")
            .containsMatchIn(runnerFile.readText())

    /**
     * Class/object name + header, as a union of two shapes:
     * - with a body: header runs to the first `{` (may span lines);
     * - body-less (e.g. `class X : Interface by delegate`): the header
     *   runs to the first `{` or to the end of the declaration. A body-less
     *   declaration may span lines when parameters are parenthesized, so the
     *   line walk continues while paren depth is positive or the line ends
     *   with `(` / `,`.
     */
    internal fun classHeaders(text: String): List<Pair<String, String>> {
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
                    header.trimEnd().endsWith(":") ||
                    // `)\n    : Interface by delegate` — supertype on its
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
    internal fun supertypeSection(header: String): String {
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

    private companion object {
        /** Declaration keywords that must never appear inside a real supertype list. */
        val DECLARATION_KEYWORD = Regex("""(fun |class |interface |object |/\*\*)""")
    }
}
