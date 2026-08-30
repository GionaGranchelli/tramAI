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
    /**
     * Whitespace-only normalization. Deliberately PRESERVES symbol/type content:
     * collapsing quoted values ('X') or digits (N) let remove-A/add-B substitution
     * hide inside the same identity (10.1c review BLOCKER 2). A changed message is
     * a NEW identity and fails the gate — conservative by design. Line/column are
     * never part of the identity anyway (separate parse fields).
     */
    fun normalize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()
}

/** Strict parser for the standalone-kotlinc output format (T0.5-proven). */
object CompilerWarningsParser {
    // <repo-relative>.kt:<line>:<col>: warning: [NAME] message
    private val WARNING = Regex("^(.+?\\.kt):(\\d+):(\\d+): warning: \\[([A-Z_0-9]+)\\] (.*)$")
    private val ERROR = Regex("^(.+?\\.kt):(\\d+):(\\d+): error: (.*)$")
    private const val GRP_PATH = 1
    private const val GRP_ERROR_LINE = 2
    private const val GRP_NAME = 4
    private const val GRP_MESSAGE = 5
    private const val MAX_REPORTED_ERRORS = 5

    /**
     * Parses compiler output into warnings grouped by identity with counts.
     *
     * Fail-closed: any `error:` diagnostic line makes the parse throw — a
     * failed compile cannot be trusted as a warning inventory.
     */
    fun parse(output: String): List<WarningEntry> {
        val lines = output.lines()
        val errors =
            lines.mapNotNull { line ->
                val e = ERROR.matchEntire(line.trim())
                if (e != null) {
                    "${e.groupValues[GRP_PATH]}:${e.groupValues[GRP_ERROR_LINE]}: ${e.groupValues[GRP_NAME]}"
                } else {
                    null
                }
            }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Compiler emitted ${errors.size} error(s); a failed compile cannot be " +
                    "verified as a warning inventory. First errors: " +
                    errors.take(MAX_REPORTED_ERRORS).joinToString(" | "),
            )
        }
        return lines
            .mapNotNull { line ->
                val w = WARNING.matchEntire(line.trim())
                if (w != null) {
                    WarningEntry(
                        path = w.groupValues[GRP_PATH],
                        diagnostic = w.groupValues[GRP_NAME],
                        message = CompilerWarningsFingerprint.normalize(w.groupValues[GRP_MESSAGE]),
                        count = 1,
                    )
                } else {
                    null
                }
            }.groupBy { Triple(it.path, it.diagnostic, it.message) }
            .map { (key, entries) -> WarningEntry(key.first, key.second, key.third, entries.sumOf { it.count }) }
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

    const val SCHEMA_VERSION = 2
    private const val ZERO_COUNT = 0

    fun readTree(json: String): JsonNode = MAPPER.readTree(json)

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
    fun fromJson(json: String?): List<WarningEntry>? =
        when {
            json == null || json.isBlank() -> null
            else -> parseTree(runCatching { MAPPER.readTree(json) }.getOrNull())
        }

    private fun parseTree(root: JsonNode?): List<WarningEntry>? =
        root
            ?.takeIf { it.get("schemaVersion")?.asInt() == SCHEMA_VERSION }
            ?.get("entries")
            ?.takeIf { it.isArray }
            ?.let { entries ->
                val parsed = entries.mapNotNull { parseEntry(it) }
                parsed.takeIf { parsed.isNotEmpty() || entries.size() == ZERO_COUNT }
            }

    private fun parseEntry(node: JsonNode): WarningEntry? {
        val path = node.get("path")?.asText()?.trim()
        val diagnostic = node.get("diagnostic")?.asText()?.trim()
        val message = node.get("message")?.asText()?.trim()
        val count = node.get("count")?.asInt()
        val invalid = path.isNullOrBlank() || diagnostic.isNullOrBlank() || message.isNullOrBlank()
        val invalidCount = count == null || count < ZERO_COUNT
        return if (invalid || invalidCount) null else WarningEntry(path, diagnostic, message, count)
    }
}
