package dev.tramai.build.quality

import java.io.File

/**
 * Scans Kotlin source files for process-global mutable state patterns.
 */
class GlobalStateInventory(private val ctx: MeasurementContext) {

    private val globalPatterns = listOf(
        // Mutable top-level val/var
        Regex("""^(?:val|var)\s+\w+\s*[:=].*mutable"""),
        // Kotlin object declarations
        Regex("""^\s*object\s+\w+"""),
        // Companion object with mutable state
        Regex("""^\s*companion\s+object"""),
        // Mutable collections at top level or in objects
        Regex("""(?:mutableMapOf|mutableListOf|mutableSetOf|ConcurrentHashMap|HashMap)\s*[\(<]"""),
        // Global registries
        Regex("""(?:registry|Registry|singleton|Singleton)\s*[:=]"""),
        // Global coroutine scopes
        Regex("""(?:GlobalScope|ProcessScope|ApplicationScope)"""),
        // Static mutable Java-style fields (@JvmStatic/@JvmField with var)
        Regex("""@JvmStatic\s+var"""),
        Regex("""@JvmField\s+var""")
    )

    private val immutableExclusions = listOf(
        "serializer", "Serializer", "Companion", "Empty", "None", "NoOp", "Default"
    )

    fun inventory(): List<GlobalStateFinding> {
        val findings = mutableListOf<GlobalStateFinding>()

        for (mod in ctx.modules) {
            mod.sourceDirs.forEach { srcDir ->
                if (!srcDir.exists()) return@forEach
                srcDir.walkTopDown().forEach { file ->
                    if (!file.isFile || file.extension != "kt") return@forEach
                    val content = file.readText()
                    val relativePath = ReportNormalizer.repoRelativePath(file, ctx.rootDir)

                    for (pattern in globalPatterns) {
                        pattern.findAll(content).forEach { match ->
                            val matched = match.value.trim()
                            // Skip immutable exclusions
                            if (immutableExclusions.any { matched.contains(it) }) return@forEach

                            val declaration = extractDeclaration(content, match.range.first)
                            val kind = classifyKind(pattern)
                            val type = extractType(matched)

                            findings.add(
                                GlobalStateFinding(
                                    module = mod.name,
                                    file = relativePath,
                                    declaration = declaration,
                                    kind = kind,
                                    type = type,
                                    mutable = true,
                                    lifecycle = "process",
                                    threadSafety = "unknown"
                                )
                            )
                        }
                    }
                }
            }
        }

        return findings.distinctBy { "${it.module}::${it.file}::${it.declaration}::${it.kind}" }
    }

    private fun extractDeclaration(content: String, offset: Int): String {
        // Walk backwards from offset to find the containing declaration
        val before = content.substring(0, offset.coerceAtMost(content.length))
        val lines = before.lines()
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            when {
                line.startsWith("object ") -> return line.removePrefix("object ").trim().split(" ").first().trim('{', ':')
                line.startsWith("class ") -> return line.removePrefix("class ").trim().split("(").first().trim('{', ':')
                line.startsWith("val ") -> return line.removePrefix("val ").trim().split(" ").first().trim('=', ':')
                line.startsWith("var ") -> return line.removePrefix("var ").trim().split(" ").first().trim('=', ':')
                line.contains("companion object") -> return "companion"
            }
        }
        return "<unknown>"
    }

    private fun classifyKind(pattern: Regex): String {
        return when (pattern.pattern) {
            in setOf("""^(?:val|var)\s+\w+\s*[:=].*mutable""") -> "mutable-top-level"
            in setOf("""^\s*object\s+\w+""") -> "object-singleton"
            in setOf("""^\s*companion\s+object""") -> "companion-object"
            in setOf("""(?:mutableMapOf|mutableListOf|mutableSetOf|ConcurrentHashMap|HashMap)\s*[\(<]""") -> "mutable-collection"
            in setOf("""(?:registry|Registry|singleton|Singleton)\s*[:=]""") -> "global-registry"
            in setOf("""(?:GlobalScope|ProcessScope|ApplicationScope)""") -> "global-scope"
            else -> "static-mutable"
        }
    }

    private fun extractType(matched: String): String {
        return when {
            matched.contains("mutableMapOf") -> "MutableMap"
            matched.contains("mutableListOf") -> "MutableList"
            matched.contains("mutableSetOf") -> "MutableSet"
            matched.contains("ConcurrentHashMap") -> "ConcurrentHashMap"
            matched.contains("HashMap") -> "HashMap"
            matched.contains("GlobalScope") -> "CoroutineScope"
            matched.contains("registry") || matched.contains("Registry") -> "Registry"
            matched.contains("singleton") || matched.contains("Singleton") -> "Singleton"
            matched.startsWith("object ") -> "object"
            matched.startsWith("companion") -> "companion"
            else -> "unknown"
        }
    }
}
