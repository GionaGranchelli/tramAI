package dev.tramai.build.quality

import org.gradle.api.Project
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Semantic public-API compatibility closure for Epic 10.2 (Track B3).
 *
 * Two contracts, both consuming BCV dump text (never reimplementing signature
 * analysis):
 *
 *  Contract 1 — CURRENT SOURCE ↔ CURRENT COMMITTED DUMP.
 *    generated (apiBuild output) vs committed api/<module>.api must match;
 *    a mismatch means the committed dump does not represent the source.
 *
 *  Contract 2 — BASE-BRANCH DUMP ↔ CURRENT DUMP. Drives stability policy:
 *    stable        → ANY base→current change (breaking or additive) FAILs
 *    preview/exp.  → change requires an EXACT hash-bound migration entry
 *    internal/excl → no compatibility gate
 *
 * Plus a stability-inversion leak scan: a stronger module's dump may not
 * reference types owned by a weaker module (stable→preview, preview→
 * experimental, ...). New inversions fail; pre-existing ones warn.
 */
data class ApiDumpEvidence(
    val generated: Map<String, String>,
    val committed: Map<String, String>,
    val base: Map<String, String>,
)

data class ApiMigrationEntry(
    val module: String,
    val fromSha256: String,
    val toSha256: String,
    val targetVersion: String,
    val rationale: String,
    val migration: String,
) {
    /** Exact-transition authorization: both hashes AND the target version must match. */
    fun authorizes(module: String, baseContent: String, committedContent: String, projectVersion: String): Boolean =
        this.module == module &&
            this.targetVersion == projectVersion &&
            this.fromSha256 == ApiCompatibilityVerifier.sha256(baseContent) &&
            this.toSha256 == ApiCompatibilityVerifier.sha256(committedContent)
}

