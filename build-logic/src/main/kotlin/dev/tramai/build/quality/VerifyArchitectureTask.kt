package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Typed 0.6.0 architecture gate (Epic 9.2d-a3c3). Same semantics as the
 * legacy doLast gate in MaintainabilityBaselinePlugin, but executes in
 * directory mode with declared inputs as the sole execution authority:
 *
 *  - baseline verification runs the same [BaselineVerifier] with
 *    [DeclaredBaselineInputs] (a3c2 machinery, including the configuration-time
 *    [DependencyGraphSnapshot] for cycle/forbidden-edge/policy enforcement)
 *  - fail-soft per-project dependency probes arrive as declared [dependencyProbeFiles]
 *  - module-manifest, enrollment and api-compatibility evidence arrive as
 *    configuration-time snapshots / declared file inputs
 *  - the report is written BEFORE the terminal exception (fail-closed contract)
 *
 * No Task.project access at execution time (a3 discipline).
 */
@Suppress("LongParameterList") // named-arg task; keeps the wiring block readable
abstract class VerifyArchitectureTask : DefaultTask() {
    private companion object {
        val publishingTopologyCodes =
            setOf(
                DiagnosticCode.MODULE_CATALOG_BOM_DRIFT,
                DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT,
            )
        val baselineCheckIds =
            setOf(
                "module-manifest",
                "dependency-boundaries",
                "dependency-cycles",
                "global-state",
                "api-architecture",
                "protocol-catalog",
                "cancellation-safety",
            )
    }

    @get:InputFile
    @get:Optional
    abstract val committedBaselineFile: RegularFileProperty

    @get:InputFile
    abstract val deviationsFile: RegularFileProperty

    @get:InputFile
    abstract val moduleCatalogFile: RegularFileProperty

    @get:InputFile
    abstract val moduleBoundariesFile: RegularFileProperty

    /** Settings script the root is derived from (same rule as a3c2). */
    @get:InputFile
    abstract val settingsFile: RegularFileProperty

    /** Project dependency graph captured at configuration time (a3c2 machinery). */
    @get:Input
    abstract val dependencyGraph: Property<DependencyGraphSnapshot>

    /** Fail-soft per-project probe outputs (architectureDependencyProbe). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyProbeFiles: ConfigurableFileCollection

    /** apiCheck module paths — configuration-time snapshot (a3c1 discipline). */
    @get:Input
    abstract val apiValidationModules: ListProperty<String>

    /** Actual Gradle project paths (manifest verification) — config-time snapshot. */
    @get:Input
    abstract val actualProjectPaths: ListProperty<String>

    /** Configured publishable module paths (manifest verification) — config-time snapshot. */
    @get:Input
    abstract val publishedModulePaths: ListProperty<String>

    /** tramai-bom api constraint module paths (manifest verification) — config-time snapshot. */
    @get:Input
    abstract val bomModulePaths: ListProperty<String>

    /** Enrollment contract XML results (architectureContractEnrollmentTest junitXml output). */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val enrollmentResultsDir: ConfigurableFileCollection

    /**
     * Generated BCV dumps (apiBuild task outputs) — the EXACT files the
     * action reads. Each entry in [generatedApiDumpOwners] is the module path
     * of the file at the same index in [generatedApiDumpFiles]; the action
     * zips them into the module→content map. No conventional-path rediscovery
     * (a3c3 P1: declared output must be the execution authority, not
     * <module>/build/api/<name>.api).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedApiDumpFiles: ConfigurableFileCollection

    /** Module path per generated dump file, same order as [generatedApiDumpFiles]. */
    @get:Input
    abstract val generatedApiDumpOwners: ListProperty<String>

    /** Consumer compile-proof markers (fail-soft producer outputs). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val consumerMarkers: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    abstract val apiMigrationsFile: RegularFileProperty

    /** Base-branch ref for `git show` base dumps (changePolicyBase or origin/master). */
    @get:Input
    abstract val baseRef: Property<String>

    @get:Input
    abstract val projectVersion: Property<String>

