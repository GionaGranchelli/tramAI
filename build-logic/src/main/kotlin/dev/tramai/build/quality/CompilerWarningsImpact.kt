package dev.tramai.build.quality

/**
 * P3-A: fail-closed impact selection for verifyCompilerWarnings.
 *
 * Replaces the round-4 binary rule ("any build-logic/global change => verify
 * everything") with a classifier over the git diff plus the real module
 * dependency graph. The contract is unchanged: verify every module whose
 * compile-warning inventory CAN have changed, never fewer; on any uncertain
 * classification fall back to FULL.
 *
 * What changes a module's warning inventory:
 *  - its own Kotlin/Java sources or its build script (dependencies/args),
 *  - the compiled classes of any module it depends on (a newly @Deprecated
 *    API in :tramai-core surfaces as a warning in every consumer's compile),
 *  - global compiler configuration (Kotlin/Gradle version, compiler args,
 *    jvmTarget, version catalog, BOM/module-catalog constraints),
 *  - the gate's own semantics (CompilerWarnings code).
 *
 * Pure scanners/verifiers in build-logic (mutation measurement, coverage,
 * static analysis, release, docs...) do NOT configure module compilation;
 * a change confined to them leaves every module inventory identical, so the
 * gate passes with zero standalone compiles.
 */
internal sealed interface CompilerWarningsImpact {
    /** No module's warning inventory can have changed — nothing to verify. */
    data object None : CompilerWarningsImpact

    /** Exhaustive verification of every compile unit. */
    data object Full : CompilerWarningsImpact

    /** The changed modules plus their transitive dependents. */
    data class Modules(
        val modulePaths: Set<String>,
    ) : CompilerWarningsImpact
}

/**
 * Classifies the name-only diff between base and HEAD.
 *
 * @param diff git diff --name-only output
 * @param dependents reverse dependency edges: module path -> modules whose
 *   compile classpath includes it (production + test scopes). If A changes,
 *   every B in dependents[A] (and their dependents) must be recompiled because
 *   B compiles against A's new classes.
 */
internal fun resolveCompilerWarningsImpact(
    diff: String,
    dependents: Map<String, Set<String>>,
): CompilerWarningsImpact {
    var full = false
    val roots = mutableSetOf<String>()
    for (line in diff.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        when {
            isGlobalCompileConfig(trimmed) -> {
                full = true
            }

            isCompilerAffectingBuildLogic(trimmed) -> {
                full = true
            }

            // build-logic/ files that are NOT compiler-affecting (scanners,
            // verifiers, gates other than CompilerWarnings) are inert — they
            // never configure module compilation. Must be excluded before the
            // module matcher, which would otherwise map them to ":build-logic".
            trimmed.startsWith("build-logic/") -> {
                Unit
            }

            isModuleChange(trimmed) -> {
                moduleOf(trimmed)?.let { roots += it }
            }

            else -> {
                // docs, scripts, workflow files, non-compile config, plain
                // verifier code: no module inventory can change.
            }
        }
    }
    return when {
        full -> CompilerWarningsImpact.Full
        roots.isEmpty() -> CompilerWarningsImpact.None
        else -> CompilerWarningsImpact.Modules(affectedModules(roots, dependents))
    }
}

/** Roots plus their transitive dependents (reverse closure over the graph). */
internal fun affectedModules(
    roots: Set<String>,
    dependents: Map<String, Set<String>>,
): Set<String> {
    val affected = roots.toMutableSet()
    val queue = ArrayDeque(roots)
    val seen = roots.toMutableSet()
    while (queue.isNotEmpty()) {
        val module = queue.removeFirst()
        for (dependent in dependents[module].orEmpty()) {
            if (seen.add(dependent)) {
                affected += dependent
                queue.addLast(dependent)
            }
        }
    }
    return affected
}

/**
 * P0 non-vacuity guard: maps an impact (+ baseline edit) to the module set the
 * gate must verify.
 *
 * A Modules impact whose paths do not correspond to collected compile units —
 * or which selects nothing at all — falls back to FULL. A classification or
 * graph bug must never yield a zero-unit verification that passes vacuously.
 */
