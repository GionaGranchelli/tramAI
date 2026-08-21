package dev.tramai.build.quality

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-level verifier for JUnit test-signature integrity.
 *
 * JUnit Jupiter silently discards `@Test` methods whose JVM return type is
 * not `void`. In Kotlin, an expression-bodied test whose final expression is
 * a chainable (non-void) AssertJ assertion — e.g.
 *
 * ```
 * @Test fun foo() = runBlocking { assertThat(x).isTrue() }
 * ```
 *
 * compiles to a non-void method, the test is never discovered, and the suite
 * stays green. The only source-visible guarantee of a void return is an
 * explicit Unit binding:
 *
 * - a block body (`fun foo() { ... }`), or
 * - an explicit `: Unit` return type, or
 * - an explicit `<Unit>` type argument on the expression head
 *   (`= runBlocking<Unit> { ... }`).
 *
 * This verifier rejects every other expression-bodied `@Test` function.
 * It is intentionally conservative: a compliant form that a future Kotlin
 * version infers as non-void must be made explicit, which is exactly the
 * invariant we want to preserve.
 *
 * Accepted scope (known fail-open false negatives, none present in tree
 * today, deliberately not handled): other JUnit discovery annotations
 * (`@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`) and exotic
 * constructs such as an anonymous `fun` inside an annotation argument
 * between `@Test` and the declaration.
 */
object JUnitTestSignatureVerifier {

    // Explicit `<Unit>` type argument on the expression head, e.g. `runBlocking<Unit> {`
    private val EXPLICIT_UNIT_HEAD =
        Regex("""^[\w.`]+\s*<Unit>\s*(?=[({.\s]).*$""")
    // `runTest { ... }` returns TestResult, which JUnit Jupiter special-cases as
    // a valid test-method return type (discovered, not skipped) — Unit-safe in
    // the discovery sense, so whitelisted without a type argument.
    private val KNOWN_UNIT_HEAD = Regex("""^runTest\s*[({].*$""")
    private val BARE_UNIT = Regex("""^Unit\s*$""")
    private val NAME = Regex("""\s*(?:public|private|internal)?\s*(?:suspend\s+)?fun\s+(`[^`]+`|[A-Za-z0-9_]+)""")

    data class Violation(val file: Path, val line: Int, val functionName: String, val expressionHead: String)

    /** Result of evaluating an accumulated `@Test` declaration. */
    private sealed interface Decision {
        data object Continue : Decision
        data object Safe : Decision
        data class Reject(val functionName: String, val expressionHead: String) : Decision
    }

