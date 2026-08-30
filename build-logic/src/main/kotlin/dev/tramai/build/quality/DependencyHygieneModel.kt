package dev.tramai.build.quality

import org.gradle.api.tasks.Input
import org.yaml.snakeyaml.Yaml

/**
 * Epic 10.1c dependency-hygiene model.
 *
 * Invariant: no unused direct dependency declared for main compilation/runtime
 * semantics, except explicitly documented non-static usages (exemption catalog).
 * Deliberately narrow — NOT a general dependency analyser.
 */
data class DeclaredDependency(
    val module: String,
    val configuration: String,
    val coordinate: String, // group:artifact (versions ignored for identity)
)

data class Exemption(
    val module: String,
    val configuration: String,
    val dependency: String,
    val reason: String,
)

/** Per-module analysis unit captured at configuration time (CC-safe strings only). */
data class DependencyUnitSpec(
    @get:Input val modulePath: String,
    /** configuration name -> declared external coordinates (group:artifact). */
    @get:Input val declared: Map<String, List<String>>,
    /** source set -> 2-segment import prefixes found in that source set. */
    @get:Input val importsBySourceSet: Map<String, Set<String>>,
)

object DependencyUsageEvaluator {

    private fun requiredSourceSet(configuration: String): String? =
        when (configuration) {
            "api", "implementation", "compileOnly", "runtimeOnly" -> "main"
            "testImplementation", "testCompileOnly", "testRuntimeOnly" -> "test"
            "testFixturesApi", "testFixturesImplementation" -> "testFixtures"
            else -> null // unknown configurations are not gated
        }

    data class Result(
        val violations: List<String>,
        val info: List<String>,
    )

    private fun staticallyUsed(prefixes: Set<String>, imports: Set<String>): Boolean =
        prefixes.any { p ->
            imports.any { i ->
                // Both sides are 2-segment prefixes (import 'com.example.api.X' → 'com.example';
                // jar package com.example.api.Y → 'com.example'). Match when the import is at
                // least as specific as the package prefix — a BROADER import (e.g. 'kotlinx'
                // from kotlinx.serialization) must NOT count as usage of kotlinx-coroutines.
                i == p || i.startsWith(p + ".")
            }
        }

    /**
     * @param packagePrefixes coordinate -> 2-segment package prefixes from its jars.
     */
    fun evaluate(
        unit: DependencyUnitSpec,
        packagePrefixes: Map<String, Set<String>>,
        exemptions: List<Exemption>,
    ): Result {
        val violations = mutableListOf<String>()
        val info = mutableListOf<String>()
        val declaredByConfiguration = unit.declared
        val exemptionsForModule = exemptions.filter { it.module == unit.modulePath }

        for ((configuration, coordinates) in declaredByConfiguration) {
            val srcset = requiredSourceSet(configuration) ?: continue
            val imports = unit.importsBySourceSet[srcset].orEmpty()
            for (coordinate in coordinates) {
                val prefixes = packagePrefixes[coordinate].orEmpty()
                if (prefixes.isEmpty()) {
                    // No class-bearing jars on the compile classpath for this
                    // coordinate — a BOM/platform declaration or an unresolvable
                    // artifact. Not provable either way; report as info, never flag.
                    info.add("no classes on classpath (BOM/platform?): ${unit.modulePath} $configuration $coordinate")
                    continue
                }
                val used = staticallyUsed(prefixes, imports)
                if (!used) {
                    val exempt =
                        exemptionsForModule.any {
                            it.configuration == configuration && it.dependency == coordinate
                        }
                    if (srcset == "main") {
                        if (exempt) {
                            info.add("exempted: ${unit.modulePath} $configuration $coordinate")
                        } else {
                            violations.add(
                                "unused main dependency: ${unit.modulePath} $configuration $coordinate",
                            )
                        }
                    } else {
                        info.add(
                            "unused ${srcset}-scope dependency (not gated): " +
                                "${unit.modulePath} $configuration $coordinate",
                        )
                    }
                }
            }
        }

        // Stale exemptions: an exemption for a dependency that is no longer
        // declared, or that is now statically used, must fail (D7/D6).
        val declaredSet =
            declaredByConfiguration
                .flatMap { (configuration, coordinates) ->
                    coordinates.map { "$configuration|$it" }
                }
                .toSet()
        for (exemption in exemptionsForModule) {
            val key = "${exemption.configuration}|${exemption.dependency}"
            if (key !in declaredSet) {
                violations.add(
                    "stale exemption: ${unit.modulePath} ${exemption.configuration} ${exemption.dependency} " +
                        "is not a declared dependency (${exemption.reason})",
                )
                continue
            }
            val srcset = requiredSourceSet(exemption.configuration) ?: continue
            val imports = unit.importsBySourceSet[srcset].orEmpty()
            val prefixes = packagePrefixes[exemption.dependency].orEmpty()
            val nowUsed = staticallyUsed(prefixes, imports)
            if (nowUsed) {
                violations.add(
                    "stale exemption: ${unit.modulePath} ${exemption.configuration} ${exemption.dependency} " +
                        "is now statically used (${exemption.reason})",
                )
            }
        }

        return Result(
            violations = violations.sorted(),
            info = info.sorted(),
        )
    }
}

object DependencyExemptionsParser {

    fun parse(yaml: String?): List<Exemption> {
        if (yaml == null || yaml.isBlank()) return emptyList()
        return try {
            val root = Yaml().load<Map<String, Any?>>(yaml)
            @Suppress("UNCHECKED_CAST")
            val items = root?.get("exemptions") as? List<Map<String, Any?>> ?: return emptyList()
            items.mapNotNull { item ->
                val module = item["module"]?.toString() ?: return@mapNotNull null
                val configuration = item["configuration"]?.toString() ?: return@mapNotNull null
                val dependency = item["dependency"]?.toString() ?: return@mapNotNull null
                val reason = item["reason"]?.toString() ?: ""
                Exemption(module, configuration, dependency, reason)
            }
        } catch (e: Exception) {
            // Malformed exemption catalog must fail closed — never silently ignore.
            throw IllegalStateException("config/dependency-hygiene/exemptions.yml is malformed: ${e.message}")
        }
    }
}