    /** Measured repository tree (source, build scripts, committed api dumps, docs). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTree: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @get:OutputFile
    abstract val architectureReportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val rootDir = settingsFile.get().asFile.parentFile
        val catalog = ModuleCatalog(moduleCatalogFile.get().asFile)
        val catalogResult = catalog.parse()
        val ctx = MeasurementContext.fromDirectory(rootDir, catalog)
        val architectureDiagnostics =
            ArchitectureReportAggregator.checkIds
                .associateWith { mutableListOf<VerificationDiagnostic>() }

        collectEvidence("baseline verification", baselineCheckIds, architectureDiagnostics) {
            val probeFiles = dependencyProbeFiles.files.sortedBy { it.path }
            val evidence = readDependencyProbeEvidence(probeFiles)
            if (evidence.failures.isNotEmpty()) {
                val message = "Dependency evidence unavailable: ${evidence.failures.joinToString("; ")}"
                baselineCheckIds.forEach { checkId ->
                    architectureDiagnostics.getValue(checkId) +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                            message,
                        )
                }
            } else {
                val resolvedDependenciesFile = File(reportDir.get().asFile, "resolved-dependencies.json")
                reportDir.get().asFile.mkdirs()
                ReportNormalizer.writeJson(
                    BaselineGenerator.sortResolvedDependencies(evidence.resolvedRecords),
                    resolvedDependenciesFile,
                )
                val generator =
                    BaselineGenerator(
                        ctx = ctx,
                        outputDir = reportDir.get().asFile,
                        writeRepositoryArtifacts = false,
                    )
                BaselineVerifier(
                    generator = generator,
                    ctx = ctx,
                    reportDir = reportDir.get().asFile,
                    declaredInputs =
                        DeclaredBaselineInputs(
                            committedBaselineFile = committedBaselineFile.orNull?.asFile,
                            resolvedDependenciesFile = resolvedDependenciesFile,
                            apiValidationModules = apiValidationModules.get().toSet(),
                            deviationsFile = deviationsFile.get().asFile,
                            moduleCatalogFile = moduleCatalogFile.get().asFile,
                            moduleBoundariesFile = moduleBoundariesFile.get().asFile,
                            dependencyGraph = dependencyGraph.get(),
                        ),
                ).verify()
                routeBaselineDiagnostics(
                    readBaselineDiagnostics(File(reportDir.get().asFile, "verification-report.json")),
                    architectureDiagnostics,
                    baselineCheckIds,
                    ::baselineCheckFor,
                )
            }
        }

        collectEvidence(
            "module manifest verification",
            setOf("module-manifest", "publishing-topology"),
            architectureDiagnostics,
        ) {
            val actualProjects = actualProjectPaths.get().toSet()
            val actualPublished = publishedModulePaths.get().toSet()
            val actualBom = bomModulePaths.get().toSet()
            addManifestDiagnostics(
                ModuleManifestVerifier.verify(catalogResult.modules, actualProjects, actualPublished, actualBom),
                architectureDiagnostics,
            )
        }

        collectEvidence(
            "enrollment contract verification",
            setOf("provider-contracts", "store-contracts"),
            architectureDiagnostics,
        ) {
            addEnrollmentDiagnostics(enrollmentResultsDir.files, architectureDiagnostics)
        }

        collectEvidence("api compatibility verification", setOf("api-architecture"), architectureDiagnostics) {
            addApiCompatibilityDiagnostics(rootDir, catalogResult.modules, architectureDiagnostics)
        }

        val report = ArchitectureReportAggregator.aggregate(architectureDiagnostics)
        val reportFile = architectureReportFile.get().asFile
        // Report is written BEFORE the terminal exception — failure must produce evidence.
        ArchitectureReportJson.write(report, reportFile, rootDir)
        if (report.status == ArchitectureCheckStatus.FAIL) {
            throw GradleException(
                "0.6.0 architecture verification FAILED: " +
                    report.checks.filter { it.status == ArchitectureCheckStatus.FAIL }.joinToString { it.id } +
                    " — see ${reportFile.path}",
            )
        }
        println("0.6.0 architecture verification PASSED — see ${reportFile.path}")
    }

    private fun addApiCompatibilityDiagnostics(
        rootDir: File,
        catalogModules: Map<String, ModuleCatalog.ModuleEntry>,
        checks: Map<String, MutableList<VerificationDiagnostic>>,
    ) {
        val apiProjectPaths = apiValidationModules.get().toSet()
        val committed = ApiCompatibilityEvidenceReader.readCommittedDumps(rootDir, apiProjectPaths)
        val generated = readDeclaredGeneratedDumps()
        val base = ApiCompatibilityEvidenceReader.readBaseDumps(rootDir, baseRef.get(), committed.keys)
        val migrationResult =
            ApiCompatibilityEvidenceReader.parseMigrations(
                apiMigrationsFile.orNull?.asFile ?: File(rootDir, "config/quality/api-migrations.yml"),
            )
        checks.getValue("api-architecture") += migrationResult.diagnostics
        val verifier =
            ApiCompatibilityVerifier(
                catalogModules = catalogModules,
                projectVersion = projectVersion.get(),
            )
        val diagnostics =
            verifier.verify(
                ApiDumpEvidence(generated = generated, committed = committed, base = base),
                migrations = migrationResult.entries,
            )
        checks.getValue("api-architecture") += diagnostics
        // C3/C4: consumer compile proofs are fail-soft producers. Read their
        // declared markers; a failed compile surfaces as typed evidence here,
        // and the report is written before the gate throws (B2 fail-closed).
        listOf("java", "kotlin").forEach { language ->
            val marker =
                consumerMarkers.files
                    .firstOrNull { it.name == "consumer-$language.json" }
            if (marker == null) {
                checks.getValue("api-architecture") +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.API_COMPATIBILITY_FAILED,
                        "Consumer compile proof for '$language' produced no marker evidence",
                    )
                return@forEach
            }
            val state = ReportNormalizer.readJson(marker, Map::class.java)
            val ok = state?.get("ok") == true
            if (!ok) {
                checks.getValue("api-architecture") +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.API_COMPATIBILITY_FAILED,
                        "Consumer compile proof FAILED for '$language': " +
                            "sources=${state?.get("sources")} classes=${state?.get("classes")} " +
                            "exitCode=${state?.get("exitCode")} — the stable API is not usable from this consumer",
                        baselineValue = state?.get("sources")?.toString(),
                        currentValue = state?.get("classes")?.toString(),
                    )
            }
        }
    }

    /**
     * Read the declared generated dump files into a module→content map.
     * Ordered pairing contract (a3c1 discipline): owner[i] <-> file[i].
     * ConfigurableFileCollection iterates in insertion order (toList()),
     * unlike the unordered Set<File> view of .files. Fail closed on
     * cardinality mismatch and duplicate owners instead of mispairing.
     */
    private fun readDeclaredGeneratedDumps(): Map<String, String> {
        val dumpOwners = generatedApiDumpOwners.get()
        val dumpFiles = generatedApiDumpFiles.toList()
        require(dumpOwners.size == dumpFiles.size) {
            "Generated API dump owner/file evidence mismatch: " +
                "${dumpOwners.size} owners, ${dumpFiles.size} files"
        }
        require(dumpOwners.distinct().size == dumpOwners.size) {
            "Duplicate generated API dump owner evidence: ${dumpOwners.sorted()}"
        }
        return dumpOwners.zip(dumpFiles).associate { (modulePath, file) ->
            modulePath to file.readText(Charsets.UTF_8)
        }
    }

