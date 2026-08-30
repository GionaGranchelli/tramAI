package dev.tramai.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Epic 10.1c compiler-warning baseline model.
 *
 * Warning identity is (repository-relative path, Kotlin internal diagnostic
 * name, normalized message fingerprint) with an explicit multiplicity count.
 * Line/column are deliberately excluded — normal edits move lines.
 *
 * The baseline is the frozen inventory of existing warnings; a warning whose
 * identity has no baseline entry, or whose count exceeds the baseline count,
 * fails the gate. Baseline entries may only shrink.
 */
data class WarningEntry(
    val path: String,
    val diagnostic: String,
    val message: String,
    val count: Int,
)

/** Message fingerprint shared by baseline generation and verification. */
object CompilerWarningsFingerprint {
    fun normalize(raw: String): String =
        raw
            .replace(Regex("\\d+"), "N")
            .replace(Regex("'[^']*'"), "'X'")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Strict parser for the standalone-kotlinc output format (T0.5-proven). */
object CompilerWarningsParser {
    // <repo-relative>.kt:<line>:<col>: warning: [NAME] message
    private val WARNING = Regex("^(.+?\\.kt):(\\d+):(\\d+): warning: \\[([A-Z_0-9]+)\\] (.*)$")
    private val ERROR = Regex("^(.+?\\.kt):(\\d+):(\\d+): error: (.*)$")

    /**
     * Parses compiler output into warnings grouped by identity with counts.
     *
     * Fail-closed: any `error:` diagnostic line makes the parse throw — a
     * failed compile cannot be trusted as a warning inventory.
     */
    fun parse(output: String): List<WarningEntry> {
        val raw = mutableListOf<WarningEntry>()
        val errors = mutableListOf<String>()
        for (line in output.lineSequence()) {
            if (line.isBlank()) continue
            val w = WARNING.matchEntire(line)
            if (w != null) {
                raw.add(
                    WarningEntry(
                        path = w.groupValues[1],
                        diagnostic = w.groupValues[4],
                        message = CompilerWarningsFingerprint.normalize(w.groupValues[5]),
                        count = 1,
                    ),
                )
                continue
            }
            val e = ERROR.matchEntire(line)
            if (e != null) {
                errors.add("${e.groupValues[1]}:${e.groupValues[2]}: ${e.groupValues[4]}")
            }
            // Any other line (source excerpt, caret marker, compiler banner) is ignored.
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Compiler emitted ${errors.size} error(s); a failed compile cannot be verified as a warning inventory. " +
                    "First errors: ${errors.take(5).joinToString(" | ")}",
            )
        }
        return raw
            .groupBy { Triple(it.path, it.diagnostic, it.message) }
            .map { (key, entries) ->
                WarningEntry(key.first, key.second, key.third, entries.sumOf { it.count })
            }
            .sortedWith(compareBy({ it.path }, { it.diagnostic }, { it.message }))
    }
}

/** Baseline comparison — the gate's pure logic (C2..C6 contract). */
object CompilerWarningsBaselineVerifier {

    data class Violation(
        val path: String,
        val diagnostic: String,
        val message: String,
        val currentCount: Int,
        val baselineCount: Int,
    )

    /**
     * Compares the current warning inventory against the baseline.
     *
     * - current entry with no baseline identity → "new warning" (C2)
     * - current count exceeding the baseline count → "additional occurrence" (C3)
     * - current count <= baseline count → OK (debt paid, C4)
     * - baseline entry with no current occurrence → OK (removal allowed)
     * - line movement (same path/name/fingerprint, different line) → OK (C5)
     */
    fun compare(
        current: List<WarningEntry>,
        baseline: List<WarningEntry>,
    ): List<Violation> {
        val baselineByIdentity = baseline.associateBy { identity(it) }
        val violations = mutableListOf<Violation>()
        for (entry in current) {
            val key = identity(entry)
            val base = baselineByIdentity[key]
            if (base == null) {
                violations.add(Violation(entry.path, entry.diagnostic, entry.message, entry.count, 0))
            } else if (entry.count > base.count) {
                violations.add(Violation(entry.path, entry.diagnostic, entry.message, entry.count, base.count))
            }
        }
        return violations.sortedWith(compareBy({ it.path }, { it.diagnostic }, { it.message }))
    }

