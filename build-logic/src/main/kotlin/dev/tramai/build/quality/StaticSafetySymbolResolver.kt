package dev.tramai.build.quality

/** Lightweight import canonicalization: Kotlin/Java imports, `as` aliases, static imports, FQN suffix resolution. */
internal class StaticSafetySymbolResolver(
    source: String,
) {
    private val aliases = mutableMapOf<String, String>()
    private val simples = mutableMapOf<String, String>()
    private val wildcardStatic = mutableMapOf<String, String>()

    init {
        source.lineSequence().forEach { raw ->
            val m = IMPORT_RE.matchEntire(raw.trim()) ?: return@forEach
            val static = m.groupValues[GROUP_STATIC]
            val fqcn = m.groupValues[GROUP_FQCN]
            val wildcard = m.groupValues[GROUP_WILDCARD]
            val alias = m.groupValues[GROUP_ALIAS]
            val simple = fqcn.substringAfterLast('.')
            when {
                alias.isNotEmpty() -> aliases[alias] = fqcn
                static.isNotEmpty() && wildcard.isNotEmpty() -> wildcardStatic[simple] = fqcn
                else -> simples[simple] = fqcn
            }
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

    /** True when a static-wildcard import of symbol's leading class can resolve `simple` as symbol's member. */
    fun resolveStaticWildcard(
        simple: String,
        symbol: String,
    ): Boolean {
        val member = symbol.split('.').drop(1).joinToString(".")
        val cls = symbol.substringBefore('.', "")
        val resolvable = cls.isNotEmpty() && member.isNotEmpty() && cls in wildcardStatic
        if (!resolvable) return false
        return if (member.endsWith("*")) simple.startsWith(member.dropLast(1)) else simple == member
    }

    private companion object {
        val IMPORT_RE =
            Regex("""^import\s+(?:(static)\s+)?([\w.$]+?)(?:\.(\*))?(?:\s+as\s+(\w+))?\s*;?\s*$""")
        const val GROUP_STATIC = 1
        const val GROUP_FQCN = 2
        const val GROUP_WILDCARD = 3
        const val GROUP_ALIAS = 4
    }
}
