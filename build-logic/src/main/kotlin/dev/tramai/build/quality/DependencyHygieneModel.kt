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
    /** source set -> full import symbols found in that source set (see importSymbolOf). */
    @get:Input val importsBySourceSet: Map<String, Set<String>>,
)

/**
 * Static evidence extracted from a dependency's class-bearing jars.
 *
 * `classes` holds full top-level class names (org.foo.Bar); `packages` holds
 * the package of each such class. Exact-class matching means sibling artifacts
 * that share a package family (org.springframework.context vs org.springframework.jdbc)
 * can no longer justify each other (10.1c round-3 review).
 */
data class JarEvidence(
    val classes: Set<String>,
    val packages: Set<String>,
) {
    companion object {
        val EMPTY = JarEvidence(emptySet(), emptySet())
    }
}

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

    /**
     * Computes which of the unit's declared coordinates are statically used by
     * the imports of one source set.
     *
     * Ambiguity-aware (10.1c round-4): exact class/owner matches win outright;
     * the package fallback (Kotlin top-level functions/properties compile into
     * facade classes) only proves usage when the symbol's package belongs to
     * EXACTLY ONE declared coordinate — multiple candidates are ambiguous and
     * get no credit, so same-package sibling artifacts cannot justify each other.
     */
    private fun usedCoordinates(
        unit: DependencyUnitSpec,
        jarEvidence: Map<String, JarEvidence>,
        srcset: String,
    ): Set<String> {
        val imports = unit.importsBySourceSet[srcset].orEmpty()
        if (imports.isEmpty()) return emptySet()
        val candidates =
            unit.declared
                .filter { (configuration, _) -> requiredSourceSet(configuration) == srcset }
                .flatMap { (_, coordinates) -> coordinates }
                .distinct()
        val used = mutableSetOf<String>()
        for (symbol in imports) {
            val exact =
                candidates.filter { coordinate ->
                    val evidence = jarEvidence[coordinate] ?: return@filter false
                    evidence.classes.any { c -> symbol == c || symbol.startsWith(c + ".") }
                }
            if (exact.isNotEmpty()) {
                used += exact
                continue
            }
            val pkg =
                if (symbol.endsWith(".*")) {
                    symbol.dropLast(2)
                } else {
                    symbol.substringBeforeLast('.', "")
                }
            val packageMatches =
                candidates.filter { coordinate ->
                    val evidence = jarEvidence[coordinate] ?: return@filter false
                    pkg in evidence.packages || pkg in evidence.classes
                }
            if (packageMatches.size == 1) used += packageMatches.single()
            // size == 0 → no evidence anywhere; size > 1 → ambiguous → fail closed
        }
        return used
    }

    /**
     * @param jarEvidence coordinate -> class/package evidence from its jars.
     */
    fun evaluate(
        unit: DependencyUnitSpec,
        jarEvidence: Map<String, JarEvidence>,
        exemptions: List<Exemption>,
    ): Result {
        val violations = mutableListOf<String>()
        val info = mutableListOf<String>()
        val usedBySourceSet =
            unit.declared.keys
                .mapNotNull { requiredSourceSet(it) }
                .toSet()
                .associateWith { srcset -> usedCoordinates(unit, jarEvidence, srcset) }
        val context =
            EvalContext(
                unit,
                jarEvidence,
                exemptions.filter { it.module == unit.modulePath },
                usedBySourceSet,
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
        val jarEvidence: Map<String, JarEvidence>,
        val exemptionsForModule: List<Exemption>,
        val usedBySourceSet: Map<String, Set<String>>,
        val violations: MutableList<String>,
        val info: MutableList<String>,
    )

    private fun checkCoordinate(
        context: EvalContext,
        configuration: String,
        srcset: String,
        coordinate: String,
    ) {
        val evidence = context.jarEvidence[coordinate] ?: JarEvidence.EMPTY
        if (evidence.classes.isEmpty() && evidence.packages.isEmpty()) {
            // No class-bearing jars on the compile classpath for this coordinate —
            // a BOM/platform declaration or an unresolvable artifact. Not provable
            // either way; report as info, never flag.
            context.info.add(
                "no classes on classpath (BOM/platform?): " +
                    "${context.unit.modulePath} $configuration $coordinate",
            )
            return
        }
        val used = context.usedBySourceSet[srcset].orEmpty().contains(coordinate)
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
            val nowUsed = context.usedBySourceSet[srcset].orEmpty().contains(exemption.dependency)
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
