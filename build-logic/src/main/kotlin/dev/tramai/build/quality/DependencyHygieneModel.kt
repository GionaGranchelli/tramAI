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

    private fun staticallyUsed(
        prefixes: Set<String>,
        imports: Set<String>,
    ): Boolean =
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
        val context =
            EvalContext(
                unit,
                packagePrefixes,
                exemptions.filter { it.module == unit.modulePath },
                violations,
                info,
            )
        for ((configuration, coordinates) in unit.declared) {
            val srcset = requiredSourceSet(configuration) ?: continue
            coordinates.forEach { coordinate ->
                checkCoordinate(context, configuration, srcset, coordinate)
            }
        }
        checkStaleExemptions(context)
        return Result(violations.sorted(), info.sorted())
    }

    private class EvalContext(
        val unit: DependencyUnitSpec,
        val packagePrefixes: Map<String, Set<String>>,
        val exemptionsForModule: List<Exemption>,
        val violations: MutableList<String>,
        val info: MutableList<String>,
    )

    private fun checkCoordinate(
        context: EvalContext,
        configuration: String,
        srcset: String,
        coordinate: String,
    ) {
        val prefixes = context.packagePrefixes[coordinate].orEmpty()
        if (prefixes.isEmpty()) {
            // No class-bearing jars on the compile classpath for this coordinate —
            // a BOM/platform declaration or an unresolvable artifact. Not provable
            // either way; report as info, never flag.
            context.info.add(
                "no classes on classpath (BOM/platform?): " +
                    "${context.unit.modulePath} $configuration $coordinate",
            )
            return
        }
        val imports = context.unit.importsBySourceSet[srcset].orEmpty()
        val used = staticallyUsed(prefixes, imports)
        if (used) return
        val exempt =
            context.exemptionsForModule.any {
                it.configuration == configuration && it.dependency == coordinate
            }
        if (srcset == "main") {
            if (exempt) {
                context.info.add("exempted: ${context.unit.modulePath} $configuration $coordinate")
            } else {
                context.violations.add("unused main dependency: ${context.unit.modulePath} $configuration $coordinate")
            }
        } else {
            context.info.add(
                "unused $srcset-scope dependency (not gated): ${context.unit.modulePath} $configuration $coordinate",
            )
        }
    }

    private fun checkStaleExemptions(context: EvalContext) {
        val unit = context.unit
        // Stale exemptions: an exemption for a dependency that is no longer
        // declared, or that is now statically used, must fail (D7/D6).
        val declaredSet =
            unit.declared
                .flatMap { (configuration, coordinates) -> coordinates.map { "$configuration|$it" } }
                .toSet()
        context.exemptionsForModule.forEach { exemption ->
            val key = "${exemption.configuration}|${exemption.dependency}"
            if (key !in declaredSet) {
                context.violations.add(
                    "stale exemption: ${unit.modulePath} ${exemption.configuration} ${exemption.dependency} " +
                        "is not a declared dependency (${exemption.reason})",
                )
                return@forEach
            }
            val srcset = requiredSourceSet(exemption.configuration) ?: return@forEach
            val imports = unit.importsBySourceSet[srcset].orEmpty()
            val prefixes = context.packagePrefixes[exemption.dependency].orEmpty()
            val nowUsed = staticallyUsed(prefixes, imports)
            if (nowUsed) {
                context.violations.add(
                    "stale exemption: ${unit.modulePath} ${exemption.configuration} ${exemption.dependency} " +
                        "is now statically used (${exemption.reason})",
                )
            }
        }
    }
}

object DependencyExemptionsParser {
    /**
     * Strict, fail-closed parse (10.1c review BLOCKER 5). Structurally malformed
     * entries are REJECTED, never silently dropped: a missing field that "just
     * disappears" would disable an exemption the author believes is active.
     *
     * Rejects: null/blank YAML when a catalog file is present (caller passes the
     * file content), missing top-level `exemptions`, non-map entries, missing or
     * blank module/configuration/dependency/reason, and duplicate identities.
     */
    fun parse(yaml: String?): List<Exemption> {
        if (yaml == null) return emptyList() // no exemption catalog file — no exemptions
        if (yaml.isBlank()) {
            throw IllegalStateException(
                "config/dependency-hygiene/exemptions.yml is present but blank — the exemption catalog " +
                    "must either not exist or contain a valid `exemptions:` list.",
            )
        }
        return try {
            val root = Yaml().load<Any?>(yaml)
            if (root !is Map<*, *>) {
                error("exemptions.yml root must be a mapping with an `exemptions:` list")
            }
            @Suppress("UNCHECKED_CAST")
            val items =
                root["exemptions"] as? List<*> ?: error(
                    "exemptions.yml must contain an `exemptions:` list",
                )
            val seen = mutableSetOf<String>()
            items.map { rawItem ->
                if (rawItem !is Map<*, *>) {
                    error("exemptions.yml entry is not a mapping: $rawItem")
                }
                val module = rawItem["module"]?.toString()?.trim()
                val configuration = rawItem["configuration"]?.toString()?.trim()
                val dependency = rawItem["dependency"]?.toString()?.trim()
                val reason = rawItem["reason"]?.toString()?.trim()
                if (module.isNullOrBlank()) error("exemptions.yml entry missing `module`: $rawItem")
                if (configuration.isNullOrBlank()) {
                    error("exemptions.yml entry missing `configuration`: $rawItem")
                }
                if (dependency.isNullOrBlank()) {
                    error("exemptions.yml entry missing `dependency`: $rawItem")
                }
                if (reason.isNullOrBlank()) {
                    error("exemptions.yml entry missing `reason` (required rationale): $rawItem")
                }
                val key = "$module|$configuration|$dependency"
                if (!seen.add(key)) {
                    error("duplicate exemption identity in exemptions.yml: $key")
                }
                Exemption(module, configuration, dependency, reason)
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: org.yaml.snakeyaml.error.YAMLException) {
            // Malformed exemption catalog must fail closed — never silently ignore.
            error("config/dependency-hygiene/exemptions.yml is malformed: ${e.message}")
        }
    }
}
