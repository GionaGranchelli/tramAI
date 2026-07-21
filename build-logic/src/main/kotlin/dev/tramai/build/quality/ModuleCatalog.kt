package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * Parses and validates the TramAI module catalogue (config/quality/module-catalog.yml).
 * This is the single authoritative source for module layer, publishability, and API stability.
 */
class ModuleCatalog(private val rootDir: File) {

    data class ModuleEntry(
        val path: String,
        val layer: String,
        val publishability: String,
        val apiStability: String
    )

    data class CatalogResult(
        val modules: Map<String, ModuleEntry>,
        val errors: List<VerificationDiagnostic>
    )

    /** The most recently parsed catalog modules, cached for instance lookups. */
    private var parsedModules: Map<String, ModuleEntry> = emptyMap()

    /** Valid layer names as defined by the architecture. */
    val validLayers = setOf(
        "core-contracts", "runtime-execution", "governance-security",
        "persistence", "provider-adapters", "framework-integrations",
        "operations-observability", "higher-capabilities",
        "applications-examples", "testing-support"
    )

    /** Valid publishability values. */
    private val validPublishability = setOf("published", "internal", "excluded")

    /** Valid API stability values. */
    private val validApiStability = setOf("stable", "preview", "internal", "excluded")

    fun parse(): CatalogResult {
        val file = File(rootDir, "config/quality/module-catalog.yml")
        if (!file.isFile) {
            return CatalogResult(emptyMap(), listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                    "Module catalog not found: ${file.absolutePath}")
            ))
        }

        val errors = mutableListOf<VerificationDiagnostic>()
        val modules = mutableMapOf<String, ModuleEntry>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }
            val entries = root["modules"] as? List<Map<String, Any>> ?: emptyList()

            for ((index, entry) in entries.withIndex()) {
                val path = entry["path"]?.toString() ?: ""
                val layer = entry["layer"]?.toString() ?: ""
                val publishability = entry["publishability"]?.toString() ?: ""
                val apiStability = entry["apiStability"]?.toString() ?: ""

                if (path.isBlank()) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                        "Module catalog entry $index: path is blank"))
                    continue
                }

                if (layer.isBlank()) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                        "$path: layer is blank"))
                } else if (layer !in validLayers) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_INVALID_LAYER,
                        "$path: invalid layer '$layer'. Valid: $validLayers",
                        modulePath = path))
                }

                if (publishability !in validPublishability) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_INVALID_LAYER,
                        "$path: invalid publishability '$publishability'. Valid: $validPublishability",
                        modulePath = path))
                }

                if (apiStability !in validApiStability) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
                        "$path: invalid apiStability '$apiStability'. Valid: $validApiStability",
                        modulePath = path))
                }

                // Rules
                if (publishability == "published" && apiStability == "excluded") {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
                        "$path: published modules must have an API stability classification (not 'excluded')",
                        modulePath = path))
                }

                if (layer == "applications-examples" && publishability != "excluded") {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_EXAMPLE_PUBLISHABLE,
                        "$path: examples must have publishability 'excluded'",
                        modulePath = path))
                }

                if (publishability == "excluded" && apiStability != "excluded") {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
                        "$path: excluded modules must have apiStability 'excluded'",
                        modulePath = path))
                }

                // Check duplicate paths
                if (path in modules) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_DUPLICATE_PATH,
                        "Module catalog: duplicate path '$path'",
                        modulePath = path))
                } else if (errors.none { it.message.startsWith("$path:") }) {
                    // Only add if no fatal errors for this entry
                    modules[path] = ModuleEntry(path, layer, publishability, apiStability)
                }
            }

            if (modules.isEmpty() && errors.isEmpty()) {
                errors.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                    "Module catalog is empty — no module entries found"))
            }

        } catch (e: Exception) {
            errors.add(VerificationDiagnostic.failure(
                DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                "Failed to parse module catalog: ${e.message}"))
        }

        // Cache parsed modules for instance lookups
        parsedModules = modules

        return CatalogResult(modules, errors)
    }

    /**
     * Validates that all Gradle project paths are present in the catalog
     * and vice-versa. Returns errors for missing/unknown entries.
     */
    fun validateAgainstProjects(
        catalogModules: Map<String, ModuleEntry>,
        projectPaths: List<String>,
        errors: MutableList<VerificationDiagnostic>
    ) {
        val catalogPaths = catalogModules.keys.toSet()

        for (projPath in projectPaths) {
            if (projPath !in catalogPaths) {
                errors.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                    "Gradle project '$projPath' has no module-catalog entry"))
            }
        }

        for (catPath in catalogPaths) {
            if (catPath !in projectPaths) {
                errors.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
                    "Module-catalog entry '$catPath' does not exist as a Gradle project"))
            }
        }
    }

    /**
     * Returns the expected layer for a module path, or null if not in catalog.
     */
    fun layerFor(path: String): String? = parsedModules[path]?.layer

    /**
     * Returns the expected publishability for a module path, or null if not in catalog.
     */
    fun publishabilityFor(path: String): String? = parsedModules[path]?.publishability

    /**
     * Returns the expected apiStability for a module path, or null if not in catalog.
     */
    fun apiStabilityFor(path: String): String? = parsedModules[path]?.apiStability

    /**
     * Returns whether a module path is published (for dependency validation).
     */
    fun isPublished(path: String): Boolean = publishabilityFor(path) == "published"

    /**
     * Returns the parsed module entry for a path, or null if not in catalog.
     */
    fun entryFor(path: String): ModuleEntry? = parsedModules[path]
}
