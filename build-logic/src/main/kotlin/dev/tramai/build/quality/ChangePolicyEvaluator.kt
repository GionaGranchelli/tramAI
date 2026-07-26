package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml

/**
 * Pure-logic evaluator for change-policy rules.
 *
 * No Gradle dependencies, no git — operates on strings and lists.
 * Designed to be tested directly without TestKit or project setup.
 *
 * Rules evaluated:
 *  1. Production and canonical baseline must not change together
 *     (unless changeClass = baseline-migration)
 *  2. Analyzer and runtime production must not change together
 *  3. Deviation modifications must carry full evidence
 *
 * @param changeClass explicit classification or null for auto-detect
 * @param changedFiles list of file paths changed on this branch
 * @param baseDeviationsYaml content of maintainability-deviations.yml at the base ref,
 *                           or null if the file doesn't exist at base
 * @param currentDeviationsYaml content of maintainability-deviations.yml in the working tree,
 *                              or null if the file was deleted
 */
data class ChangePolicyInput(
    val changeClass: String?,
    val changedFiles: List<String>,
    val baseDeviationsYaml: String?,
    val currentDeviationsYaml: String?
)

data class PolicyViolation(
    val rule: String,
    val message: String
) {
    fun formatted(): String = "POLICY [$rule]: $message"
}

data class ChangePolicyResult(
    val violations: List<PolicyViolation>,
    val passed: Boolean
)

object ChangePolicyEvaluator {

    /** Detected primary change class when none is explicitly provided. */
    fun detectChangeClass(changedFiles: List<String>): String = when {
        // If the only production changes are in build-logic, it's a build-logic PR
        changedFiles.all { it.startsWith("build-logic/") } -> "build-logic"
        // If baseline changes, check if accompanied by scanner/analyzer changes
        changedFiles.any { isBaselinePath(it) } &&
            changedFiles.any { isAnalyzerPath(it) } -> "baseline-migration"
        // Default to runtime
        else -> "runtime-behaviour"
    }