internal fun resolveVerifyModules(
    impact: CompilerWarningsImpact,
    baselineChanged: Boolean,
    allModulePaths: Set<String>,
): Set<String> {
    if (baselineChanged || impact is CompilerWarningsImpact.Full) return allModulePaths
    val requested = (impact as? CompilerWarningsImpact.Modules)?.modulePaths.orEmpty()
    val unknown = requested - allModulePaths
    return if (requested.isEmpty() || unknown.isNotEmpty()) {
        allModulePaths
    } else {
        requested
    }
}

/**
 * Global build/compiler configuration: a change here can alter every module's
 * compile classpath, args, jvm target, or the compiler itself.
 */
internal fun isGlobalCompileConfig(line: String): Boolean =
    line == "gradle/libs.versions.toml" ||
        line == "gradle.properties" ||
        line == "settings.gradle.kts" ||
        line == "settings.gradle" ||
        line == "build.gradle.kts" ||
        line == "build.gradle" ||
        line == "gradle/wrapper/gradle-wrapper.properties" ||
        line.startsWith("gradle/wrapper/") ||
        // BOM constraints (TramaiJavaPlatformPlugin api constraints) are derived
        // from module-catalog.yml through quality.ModuleManifest — a catalog
        // change alters the BOM surface every module resolves against.
        line == "config/quality/module-catalog.yml"

/**
 * Build-logic code that CAN change module compilation.
 *
 * Verified coupling surface (import scan over conventions/ + publishing/):
 *  - conventions package — the only plugins applied per-module (compile args,
 *    jvm target, toolchains, dependencies),
 *  - publishing package — applied to every subproject from the root subprojects
 *    block; publication metadata derives from the module catalog,
 *  - quality/ModuleManifest.kt + ModuleCatalog.kt — imported by conventions
 *    (TramaiJavaPlatformPlugin) and publishing for BOM/publication module
 *    sets; a change can alter the constraint surface modules resolve against,
 *  - quality/CompilerWarnings* PRODUCTION sources only — the gate itself
 *    (parser/compiler pin); src/test files and fixture build scripts do NOT
 *    configure module compilation,
 *  - build-logic's own build files (plugin declarations / classpath), matched
 *    by exact path so test fixtures named *.gradle.kts stay inert.
 *
 * Every other build-logic source (scanners, verifiers, maintainability,
 * static analysis, dependency hygiene, release, docs, supplychain,
 * sovereign) is applied at the ROOT only and never configures module
 * compilation — a change there leaves every module inventory identical.
 *
 * Fail-closed note: if a future convention/publishing plugin starts importing
 * a helper from another package, this list MUST grow to keep the gate sound —
 * the wiring contract test asserts exactly this surface.
 */
internal fun isCompilerAffectingBuildLogic(line: String): Boolean {
    if (!line.startsWith("build-logic/")) return false
    return line == "build-logic/build.gradle.kts" ||
        line == "build-logic/build.gradle" ||
        line == "build-logic/settings.gradle.kts" ||
        line == "build-logic/settings.gradle" ||
        line == "build-logic/gradle.properties" ||
        line.startsWith("build-logic/src/main/kotlin/dev/tramai/build/conventions/") ||
        line.startsWith("build-logic/src/main/kotlin/dev/tramai/build/publishing/") ||
        line == "build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleManifest.kt" ||
        line == "build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleCatalog.kt" ||
        line.startsWith("build-logic/src/main/kotlin/dev/tramai/build/quality/CompilerWarnings")
}

/** A .kt/.java/module build-script change inside a module directory. */
internal fun isModuleChange(line: String): Boolean =
    line.endsWith(".kt") ||
        line.endsWith(".java") ||
        line.endsWith("build.gradle.kts") ||
        line.endsWith("build.gradle")

internal fun moduleOf(line: String): String? {
    val parts = line.split("/")
    return when {
        parts.size >= MIN_DELTA_SEGMENTS && parts[0] == "examples" -> ":examples:${parts[1]}"
        parts.size >= 2 -> ":" + parts[0]
        else -> null
    }
}

private const val MIN_DELTA_SEGMENTS = 3