    /**
     * Scans every `src/test` and `src/testFixtures` Kotlin source under [root]
     * and returns the violations found. Does not throw.
     */
    fun scan(root: Path): List<Violation> {
        val violations = mutableListOf<Violation>()
        if (!Files.isDirectory(root)) return violations
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { p ->
                    // Normalize separators so matching works on any platform.
                    val path = p.toString().replace(File.separatorChar, '/')
                    // Source trees only. Gradle output lives under <module>/build/,
                    // never under src/test or src/testFixtures, so no extra /build/
                    // exclusion is needed — and one would wrongly skip the
                    // `dev/tramai/build/*` source package (the guard's own home).
                    (path.contains("/src/test/") || path.contains("/src/testFixtures/"))
                }
                .forEach { file -> violations += scanFile(file) }
        }
        return violations
    }

    /**
     * Accumulates lines from `@Test` through the function declaration and
     * decides only once the body form (`{` vs `=`) is established, so
     * multi-line signatures and multi-line annotations cannot bypass the
     * check. Returns [Decision.Continue] until the declaration resolves.
     */
    private fun decide(decl: String): Decision {
        val funIdx = decl.indexOf("fun ")
        if (funIdx < 0) return Decision.Continue
        val openParen = decl.indexOf('(', funIdx)
        if (openParen < 0) return Decision.Continue
        var depth = 0
        var i = openParen
        while (i < decl.length) {
            when (decl[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        var j = i + 1
                        while (j < decl.length && decl[j].isWhitespace()) j++
                        if (j >= decl.length) return Decision.Continue
                        val name = NAME.find(decl, funIdx)?.groupValues?.get(1) ?: decl.substring(funIdx)
                        return when {
                            decl[j] == '{' -> Decision.Safe
                            decl[j] == '=' -> headDecision(decl.substring(j + 1), name)
                            decl[j] == ':' -> {
                                // Explicit return type: `: Unit` is safe, anything
                                // else with `=` needs the head to be Unit-safe.
                                val eqOrBrace = decl.indexOfAny(charArrayOf('=', '{'), j + 1)
                                when {
                                    eqOrBrace < 0 -> Decision.Continue
                                    decl[eqOrBrace] == '{' -> Decision.Safe
                                    decl.substring(j + 1, eqOrBrace).trim() == "Unit" -> Decision.Safe
                                    else -> headDecision(decl.substring(eqOrBrace + 1), name)
                                }
                            }
                            else -> Decision.Continue
                        }
                    }
                }
            }
            i++
        }
        return Decision.Continue
    }

    private fun headDecision(headRaw: String, name: String): Decision {
        val head = headRaw.trim()
        return if (EXPLICIT_UNIT_HEAD.matches(head) || KNOWN_UNIT_HEAD.matches(head) || BARE_UNIT.matches(head)) {
            Decision.Safe
        } else {
            Decision.Reject(name, head)
        }
    }

    private fun scanFile(file: Path): List<Violation> {
        val lines = try {
            Files.readAllLines(file)
        } catch (_: Exception) {
            return emptyList()
        }
        val violations = mutableListOf<Violation>()
        var pending: StringBuilder? = null
        var inRawString = false
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (inRawString) {
                if (line.countOccurrencesOf("\"\"\"") % 2 == 1) {
                    inRawString = false
                }
                continue
            }
            // Lines inside `"""..."""` raw strings (test fixtures contain
            // verbatim `@Test fun ... = ...` samples) are not real code.
            if (line.countOccurrencesOf("\"\"\"") % 2 == 1) {
                inRawString = true
                continue
            }
            // Comments and blank lines do not clear a pending declaration
            // (annotations may legally be separated from their function).
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue
            }
            if (line.startsWith("@")) {
                if (line.matches(Regex("""^@Test\b.*$"""))) {
                    pending = StringBuilder(line.substringAfter("@Test"))
                    pending = resolve(pending, file, index, violations)
                } else if (pending != null) {
                    pending.append(' ').append(line)
                    pending = resolve(pending, file, index, violations)
                }
                continue
            }
            if (pending != null) {
                pending.append('\n').append(line)
                pending = resolve(pending, file, index, violations)
                continue
            }
        }
        return violations
    }

    /**
     * Evaluates an accumulated declaration; returns the continuation state
     * (null once the declaration resolved to Safe or Reject).
     */
    private fun resolve(pending: StringBuilder, file: Path, line: Int, violations: MutableList<Violation>): StringBuilder? {
        return when (val decision = decide(pending.toString())) {
            is Decision.Reject -> {
                violations += Violation(file, line + 1, decision.functionName, decision.expressionHead)
                null
            }
            Decision.Safe -> null
            Decision.Continue -> pending
        }
    }
    private fun String.countOccurrencesOf(sub: String): Int {
        var count = 0
        var idx = indexOf(sub)
        while (idx >= 0) {
            count++
            idx = indexOf(sub, idx + sub.length)
        }
        return count
    }

    /** Renders a human-readable failure report. */
    fun render(violations: List<Violation>): String =
        violations.joinToString("\n") { v ->
            "${v.file}:${v.line} — @Test fun ${v.functionName} uses an expression body " +
                "with non-Unit inferred return type (head: ${v.expressionHead}). " +
                "@Test methods must return void: use a block body or an explicit Unit binding " +
                "(e.g. `fun x() { runBlocking { ... } }` or `= runBlocking<Unit> { ... }`)."
        }
}
