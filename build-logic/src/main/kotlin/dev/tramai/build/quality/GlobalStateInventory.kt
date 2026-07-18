package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Scans Kotlin source files for process-global mutable state patterns.
 */
class GlobalStateInventory(private val rootProject: Project) {

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
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }

        for (proj in projects) {
            val srcDir = File(proj.projectDir, "src/main/kotlin")
            if (!srcDir.exists()) continue

            srcDir.walkTopDown().forEach { file ->
                if (!file.isFile || file.extension != "kt") return@forEach
                processFile(file, proj.name, findings)
            }
        }

        return findings
    }

    private fun processFile(file: File, moduleName: String, findings: MutableList<GlobalStateFinding>) {
        val lines = file.readLines()
        val relativePath = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)

        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Check for object declarations
            val objectMatch = Regex("""^\s*object\s+(\w+)""").find(line)
            if (objectMatch != null) {
                val name = objectMatch.groupValues[1]
                if (immutableExclusions.none { name.contains(it) }) {
                    val isMutable = checkObjectMutability(lines, lineIdx)
                    if (isMutable) {
                        findings.add(
                            GlobalStateFinding(
                                module = moduleName,
                                file = relativePath,
                                declaration = name,
                                kind = "object",
                                type = "singleton",
                                mutable = true,
                                lifecycle = "process",
                                threadSafety = checkThreadSafety(lines, lineIdx)
                            )
                        )
                    }
                }
            }

            // Check for companion objects with mutable state
            if (Regex("""^\s*companion\s+object""").containsMatchIn(line)) {
                val hasMutable = checkCompanionMutability(lines, lineIdx)
                if (hasMutable) {
                    findings.add(
                        GlobalStateFinding(
                            module = moduleName,
                            file = relativePath,
                            declaration = "companion object",
                            kind = "companion_object",
                            type = "companion",
                            mutable = true,
                            lifecycle = "class",
                            threadSafety = "unknown"
                        )
                    )
                }
            }

            // Check for mutable top-level collections
            if (Regex("""^(?:val|var)\s+\w+\s*=\s*(?:mutableMapOf|mutableListOf|mutableSetOf|ConcurrentHashMap)""").containsMatchIn(trimmed)) {
                val declMatch = Regex("""^(?:val|var)\s+(\w+)""").find(trimmed)
                val name = declMatch?.groupValues?.get(1) ?: "unknown"
                findings.add(
                    GlobalStateFinding(
                        module = moduleName,
                        file = relativePath,
                        declaration = name,
                        kind = "mutable_collection",
                        type = "collection",
                        mutable = true,
                        lifecycle = "process",
                        threadSafety = if (trimmed.contains("ConcurrentHashMap")) "concurrent" else "unknown"
                    )
                )
            }

            // Check for GlobalScope usage
            if (trimmed.contains("GlobalScope")) {
                findings.add(
                    GlobalStateFinding(
                        module = moduleName,
                        file = relativePath,
                        declaration = "GlobalScope",
                        kind = "global_scope",
                        type = "coroutine_scope",
                        mutable = true,
                        lifecycle = "process",
                        threadSafety = "unknown"
                    )
                )
            }
        }
    }

    private fun checkObjectMutability(lines: List<String>, startIdx: Int): Boolean {
        val end = findBlockEnd(lines, startIdx)
        for (i in startIdx until minOf(end, lines.size)) {
            val line = lines[i].trim()
            if (line.contains("var ") ||
                line.contains("mutable") ||
                line.contains("ConcurrentHashMap") ||
                line.contains("AtomicReference") ||
                line.contains("AtomicBoolean") ||
                line.contains("AtomicInteger")) {
                return true
            }
        }
        return false
    }

    private fun checkCompanionMutability(lines: List<String>, startIdx: Int): Boolean {
        val end = findBlockEnd(lines, startIdx)
        for (i in startIdx until minOf(end, lines.size)) {
            val line = lines[i].trim()
            if ((line.contains("var ") || line.contains("mutable")) && !line.startsWith("//")) {
                return true
            }
        }
        return false
    }

    private fun checkThreadSafety(lines: List<String>, startIdx: Int): String {
        val end = findBlockEnd(lines, startIdx)
        val block = lines.subList(startIdx, minOf(end, lines.size)).joinToString("\n")
        return when {
            block.contains("synchronized") || block.contains("@Synchronized") -> "synchronized"
            block.contains("ConcurrentHashMap") -> "concurrent"
            block.contains("AtomicReference") || block.contains("AtomicBoolean") -> "atomic"
            block.contains("Mutex") || block.contains("withLock") -> "mutex"
            else -> "unknown"
        }
    }

    private fun findBlockEnd(lines: List<String>, startIdx: Int): Int {
        var braceCount = 0
        var found = false
        for (i in startIdx until lines.size) {
            braceCount += lines[i].count { it == '{' }
            braceCount -= lines[i].count { it == '}' }
            if (braceCount > 0) found = true
            if (found && braceCount == 0) return i + 1
        }
        return startIdx + 1
    }
}