class ApiCompatibilityVerifier(
    private val catalogModules: Map<String, ModuleCatalog.ModuleEntry>,
    private val projectVersion: String,
) {

    fun verify(evidence: ApiDumpEvidence, migrations: List<ApiMigrationEntry>): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        verifyContract1(evidence, diagnostics)
        verifyContract2(evidence, migrations, diagnostics)
        verifyRegistryHygiene(evidence, migrations, diagnostics)
        verifyStabilityInversions(evidence, diagnostics)
        return diagnostics
    }

    // ── Contract 1: source ↔ committed dump ────────────────────────────────

    private fun verifyContract1(evidence: ApiDumpEvidence, diagnostics: MutableList<VerificationDiagnostic>) {
        evidence.committed.keys.intersect(evidence.generated.keys).sorted().forEach { module ->
            val generated = evidence.generated.getValue(module)
            val committed = evidence.committed.getValue(module)
            if (generated != committed) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Contract-1: committed API dump for '$module' does not represent current source " +
                        "(generated ${generated.length} bytes vs committed ${committed.length} bytes); run apiDump and commit the dump",
                    modulePath = module,
                    baselineValue = sha256(committed),
                    currentValue = sha256(generated)
                )
            }
        }
    }

    // ── Contract 2: base-branch dump ↔ current dump, stability policy ──────

    private fun verifyContract2(
        evidence: ApiDumpEvidence,
        migrations: List<ApiMigrationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        evidence.committed.keys.sorted().forEach { module ->
            val stability = stabilityOf(module) ?: return@forEach
            if (!applicable(stability)) return@forEach
            val committed = evidence.committed.getValue(module)

            if (module !in evidence.base) {
                // Fail-closed: no base dump means no compatibility claim is possible.
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Contract-2: base-branch API dump for '$module' is unavailable; " +
                        "cannot certify API compatibility (resolve -PchangePolicyBase or fetch origin/master)",
                    modulePath = module
                )
                return@forEach
            }
            val base = evidence.base.getValue(module)
            if (base == committed) return@forEach

            when (stability) {
                "stable" -> diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Stable API module '$module' changed (breaking or additive); stable API is frozen for $projectVersion. " +
                        "Migration entries cannot authorize stable changes.",
                    modulePath = module,
                    baselineValue = sha256(base),
                    currentValue = sha256(committed)
                )

                "preview", "experimental" -> {
                    val authorized = migrations.any { it.authorizes(module, base, committed, projectVersion) }
                    if (!authorized) {
                        diagnostics += VerificationDiagnostic.failure(
                            DiagnosticCode.API_COMPATIBILITY_FAILED,
                            "API module '$module' changed without an exact hash-bound migration entry; " +
                                "add an entry binding fromSha256=${sha256(base)} toSha256=${sha256(committed)} " +
                                "for targetVersion=$projectVersion in config/quality/api-migrations.yml",
                            modulePath = module,
                            baselineValue = sha256(base),
                            currentValue = sha256(committed)
                        )
                    }
                }

                // internal / excluded: no compatibility gate
            }
        }
    }

    // ── Migration registry hygiene ──────────────────────────────────────────

    private fun verifyRegistryHygiene(
        evidence: ApiDumpEvidence,
        migrations: List<ApiMigrationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        // duplicates: same (module, fromSha256, toSha256) more than once
        migrations.groupBy { Triple(it.module, it.fromSha256, it.toSha256) }
            .filterValues { it.size > 1 }
            .keys
            .sortedBy { it.first }
            .forEach { (module, from, to) ->
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Duplicate migration entries for '$module' ($from → $to); each transition needs exactly one entry",
                    modulePath = module,
                    baselineValue = from,
                    currentValue = to
                )
            }

        // orphans / wrong hashes / target version: entry must match a real transition
        migrations.forEach { entry ->
            val realTransition = actualTransition(evidence, entry.module)
            val matches = realTransition != null &&
                realTransition.first == entry.fromSha256 &&
                realTransition.second == entry.toSha256 &&
                entry.targetVersion == projectVersion
            if (!matches) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Migration entry for '${entry.module}' is stale, orphaned, or hash-mismatched " +
                        "(declared ${entry.fromSha256} → ${entry.toSha256} for ${entry.targetVersion}, " +
                        "actual ${realTransition?.first ?: "<no change>"} → ${realTransition?.second ?: "<no change>"} " +
                        "for $projectVersion)",
                    modulePath = entry.module,
                    baselineValue = entry.fromSha256,
                    currentValue = entry.toSha256
                )
            }
        }
    }

    private fun actualTransition(evidence: ApiDumpEvidence, module: String): Pair<String, String>? {
        val base = evidence.base[module] ?: return null
        val committed = evidence.committed[module] ?: return null
        if (base == committed) return null
        return sha256(base) to sha256(committed)
    }

    // ── Stability-inversion leak scan ───────────────────────────────────────

    private fun verifyStabilityInversions(evidence: ApiDumpEvidence, diagnostics: MutableList<VerificationDiagnostic>) {
        val ownership = ownershipByModule(evidence.committed)
        val descriptorToModule = ownership.entries
            .flatMap { (module, descriptors) -> descriptors.map { it to module } }
            .toMap()

        evidence.committed.keys.sorted().forEach { module ->
            val strength = strengthOf(module) ?: return@forEach
            val current = evidence.committed.getValue(module)
            val base = evidence.base[module]
            descriptorToModule.forEach { (descriptor, owner) ->
                if (owner == module) return@forEach
                val ownerStrength = strengthOf(owner) ?: return@forEach
                if (ownerStrength >= strength) return@forEach
                if (!current.contains(descriptor)) return@forEach

                val preExisting = base?.contains(descriptor) == true
                if (preExisting) {
                    diagnostics += VerificationDiagnostic(
                        code = DiagnosticCode.API_COMPATIBILITY_FAILED,
                        severity = DiagnosticSeverity.WARNING,
                        message = "Pre-existing stability inversion: '${module}' (${stabilityOf(module)}) references " +
                            "'$descriptor' owned by '${owner}' (${stabilityOf(owner)}); stronger APIs may only expose " +
                            "equally-strong or stronger types",
                        modulePath = module,
                        baselineValue = descriptor,
                        currentValue = owner
                    )
                } else {
                    diagnostics += VerificationDiagnostic.failure(
                        DiagnosticCode.API_COMPATIBILITY_FAILED,
                        "Stability inversion: '${module}' (${stabilityOf(module)}) now references '$descriptor' " +
                            "owned by '${owner}' (${stabilityOf(owner)}); stronger APIs may only expose " +
                            "equally-strong or stronger TramAI types",
                        modulePath = module,
                        baselineValue = descriptor,
                        currentValue = owner
                    )
                }
            }
        }
    }

    /** Extract owned public class descriptors from each module's committed dump. */
    private fun ownershipByModule(committed: Map<String, String>): Map<String, Set<String>> =
        committed.mapValues { (_, content) -> ownedDescriptors(content) }

    /** Lines of the BCV dump form `public ... class <descriptor> ...` yield owned descriptors. */
    private fun ownedDescriptors(dumpContent: String): Set<String> =
        dumpContent.lineSequence()
            .mapNotNull { line ->
                val match = OWNED_CLASS_REGEX.find(line) ?: return@mapNotNull null
                match.groupValues[1]
            }
            .toSet()

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun stabilityOf(module: String): String? = catalogModules[module]?.apiStability?.yaml

    private fun strengthOf(module: String): Int? =
        when (catalogModules[module]?.apiStability) {
            ModuleApiStability.STABLE -> 4
            ModuleApiStability.PREVIEW -> 3
            ModuleApiStability.EXPERIMENTAL -> 2
            ModuleApiStability.INTERNAL, ModuleApiStability.EXCLUDED -> 1
            null -> null
        }

    private fun applicable(stability: String): Boolean = stability in setOf("stable", "preview", "experimental")

    companion object {
        private val OWNED_CLASS_REGEX = Regex("""\bclass\s+(dev/tramai/[\w/$]+)""")

        fun sha256(content: String): String =
            MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Guard for consumer-compile proofs: real non-empty sources AND real compiled classes. */
object ConsumerCompatibilityGuard {

    fun validate(sourceDir: File, classesDir: File, extension: String): List<VerificationDiagnostic> =
        validateSources(sourceDir, extension) + validateClasses(classesDir)

    fun validateSources(sourceDir: File, extension: String): List<VerificationDiagnostic> {
        val fileExtension = if (extension == "kotlin") "kt" else extension
        val sources = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == fileExtension }
            .toList()
        return if (sources.isEmpty()) {
            listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Consumer fixture '$sourceDir' has no .$extension sources; " +
                        "an empty fixture proves nothing (zero-source trap)"
                )
            )
        } else {
            emptyList()
        }
    }

    fun validateClasses(classesDir: File): List<VerificationDiagnostic> {
        val classes = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .toList()
        return if (classes.isEmpty()) {
            listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.API_COMPATIBILITY_FAILED,
                    "Consumer fixture produced no compiled classes in '$classesDir'; " +
                        "compilation must actually succeed"
                )
            )
        } else {
            emptyList()
        }
    }
}

