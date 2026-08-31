package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

data class SafetyFinding(
    val rule: String,
    val path: String,
    val line: Int,
    val symbol: String,
    val snippet: String,
    val exempt: Boolean = false,
)

@CacheableTask
abstract class VerifyStaticSafetyGuardsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @get:OutputDirectory
    abstract val reportsDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = File(repositoryRoot.get())
        val config = StaticSafetyGuardConfigParser.parse(configFile.get().asFile.readText(), root)
        val exemptionMap = config.exemptions.associateBy { Triple(it.rule, it.path, it.symbol) }
        val findings =
            sourceFiles.files
                .filter { it.isFile }
                .flatMap { scan(it, root, config) }
                .map { f -> f.copy(exempt = Triple(f.rule, f.path, f.symbol) in exemptionMap) }
                .sortedWith(compareBy({ it.path }, { it.line }, { it.rule }, { it.symbol }))
        val dir = reportsDir.get().asFile.apply { mkdirs() }
        val reportBody = findings.joinToString("\n") { format(it) } + if (findings.isEmpty()) "" else "\n"
        dir.resolve("findings.txt").writeText(reportBody)
        val unexplained = findings.filterNot { it.exempt }
        val ruleCounts = config.rules.joinToString("\n") { r -> "${r.id}: ${findings.count { it.rule == r.id }}" }
        dir.resolve("summary.txt").writeText(
            "Static safety guards\n" +
                "findings: ${findings.size}\n" +
                "unexplained: ${unexplained.size}\n" +
                "exemptions live: ${exemptionMap.size}\n" +
                "stale exemptions: ${staleTriples(exemptionMap, findings).size}\n" +
                ruleCounts,
        )
        val violations =
            buildString {
                unexplained.forEach { appendLine(format(it)) }
                countMismatches(exemptionMap, findings).forEach { appendLine(it) }
                staleTriples(exemptionMap, findings).forEach { (key, _) ->
                    appendLine("stale exemption: ${key.first} | ${key.second} | ${key.third}")
                }
                if (unexplained.isNotEmpty() || isNotEmpty()) {
                    append("Fix the code or add a scoped exemption with rationale to config/quality/static-safety-guards.yml")
                }
            }
        if (violations.isNotBlank()) throw GradleException(violations)
        logger.lifecycle(
            "static-safety-guards: ${findings.size} findings, ${exemptionMap.size} exemption entries, 0 unexplained",
        )
    }

    private fun countMismatches(
        exemptionMap: Map<Triple<String, String, String>, StaticSafetyExemption>,
        findings: List<SafetyFinding>,
    ): List<String> =
        exemptionMap.mapNotNull { (key, ex) ->
            val actual = findings.count { Triple(it.rule, it.path, it.symbol) == key }
            when {
                actual == 0 -> {
                    null
                }

                // stale, reported separately
                actual != ex.occurrences -> {
                    "exemption count mismatch: ${key.first} | ${key.second} | ${key.third} | declared ${ex.occurrences}, actual $actual"
                }

                else -> {
                    null
                }
            }
        }

    private fun staleTriples(
        exemptionMap: Map<Triple<String, String, String>, StaticSafetyExemption>,
        findings: List<SafetyFinding>,
    ): List<Pair<Triple<String, String, String>, StaticSafetyExemption>> =
        exemptionMap.filter { (key, _) -> findings.none { Triple(it.rule, it.path, it.symbol) == key } }.toList()

    private fun format(f: SafetyFinding): String {
        val prefix = if (f.exempt) "(exempt) " else ""
        return "${f.rule} | ${f.path} | ${f.line} | ${f.symbol} | $prefix${f.snippet}"
    }

    private fun scan(
        file: File,
        root: File,
        config: StaticSafetyGuardConfig,
    ): List<SafetyFinding> {
        val path =
            root
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        val text = file.readText()
        val imports = Imports(text)
        val tokens = Lexer(text).lex()
        val out = mutableListOf<SafetyFinding>()

        fun approved(rule: StaticSafetyRule): Boolean =
            rule.approvedPaths.any { path.startsWith(it.trimEnd('/') + "/") || path == it.trimEnd('/') }

        for ((i, t) in tokens.withIndex()) {
            // Call site = identifier followed by '(' or by '{' (trailing-lambda call without parens).
            if (t.kind != Kind.ID || i + 1 >= tokens.size || (tokens[i + 1].text != "(" && tokens[i + 1].text != "{")) continue
            val q = imports.resolve(qualified(tokens, i))
            val simple = t.text
            config.rules.forEach { r ->
                if (!approved(r)) {
                    val matched =
                        when (r.match) {
                            "call-name" -> {
                                callMatches(simple, q, r.symbols, r.receiverSymbols)
                            }

                            "multi" -> {
                                callMatches(simple, q, r.symbols, r.receiverSymbols) ||
                                    (simple in r.blockReadSymbols && bodyReceiver(tokens, i))
                            }

                            "receiver-call" -> {
                                simple in r.symbols &&
                                    receiverMatch(q, r) && argHas(tokens, i, r.sensitiveSymbols)
                            }

                            else -> {
                                false
                            }
                        }
                    if (matched) {
                        out += SafetyFinding(r.id, path, t.line, matchedSymbol(simple, q, r), snippet(file, t.line))
                    }
                    if ((r.match == "body-use-block" || r.match == "multi") && simple == "use" &&
                        bodyReceiver(tokens, i) && blockHas(tokens, i, r.blockReadSymbols)
                    ) {
                        val line = blockLine(tokens, i, r.blockReadSymbols)
                        val symbol = r.blockReadSymbols.first { blockContains(tokens, i, it) }
                        out += SafetyFinding(r.id, path, line, symbol, snippet(file, line))
                    }
                }
            }
        }
        return out.distinctBy { Triple(it.rule, it.line, it.symbol) }
    }

    private fun matchedSymbol(
        s: String,
        q: String,
        r: StaticSafetyRule,
    ): String = r.symbols.firstOrNull { symbolMatches(s, q, it, r.receiverSymbols) } ?: s

    private fun callMatches(
        s: String,
        q: String,
        syms: List<String>,
        receiverSymbols: List<String>,
    ): Boolean = syms.any { symbolMatches(s, q, it, receiverSymbols) }

    private fun symbolMatches(
        s: String,
        q: String,
        symbol: String,
        receiverSymbols: List<String>,
    ): Boolean {
        val segments = q.split('.')
        return when {
            symbol.endsWith("*") -> {
                val prefix = symbol.dropLast(1).split('.')
                segments.size >= prefix.size &&
                    segments.takeLast(prefix.size).dropLast(1) == prefix.dropLast(1) &&
                    segments.last().startsWith(prefix.last())
            }

            symbol.contains('.') -> {
                val symSegs = symbol.split('.')
                segments.size >= symSegs.size && segments.takeLast(symSegs.size) == symSegs
            }

            else -> {
                s == symbol ||
                    (symbol in receiverSymbols && segments.dropLast(1).contains(symbol))
            }
        }
    }

    private fun qualified(
        ts: List<Tok>,
        i: Int,
    ): String {
        var j = i
        while (j >= 2 && ts[j - 1].text == "." && ts[j - 2].kind == Kind.ID) j -= 2
        return ts.subList(j, i + 1).joinToString("") { it.text }
    }

    private fun receiverMatch(
        q: String,
        r: StaticSafetyRule,
    ): Boolean {
        val rec = q.substringBeforeLast('.', "")
        return rec in r.receivers ||
            rec.endsWith("Logger") ||
            (rec.split('.').lastOrNull() in r.receivers)
    }

    private fun argHas(
        ts: List<Tok>,
        i: Int,
        syms: List<String>,
    ): Boolean {
        val open = ts.getOrNull(i + 1)?.text
        val (regionStart, regionEnd) =
            when (open) {
                "(" -> i + 2 to balanced(ts, i + 1, "(", ")")
                "{" -> i + 2 to balanced(ts, i + 1, "{", "}")
                else -> i + 2 to i + 2
            }
        return (regionStart until regionEnd).any { n ->
            ts[n].kind == Kind.ID && (
                ts[n].text in syms ||
                    (
                        "document.content" in syms && ts[n].text == "content" &&
                            ts.getOrNull(n - 1)?.text == "." && ts.getOrNull(n - 2)?.text == "document"
                    )
            )
        }
    }

    private fun bodyReceiver(
        ts: List<Tok>,
        i: Int,
    ): Boolean = i >= 4 && ts[i - 1].text == "." && ts[i - 2].text == ")" && ts[i - 3].text == "(" && ts[i - 4].text == "body"

    private fun blockHas(
        ts: List<Tok>,
        i: Int,
        syms: List<String>,
    ): Boolean = syms.any { s -> blockContains(ts, i, s) }

    private fun blockContains(
        ts: List<Tok>,
        i: Int,
        s: String,
    ): Boolean {
        val open = ts.indexOfFirstFrom(i + 1) { it.text == "{" }
        if (open < 0) return false
        val end = balanced(ts, open, "{", "}")
        return ts.subList(open + 1, end).zipWithNext().any { it.first.text == s && it.second.text == "(" }
    }

    private fun blockLine(
        ts: List<Tok>,
        i: Int,
        syms: List<String>,
    ): Int =
        ts
            .withIndex()
            .firstOrNull { (n, t) -> t.text in syms && ts.getOrNull(n + 1)?.text == "(" && n > i }
            ?.value
            ?.line ?: ts[i].line

    private fun List<Tok>.indexOfFirstFrom(
        start: Int,
        p: (Tok) -> Boolean,
    ): Int = indices.firstOrNull { it >= start && p(this[it]) } ?: -1

    private fun balanced(
        ts: List<Tok>,
        start: Int,
        a: String,
        b: String,
    ): Int {
        var d = 0
        for (j in start until ts.size) {
            if (ts[j].text == a) d++
            if (ts[j].text == b) {
                d--
                if (d == 0) return j
            }
        }
        return ts.size
    }

    private fun snippet(
        f: File,
        line: Int,
    ): String =
        f
            .readLines()
            .getOrNull(line - 1)
            ?.trim()
            ?.take(240) ?: ""
}