    private fun identity(entry: WarningEntry) = Triple(entry.path, entry.diagnostic, entry.message)
}

/** Baseline JSON read/write. Hand-rolled node binding — no Kotlin-module dependency needed. */
object CompilerWarningsBaselineIo {
    private val MAPPER = ObjectMapper()

    const val SCHEMA_VERSION = 1

    fun toJson(entries: List<WarningEntry>): String {
        val root: ObjectNode = MAPPER.createObjectNode()
        root.put("schemaVersion", SCHEMA_VERSION)
        val arr: ArrayNode = root.putArray("entries")
        for (e in entries.sortedWith(compareBy({ it.path }, { it.diagnostic }, { it.message }))) {
            val node: ObjectNode = arr.addObject()
            node.put("path", e.path)
            node.put("diagnostic", e.diagnostic)
            node.put("message", e.message)
            node.put("count", e.count)
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    /** Returns null for absent/blank content (treated as fail-closed by the task). */
    fun fromJson(json: String?): List<WarningEntry>? {
        if (json == null || json.isBlank()) return null
        return try {
            val root: JsonNode = MAPPER.readTree(json)
            if (root.get("schemaVersion")?.asInt() != SCHEMA_VERSION) return null
            val entries = root.get("entries") ?: return null
            if (!entries.isArray) return null
            entries.map { node ->
                val path = node.get("path")?.asText() ?: return null
                val diagnostic = node.get("diagnostic")?.asText() ?: return null
                val message = node.get("message")?.asText() ?: return null
                val count = node.get("count")?.asInt() ?: return null
                if (path.isBlank() || diagnostic.isBlank() || count < 0) return null
                WarningEntry(path, diagnostic, message, count)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parses build-logic warnings captured from the REAL Gradle compile output.
 *
 * NOTE: build-logic is deliberately excluded from the compiler-warning gate
 * (kotlin-dsl cannot be reproduced standalone; cross-build output capture is
 * unreliable). This parser is retained for a future build-logic-specific gate.
 * Format (Gradle-rendered, with -Xrender-internal-diagnostic-names):
 *   w: file:///abs/path.kt:line:col: [NAME] message
 */
object CompilerWarningsGradleOutputParser {
    // Gradle-rendered format (with -Xrender-internal-diagnostic-names):
    //   w: file:///abs/path.kt:line:col [NAME] message
    private val WARNING = Regex("^w: file://(.+?\\.kt):(\\d+):(\\d+) (?:warning: )?\\[([A-Z_0-9]+)\\] (.*)$")
    private val ERROR = Regex("^w: file://(.+?\\.kt):(\\d+):(\\d+) (?:error: )(.*)$")

    fun parse(outputLines: List<String>, repositoryRoot: String): List<WarningEntry> {
        val raw = mutableListOf<WarningEntry>()
        val errors = mutableListOf<String>()
        for (line in outputLines) {
            val w = WARNING.matchEntire(line.trim())
            if (w != null) {
                val abs = w.groupValues[1]
                val rel = if (abs.startsWith(repositoryRoot)) {
                    abs.removePrefix(repositoryRoot).removePrefix("/")
                } else {
                    abs
                }
                raw.add(
                    WarningEntry(
                        path = rel,
                        diagnostic = w.groupValues[4],
                        message = CompilerWarningsFingerprint.normalize(w.groupValues[5]),
                        count = 1,
                    ),
                )
                continue
            }
            val e = ERROR.matchEntire(line.trim())
            if (e != null) errors.add(e.groupValues[1])
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Compiler emitted ${errors.size} error(s) for build-logic; a failed compile cannot be " +
                    "verified as a warning inventory. First: ${errors.take(3).joinToString(" | ")}",
            )
        }
        return raw
            .groupBy { Triple(it.path, it.diagnostic, it.message) }
            .map { (key, entries) ->
                WarningEntry(key.first, key.second, key.third, entries.sumOf { it.count })
            }
            .sortedWith(compareBy({ it.path }, { it.diagnostic }, { it.message }))
    }
}
