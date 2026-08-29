package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml

/**
 * Pure-logic evaluator for change-policy rules.
 *
 * No Gradle dependencies, no git — operates on strings and lists.
 * Designed to be tested directly without TestKit or project setup.
 *
 * Supported change classes (set via -PchangeClass or auto-detected):
 *  - runtime-behaviour (default): production logic, tests, internal refactoring
 *  - build-logic: analyzers, Gradle plugins, tooling
 *  - baseline-migration: explicit override that permits analyzer + baseline changes
 *    but still rejects tramai runtime production changes (not src/main/)
 *
 * The baseline-migration class is NEVER auto-detected. It must be set explicitly.
 */
data class ChangePolicyInput(
    val changeClass: String?,
    val changedFiles: List<String>,
    val baseDeviationsYaml: String?,
    val currentDeviationsYaml: String?,
)

data class PolicyViolation(
    val rule: String,
    val message: String,
) {
    fun formatted(): String = "POLICY [$rule]: $message"
}

data class ChangePolicyResult(
    val violations: List<PolicyViolation>,
    val passed: Boolean,
)

/**
 * Sealed result type for deviation YAML parsing.
 *  - Success: parsed correctly
 *  - NotFound: file doesn't exist at the ref
 *  - Invalid: file exists but is malformed, blank, or structurally invalid
 */
sealed class DeviationParseResult {
    data class Success(
        val deviations: Map<String, Map<String, Any?>>,
    ) : DeviationParseResult()

    data object NotFound : DeviationParseResult()

    data class Invalid(
        val reason: String,
    ) : DeviationParseResult()
}

object ChangePolicyEvaluator {
    /** Auto-detected change class when -PchangeClass is not provided. */
    fun detectChangeClass(changedFiles: List<String>): String =
        when {
            // If the only changes are build tooling (build-logic, root build scripts,
            // docs), it's a build-logic PR. Root build scripts are configuration, not
            // runtime production code.
            changedFiles.all {
                it.startsWith("build-logic/") ||
                    it.startsWith("docs/") ||
                    it.endsWith(".md") ||
                    isBuildScriptPath(it)
            } -> "build-logic"

            // Default to runtime
            else -> "runtime-behaviour"
        }

    /** Root-level build configuration files count as build tooling, not runtime. */
    fun isBuildScriptPath(path: String): Boolean =
        path == "build.gradle.kts" ||
            path == "settings.gradle.kts" ||
            path == "gradle.properties" ||
            path.startsWith("gradle/")