    fun evaluate(input: ChangePolicyInput): ChangePolicyResult {
        val violations = mutableListOf<PolicyViolation>()
        val files = input.changedFiles

        if (files.isEmpty()) return ChangePolicyResult(emptyList(), passed = true)

        val changeClass = input.changeClass ?: detectChangeClass(files)

        // --- Rule 1: Production + baseline separation ---
        if (changeClass != "baseline-migration") {
            val productionChanged = files.any { isProductionPath(it) }
            val baselineChanged = files.any { isBaselinePath(it) }

            if (productionChanged && baselineChanged) {
                violations.add(
                    PolicyViolation(
                        rule = "production-baseline-separation",
                        message = buildString {
                            append("Production source and canonical baseline must not change together.\n")
                            append("  Detected change class: $changeClass\n")
                            append("  To change both, classify this PR as 'baseline-migration' via -PchangeClass=baseline-migration.\n")
                            append("  Changed production: ${files.filter { isProductionPath(it) }.joinToString(", ")}\n")
                            append("  Changed baseline: ${files.filter { isBaselinePath(it) }.joinToString(", ")}")
                        }
                    )
                }
        }

        // --- Rule 2: Analyzer + runtime separation ---
        if (changeClass != "baseline-migration") {
            val analyzerChanged = files.any { isAnalyzerPath(it) || isAnalyzerAdjacentPath(it) }
            val runtimeChanged = files.any { isRuntimeProductionPath(it) }

            if (analyzerChanged && runtimeChanged) {
                violations.add(
                    PolicyViolation(
                        rule = "analyzer-runtime-separation",
                        message = buildString {
                            append("Analyzer/tooling code and runtime production modules must not change together.\n")
                            append("  Detected change class: $changeClass\n")
                            append("  Submit separate PRs: one for tooling, one for runtime remediation.\n")
                            append("  Changed analyzer: ${files.filter { isAnalyzerPath(it) || isAnalyzerAdjacentPath(it) }.joinToString(", ")}\n")
                            append("  Changed runtime: ${files.filter { isRuntimeProductionPath(it) }.joinToString(", ")}")
                        }
                    )
                }
        }

        // --- Rule 3: Deviation evidence validation ---
        val deviationsChanged = files.any { isDeviationsPath(it) }
        if (deviationsChanged) {
            val baseDeviations = parseDeviations(input.baseDeviationsYaml)
            val currentDeviations = parseDeviations(input.currentDeviationsYaml)

            // Case: file was deleted
            if (currentDeviations == null) {
                violations.add(
                    PolicyViolation(
                        rule = "deviation-evidence",
                        message = "maintainability-deviations.yml was deleted. Deviations cannot be removed without a migration plan."
                    )
                )
            } else {
                // Check each current deviation for required fields
                val requiredFields = listOf("baseline", "allowed", "reason", "acceptedAt", "targetPhase", "owner")
                for ((id, fields) in currentDeviations) {
                    val missing = requiredFields.filter { it !in fields }
                    if (missing.isNotEmpty()) {
                        violations.add(
                            PolicyViolation(
                                rule = "deviation-evidence",
                                message = "Deviation $id is missing required fields: ${missing.joinToString(", ")}."
                            )
                        )
                    }
                }

                // Compare base vs current: detect increased allowed values
                if (baseDeviations != null) {
                    for ((id, currentFields) in currentDeviations) {
                        val baseFields = baseDeviations[id]
                        if (baseFields != null) {
                            val baseAllowed = baseFields["allowed"]?.toString()?.toDoubleOrNull()
                            val currentAllowed = currentFields["allowed"]?.toString()?.toDoubleOrNull()
                            if (baseAllowed != null && currentAllowed != null && currentAllowed > baseAllowed) {
                                // An increase requires a documented reason and future owner
                                val hasReason = currentFields["reason"]?.toString()?.isNotBlank() == true
                                val hasTargetPhase = currentFields["targetPhase"]?.toString()?.isNotBlank() == true
                                if (!hasReason || !hasTargetPhase) {
                                    violations.add(
                                        PolicyViolation(
                                            rule = "deviation-evidence",
                                            message = "Deviation $id allowed value increased from $baseAllowed to $currentAllowed " +
                                                "but is missing a documented reason and targetPhase."
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return ChangePolicyResult(
            violations = violations,
            passed = violations.isEmpty()
        )
    }

    // --- Production path detection ---
    fun isProductionPath(path: String): Boolean =
        path.contains("/src/main/")

    fun isBaselinePath(path: String): Boolean =
        path == "config/quality/0.6.0-baseline.json"

    fun isDeviationsPath(path: String): Boolean =
        path == "config/quality/maintainability-deviations.yml"

    /** Paths that classify as analyzer/tooling code. */
    fun isAnalyzerPath(path: String): Boolean =
        path.startsWith("build-logic/") && (
            path.contains("Scanner") ||
                path.contains("Verifier") ||
                path.contains("VerifierTask") ||
                path.contains("Inventory") ||
                path.endsWith("Plugin.kt")
            )

    /** Paths adjacent to analyzers (normalizers, catalogs, models, tasks). */
    fun isAnalyzerAdjacentPath(path: String): Boolean =
        path.startsWith("build-logic/") && (
            path.contains("/quality/") ||
                path.contains("/baseline/") ||
                path.startsWith("build-logic/build.gradle.kts")
            )

    /** Runtime production modules (not build-logic, not spring-boot-starters). */
    fun isRuntimeProductionPath(path: String): Boolean =
        path.startsWith("tramai-") &&
            !path.startsWith("tramai-spring-boot-starter") &&
            !path.startsWith("tramai-observability") &&
            path.contains("/src/main/")

    // --- YAML deviation parsing ---

    /**
     * Parses a deviations YAML file and returns a map of deviation ID -> field map.
     * Returns null if the YAML is null/empty (file doesn't exist).
     * Returns an empty map if the file has no deviations.
     */
    fun parseDeviations(yamlContent: String?): Map<String, Map<String, Any?>>? {
        if (yamlContent == null) return null
        if (yamlContent.isBlank()) return emptyMap()

        return try {
            val yaml = Yaml()
            val root = yaml.load<Map<String, Any?>>(yamlContent) ?: return emptyMap()
            @Suppress("UNCHECKED_CAST")
            val deviations = root["deviations"] as? List<Map<String, Any?>> ?: return emptyMap()
            deviations.mapNotNull { dev ->
                val id = dev["id"]?.toString() ?: return@mapNotNull null
                id to dev
            }.toMap()
        } catch (e: Exception) {
            // If YAML is malformatted, return empty — the gate will catch missing evidence
            emptyMap()
        }
    }
}
