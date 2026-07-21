package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate

/**
 * Parses and validates maintainability deviations from config/quality/maintainability-deviations.yml.
 *
 * Deviation scopes follow an explicit validated grammar:
 *   - "*"                              (global)
 *   - ":tramai-engine"                 (module)
 *   - ":tramai-engine:path/to/File.kt" (file)
 *   - ":tramai-engine:path/to/File.kt#Declaration" (declaration)
 *   - ":tramai-provider-*"             (wildcard module prefix — only at end)
 *
 * Any other scope format is rejected as malformed.
 */
class DeviationParser(private val rootDir: File) {

    data class DeviationEntry(
        val id: String,
        val metric: String,
        val scope: String,
        val baseline: Int,
        val allowed: Int,
        val reason: String,
        val acceptedAt: String,
        val targetPhase: String,
        val owner: String
    )

    data class DeviationScope(
        val modulePath: String?,
        val filePath: String?,
        val declaration: String?,
        val isWildcard: Boolean
    ) {
        /**
         * Check whether this deviation scope covers a given finding scope.
         *
         * Matching rules:
         * - *: matches everything.
         * - :module: exact module equality.
         * - :prefix-*: explicit module-prefix matching.
         * - :module:path: exact normalized module and repository-path equality.
         * - :module:path#Declaration: exact equality on all three fields.
         */
        fun covers(finding: FindingScope): Boolean {
            // Global wildcard: * matches everything
            if (isWildcard && modulePath == null) return true

            // Prefix wildcard: :tramai-* matches :tramai-engine, :tramai-core, etc.
            if (isWildcard && modulePath != null) {
                val fm = finding.modulePath ?: return false
                val normalizedPrefix = modulePath!!.removeSuffix("-")
                // Match exact module or prefix
                return fm == normalizedPrefix || fm.startsWith(normalizedPrefix + ":")
            }

            // Declaration scope: :module:path#Declaration
            if (declaration != null) {
                return modulePath == finding.modulePath &&
                        filePath == finding.repositoryPath &&
                        declaration == finding.declaration
            }

            // File scope: :module:path (exact match)
            if (filePath != null) {
                return modulePath == finding.modulePath &&
                        filePath == finding.repositoryPath
            }

            // Module scope: :module (exact match only — never prefix)
            if (modulePath != null) {
                return modulePath == finding.modulePath
            }

            return false
        }
    }

    /**
     * Represents the scope of a finding for deviation matching.
     */
    data class FindingScope(
        val modulePath: String?,
        val repositoryPath: String?,
        val declaration: String?
    )

    data class ParseResult(
        val deviations: List<DeviationEntry>,
        val diagnostics: List<VerificationDiagnostic>
    ) {
        val errors: List<String> get() = diagnostics
            .filter { it.severity == DiagnosticSeverity.FAILURE || it.severity == DiagnosticSeverity.WARNING }
            .map { "${it.code}: ${it.message}" }
    }

    /**
     * Parse the deviation scope string into a structured object.
     * Validated grammar: *, :module, :module:path, :module:path#Declaration, :module-prefix-*
     */
    fun parseScope(scope: String): DeviationScope? {
        val trimmed = scope.trim().removePrefix("\"").removeSuffix("\"")

        if (trimmed == "*") {
            return DeviationScope(null, null, null, isWildcard = true)
        }

        if (trimmed.startsWith(":")) {
            // Check for wildcard at end: `:tramai-*` or `:tramai-provider-*`
            if (trimmed.endsWith("-*")) {
                val prefix = trimmed.removeSuffix("*") // keep the trailing `-`
                if (prefix.startsWith(":") && prefix.length > 1) {
                    return DeviationScope(prefix, null, null, isWildcard = true)
                }
            }

            // Check for declaration: `:module:path/to/File.kt#Declaration`
            val hashIdx = trimmed.indexOf('#')
            val declarationName = if (hashIdx >= 0) trimmed.substring(hashIdx + 1) else null
            val pathPart = if (hashIdx >= 0) trimmed.substring(0, hashIdx) else trimmed

            // Split into module and file path
            val colonIdx = pathPart.indexOf(':', 1) // second colon
            if (colonIdx >= 0) {
                val modulePath = pathPart.substring(0, colonIdx)
                val filePath = pathPart.substring(colonIdx + 1)
                return DeviationScope(modulePath, filePath, declarationName, isWildcard = false)
            } else {
                // Module only (e.g. `:tramai-engine`)
                return DeviationScope(pathPart, null, declarationName, isWildcard = false)
            }
        }

        // Not a valid scope — legacy substring-based scopes are also rejected
        return null
    }