    private fun readBaselineDiagnostics(reportFile: File): List<VerificationDiagnostic> {
        if (!reportFile.isFile) {
            throw GradleException("Baseline verifier did not produce ${reportFile.path}")
        }
        val report = ReportNormalizer.readJson(reportFile, Map::class.java)
        val diagnostics =
            report["diagnostics"] as? List<*>
                ?: throw GradleException("Baseline verification report has no diagnostics array")
        return diagnostics.map { raw ->
            val entry = raw as? Map<*, *> ?: throw GradleException("Malformed baseline diagnostic")
            VerificationDiagnostic(
                code = DiagnosticCode.valueOf(entry["code"]?.toString() ?: error("Baseline diagnostic code missing")),
                severity =
                    DiagnosticSeverity.valueOf(
                        entry["severity"]?.toString() ?: error("Baseline diagnostic severity missing"),
                    ),
                message = entry["message"]?.toString() ?: error("Baseline diagnostic message missing"),
                modulePath = entry["modulePath"]?.toString(),
                findingId = entry["findingId"]?.toString(),
                deviationId = entry["deviationId"]?.toString(),
                baselineValue = entry["baselineValue"]?.toString(),
                currentValue = entry["currentValue"]?.toString(),
            )
        }
    }

    /**
     * Exhaustive classification of every DiagnosticCode. No else branch: adding a
     * new DiagnosticCode forces a decision at compile time — either it belongs to
     * an architecture check here, or it is explicitly excluded.
     */
    private fun baselineCheckFor(code: DiagnosticCode): String? =
        when (code) {
            // Module catalogue (all codes except BOM/publishing drift, which are publishing-topology)
            DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
            DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
            DiagnosticCode.MODULE_CATALOG_DUPLICATE_PATH,
            DiagnosticCode.MODULE_CATALOG_INVALID_LAYER,
            DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
            DiagnosticCode.MODULE_CATALOG_EXAMPLE_PUBLISHABLE,
            DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
            DiagnosticCode.MODULE_CATALOG_INVALID_SCHEMA,
            DiagnosticCode.MODULE_CATALOG_INVALID_MATURITY,
            DiagnosticCode.MODULE_CATALOG_INVALID_PUBLISHABILITY,
            DiagnosticCode.MODULE_CATALOG_INVALID_VISIBILITY,
            DiagnosticCode.MODULE_CATALOG_INVALID_RELEASE_INCLUSION,
            DiagnosticCode.MODULE_CATALOG_INVALID_POLICY,
            DiagnosticCode.MODULE_CATALOG_BLANK_OWNER,
            DiagnosticCode.MODULE_CATALOG_BLANK_RATIONALE,
            DiagnosticCode.MODULE_CATALOG_MISSING_DESCRIPTION,
            DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION,
            -> "module-manifest"

            DiagnosticCode.MODULE_CATALOG_BOM_DRIFT,
            DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT,
            -> "publishing-topology"

            DiagnosticCode.FORBIDDEN_LAYER_EDGE,
            DiagnosticCode.SELF_DEPENDENCY,
            -> "dependency-boundaries"

            DiagnosticCode.NEW_DEPENDENCY_CYCLE,
            -> "dependency-cycles"

            DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
            -> "global-state"

            DiagnosticCode.API_BASELINE_EMPTY,
            DiagnosticCode.API_DUMP_MISSING,
            DiagnosticCode.API_DUMP_DUPLICATE,
            DiagnosticCode.API_MODULE_UNCLASSIFIED,
            DiagnosticCode.API_VALIDATION_NOT_CONFIGURED,
            DiagnosticCode.API_COMPATIBILITY_FAILED,
            DiagnosticCode.API_HASH_CHANGED,
            DiagnosticCode.API_DUMP_NONDETERMINISTIC,
            -> "api-architecture"

            DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
            -> "protocol-catalog"

            DiagnosticCode.NEW_CANCELLATION_FINDING,
            DiagnosticCode.CANCELLATION_RISK_WORSENED,
            -> "cancellation-safety"

            // Explicitly outside the 0.6.0 architecture gate.
            DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
            DiagnosticCode.ANALYZER_COMMIT_NOT_ANCESTOR,
            DiagnosticCode.MEASURED_TREE_MISMATCH,
            DiagnosticCode.TAG_COMMIT_MISMATCH,
            DiagnosticCode.TAG_TREE_MISMATCH,
            DiagnosticCode.DIRTY_WORKTREE,
            DiagnosticCode.DEPENDENCY_BASELINE_EMPTY,
            DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
            DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION,
            DiagnosticCode.SNAPSHOT_DEPENDENCY,
            DiagnosticCode.DEPENDENCY_CONVERGENCE_FAILURE,
            DiagnosticCode.DEPENDENCY_ADDED,
            DiagnosticCode.DEPENDENCY_REMOVED,
            DiagnosticCode.DEPENDENCY_VERSION_CHANGED,
            DiagnosticCode.TEST_QUALITY_CONFIGURATION_INVALID,
            DiagnosticCode.COVERAGE_REPORT_MISSING,
            DiagnosticCode.COVERAGE_REPORT_MALFORMED,
            DiagnosticCode.COVERAGE_COUNTER_MISSING,
            DiagnosticCode.COVERAGE_PATH_LEAK,
            DiagnosticCode.COVERAGE_REGRESSION,
            DiagnosticCode.COVERAGE_FAMILY_EMPTY,
            DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED,
            DiagnosticCode.MUTATION_REPORT_MISSING,
            DiagnosticCode.MUTATION_REPORT_MALFORMED,
            DiagnosticCode.MUTATION_TARGET_EMPTY,
            DiagnosticCode.MUTATION_REGRESSION,
            DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED,
            DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED,
            DiagnosticCode.TEST_REPORT_MISSING,
            DiagnosticCode.TEST_PERFORMANCE_REGRESSION,
            DiagnosticCode.CRITICAL_TEST_REGRESSION,
            DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED,
            DiagnosticCode.TEST_QUALITY_STATUS_PENDING,
            DiagnosticCode.NEW_NONDETERMINISM_FINDING,
            DiagnosticCode.HOTSPOT_REGRESSION,
            DiagnosticCode.NEW_TOP_FIVE_HOTSPOT,
            DiagnosticCode.FILE_GROWTH_EXCEEDED,
            DiagnosticCode.INVALID_DEVIATION_SCOPE,
            DiagnosticCode.ORPHANED_DEVIATION,
            DiagnosticCode.EXPIRED_DEVIATION,
            DiagnosticCode.DUPLICATE_DEVIATION,
            DiagnosticCode.MALFORMED_DEVIATION,
            DiagnosticCode.DEVIATION_BASELINE_MISMATCH,
            DiagnosticCode.DEVIATION_COVERAGE_EXCEEDED,
            DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
            DiagnosticCode.EMPTY_SECTION,
            // Nondeterminism authority contract (Epic 8.3d PR 2) — enforced by the
            // verifyRuntimeNondeterminism task directly, not the maintainability baseline.
            DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING,
            DiagnosticCode.NONDETERMINISM_STALE_ENTRY,
            DiagnosticCode.NONDETERMINISM_MISMATCHED_CLASSIFICATION,
            DiagnosticCode.NONDETERMINISM_OCCURRENCE_MISMATCH,
            DiagnosticCode.NONDETERMINISM_DUPLICATE_ENTRY,
            DiagnosticCode.NONDETERMINISM_INVALID_DISPOSITION,
            DiagnosticCode.NONDETERMINISM_MISSING_RATIONALE,
            DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA,
            // Module documentation contract (Epic 11.2b3) — enforced by the
            // verifyModuleDocContract task directly, not a maintainability baseline.
            DiagnosticCode.MODULE_CARD_MISSING,
            DiagnosticCode.MODULE_CARD_ORPHAN,
            DiagnosticCode.MODULE_CARD_HEADING_MISSING,
            DiagnosticCode.MODULE_CARD_LINK_BROKEN,
            DiagnosticCode.MODULE_CARD_INLINE_PATH_BROKEN,
            DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION,
            DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY,
            DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT,
            DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH,
            -> null
        }