/** Lightweight import canonicalization: aliases, normal/static imports, FQN suffix resolution. */
private class Imports(
    source: String,
) {
    private val aliases = mutableMapOf<String, String>()
    private val simples = mutableMapOf<String, String>()

    init {
        source.lineSequence().forEach { raw ->
            val line = raw.trim()
            val m = IMPORT_RE.matchEntire(line) ?: return@forEach
            val fqcn = m.groupValues[1]
            val alias = m.groupValues[2]
            val simple = fqcn.substringAfterLast('.')
            if (alias.isNotEmpty()) aliases[alias] = fqcn else simples[simple] = fqcn
        }
    }

    fun resolve(q: String): String {
        val first = q.substringBefore('.', q)
        val rest = q.substringAfter('.', "")
        return when {
            aliases.containsKey(first) -> aliases.getValue(first) + if (rest.isEmpty()) "" else ".$rest"
            simples.containsKey(first) -> simples.getValue(first) + if (rest.isEmpty()) "" else ".$rest"
            else -> q
        }
    }

    private companion object {
        val IMPORT_RE = Regex("""^import\s+([\w.$]+)(?:\s+as\s+(\w+))?\s*$""")
    }
}

private enum class Kind { ID, PUNCT }

private data class Tok(
    val text: String,
    val line: Int,
    val kind: Kind,
)

