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
 * today, deliberately not handled): multi-line function signatures
 * (`fun foo(\n...) = expr`), annotation parameters on the same line as
 * `@Test` (`@Test(timeout = ...)`), and other JUnit discovery annotations
 * (`@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`).
 */
object JUnitTestSignatureVerifier {

    private val FUN_SIGNATURE =
        Regex("""^\s*(?:public|private|internal)?\s*(?:suspend\s+)?fun\s+(`[^`]+`|[A-Za-z0-9_]+)\s*\([^)]*\)\s*=\s*(\S.*)$""")
    private val EXPLICIT_UNIT_SIGNATURE =
        Regex("""^\s*(?:public|private|internal)?\s*(?:suspend\s+)?fun\s+(`[^`]+`|[A-Za-z0-9_]+)\s*\([^)]*\)\s*:\s*Unit\s*=.*$""")
    // Explicit `<Unit>` type argument on the expression head, e.g. `runBlocking<Unit> {`
    private val EXPLICIT_UNIT_HEAD =
        Regex("""^[\w.`]+\s*<Unit>\s*(?=[({.\s]).*$""")
    // `runTest { ... }` returns TestResult, which JUnit Jupiter special-cases as
    // a valid test-method return type (discovered, not skipped) — Unit-safe in
    // the discovery sense, so whitelisted without a type argument.
    private val KNOWN_UNIT_HEAD = Regex("""^runTest\s*[({].*$""")
    private val BARE_UNIT = Regex("""^Unit\s*$""")

    data class Violation(val file: Path, val line: Int, val functionName: String, val expressionHead: String)

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
                    val path = p.toString()
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

    private fun scanFile(file: Path): List<Violation> {
        val lines = try {
            Files.readAllLines(file)
        } catch (_: Exception) {
            return emptyList()
        }
        val violations = mutableListOf<Violation>()
        var pendingTest = false
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
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue
            }
            if (line.startsWith("@")) {
                if (line.matches(Regex("""^@Test\b.*$"""))) {
                    // Same-line form: `@Test fun `name`() = <expr>` — the fun
                    // signature shares the @Test line. Evaluate it directly.
                    val inline = line.substringAfter("@Test").trim()
                    if (inline.startsWith("fun ")) {
                        if (!EXPLICIT_UNIT_SIGNATURE.matches(inline)) {
                            val match = FUN_SIGNATURE.matchEntire(inline)
                            if (match != null) {
                                val head = match.groupValues[2]
                                if (!EXPLICIT_UNIT_HEAD.matches(head) && !KNOWN_UNIT_HEAD.matches(head) && !BARE_UNIT.matches(head)) {
                                    violations += Violation(file, index + 1, match.groupValues[1], head)
                                }
                            }
                        }
                    } else {
                        pendingTest = true
                    }
                }
                continue
            }
            if (line.startsWith("fun ") && pendingTest) {
                pendingTest = false
                if (EXPLICIT_UNIT_SIGNATURE.matches(line)) {
                    continue
                }
                val match = FUN_SIGNATURE.matchEntire(line)
                if (match != null) {
                    val head = match.groupValues[2]
                    if (!EXPLICIT_UNIT_HEAD.matches(head) && !KNOWN_UNIT_HEAD.matches(head) && !BARE_UNIT.matches(head)) {
                        violations += Violation(file, index + 1, match.groupValues[1], head)
                    }
                }
                continue
            }
            pendingTest = false
        }
        return violations
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