    private fun addManifestDiagnostics(
        diagnostics: List<VerificationDiagnostic>,
        checks: Map<String, MutableList<VerificationDiagnostic>>,
    ) {
        diagnostics.forEach { diagnostic ->
            val check = if (diagnostic.code in publishingTopologyCodes) "publishing-topology" else "module-manifest"
            checks.getValue(check) += diagnostic
        }
    }

    private fun addEnrollmentDiagnostics(
        resultsFiles: Set<File>,
        checks: Map<String, MutableList<VerificationDiagnostic>>,
    ) {
        val reports =
            resultsFiles
                .flatMap { file ->
                    if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList() else listOf(file)
                }.filter { it.name.startsWith("TEST-") && it.extension == "xml" }
                .sortedBy { it.name }
        if (reports.isEmpty()) {
            listOf("provider-contracts", "store-contracts").forEach { check ->
                checks.getValue(check) +=
                    VerificationDiagnostic.failure(
                        DiagnosticCode.EMPTY_SECTION,
                        "Enrollment architecture test results are missing from ${resultsFiles.map { it.path }}",
                    )
            }
            return
        }

        val discoveredClasses = mutableSetOf<String>()
        reports.forEach { report ->
            val className = report.name.removePrefix("TEST-").removeSuffix(".xml")
            discoveredClasses += className
            val check =
                when {
                    className == "dev.tramai.testing.ProviderTckEnrollmentArchitectureTest" -> "provider-contracts"
                    className.endsWith("EnrollmentArchitectureTest") -> "store-contracts"
                    else -> null
                } ?: return@forEach
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
            val document = factory.newDocumentBuilder().parse(report)
            val cases = document.getElementsByTagName("testcase")
            for (index in 0 until cases.length) {
                val testCase = cases.item(index) as org.w3c.dom.Element
                for (childIndex in 0 until testCase.childNodes.length) {
                    val child = testCase.childNodes.item(childIndex)
                    if (child.nodeName !in setOf("failure", "error")) continue
                    val failure = child as org.w3c.dom.Element
                    val message = failure.getAttribute("message").ifBlank { failure.textContent.trim() }
                    checks.getValue(check) +=
                        VerificationDiagnostic.failure(
                            DiagnosticCode.EMPTY_SECTION,
                            "$className failed: $message",
                        )
                }
            }
        }

        // Pin guard identities: deleting/renaming one enrollment class must FAIL
        // even if the others still run. Discovery by identity, not by count.
        enrollmentGuardDiagnostics(discoveredClasses).forEach { (check, diagnostics) ->
            checks.getValue(check) += diagnostics
        }
    }
}