/**
 * Reads the evidence inputs for the api compatibility gate:
 *  - committed dumps: api/<module>.api next to each module's source root
 *  - generated dumps: build/api/<module>.api produced by BCV apiBuild
 *  - base dumps: git show <baseRef>:<path> for the base-branch comparison
 *  - migration registry: config/quality/api-migrations.yml
 */
object ApiCompatibilityEvidenceReader {

    /** Committed dump path convention: <moduleDir>/api/<moduleName>.api. */
    fun committedDumpPath(moduleDir: File, moduleName: String): File =
        File(moduleDir, "api/$moduleName.api")

    /** Generated dump path convention: <moduleBuildDir>/api/<moduleName>.api. */
    fun generatedDumpPath(buildDir: File, moduleName: String): File =
        File(buildDir, "api/$moduleName.api")

    /**
     * Read committed dumps for every Gradle subproject with an apiCheck task.
     * Keys are project paths (e.g. ":tramai-core"); values are dump contents.
     * Modules without a committed dump are omitted (tramai-bom: not applicable).
     */
    fun readCommittedDumps(rootDir: File, projectPaths: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        projectPaths.sorted().forEach { path ->
            val name = path.substringAfterLast(':')
            val dir = File(rootDir, path.removePrefix(":").replace(':', '/'))
            val dump = committedDumpPath(dir, name)
            if (dump.isFile) result[path] = dump.readText(Charsets.UTF_8)
        }
        return result
    }

    /** Read apiBuild-generated dumps for subprojects that already produced them. */
    fun readGeneratedDumps(project: Project): Map<String, String> {
        val result = linkedMapOf<String, String>()
        project.allprojects.filter { it != project && it.buildFile.exists() }.sortedBy { it.path }.forEach { sub ->
            val dump = generatedDumpPath(
                sub.layout.buildDirectory.get().asFile,
                sub.name,
            )
            if (dump.isFile) result[sub.path] = dump.readText(Charsets.UTF_8)
        }
        return result
    }

    /**
     * Read base-branch dumps via `git show <baseRef>:<repo-relative-path>`.
     * A missing file at base (new module) yields an empty string — every added
     * line then counts as a change subject to stability policy. A git failure
     * (unresolvable ref, no repo) throws → the gate's fail-closed evidence
     * collector converts it into typed FAILURE diagnostics.
     */
    fun readBaseDumps(rootDir: File, baseRef: String, modulePaths: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        modulePaths.sorted().forEach { path ->
            val name = path.substringAfterLast(':')
            val relDir = path.removePrefix(":").replace(':', '/')
            val relDumpPath = "$relDir/api/$name.api"
            val content = gitShow(rootDir, "$baseRef:$relDumpPath")
            result[path] = content
        }
        return result
    }

    private fun gitShow(rootDir: File, spec: String): String {
        val process = ProcessBuilder("git", "show", spec)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            // Path absent at base = new module → treat as empty base dump.
            if (output.contains("exists on disk, but not in")) {
                return ""
            }
            throw IllegalStateException("git show $spec failed (exit $exit): ${output.take(200)}")
        }
        return output
    }

    /** Parse config/quality/api-migrations.yml into typed entries. Empty file → empty list. */
    fun parseMigrations(file: File): List<ApiMigrationEntry> {
        if (!file.isFile) return emptyList()
        val raw = FileInputStream(file).use { input ->
            val options = LoaderOptions().apply { maxAliasesForCollections = 200 }
            Yaml(SafeConstructor(options)).load<Map<String, Any>>(input)
        }
        val entries = raw?.get("migrations") as? List<*> ?: return emptyList()
        return entries.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            ApiMigrationEntry(
                module = entry["module"]?.toString() ?: return@mapNotNull null,
                fromSha256 = entry["fromSha256"]?.toString() ?: "",
                toSha256 = entry["toSha256"]?.toString() ?: "",
                targetVersion = entry["targetVersion"]?.toString() ?: "",
                rationale = entry["rationale"]?.toString().orEmpty(),
                migration = entry["migration"]?.toString().orEmpty(),
            )
        }
    }
}
