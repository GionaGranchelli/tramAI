package dev.tramai.build.quality

import java.io.File

internal data class SafetyFinding(
    val rule: String,
    val path: String,
    val line: Int,
    val offset: Int,
    val symbol: String,
    val snippet: String,
    val exempt: Boolean = false,
)

private data class CallContext(
    val tokens: List<Tok>,
    val i: Int,
    val q: String,
    val simple: String,
    val resolver: StaticSafetySymbolResolver,
    val path: String,
    val file: File,
)

/** Rule evaluation over the lexed token stream. Occurrence identity is token offset, not line. */
internal class StaticSafetySourceScanner(
    private val config: StaticSafetyGuardConfig,
    private val root: File,
) {
    fun scan(file: File): List<SafetyFinding> {
        val path =
            root
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/')
        val text = file.readText()
        val tokens = StaticSafetyLexer(text).lex()
        val resolver = StaticSafetySymbolResolver(text)
        val ctxBase = CallContext(tokens, 0, "", "", resolver, path, file)
        val out = mutableListOf<SafetyFinding>()
        for ((i, t) in tokens.withIndex()) {
            val isCallSite =
                t.kind == Kind.ID && i + 1 < tokens.size &&
                    (tokens[i + 1].text == "(" || tokens[i + 1].text == "{")
            if (!isCallSite) continue
            val ctx = ctxBase.copy(i = i, q = resolver.resolve(qualified(tokens, i)), simple = t.text)
            config.rules.forEach { r ->
                if (approved(r, ctx.path)) return@forEach
                val direct = evaluate(ctx, r)
                if (direct != null) {
                    out += direct
                    return@forEach
                }
                val block = blockFinding(ctx, r)
                if (block != null) out += block
            }
        }
        return out.distinctBy { Triple(it.rule, it.offset, it.symbol) }
    }

    private fun evaluate(
        ctx: CallContext,
        r: StaticSafetyRule,
    ): SafetyFinding? {
        val matched =
            when (r.match) {
                "call-name" -> {
                    callMatches(ctx, r)
                }

                "multi" -> {
                    callMatches(ctx, r) ||
                        (ctx.simple in r.blockReadSymbols && bodyReceiver(ctx.tokens, ctx.i))
                }

                "receiver-call" -> {
                    ctx.simple in r.symbols &&
                        receiverMatch(ctx.q, r) && argHas(ctx.tokens, ctx.i, r.sensitiveSymbols)
                }

                else -> {
                    false
                }
            }
        return if (matched) {
            val symbol = matchedSymbol(ctx, r)
            SafetyFinding(
                r.id,
                ctx.path,
                ctx.tokens[ctx.i].line,
                ctx.tokens[ctx.i].offset,
                symbol,
                snippet(ctx.file, ctx.tokens[ctx.i].line),
            )
        } else {
            null
        }
    }

    private fun blockFinding(
        ctx: CallContext,
        r: StaticSafetyRule,
    ): SafetyFinding? {
        val supported = r.match == "body-use-block" || r.match == "multi"
        val isUse = ctx.tokens[ctx.i].text == "use" && bodyReceiver(ctx.tokens, ctx.i)
        val hit =
            if (supported && isUse) {
                r.blockReadSymbols.firstOrNull { blockContains(ctx.tokens, ctx.i, it) }
            } else {
                null
            }
        return if (hit == null) {
            null
        } else {
            val (line, offset) = blockHit(ctx.tokens, ctx.i, hit)
            SafetyFinding(r.id, ctx.path, line, offset, hit, snippet(ctx.file, line))
        }
    }

    private fun matchedSymbol(
        ctx: CallContext,
        r: StaticSafetyRule,
    ): String = r.symbols.firstOrNull { symbolMatches(ctx, it, r.receiverSymbols) } ?: ctx.simple

    private fun callMatches(
        ctx: CallContext,
        r: StaticSafetyRule,
    ): Boolean = r.symbols.any { symbolMatches(ctx, it, r.receiverSymbols) }

    private fun symbolMatches(
        ctx: CallContext,
        symbol: String,
        receiverSymbols: List<String>,
    ): Boolean {
        if (ctx.resolver.resolveStaticWildcard(ctx.simple, symbol)) return true
        val segments = ctx.q.split('.')
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
                ctx.simple == symbol ||
                    (symbol in receiverSymbols && segments.dropLast(1).contains(symbol))
            }
        }
    }
}

private fun approved(
    rule: StaticSafetyRule,
    path: String,
): Boolean = rule.approvedPaths.any { path.startsWith(it.trimEnd('/') + "/") || path == it.trimEnd('/') }

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
    val region =
        when (open) {
            "(" -> i + 2 until balanced(ts, i + 1, "(", ")")
            "{" -> i + 2 until balanced(ts, i + 1, "{", "}")
            else -> i + 2 until i + 2
        }
    return region.any { n ->
        ts[n].kind == Kind.ID && (
            ts[n].text in syms ||
                (
                    "document.content" in syms && ts[n].text == "content" &&
                        ts.getOrNull(n - 1)?.text == "." && ts.getOrNull(n - 2)?.text == "document"
                )
        )
    }
}

private const val BODY_RECEIVER_DISTANCE = 4

private fun bodyReceiver(
    ts: List<Tok>,
    i: Int,
): Boolean {
    val b = i - BODY_RECEIVER_DISTANCE
    val chain = listOf("body", "(", ")", ".")
    return i >= BODY_RECEIVER_DISTANCE &&
        chain.withIndex().all { (n, text) -> ts[b + n].text == text }
}

private fun blockContains(
    ts: List<Tok>,
    i: Int,
    s: String,
): Boolean {
    val open = (i + 1 until ts.size).firstOrNull { ts[it].text == "{" } ?: return false
    val end = balanced(ts, open, "{", "}")
    return ts.subList(open + 1, end).zipWithNext().any { it.first.text == s && it.second.text == "(" }
}

private fun blockHit(
    ts: List<Tok>,
    i: Int,
    s: String,
): Pair<Int, Int> {
    val hit = ts.withIndex().firstOrNull { (n, t) -> t.text == s && ts.getOrNull(n + 1)?.text == "(" && n > i }
    return (hit?.value?.line ?: ts[i].line) to (hit?.value?.offset ?: ts[i].offset)
}

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

private const val SNIPPET_LEN = 240

private fun snippet(
    file: File,
    line: Int,
): String =
    file
        .readLines()
        .getOrNull(line - 1)
        ?.trim()
        ?.take(SNIPPET_LEN) ?: ""