    fun parse(): ParseResult {
        val file = File(rootDir, "config/quality/maintainability-deviations.yml")
        if (!file.isFile) {
            return ParseResult(emptyList(), listOf(
                VerificationDiagnostic.failure(DiagnosticCode.MALFORMED_DEVIATION,
                    "Deviation file not found: ${file.absolutePath}")
            ))
        }

        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val deviations = mutableListOf<DeviationEntry>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }
            val entries = root["deviations"] as? List<Map<String, Any>> ?: emptyList()

            for ((index, entry) in entries.withIndex()) {
                val id = entry["id"]?.toString() ?: "MQ-${index}"
                val metric = entry["metric"]?.toString() ?: ""
                val scope = entry["scope"]?.toString() ?: ""
                val baseline = (entry["baseline"] as? Number)?.toInt() ?: 0
                val allowed = (entry["allowed"] as? Number)?.toInt() ?: 0
                val reason = entry["reason"]?.toString() ?: ""
                val acceptedAt = entry["acceptedAt"]?.toString() ?: ""
                val targetPhase = entry["targetPhase"]?.toString() ?: ""
                val owner = entry["owner"]?.toString() ?: ""

                // Validate basic fields
                if (id.isBlank() || id == "MQ-${index}") {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION,
                        "Deviation at index $index: id is blank or auto-generated"))
                }
                if (metric.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: metric is blank"))
                }
                if (scope.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: scope is blank"))
                }
                if (reason.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: reason is blank"))
                }
                if (acceptedAt.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: acceptedAt is blank"))
                }
                if (targetPhase.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: targetPhase is blank"))
                }
                if (owner.isBlank()) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION, "$id: owner is blank"))
                }

                // Validate scope format
                val parsedScope = parseScope(scope)
                if (parsedScope == null) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.INVALID_DEVIATION_SCOPE,
                        "$id: invalid scope '$scope'. Use ':module', ':module:path', ':module:path#Decl', or '*'"))
                }

                // Validate acceptedAt as ISO date
                if (acceptedAt.isNotBlank()) {
                    try {
                        LocalDate.parse(acceptedAt)
                    } catch (e: Exception) {
                        diagnostics.add(VerificationDiagnostic.failure(
                            DiagnosticCode.MALFORMED_DEVIATION,
                            "$id: acceptedAt '$acceptedAt' is not a valid ISO-8601 date: ${e.message}"))
                    }
                }

                // Validate numeric constraints
                if (baseline < 0) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION,
                        "$id: baseline ($baseline) must be >= 0"))
                }
                if (allowed < 0) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION,
                        "$id: allowed ($allowed) must be >= 0"))
                }
                if (allowed < baseline && baseline > 0) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MALFORMED_DEVIATION,
                        "$id: allowed ($allowed) must be >= baseline ($baseline)"))
                }

                // Check expired target phase
                val expiredPrefixes = listOf("0.6.0", "0.5", "0.4", "0.3", "0.2", "0.1")
                if (expiredPrefixes.any { targetPhase.startsWith(it) }) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.EXPIRED_DEVIATION,
                        "$id: targetPhase $targetPhase has expired"))
                }

                deviations.add(
                    DeviationEntry(id, metric, scope, baseline, allowed, reason, acceptedAt, targetPhase, owner)
                )
            }

            checkDuplicateIds(deviations, diagnostics)

        } catch (e: Exception) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.MALFORMED_DEVIATION,
                "Failed to parse deviation file: ${e.message}"))
        }

        return ParseResult(deviations, diagnostics)
    }

    /**
     * Validate that a deviation's baseline matches the actual computed baseline value.
     */
    fun validateBaselineMatch(baseline: Int, actualBaseline: Int): VerificationDiagnostic? {
        if (baseline != actualBaseline) {
            return VerificationDiagnostic.failure(
                DiagnosticCode.DEVIATION_BASELINE_MISMATCH,
                "Deviation baseline $baseline does not match actual baseline $actualBaseline",
                baselineValue = baseline.toString(),
                currentValue = actualBaseline.toString()
            )
        }
        return null
    }

    private fun checkDuplicateIds(deviations: List<DeviationEntry>, diagnostics: MutableList<VerificationDiagnostic>) {
        val duplicates = deviations.groupBy { it.id }.filter { it.value.size > 1 }
        for ((id, entries) in duplicates) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.DUPLICATE_DEVIATION,
                "Duplicate deviation ID: $id (${entries.size} entries)"))
        }
    }

    /**
     * Find a deviation that covers a given finding.
     * Uses FindingScope and covers() for typed matching.
     */
    fun findCoveringDeviation(
        deviations: List<DeviationEntry>,
        metric: String,
        findingScope: FindingScope,
        currentValue: Int
    ): DeviationEntry? {
        return deviations.find { dev ->
            dev.metric == metric &&
                currentValue <= dev.allowed &&
                (parseScope(dev.scope)?.covers(findingScope) == true)
        }
    }
}