/** Token-aware lexer; skips comments/strings but still lexes executable `${...}` interpolation. */
private class Lexer(
    private val s: String,
) {
    fun lex(): List<Tok> = lexRange(0, s.length, 1)

    private fun lexRange(
        from: Int,
        until: Int,
        startLine: Int,
    ): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = from
        var line = startLine
        var block = 0

        fun id(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

        while (i < until) {
            val c = s[i]
            if (block > 0) {
                when {
                    i + 1 < until && s.startsWith("/*", i) -> {
                        block++
                        i += 2
                    }

                    i + 1 < until && s.startsWith("*/", i) -> {
                        block--
                        i += 2
                    }

                    else -> {
                        if (c == '\n') line++
                        i++
                    }
                }
                continue
            }
            if (i + 1 < until && s.startsWith("//", i)) {
                i += 2
                while (i < until && s[i] != '\n') i++
                continue
            }
            if (i + 1 < until && s.startsWith("/*", i)) {
                block = 1
                i += 2
                continue
            }
            if (s.startsWith("\"\"\"", i)) {
                i += 3
                while (i < until) {
                    if (s.startsWith("\"\"\"", i)) break
                    if (s[i] == '\n') line++
                    if (s[i] == '$' && i + 1 < until && s[i + 1] == '{') {
                        val (end, contentLine) = interpolationEnd(i + 2, until, line)
                        out += lexRange(i + 2, end, line)
                        line = contentLine
                        i = end + 1
                        continue
                    }
                    i++
                }
                i += 3
                continue
            }
            if (c == '"' || c == '\'') {
                val q = c
                i++
                while (i < until && s[i] != q) {
                    if (s[i] == '\\') {
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') line++
                    if (s[i] == '$' && i + 1 < until && s[i + 1] == '{') {
                        val (end, contentLine) = interpolationEnd(i + 2, until, line)
                        out += lexRange(i + 2, end, line)
                        line = contentLine
                        i = end + 1
                        continue
                    }
                    i++
                }
                i++
                continue
            }
            if (id(c)) {
                val l = line
                val st = i
                i++
                while (i < until && id(s[i])) i++
                out += Tok(s.substring(st, i), l, Kind.ID)
                continue
            }
            if (c in ".(){}") out += Tok(c.toString(), line, Kind.PUNCT)
            if (c == '\n') line++
            i++
        }
        return out
    }

    /** Returns (index after matching '}', absolute line after the content) for the `${...}` starting at from. */
    private fun interpolationEnd(
        from: Int,
        until: Int,
        startLine: Int,
    ): Pair<Int, Int> {
        var d = 0
        var i = from
        var line = startLine
        while (i < until) {
            val c = s[i]
            when {
                c == '\n' -> {
                    line++
                }

                c == '"' || c == '\'' -> {
                    val q = c
                    i++
                    while (i < until && s[i] != q) {
                        if (s[i] == '\\') {
                            i += 2
                        } else {
                            if (s[i] == '\n') line++
                            i++
                        }
                    }
                }

                c == '{' -> {
                    d++
                }

                c == '}' -> {
                    if (d == 0) return i to line else d--
                }
            }
            i++
        }
        return until to line
    }
}
