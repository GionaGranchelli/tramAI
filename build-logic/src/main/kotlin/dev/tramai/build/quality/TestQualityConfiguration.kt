package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

data class TestQualityConfiguration(
    val schemaVersion: String,
    val criticalModules: List<String>,
    val coverage: CoverageConfiguration,
    val mutation: MutationConfiguration
) {
    data class CoverageConfiguration(
        val regressionTolerancePercentagePoints: Double,
        val exclusions: List<CoverageExclusion>
    )

    data class MutationConfiguration(
        val regressionTolerancePercentagePoints: Double,
        val targetFamilies: Map<String, MutationTargetFamily>
    )

    data class MutationTargetFamily(
        val modules: List<String>,
        val targetClasses: List<String> = listOf("dev.tramai.*"),
        val targetTests: List<String> = listOf("dev.tramai.*")
    )

    companion object {
        fun load(repositoryRoot: File): TestQualityConfiguration {
            val catalog = ModuleCatalog(repositoryRoot).parse()
            val catalogFailures = catalog.errors.filter { it.severity == DiagnosticSeverity.FAILURE }
            if (catalogFailures.isNotEmpty()) {
                throw GradleException(
                    "Cannot validate test-quality configuration because module catalog is invalid: " +
                        catalogFailures.joinToString("; ") { it.message }
                )
            }
            return parse(
                File(repositoryRoot, "config/quality/test-quality.yml"),
                catalog.modules.keys
            )
        }

        fun parse(file: File, knownModules: Set<String>): TestQualityConfiguration {
            if (!file.isFile) {
                throw GradleException("Test-quality configuration not found: ${file.absolutePath}")
            }
            val loaderOptions = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 20
            }
            val raw = try {
                @Suppress("UNCHECKED_CAST")
                Yaml(loaderOptions).load<Map<String, Any?>>(file.readText(Charsets.UTF_8))
                    ?: emptyMap()
            } catch (e: Exception) {
                throw GradleException("Invalid test-quality configuration: ${e.message}", e)
            }

            fun map(value: Any?, path: String): Map<String, Any?> {
                if (value !is Map<*, *>) throw GradleException("$path must be a mapping")
                return value.entries.associate { (key, item) ->
                    (key as? String ?: throw GradleException("$path contains a non-string key")) to item
                }
            }

            fun strings(value: Any?, path: String): List<String> {
                if (value !is List<*>) throw GradleException("$path must be a list")
                return value.mapIndexed { index, item ->
                    (item as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw GradleException("$path[$index] must be a non-empty string")
                }
            }

            fun percentage(value: Any?, path: String): Double {
                val result = (value as? Number)?.toDouble()
                    ?: value?.toString()?.toDoubleOrNull()
                    ?: throw GradleException("$path must be a percentage")
                if (!result.isFinite() || result < 0.0 || result > 100.0) {
                    throw GradleException("$path must be between 0 and 100")
                }
                return result
            }

            val schemaVersion = raw["schemaVersion"]?.toString()
                ?: throw GradleException("schemaVersion is required")
            if (schemaVersion != "1") throw GradleException("Unsupported test-quality schemaVersion '$schemaVersion'")

            val criticalModules = strings(raw["criticalModules"], "criticalModules")
            if (criticalModules.isEmpty()) throw GradleException("criticalModules must not be empty")
            if (criticalModules.size != criticalModules.distinct().size) {
                throw GradleException("criticalModules must not contain duplicates")
            }

            val coverageRaw = map(raw["coverage"], "coverage")
            val exclusionsRaw = coverageRaw["exclusions"] as? List<*>
                ?: throw GradleException("coverage.exclusions must be a list")
            val exclusions = exclusionsRaw.mapIndexed { index, item ->
                val entry = map(item, "coverage.exclusions[$index]")
                val pattern = entry["pattern"]?.toString()?.trim().orEmpty()
                val reason = entry["reason"]?.toString()?.trim().orEmpty()
                if (pattern.isEmpty()) throw GradleException("coverage.exclusions[$index].pattern must not be empty")
                if (isAbsolutePath(pattern)) {
                    throw GradleException("coverage.exclusions[$index].pattern must be repository-relative")
                }
                if (reason.isEmpty()) {
                    throw GradleException("coverage.exclusions[$index].reason must not be empty")
                }
                CoverageExclusion(pattern, reason)
            }
            val exclusionPatterns = exclusions.map { it.pattern }
            if (exclusionPatterns.size != exclusionPatterns.distinct().size) {
                throw GradleException("coverage.exclusions must not contain duplicate patterns")
            }

            val mutationRaw = map(raw["mutation"], "mutation")
            val familiesRaw = map(mutationRaw["targetFamilies"], "mutation.targetFamilies")
            if (familiesRaw.isEmpty()) throw GradleException("mutation.targetFamilies must not be empty")
            val families = linkedMapOf<String, MutationTargetFamily>()
            familiesRaw.forEach { (family, value) ->
                if (family.isBlank()) throw GradleException("Mutation family name must not be empty")
                val familyMap = map(value, "mutation.targetFamilies.$family")
                val modules = strings(
                    familyMap["modules"],
                    "mutation.targetFamilies.$family.modules"
                )
                if (modules.isEmpty()) {
                    throw GradleException("mutation.targetFamilies.$family.modules must not be empty")
                }
                val targetClasses = if ("targetClasses" in familyMap) {
                    strings(familyMap["targetClasses"], "mutation.targetFamilies.$family.targetClasses")
                } else {
                    listOf("dev.tramai.*")
                }
                val targetTests = if ("targetTests" in familyMap) {
                    strings(familyMap["targetTests"], "mutation.targetFamilies.$family.targetTests")
                } else {
                    listOf("dev.tramai.*")
                }
                if (targetClasses.size != targetClasses.distinct().size) {
                    throw GradleException("mutation.targetFamilies.$family.targetClasses must not contain duplicate patterns")
                }
                if (targetTests.size != targetTests.distinct().size) {
                    throw GradleException("mutation.targetFamilies.$family.targetTests must not contain duplicate patterns")
                }
                families[family] = MutationTargetFamily(
                    modules = modules,
                    targetClasses = targetClasses,
                    targetTests = targetTests
                )
            }

            val referencedModules = criticalModules + families.values.flatMap { it.modules }
            val unknownModules = referencedModules.toSet() - knownModules
            if (unknownModules.isNotEmpty()) {
                throw GradleException("Test-quality configuration references unknown modules: ${unknownModules.sorted().joinToString()}")
            }

            return TestQualityConfiguration(
                schemaVersion = schemaVersion,
                criticalModules = criticalModules,
                coverage = CoverageConfiguration(
                    regressionTolerancePercentagePoints = percentage(
                        coverageRaw["regressionTolerancePercentagePoints"],
                        "coverage.regressionTolerancePercentagePoints"
                    ),
                    exclusions = exclusions
                ),
                mutation = MutationConfiguration(
                    regressionTolerancePercentagePoints = percentage(
                        mutationRaw["regressionTolerancePercentagePoints"],
                        "mutation.regressionTolerancePercentagePoints"
                    ),
                    targetFamilies = families
                )
            )
        }

        private fun isAbsolutePath(path: String): Boolean =
            File(path).isAbsolute || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(path)
    }
}