    fun evaluate(input: ChangePolicyInput): ChangePolicyResult {
        val violations = mutableListOf<PolicyViolation>()
        val files = input.changedFiles
        if (files.isEmpty()) return ChangePolicyResult(emptyList(), passed = true)

        val changeClass = input.changeClass ?: detectChangeClass(files)

        // Compute path categories once
        val productionChanged = files.any { isProductionPath(it) }
        val baselineChanged = files.any { isBaselinePath(it) }
        val analyzerChanged = files.any { isAnalyzerPath(it) || isAnalyzerAdjacentPath(it) }
        val runtimeChanged = files.any { isRuntimeProductionPath(it) }

        // === Baseline-migration: narrow exceptional class ===
        // This block runs FIRST, before the general rules below.
        // It does NOT fall through — the general rules check !baseline-migration.
        if (changeClass == "baseline-migration") {
            // MUST change the canonical baseline
            if (!baselineChanged) {
                violations.add(
                    PolicyViolation(
                        "production-baseline-separation",
                        "A baseline-migration PR must change the canonical baseline " +
                            "(config/quality/0.6.0-baseline.json or config/detekt/baseline.xml). " +
                            "Found no changes to any canonical baseline.",
                    ),
                )
            }
            // MUST include an analyzer change
            if (!analyzerChanged) {
                violations.add(
                    PolicyViolation(
                        "analyzer-runtime-separation",
                        "A baseline-migration PR must include an analyzer change (build-logic/). " +
                            "Found no changes in build-logic/.",
                    ),
                )
            }
            // MUST NOT include tramai runtime production changes
            if (runtimeChanged) {
                violations.add(runtimeInBaselineMigrationViolation(files))
            }
            // Skip general rules below — baseline-migration is fully validated here.
            // (Still evaluate deviation changes if present.)
        } else {
            // === Rule 1: Production + baseline separation ===
            if (productionChanged && baselineChanged) {
                violations.add(productionBaselineSeparationViolation(files, changeClass))
            }

            // === Rule 2: Analyzer + runtime separation ===
            if (analyzerChanged && runtimeChanged) {
                violations.add(analyzerRuntimeSeparationViolation(files))
            }
        }

        // === Rule 3: Deviation evidence validation ===
        val deviationsChanged = files.any { isDeviationsPath(it) }
        if (deviationsChanged) {
            val baseResult = parseResult(input.baseDeviationsYaml)
            val currentResult = parseResult(input.currentDeviationsYaml)

            // Validate base parse
            if (baseResult is DeviationParseResult.Invalid) {
                violations.add(
                    PolicyViolation(
                        "deviation-evidence",
                        "Base deviation YAML is invalid: ${baseResult.reason}",
                    ),
                )
            }

            // Validate current parse
            when (currentResult) {
                is DeviationParseResult.Invalid -> {
                    violations.add(
                        PolicyViolation(
                            "deviation-evidence",
                            "Current deviation YAML is invalid: ${currentResult.reason}",
                        ),
                    )
                }

                is DeviationParseResult.NotFound -> {
                    violations.add(
                        PolicyViolation(
                            "deviation-evidence",
                            "maintainability-deviations.yml was deleted. Deviations cannot be removed without a migration plan.",
                        ),
                    )
                }

                is DeviationParseResult.Success -> {
                    val current = currentResult.deviations
                    val base = (baseResult as? DeviationParseResult.Success)?.deviations ?: emptyMap()

                    // Check: required fields on every current deviation
                    val requiredFields = listOf("baseline", "allowed", "reason", "acceptedAt", "targetPhase", "owner")
                    for ((id, fields) in current) {
                        val missing = requiredFields.filter { it !in fields }
                        if (missing.isNotEmpty()) {
                            violations.add(
                                PolicyViolation(
                                    "deviation-evidence",
                                    "Deviation $id is missing required fields: ${missing.joinToString(", ")}.",
                                ),
                            )
                        }

                        // Validate field types: baseline and allowed must be numeric
                        val baselineVal = fields["baseline"]?.toString()?.toDoubleOrNull()
                        val allowedVal = fields["allowed"]?.toString()?.toDoubleOrNull()
                        if (baselineVal == null) {
                            violations.add(
                                PolicyViolation(
                                    "deviation-evidence",
                                    "Deviation $id has non-numeric baseline value.",
                                ),
                            )
                        }
                        if (allowedVal == null) {
                            violations.add(
                                PolicyViolation(
                                    "deviation-evidence",
                                    "Deviation $id has non-numeric allowed value.",
                                ),
                            )
                        }

                        // Validate: reason, acceptedAt, targetPhase, owner must be nonblank
                        val textFields = listOf("reason", "acceptedAt", "targetPhase", "owner")
                        for (field in textFields) {
                            val value = fields[field]?.toString()
                            if (value.isNullOrBlank()) {
                                violations.add(
                                    PolicyViolation(
                                        "deviation-evidence",
                                        "Deviation $id has blank $field.",
                                    ),
                                )
                            }
                        }
                    }

                    // Check: removed deviation IDs
                    val removedIds = base.keys - current.keys
                    if (removedIds.isNotEmpty()) {
                        violations.add(
                            PolicyViolation(
                                "deviation-evidence",
                                "Deviations removed without migration: ${removedIds.joinToString(", ")}. " +
                                    "Removing deviations requires a documented migration.",
                            ),
                        )
                    }

                    // Check: added deviation IDs (new deviations require explicit evidence)
                    val addedIds = current.keys - base.keys
                    if (addedIds.isNotEmpty()) {
                        // New deviations are fine as long as they have all required fields
                        // (already checked above)
                    }

                    // Check: allowed value increases require explicit new approval
                    for ((id, currentFields) in current) {
                        val baseFields = base[id] ?: continue
                        val baseAllowed = baseFields["allowed"]?.toString()?.toDoubleOrNull()
                        val currentAllowed = currentFields["allowed"]?.toString()?.toDoubleOrNull()
                        if (baseAllowed != null && currentAllowed != null && currentAllowed > baseAllowed) {
                            // Check that reason, targetPhase, acceptedAt, and owner are all
                            // nonblank AND at least one has changed from the base
                            val reasonBlank = currentFields["reason"]?.toString().isNullOrBlank()
                            val phaseBlank = currentFields["targetPhase"]?.toString().isNullOrBlank()
                            val dateBlank = currentFields["acceptedAt"]?.toString().isNullOrBlank()
                            val ownerBlank = currentFields["owner"]?.toString().isNullOrBlank()

                            val baseReason = baseFields["reason"]?.toString().orEmpty()
                            val basePhase = baseFields["targetPhase"]?.toString().orEmpty()
                            val baseDate = baseFields["acceptedAt"]?.toString().orEmpty()

                            val reasonSame = currentFields["reason"]?.toString() == baseReason
                            val phaseSame = currentFields["targetPhase"]?.toString() == basePhase
                            val dateSame = currentFields["acceptedAt"]?.toString() == baseDate

                            if (reasonBlank || phaseBlank || dateBlank || ownerBlank) {
                                violations.add(
                                    PolicyViolation(
                                        "deviation-evidence",
                                        "Deviation $id allowed value increased from $baseAllowed to $currentAllowed " +
                                            "but required fields (reason, targetPhase, acceptedAt, owner) must all be nonblank.",
                                    ),
                                )
                            } else if (reasonSame && phaseSame && dateSame) {
                                violations.add(
                                    PolicyViolation(
                                        "deviation-evidence",
                                        "Deviation $id allowed value increased from $baseAllowed to $currentAllowed " +
                                            "but reason, targetPhase, and acceptedAt are unchanged from the base. " +
                                            "Ceiling increases require new justification and approval metadata.",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        return ChangePolicyResult(
            violations = violations,
            passed = violations.isEmpty(),
        )
    }

    // --- Violation message builders ---

    private fun productionBaselineSeparationViolation(
        files: List<String>,
        changeClass: String,
    ): PolicyViolation {
        val prodPaths = files.filter { isProductionPath(it) }.joinToString(", ")
        val baselinePaths = files.filter { isBaselinePath(it) }.joinToString(", ")
        return PolicyViolation(
            rule = "production-baseline-separation",
            message =
                buildString {
                    append("Production source and canonical baseline must not change together.")
                    append("\n  Detected change class: $changeClass")
                    append("\n  To change both, classify this PR as 'baseline-migration' via -PchangeClass=baseline-migration.")
                    append("\n  Changed production: $prodPaths")
                    append("\n  Changed baseline: $baselinePaths")
                },
        )
    }

    private fun runtimeInBaselineMigrationViolation(files: List<String>): PolicyViolation {
        val runtimePaths = files.filter { isRuntimeProductionPath(it) }.joinToString(", ")
        return PolicyViolation(
            rule = "production-baseline-separation",
            message =
                buildString {
                    append("Baseline-migration PRs must not include tramai runtime production changes.")
                    append("\n  Use a separate runtime-remediation PR for runtime changes.")
                    append("\n  Changed runtime: $runtimePaths")
                },
        )
    }

    private fun analyzerRuntimeSeparationViolation(files: List<String>): PolicyViolation {
        val analyzerPaths = files.filter { isAnalyzerPath(it) || isAnalyzerAdjacentPath(it) }.joinToString(", ")
        val runtimePaths = files.filter { isRuntimeProductionPath(it) }.joinToString(", ")
        return PolicyViolation(
            rule = "analyzer-runtime-separation",
            message =
                buildString {
                    append("Analyzer/tooling code and runtime production modules must not change together.")
                    append("\n  Detected change class: ${ChangePolicyEvaluator.detectChangeClass(files)}")
                    append("\n  Submit separate PRs: one for tooling, one for runtime remediation.")
                    append("\n  Changed analyzer: $analyzerPaths")
                    append("\n  Changed runtime: $runtimePaths")
                },
        )
    }

    // --- Path detection ---

    fun isProductionPath(path: String): Boolean = path.contains("/src/main/")

    fun isBaselinePath(path: String): Boolean =
        path == "config/quality/0.6.0-baseline.json" ||
            path == "config/detekt/baseline.xml"

    fun isDeviationsPath(path: String): Boolean = path == "config/quality/maintainability-deviations.yml"

    /** Paths that classify as analyzer/tooling code. */
    fun isAnalyzerPath(path: String): Boolean =
        path.startsWith("build-logic/") && (
            path.contains("Scanner") ||
                path.contains("Verifier") ||
                path.contains("VerifierTask") ||
                path.contains("Inventory") ||
                path.endsWith("Plugin.kt")
        )

    /** Paths adjacent to analyzers (normalizers, catalogs, models, tasks, tests). */
    fun isAnalyzerAdjacentPath(path: String): Boolean =
        path.startsWith("build-logic/") && (
            path.contains("/quality/") ||
                path.contains("/baseline/") ||
                path.contains("/test/") ||
                path.startsWith("build-logic/build.gradle.kts")
        )

    /**
     * Runtime production modules.
     * All tramai modules under src/main/ are runtime production.
     * No exclusions — starters and observability ship production code.
     */
    fun isRuntimeProductionPath(path: String): Boolean = path.startsWith("tramai-") && path.contains("/src/main/")

    // --- Deviation YAML parsing ---

    /**
     * Parses deviations YAML into a sealed result type.
     * Invalid YAML (malformed, blank, structurally broken) returns [DeviationParseResult.Invalid]
     * and MUST fail the gate.
     */
    fun parseResult(yamlContent: String?): DeviationParseResult {
        if (yamlContent == null) return DeviationParseResult.NotFound
        if (yamlContent.isBlank()) return DeviationParseResult.Invalid("YAML content is blank")

        return try {
            val yaml = Yaml()
            val root = yaml.load<Map<String, Any?>>(yamlContent)
            if (root == null) return DeviationParseResult.Invalid("YAML is empty (no document)")
            @Suppress("UNCHECKED_CAST")
            val deviations = root["deviations"]
            if (deviations == null) return DeviationParseResult.Invalid("Missing top-level 'deviations' key")
            if (deviations !is List<*>) return DeviationParseResult.Invalid("'deviations' is not a list")
            if (deviations.isEmpty()) return DeviationParseResult.Success(emptyMap())

            val result = mutableMapOf<String, Map<String, Any?>>()
            for ((i, entry) in deviations.withIndex()) {
                if (entry !is Map<*, *>) {
                    return DeviationParseResult.Invalid("Deviation entry at index $i is not a map")
                }
                @Suppress("UNCHECKED_CAST")
                val dev = entry as Map<String, Any?>
                val id = dev["id"]?.toString()
                if (id.isNullOrBlank()) {
                    return DeviationParseResult.Invalid("Deviation entry at index $i has no 'id' field")
                }
                if (result.put(id, dev) != null) {
                    return DeviationParseResult.Invalid("Duplicate deviation id: $id")
                }
            }
            DeviationParseResult.Success(result)
        } catch (e: Exception) {
            DeviationParseResult.Invalid("YAML parse error: ${e.message ?: "unknown error"}")
        }
    }
}
