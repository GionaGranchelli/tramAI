package dev.tramai.build.quality

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.FileInputStream

enum class ModuleLayer(
    val yaml: String,
) {
    CORE_CONTRACTS("core-contracts"),
    RUNTIME_EXECUTION("runtime-execution"),
    GOVERNANCE_SECURITY("governance-security"),
    PERSISTENCE("persistence"),
    PROVIDER_ADAPTERS("provider-adapters"),
    FRAMEWORK_INTEGRATIONS("framework-integrations"),
    OPERATIONS_OBSERVABILITY("operations-observability"),
    HIGHER_CAPABILITIES("higher-capabilities"),
    APPLICATIONS_EXAMPLES("applications-examples"),
    TESTING_SUPPORT("testing-support"),
    ;

    companion object {
        fun fromYaml(value: String) = entries.firstOrNull { it.yaml == value }
    }
}

enum class ModuleMaturity(
    val yaml: String,
) {
    STABLE("stable"),
    PREVIEW("preview"),
    EXPERIMENTAL("experimental"),
    INTERNAL("internal"),
    ;

    companion object {
        fun fromYaml(value: String) =
            entries.firstOrNull {
                it.yaml ==
                    value
            }
    }
}

enum class ModulePublishability(
    val yaml: String,
) {
    PUBLISHED("published"),
    INTERNAL("internal"),
    EXCLUDED("excluded"),
    ;

    companion object {
        fun fromYaml(value: String) =
            entries.firstOrNull {
                it.yaml ==
                    value
            }
    }
}

enum class ModuleApiStability(
    val yaml: String,
) {
    STABLE("stable"),
    PREVIEW("preview"),
    EXPERIMENTAL("experimental"),
    INTERNAL("internal"),
    EXCLUDED("excluded"),
    ;

    companion object {
        fun fromYaml(value: String) =
            entries.firstOrNull {
                it.yaml ==
                    value
            }
    }
}

enum class ModuleVisibility(
    val yaml: String,
) {
    PUBLIC("public"),
    INTERNAL("internal"),
    EXCLUDED("excluded"),
    ;

    companion object {
        fun fromYaml(value: String) =
            entries.firstOrNull {
                it.yaml ==
                    value
            }
    }
}

enum class ReleaseInclusion(
    val yaml: String,
) {
    INCLUDED("included"),
    INTERNAL_ONLY("internal_only"),
    EXCLUDED("excluded"),
    ;

    companion object {
        fun fromYaml(value: String) =
            entries.firstOrNull {
                it.yaml ==
                    value
            }
    }
}

/**
 * Parses the authoritative, versioned module architecture manifest.
 *
 * The primary authority is the exact catalog [File] (declared input = execution
 * authority, a3 discipline). [fromRootDir] retains the historical
 * conventional-path contract (rootDir/config/quality/module-catalog.yml) for
 * existing callers; the parser itself is not duplicated.
 */
