package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * Parses and validates `config/quality/runtime-nondeterminism.yml` — the Epic 8.3d
 * semantic allowlist that classifies every production nondeterminism finding.
 *
 * Mirrors [DeviationParser]: SnakeYAML load, structured validation, and
 * deterministic [VerificationDiagnostic] failures. Failures are reported, never
 * thrown from parsing — the Gradle task converts them into a build failure.
 */
class NondeterminismAllowlistParser(private val rootDir: File) {

    data class AllowlistEntry(
        val module: String,
        val file: String,
        val source: String,
        val category: String,
        val scannerClassification: String,
        val disposition: String,
        val authority: String,
        val occurrences: Int,
        val rationale: String
    ) {
        val identityKey: String get() = "$module\u0000$file\u0000$source"
    }

    data class ParseResult(
        val entries: List<AllowlistEntry>,
        val diagnostics: List<VerificationDiagnostic>
    )

    companion object {
        val ALLOWED_DISPOSITIONS = setOf(
            "AUTHORITY",
            "CAPABILITY_AUTHORITY",
            "COMPOSITION_BOUNDARY",
            "PUBLIC_COMPATIBILITY_BOUNDARY"
        )
    }

    fun parse(): ParseResult {
        val file = File(rootDir, "config/quality/runtime-nondeterminism.yml")
        if (!file.isFile) {
            return ParseResult(emptyList(), listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                    "Nondeterminism allowlist file not found: ${file.absolutePath}"
                )
            ))
        }

        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val entries = mutableListOf<AllowlistEntry>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }
            if (root == null) {
                return ParseResult(emptyList(), listOf(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Nondeterminism allowlist file is empty: ${file.absolutePath}"
                    )
                ))
            }
            val rawEntries = root["entries"]
            if (rawEntries != null && rawEntries !is List<*>) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                    "Allowlist 'entries' must be a list (was ${rawEntries.javaClass.simpleName})"))
            }
            val entryMaps: List<Map<String, Any>> = (rawEntries as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()

            val seen = mutableSetOf<String>()
            for ((index, entry) in entryMaps.withIndex()) {
                val module = entry["module"]?.toString() ?: ""
                val path = entry["file"]?.toString() ?: ""
                val source = entry["source"]?.toString() ?: ""
                val category = entry["category"]?.toString() ?: ""
                val scannerClassification = entry["scannerClassification"]?.toString() ?: ""
                val disposition = entry["disposition"]?.toString() ?: ""
                val authority = entry["authority"]?.toString() ?: ""
                val rationale = entry["rationale"]?.toString() ?: ""
                val occurrences = (entry["occurrences"] as? Number)?.toInt() ?: 1

                if (module.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry at index $index: module is blank"))
                }
                if (path.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry at index $index: file is blank"))
                }
                if (source.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry at index $index: source is blank"))
                }
                if (category.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry at index $index: category is blank"))
                }
                if (scannerClassification.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry at index $index: scannerClassification is blank"))
                }
                if (disposition !in ALLOWED_DISPOSITIONS) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_DISPOSITION,
                        "Allowlist entry ${module}:$path $source: unknown disposition '$disposition' — allowed: ${ALLOWED_DISPOSITIONS.joinToString(", ")}"))
                }
                if (authority.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_MISSING_RATIONALE,
                        "Allowlist entry ${module}:$path $source: authority is blank"))
                }
                if (rationale.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_MISSING_RATIONALE,
                        "Allowlist entry ${module}:$path $source: rationale is blank"))
                }
                if (occurrences < 1) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                        "Allowlist entry ${module}:$path $source: occurrences must be >= 1 (was $occurrences)"))
                }

                val parsed = AllowlistEntry(
                    module = module,
                    file = path,
                    source = source,
                    category = category,
                    scannerClassification = scannerClassification,
                    disposition = disposition,
                    authority = authority,
                    occurrences = occurrences,
                    rationale = rationale
                )
                if (!seen.add(parsed.identityKey)) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_DUPLICATE_ENTRY,
                        "Duplicate allowlist entry: $module:$path $source — each (module, file, source) identity may appear exactly once"))
                }
                entries.add(parsed)
            }
        } catch (e: Exception) {
            return ParseResult(emptyList(), listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
                    "Failed to parse nondeterminism allowlist ${file.absolutePath}: ${e.message}"
                )
            ))
        }

        return ParseResult(entries, diagnostics)
    }
}