class ModuleCatalog(
    catalogFile: File,
) {
    private val catalogFile: File = catalogFile

    data class ModuleEntry(
        val path: String,
        val layer: ModuleLayer,
        val maturity: ModuleMaturity,
        val publishability: ModulePublishability,
        val apiStability: ModuleApiStability,
        val visibility: ModuleVisibility,
        val owner: String,
        val dependencyPolicy: String,
        val releaseInclusion: ReleaseInclusion,
        val rationale: String,
        val description: String?,
    )

    data class CatalogResult(
        val modules: Map<String, ModuleEntry>,
        val dependencyPolicies: Map<String, Set<ModuleLayer>>,
        val errors: List<VerificationDiagnostic>,
    )

    private var parsedModules: Map<String, ModuleEntry> = emptyMap()
    private var parsedPolicies: Map<String, Set<ModuleLayer>> = emptyMap()
    val validLayers = ModuleLayer.entries.map { it.yaml }.toSet()

    fun parse(): CatalogResult {
        val file = catalogFile
        if (!file.isFile) {
            return CatalogResult(
                emptyMap(),
                emptyMap(),
                listOf(failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, "Module catalog not found: ${file.absolutePath}")),
            )
        }
        val errors = mutableListOf<VerificationDiagnostic>()
        val modules = linkedMapOf<String, ModuleEntry>()
        val policies = linkedMapOf<String, Set<ModuleLayer>>()
        try {
            val root =
                FileInputStream(file).use {
                    val loaderOptions = LoaderOptions().apply { maxAliasesForCollections = 200 }
                    Yaml(SafeConstructor(loaderOptions)).load<Map<String, Any>>(it)
                }
            if (root["schemaVersion"]?.toString() !=
                "3"
            ) {
                errors += failure(DiagnosticCode.MODULE_CATALOG_INVALID_SCHEMA, "Module catalog schemaVersion must be '3'")
            }
            val policyRaw = root["dependencyPolicies"] as? Map<String, Map<String, Any>> ?: emptyMap()
            policyRaw.forEach { (name, definition) ->
                val rawLayers = definition["allowedLayers"] as? List<*> ?: emptyList<Any>()
                val layers = rawLayers.mapNotNull { ModuleLayer.fromYaml(it.toString()) }.toSet()
                if (layers.size != rawLayers.size ||
                    layers.isEmpty()
                ) {
                    errors +=
                        failure(DiagnosticCode.MODULE_CATALOG_INVALID_POLICY, "Dependency policy '$name' has invalid allowedLayers")
                }
                policies[name] = layers
            }
            val entries = root["modules"] as? List<Map<String, Any>> ?: emptyList()
            entries.forEachIndexed { index, raw -> parseEntry(index, raw, policies, modules, errors) }
            if (modules.isEmpty() &&
                errors.isEmpty()
            ) {
                errors +=
                    failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, "Module catalog is empty — no module entries found")
            }
        } catch (e: Exception) {
            errors +=
                failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, "Failed to parse module catalog: ${e.message}")
        }
        parsedModules = modules
        parsedPolicies = policies
        return CatalogResult(modules, policies, errors)
    }

    private fun parseEntry(
        index: Int,
        raw: Map<String, Any>,
        policies: Map<String, Set<ModuleLayer>>,
        modules: MutableMap<String, ModuleEntry>,
        errors: MutableList<VerificationDiagnostic>,
    ) {
        val path = raw["path"]?.toString().orEmpty()
        if (path.isBlank()) {
            errors += failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, "Module catalog entry $index: path is blank")
            return
        }

        fun <T> enum(
            field: String,
            value: T?,
            code: DiagnosticCode,
        ): T? {
            if (value == null) errors += failure(code, "$path: invalid $field '${raw[field]?.toString().orEmpty()}'", path)
            return value
        }
        val layer = enum("layer", ModuleLayer.fromYaml(raw["layer"]?.toString().orEmpty()), DiagnosticCode.MODULE_CATALOG_INVALID_LAYER)
        val maturity =
            enum("maturity", ModuleMaturity.fromYaml(raw["maturity"]?.toString().orEmpty()), DiagnosticCode.MODULE_CATALOG_INVALID_MATURITY)
        val publishability =
            enum(
                "publishability",
                ModulePublishability.fromYaml(raw["publishability"]?.toString().orEmpty()),
                DiagnosticCode.MODULE_CATALOG_INVALID_PUBLISHABILITY,
            )
        val api =
            enum(
                "apiStability",
                ModuleApiStability.fromYaml(raw["apiStability"]?.toString().orEmpty()),
                DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
            )
        val visibility =
            enum(
                "visibility",
                ModuleVisibility.fromYaml(raw["visibility"]?.toString().orEmpty()),
                DiagnosticCode.MODULE_CATALOG_INVALID_VISIBILITY,
            )
        val owner = raw["owner"]?.toString().orEmpty()
        val policy = raw["dependencyPolicy"]?.toString().orEmpty()
        val release =
            enum(
                "releaseInclusion",
                ReleaseInclusion.fromYaml(raw["releaseInclusion"]?.toString().orEmpty()),
                DiagnosticCode.MODULE_CATALOG_INVALID_RELEASE_INCLUSION,
            )
        val rationale = raw["rationale"]?.toString().orEmpty()
        val description = raw["description"]?.toString()
        if (owner.isBlank()) errors += failure(DiagnosticCode.MODULE_CATALOG_BLANK_OWNER, "$path: owner is blank", path)
        if (rationale.isBlank()) errors += failure(DiagnosticCode.MODULE_CATALOG_BLANK_RATIONALE, "$path: rationale is blank", path)
        if (policy !in
            policies
        ) {
            errors += failure(DiagnosticCode.MODULE_CATALOG_INVALID_POLICY, "$path: unknown dependencyPolicy '$policy'", path)
        }
        if (path in modules) errors += failure(DiagnosticCode.MODULE_CATALOG_DUPLICATE_PATH, "Module catalog: duplicate path '$path'", path)
        if (layer == null || maturity == null || publishability == null || api == null || visibility == null || release == null) return
        if (publishability == ModulePublishability.PUBLISHED &&
            description.isNullOrBlank()
        ) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_DESCRIPTION,
                    "$path: published modules must have a non-blank description",
                    path,
                )
        }
        if (publishability == ModulePublishability.PUBLISHED &&
            api == ModuleApiStability.EXCLUDED
        ) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
                    "$path: published modules must not have apiStability 'excluded'",
                    path,
                )
        }
        if (layer == ModuleLayer.APPLICATIONS_EXAMPLES &&
            publishability != ModulePublishability.EXCLUDED
        ) {
            errors +=
                failure(DiagnosticCode.MODULE_CATALOG_EXAMPLE_PUBLISHABLE, "$path: examples must have publishability 'excluded'", path)
        }
        if (publishability == ModulePublishability.EXCLUDED &&
            api != ModuleApiStability.EXCLUDED
        ) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
                    "$path: excluded modules must have apiStability 'excluded'",
                    path,
                )
        }
        if (visibility == ModuleVisibility.PUBLIC &&
            publishability == ModulePublishability.INTERNAL
        ) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION,
                    "$path: public visibility cannot be internal publishability",
                    path,
                )
        }
        if (!apiStrengthFitsMaturity(api, maturity)) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION,
                    "$path: API strength '$api' cannot exceed maturity '$maturity' (stable API requires stable maturity; preview requires preview+; experimental requires experimental+)",
                    path,
                )
        }
        if (release == ReleaseInclusion.INCLUDED &&
            publishability != ModulePublishability.PUBLISHED
        ) {
            errors +=
                failure(DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION, "$path: included release requires published module", path)
        }
        if (maturity == ModuleMaturity.INTERNAL && api != ModuleApiStability.INTERNAL &&
            api != ModuleApiStability.EXCLUDED
        ) {
            errors +=
                failure(
                    DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION,
                    "$path: internal maturity cannot promise stable/preview API",
                    path,
                )
        }
        if (errors.none { it.modulePath == path }) {
            modules[path] =
                ModuleEntry(path, layer, maturity, publishability, api, visibility, owner, policy, release, rationale, description)
        }
    }

    fun validateAgainstProjects(
        catalogModules: Map<String, ModuleEntry>,
        projectPaths: List<String>,
        errors: MutableList<VerificationDiagnostic>,
    ) {
        projectPaths
            .filter {
                it !in
                    catalogModules
            }.forEach {
                errors +=
                    failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, "Gradle project '$it' has no module-catalog entry")
            }
        catalogModules.keys
            .filter {
                it !in
                    projectPaths
            }.forEach {
                errors +=
                    failure(DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY, "Module-catalog entry '$it' does not exist as a Gradle project")
            }
    }

    fun layerFor(path: String): String? = parsedModules[path]?.layer?.yaml

    fun publishabilityFor(path: String): String? = parsedModules[path]?.publishability?.yaml

    fun apiStabilityFor(path: String): String? = parsedModules[path]?.apiStability?.yaml

    fun isPublished(path: String) = parsedModules[path]?.publishability == ModulePublishability.PUBLISHED

    fun entryFor(path: String): ModuleEntry? = parsedModules[path]

    fun allowedLayersFor(path: String): Set<ModuleLayer>? = parsedModules[path]?.dependencyPolicy?.let(parsedPolicies::get)

    private fun failure(
        code: DiagnosticCode,
        message: String,
        path: String? = null,
    ) = VerificationDiagnostic.failure(code, message, path)

    companion object {
        /** Conventional-path factory: rootDir/config/quality/module-catalog.yml (historical contract). */
        fun fromRootDir(rootDir: File): ModuleCatalog = ModuleCatalog(File(rootDir, "config/quality/module-catalog.yml"))

        /** Strength matrix: API strength may never exceed module maturity. */
        fun apiStrengthFitsMaturity(
            api: ModuleApiStability,
            maturity: ModuleMaturity,
        ): Boolean =
            when (api) {
                ModuleApiStability.STABLE -> {
                    maturity == ModuleMaturity.STABLE
                }

                ModuleApiStability.PREVIEW -> {
                    maturity == ModuleMaturity.STABLE || maturity == ModuleMaturity.PREVIEW
                }

                ModuleApiStability.EXPERIMENTAL -> {
                    maturity == ModuleMaturity.STABLE || maturity == ModuleMaturity.PREVIEW ||
                        maturity == ModuleMaturity.EXPERIMENTAL
                }

                ModuleApiStability.INTERNAL, ModuleApiStability.EXCLUDED -> {
                    true
                }
            }
    }
}
