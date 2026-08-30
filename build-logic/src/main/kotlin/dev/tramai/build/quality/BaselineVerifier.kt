package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Compares the currently generated baseline against the committed baseline.
 * Uses stable FindingIdentity, typed VerificationDiagnostic, ModuleCatalog,
 * and ModuleBoundaries for all checks.
 */
class BaselineVerifier(
    private val generator: BaselineGenerator,
    private val ctx: MeasurementContext,
    private val reportDir: File,
    /**
     * Declared committed-baseline input (Epic 9.2d-a3c2). Null keeps the
     * legacy conventional-path lookup for the fromProject caller.
     */
    private val committedBaselineFile: File? = null,
    /**
     * Declared resolved-dependency baseline input (Epic 9.2d-a3c2). Null keeps
     * the legacy generator lookup (gradleProject build dir).
     */
    private val resolvedDependenciesFile: File? = null,
    /**
     * Declared apiCheck module set (Epic 9.2d-a3c2). Null keeps the legacy
     * allprojects walk for the fromProject caller.
     */
    private val apiValidationModules: Set<String>? = null,
) {
    private val deviationParser = DeviationParser(ctx.rootDir)
    private val budgetEvaluator = DeviationBudgetEvaluator(deviationParser)
    private val moduleCatalog = ModuleCatalog.fromRootDir(ctx.rootDir)
    private val moduleBoundaries = ModuleBoundaries(ctx.rootDir)

    fun verify(): VerificationReport {
        // Delete old report before starting
        val reportFile = File(reportDir, "verification-report.json")
        reportFile.delete()

        val diagnostics = mutableListOf<VerificationDiagnostic>()
        try {
            // 1. Load committed baseline (declared input = execution authority; a3c2)
            val committedFile = committedBaselineFile ?: File(ctx.rootDir, "config/quality/0.6.0-baseline.json")
            if (!committedFile.isFile) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.EMPTY_SECTION,
                        "Committed baseline not found: ${committedFile.absolutePath}",
                    ),
                )
                return diagnosticsToReport(diagnostics)
            }

            val committed: BaselineDocument =
                try {
                    ReportNormalizer.readJson(committedFile, BaselineDocument::class.java)
                } catch (e: Exception) {
                    diagnostics.add(
                        VerificationDiagnostic.failure(
                            DiagnosticCode.EMPTY_SECTION,
                            "Failed to read committed baseline: ${e.message}",
                        ),
                    )
                    return diagnosticsToReport(diagnostics)
                }

            // 2. Parse deviations with typed diagnostics
            val deviationResult = deviationParser.parse()
            diagnostics.addAll(deviationResult.diagnostics)

            // 3. Parse module catalog
            val catalogResult = moduleCatalog.parse()
            diagnostics.addAll(catalogResult.errors)

            // 4. Parse boundary rules
            val boundaryResult = moduleBoundaries.parse()
            diagnostics.addAll(boundaryResult.errors)

            // 5. Verify baseline identity
            verifyBaselineIdentity(committed, diagnostics)

            // 6. Generate current measurements (needed before catalogue validation)
            // a3c2: the typed path runs in directory mode (gradleProject == null)
            // with the resolved-dependencies file declared as an input; the
            // fromProject caller (verify060Architecture) keeps the legacy lookup.
            val currentCtx = ctx.gradleProject?.let { MeasurementContext.fromProject(it) } ?: ctx
            val tempGenerator =
                BaselineGenerator(
                    ctx = currentCtx,
                    outputDir = reportDir,
                    writeRepositoryArtifacts = false,
                )

            val currentDependencies =
                try {
                    if (resolvedDependenciesFile != null) {
                        // Declared input = execution authority: the aggregate task's
                        // typed output is the same file the legacy path read from the
                        // project build dir.
                        val records =
                            ReportNormalizer.readJson(resolvedDependenciesFile, Array<ResolvedDependency>::class.java).toList()
                        BaselineGenerator.sortResolvedDependencies(records)
                    } else {
                        if (ctx.gradleProject == null) {
                            throw GradleException("Resolved dependency baseline input is required in directory mode")
                        }
                        tempGenerator.generateResolvedDependencyGraph()
                    }
                } catch (e: Exception) {
                    diagnostics.add(
                        VerificationDiagnostic.failure(
                            DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                            "Failed to resolve current production dependencies: ${e.message}",
                        ),
                    )
                    return diagnosticsToReport(diagnostics)
                }

            val current: BaselineDocument =
                try {
                    tempGenerator.generateCompleteBaseline(dependencyOverride = currentDependencies)
                } catch (e: Exception) {
                    diagnostics.add(
                        VerificationDiagnostic.failure(
                            DiagnosticCode.EMPTY_SECTION,
                            "Failed to generate current baseline: ${e.message}",
                        ),
                    )
                    return diagnosticsToReport(diagnostics)
                }

            // 7. Verify module catalogue against CURRENT projects (not committed)
            verifyModuleCatalog(current, catalogResult, diagnostics)

            // 8. Verify mandatory sections
            verifyMandatorySections(current, diagnostics)

            // 9. Verify API and resolved dependency baselines with typed diagnostics
            val apiModules =
                apiValidationModules
                    ?: ctx.gradleProject
                        ?.allprojects
                        ?.filter { it.tasks.findByName("apiCheck") != null }
                        ?.map { it.path }
                        ?.toSet()
                        .orEmpty()
            diagnostics.addAll(
                ApiBaselineVerifier(
                    repositoryRoot = ctx.rootDir,
                    catalogModules = catalogResult.modules,
                    apiValidationModules = apiModules,
                ).verify(committed.api, current.api),
            )
            val dependencyDiagnostics =
                DependencyBaselineVerifier().verify(
                    committed.dependencies.resolvedDependencies,
                    current.dependencies.resolvedDependencies,
                )
            diagnostics.addAll(dependencyDiagnostics)
            ReportNormalizer.writeJson(
                dependencyDiagnostics.filter {
                    it.code in
                        setOf(
                            DiagnosticCode.DEPENDENCY_ADDED,
                            DiagnosticCode.DEPENDENCY_REMOVED,
                            DiagnosticCode.DEPENDENCY_VERSION_CHANGED,
                        )
                },
                File(reportDir, "dependency-changes.json"),
            )

            // 10. Compare dimensions with FindingIdentity
            verifyCancellationCatches(committed, current, deviationResult.deviations, diagnostics)
            verifyGlobalState(committed, current, deviationResult.deviations, diagnostics)
            verifyNondeterminism(committed, current, deviationResult.deviations, diagnostics)
            verifyDependencyCycles(committed, current, deviationResult.deviations, diagnostics)
            verifyProtocolCatalog(committed, current, diagnostics)
            verifyStructuralHotspots(committed, current, deviationResult.deviations, diagnostics)
            verifyForbiddenEdges(committed, current, diagnostics)
            verifyDependencyPolicies(current, diagnostics)
            verifyDocumentDrift(committed, diagnostics)
        } finally {
            // Always write report, even on partial failures
            writeVerificationReport(diagnostics)
        }

        return diagnosticsToReport(diagnostics)
    }

    // ─── Identity verification ───

    private fun verifyBaselineIdentity(
        committed: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val id = committed.baselineIdentity

        if (id.baselineCommitSha.isBlank()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Committed baseline has empty baselineCommitSha",
                ),
            )
        }
        if (id.tramaiVersion == "unspecified" || id.tramaiVersion.isBlank()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Committed baseline has invalid tramaiVersion: '${id.tramaiVersion}'",
                ),
            )
        }

        if (!id.workingTreeClean) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.DIRTY_WORKTREE,
                    "Committed baseline was generated from a dirty worktree",
                ),
            )
        }
        if (id.measuredSourceTreeHash.isBlank()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Committed baseline has empty measuredSourceTreeHash",
                ),
            )
        }
        if (id.measuredGitTreeSha.isBlank()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Committed baseline has empty measuredGitTreeSha",
                ),
            )
        }
        if (id.analyzerCommitSha.isBlank()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Committed baseline has empty analyzerCommitSha",
                ),
            )
        }

        // Tag/commit/tree verification with typed diagnostics
        if (id.baselineCommitSha.isNotBlank() && id.measuredCommitSha.isNotBlank() &&
            id.baselineCommitSha != id.measuredCommitSha
        ) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.TAG_COMMIT_MISMATCH,
                    "Baseline provenance mismatch: measuredCommitSha (${id.measuredCommitSha.take(8)}) " +
                        "differs from baselineCommitSha (${id.baselineCommitSha.take(8)})",
                ),
            )
        }

        // Independent tree SHA verification
        if (id.measuredGitTreeSha.isNotBlank() && id.measuredCommitSha.isNotBlank()) {
            val recomputedTreeSha = resolveRefOrFail("${id.measuredCommitSha}^{tree}", diagnostics)
            if (recomputedTreeSha != null && recomputedTreeSha != id.measuredGitTreeSha) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MEASURED_TREE_MISMATCH,
                        "measuredGitTreeSha (${id.measuredGitTreeSha.take(8)}) does not match " +
                            "independently computed tree SHA (${recomputedTreeSha.take(8)})",
                    ),
                )
            }
        }

        // Tag resolves correctly (only validate non-blank identity fields)
        if (id.baselineCommitSha.isNotBlank()) {
            val tagCommit = resolveRefOrFail("${id.releaseTag}^{commit}", diagnostics)
            if (tagCommit != null && tagCommit != id.baselineCommitSha) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.TAG_COMMIT_MISMATCH,
                        "Release tag ${id.releaseTag} resolves to ${tagCommit.take(8)}, " +
                            "but baseline claims ${id.baselineCommitSha.take(8)}",
                    ),
                )
            }
            if (id.measuredGitTreeSha.isNotBlank()) {
                val tagTree = resolveRefOrFail("${id.releaseTag}^{tree}", diagnostics)
                if (tagTree != null && tagTree != id.measuredGitTreeSha) {
                    diagnostics.add(
                        VerificationDiagnostic.failure(
                            DiagnosticCode.TAG_TREE_MISMATCH,
                            "Release tag ${id.releaseTag} tree is ${tagTree.take(8)}, " +
                                "but baseline records ${id.measuredGitTreeSha.take(8)}",
                        ),
                    )
                }
            }
        }

        // Analyzer ancestor check
        if (id.analyzerCommitSha.isNotBlank()) {
            val analyzerExists = resolveRefOrFail("${id.analyzerCommitSha}^{commit}", diagnostics)
            if (analyzerExists != null) {
                val isAncestor =
                    try {
                        val process =
                            ProcessBuilder(
                                listOf("git", "merge-base", "--is-ancestor", id.analyzerCommitSha, "HEAD"),
                            ).directory(ctx.rootDir).redirectErrorStream(true).start()
                        process.waitFor() == 0
                    } catch (_: Exception) {
                        false
                    }

                if (!isAncestor) {
                    diagnostics.add(
                        VerificationDiagnostic.failure(
                            DiagnosticCode.ANALYZER_COMMIT_NOT_ANCESTOR,
                            "analyzerCommitSha (${id.analyzerCommitSha.take(8)}) is not an ancestor of HEAD",
                        ),
                    )
                }
            }
        }
    }

    private fun resolveRefOrFail(
        ref: String,
        diagnostics: MutableList<VerificationDiagnostic>,
    ): String? =
        try {
            val result = ctx.runGit("rev-parse", ref)
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                    "Unable to resolve $ref: ${e.message}",
                ),
            )
            null
        }

    // ─── Module catalogue verification ───

    private fun verifyModuleCatalog(
        current: BaselineDocument,
        catalogResult: ModuleCatalog.CatalogResult,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val projectPaths =
            current.structural.moduleDependencies.modules
                .toList()
        val catalogModules = catalogResult.modules

        // Missing modules in catalogue
        for (projPath in projectPaths) {
            if (projPath !in catalogModules) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                        "Gradle project '$projPath' has no module-catalog entry",
                    ),
                )
            }
        }

        // Unknown modules in catalogue
        for (catPath in catalogModules.keys) {
            if (catPath !in projectPaths) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
                        "Module-catalog entry '$catPath' does not exist as a Gradle project",
                    ),
                )
            }
        }

        // Verify catalogue classifications match current baseline
        for (mod in current.structural.modules) {
            val catalogEntry = catalogModules[mod.path] ?: continue
            if (catalogEntry.layer.yaml != mod.layer) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                        "Module '${mod.path}': catalogue declares layer '${catalogEntry.layer.yaml}' but " +
                            "current baseline has '${mod.layer}'",
                    ),
                )
            }

            val expectedPublished = catalogEntry.publishability == ModulePublishability.PUBLISHED
            if (expectedPublished != mod.publishable) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                        "Module '${mod.path}': catalogue declares publishability '${catalogEntry.publishability.yaml}' " +
                            "but current baseline has '${if (mod.publishable) "published" else "not published"}'",
                    ),
                )
            }
        }
    }

    // ─── Mandatory sections ───

    private fun verifyMandatorySections(
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        if (current.structural.moduleDependencies.modules
                .isEmpty()
        ) {
            diagnostics.add(
                VerificationDiagnostic.failure(DiagnosticCode.EMPTY_SECTION, "Current baseline has empty module list"),
            )
        }
        if (current.runtimeSafety.cancellationCatches.isEmpty() &&
            current.runtimeSafety.testCancellationCatches.isEmpty()
        ) {
            diagnostics.add(
                VerificationDiagnostic.failure(DiagnosticCode.EMPTY_SECTION, "Current baseline has empty cancellation catch inventory"),
            )
        }
        if (current.structural.sourceMetrics.byModule
                .isEmpty()
        ) {
            diagnostics.add(
                VerificationDiagnostic.failure(DiagnosticCode.EMPTY_SECTION, "Current baseline has empty source metrics"),
            )
        }
        if (current.protocolCatalog.entries.isEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.failure(DiagnosticCode.EMPTY_SECTION, "Current baseline has empty protocol catalog"),
            )
        }
        if (current.dependencies.resolvedDependencies.isEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.DEPENDENCY_BASELINE_EMPTY,
                    "Current baseline has empty resolved dependencies",
                ),
            )
        }
    }

    // ─── Multiset comparison utilities ───

    /**
     * Count findings by identity key, detecting multiple occurrences.
     */
    private fun countById(findings: List<FindingIdentity>): Map<String, Int> =
        findings.groupBy { it.toIdentityKey() }.mapValues { it.value.size }

    // ─── Shared aggregate deviation budget evaluator ───
    //
    // Delegates to DeviationBudgetEvaluator for testable policy logic.

    /**
     * Evaluate the aggregate deviation budget for a safety metric.
     * Delegates to DeviationBudgetEvaluator.
     */
    private fun evaluateDeviationBudget(
        metricName: String,
        deviations: List<DeviationParser.DeviationEntry>,
        committedIds: List<String>,
        currentIds: List<String>,
        allCurrent: List<Any?>,
        riskFilter: ((Any?) -> Boolean)? = null,
        toModuleScope: (Any?) -> DeviationParser.FindingScope,
        diagnosticCode: DiagnosticCode,
        riskWorseCode: DiagnosticCode? = null,
        riskWorseningMetricName: String? = null,
        committedFindings: List<Any?>? = null,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        budgetEvaluator.evaluateDeviationBudget(
            metricName = metricName,
            deviations = deviations,
            committedIds = committedIds,
            currentIds = currentIds,
            allCurrent = allCurrent,
            riskFilter = riskFilter,
            toModuleScope = toModuleScope,
            diagnosticCode = diagnosticCode,
            riskWorseCode = riskWorseCode,
            riskWorseningMetricName = riskWorseningMetricName,
            committedFindings = committedFindings,
            diagnostics = diagnostics,
        )
    }

    /** Compute the FindingScope for module-based matching from a finding. */
    private fun moduleScope(finding: Any?): DeviationParser.FindingScope = DeviationBudgetEvaluator.moduleScope(finding)

    /** Extract the module path string from an arbitrary finding. */
    private fun modulePathOf(finding: Any?): String = DeviationBudgetEvaluator.modulePathOf(finding)

    /** Extract a stable identity key from an arbitrary finding. */
    private fun findingIdentityKey(finding: Any?): String = DeviationBudgetEvaluator.findingIdentityKey(finding)

    // ─── Finding-level comparison with FindingIdentity ───

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val allCurrent = current.runtimeSafety.cancellationCatches.toList<Any?>()
        evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            deviations = deviations,
            committedIds =
                committed.runtimeSafety.cancellationCatches.map {
                    FindingIdentity.fromCancellationCatch(it).toIdentityKey()
                },
            currentIds =
                current.runtimeSafety.cancellationCatches.map {
                    FindingIdentity.fromCancellationCatch(it).toIdentityKey()
                },
            allCurrent = allCurrent,
            riskFilter = { f -> (f as CancellationCatchFinding).risk == "critical" },
            toModuleScope = { f -> moduleScope(f) },
            diagnosticCode = DiagnosticCode.NEW_CANCELLATION_FINDING,
            riskWorseCode = DiagnosticCode.CANCELLATION_RISK_WORSENED,
            riskWorseningMetricName = "cancellationRiskWorsening",
            committedFindings = committed.runtimeSafety.cancellationCatches.toList<Any?>(),
            diagnostics = diagnostics,
        )

        val removed =
            committed.runtimeSafety.cancellationCatches
                .map {
                    FindingIdentity.fromCancellationCatch(it).toIdentityKey()
                }.toSet() -
                current.runtimeSafety.cancellationCatches
                    .map {
                        FindingIdentity.fromCancellationCatch(it).toIdentityKey()
                    }.toSet()
        if (removed.isNotEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.improvement(
                    DiagnosticCode.NEW_CANCELLATION_FINDING,
                    "${removed.size} cancellation catch(es) resolved",
                ),
            )
        }
    }

    private fun verifyGlobalState(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val allCurrent = current.runtimeSafety.globalState.toList<Any?>()
        evaluateDeviationBudget(
            metricName = "globalMutableState",
            deviations = deviations,
            committedIds =
                committed.runtimeSafety.globalState.map {
                    FindingIdentity.fromGlobalState(it).toIdentityKey()
                },
            currentIds =
                current.runtimeSafety.globalState.map {
                    FindingIdentity.fromGlobalState(it).toIdentityKey()
                },
            allCurrent = allCurrent,
            toModuleScope = { f -> moduleScope(f) },
            diagnosticCode = DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
            diagnostics = diagnostics,
        )
    }

    private fun verifyNondeterminism(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val allCurrent = current.runtimeSafety.nondeterminism.toList<Any?>()
        evaluateDeviationBudget(
            metricName = "nondeterminismSources",
            deviations = deviations,
            committedIds =
                committed.runtimeSafety.nondeterminism.map {
                    FindingIdentity.fromNondeterminism(it).toIdentityKey()
                },
            currentIds =
                current.runtimeSafety.nondeterminism.map {
                    FindingIdentity.fromNondeterminism(it).toIdentityKey()
                },
            allCurrent = allCurrent,
            toModuleScope = { f -> moduleScope(f) },
            diagnosticCode = DiagnosticCode.NEW_NONDETERMINISM_FINDING,
            diagnostics = diagnostics,
        )
    }

    private fun verifyDependencyCycles(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val committedCycles =
            committed.structural.moduleDependencies.cycles
                .map { it.sorted().joinToString("\u2192") }
                .toSet()
        val currentCycles =
            current.structural.moduleDependencies.cycles
                .map { it.sorted().joinToString("\u2192") }
                .toSet()

        val newCycles = currentCycles - committedCycles
        for (cycle in newCycles) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_DEPENDENCY_CYCLE,
                    "New dependency cycle detected: $cycle",
                ),
            )
        }

        val removedCycles = committedCycles - currentCycles
        if (removedCycles.isNotEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.improvement(
                    DiagnosticCode.NEW_DEPENDENCY_CYCLE,
                    "Dependency cycles resolved: ${removedCycles.joinToString(", ")}",
                ),
            )
        }
    }

    private fun verifyForbiddenEdges(
        committed: BaselineDocument,
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        // Check edges from current Gradle mode (which has dependency resolution)
        for (edge in current.structural.moduleDependencies.edges) {
            val result = moduleBoundaries.checkEdge(edge.from, edge.to, moduleCatalog)
            if (result != null) {
                diagnostics.add(result)
            }
        }
    }

    private fun verifyDependencyPolicies(
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        for (edge in current.structural.moduleDependencies.edges) {
            val allowed = moduleCatalog.allowedLayersFor(edge.from) ?: continue
            val target = moduleCatalog.entryFor(edge.to) ?: continue
            if (target.layer !in allowed) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                        "Dependency policy violation: ${edge.from} may not depend on ${edge.to} (${target.layer.yaml})",
                        modulePath = edge.from,
                    ),
                )
            }
        }
    }

    private fun verifyProtocolCatalog(
        committed: BaselineDocument,
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val committedByKey =
            committed.protocolCatalog.entries.associateBy {
                "${it.category}::${it.name}::${it.value}::${it.source}"
            }
        val currentByKey =
            current.protocolCatalog.entries.associateBy {
                "${it.category}::${it.name}::${it.value}::${it.source}"
            }

        for ((key, entry) in committedByKey) {
            if (entry.stability == "stable-contract" && key !in currentByKey) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
                        "Stable protocol contract removed: ${entry.name} (${entry.category})",
                    ),
                )
            }
        }

        val committedUnclassified = committed.protocolCatalog.entries.count { it.stability == "unclassified" }
        val currentUnclassified = current.protocolCatalog.entries.count { it.stability == "unclassified" }
        if (currentUnclassified > committedUnclassified) {
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
                    "Unclassified protocol entries increased: $committedUnclassified \u2192 $currentUnclassified",
                ),
            )
        }
    }

    private fun verifyStructuralHotspots(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        // Validate deviation baselines against committed measurements
        validateDeviationBaselines(committed, deviations, diagnostics)

        for (dev in deviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue

            when (dev.metric) {
                "constructorParameterCount" -> {
                    val matchedHotspots =
                        current.structural.structuralHotspots.mostConstructorParameters
                            .filter { h -> scopeMatchesHotspot(parsedScope, h) }

                    for (hotspot in matchedHotspots) {
                        if (hotspot.value > dev.allowed) {
                            diagnostics.add(
                                VerificationDiagnostic.failure(
                                    DiagnosticCode.HOTSPOT_REGRESSION,
                                    "${dev.id}: ${hotspot.module}/${hotspot.declaration} constructor has ${hotspot.value} " +
                                        "parameters, exceeds allowed ${dev.allowed}",
                                    modulePath = hotspot.module,
                                    deviationId = dev.id,
                                    baselineValue = dev.baseline.toString(),
                                    currentValue = hotspot.value.toString(),
                                ),
                            )
                        }
                    }
                }

                "fileSize" -> {
                    val allFiles =
                        current.structural.structuralHotspots.largestProductionFiles +
                            current.structural.structuralHotspots.largestBuildFiles
                    val matchedHotspots = allFiles.filter { h -> scopeMatchesHotspot(parsedScope, h) }

                    for (hotspot in matchedHotspots) {
                        if (hotspot.value > dev.allowed) {
                            diagnostics.add(
                                VerificationDiagnostic.failure(
                                    DiagnosticCode.HOTSPOT_REGRESSION,
                                    "${dev.id}: file ${hotspot.path} is ${hotspot.value} lines, " +
                                        "exceeds allowed ${dev.allowed}",
                                    modulePath = hotspot.module,
                                    deviationId = dev.id,
                                    baselineValue = dev.baseline.toString(),
                                    currentValue = hotspot.value.toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Top-5 change detection
        val committedTop =
            committed.structural.structuralHotspots.largestProductionFiles
                .take(5)
                .map { it.path }
                .toSet()
        val currentTop =
            current.structural.structuralHotspots.largestProductionFiles
                .take(5)
                .map { it.path }
                .toSet()
        val newInTop = currentTop - committedTop
        if (newInTop.isNotEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.NEW_TOP_FIVE_HOTSPOT,
                    "New files entered top-5 production hotspots: ${newInTop.joinToString(", ")}",
                ),
            )
        }

        // 20% growth detection
        val committedByPath =
            committed.structural.structuralHotspots.largestProductionFiles
                .associateBy { it.path }
        for (currentHotspot in current.structural.structuralHotspots.largestProductionFiles) {
            val committedHotspot = committedByPath[currentHotspot.path]
            if (committedHotspot != null && currentHotspot.value > committedHotspot.value * 1.2) {
                diagnostics.add(
                    VerificationDiagnostic.warning(
                        DiagnosticCode.FILE_GROWTH_EXCEEDED,
                        "${currentHotspot.path} grew >20%: ${committedHotspot.value} \u2192 ${currentHotspot.value} lines",
                    ),
                )
            }
        }

        // Orphaned deviation detection using typed covers()
        for (dev in deviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue
            if (dev.metric != "fileSize" && dev.metric != "constructorParameterCount") continue
            if (parsedScope.filePath == null && !parsedScope.isWildcard) continue

            val allHotspots =
                current.structural.structuralHotspots.largestProductionFiles +
                    current.structural.structuralHotspots.largestBuildFiles +
                    current.structural.structuralHotspots.mostConstructorParameters +
                    current.structural.structuralHotspots.mostFunctionParameters

            val matched =
                allHotspots.any { h ->
                    val findingScope =
                        DeviationParser.FindingScope(
                            modulePath = if (h.module.startsWith(":")) h.module else ":${h.module}",
                            repositoryPath = h.path,
                            declaration = h.declaration.ifBlank { null },
                        )
                    parsedScope.covers(findingScope)
                }

            if (!matched && !parsedScope.isWildcard) {
                diagnostics.add(
                    VerificationDiagnostic.warning(
                        DiagnosticCode.ORPHANED_DEVIATION,
                        "${dev.id}: deviation scope '${dev.scope}' does not match any current hotspot — orphaned?",
                    ),
                )
            }
        }
    }

    /** Check whether a structural hotspot matches a parsed deviation scope. */
    private fun scopeMatchesHotspot(
        scope: DeviationParser.DeviationScope,
        hotspot: StructuralHotspot,
    ): Boolean {
        val findingScope =
            DeviationParser.FindingScope(
                modulePath = if (hotspot.module.startsWith(":")) hotspot.module else ":${hotspot.module}",
                repositoryPath = hotspot.path,
                declaration = hotspot.declaration.ifBlank { null },
            )
        return scope.covers(findingScope)
    }

    /**
     * Validate that each deviation's recorded baseline value matches the
     * committed canonical measurement for that metric.
     * Emits DEVIATION_BASELINE_MISMATCH when they differ.
     */
    private fun validateDeviationBaselines(
        committed: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        for (dev in deviations) {
            val actualBaseline = computeCommittedBaseline(committed, dev.metric, dev.scope)
            if (actualBaseline != null && actualBaseline != dev.baseline) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.DEVIATION_BASELINE_MISMATCH,
                        "${dev.id}: recorded baseline ${dev.baseline} does not match canonical measurement $actualBaseline for metric '${dev.metric}' at scope '${dev.scope}'",
                        deviationId = dev.id,
                        baselineValue = dev.baseline.toString(),
                        currentValue = actualBaseline.toString(),
                    ),
                )
            }
        }
    }

    /**
     * Compute the committed baseline value for a given metric and scope.
     * Filters findings to those matching the deviation's scope for accuracy.
     * Returns null when the metric is not directly comparable (e.g. fileSize
     * varies per file, not globally).
     */
    private fun computeCommittedBaseline(
        committed: BaselineDocument,
        metric: String,
        scope: String,
    ): Int? {
        val parsedScope = deviationParser.parseScope(scope) ?: return null

        fun matchesScope(modulePath: String): Boolean {
            val norm = if (modulePath.startsWith(":")) modulePath else ":$modulePath"
            return parsedScope.covers(DeviationParser.FindingScope(norm, null, null))
        }

        return when (metric) {
            "cancellationCriticalCount" -> {
                committed.runtimeSafety.cancellationCatches.count {
                    it.risk == "critical" && matchesScope(it.module)
                }
            }

            "cancellationRiskWorsening" -> {
                null
            }

            "globalMutableState" -> {
                committed.runtimeSafety.globalState.count {
                    matchesScope(it.module)
                }
            }

            "nondeterminismSources" -> {
                committed.runtimeSafety.nondeterminism.count {
                    matchesScope(it.module)
                }
            }

            "constructorParameterCount" -> {
                // Filter committed constructor hotspots by scope, get max value
                val matched =
                    committed.structural.structuralHotspots.mostConstructorParameters.filter { h ->
                        val fs =
                            DeviationParser.FindingScope(
                                modulePath = if (h.module.startsWith(":")) h.module else ":$h.module",
                                repositoryPath = h.path,
                                declaration = h.declaration.ifBlank { null },
                            )
                        parsedScope.covers(fs)
                    }
                if (matched.isEmpty()) null else matched.maxOf { it.value }
            }

            "fileSize" -> {
                // Filter committed file hotspots by scope, get max value
                val allCommittedFiles =
                    committed.structural.structuralHotspots.largestProductionFiles +
                        committed.structural.structuralHotspots.largestBuildFiles
                val matched =
                    allCommittedFiles.filter { h ->
                        val fs =
                            DeviationParser.FindingScope(
                                modulePath = if (h.module.startsWith(":")) h.module else ":$h.module",
                                repositoryPath = h.path,
                                declaration = null,
                            )
                        parsedScope.covers(fs)
                    }
                if (matched.isEmpty()) null else matched.maxOf { it.value }
            }

            else -> {
                null
            }
        }
    }

    private fun verifyDocumentDrift(
        committed: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>,
    ) {
        val releaseNotesFile = File(ctx.rootDir, "docs/releases/0.6.0-maintainability-baseline.md")
        if (!releaseNotesFile.isFile) return

        val content = releaseNotesFile.readText()

        // Verify baseline SHA in release notes matches baseline JSON
        val canonicalSha = committed.baselineIdentity.baselineCommitSha
        if (canonicalSha.isNotBlank() && canonicalSha !in content) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
                    "Release notes do not reference baseline commit SHA $canonicalSha",
                ),
            )
        }

        // Verify module count
        val moduleCount = committed.structural.modules.size
        val moduleCountRefs = Regex("""(\d+)\s*modules?""", RegexOption.IGNORE_CASE).findAll(content)
        if (moduleCountRefs.any { it.groupValues[1].toIntOrNull() != moduleCount }) {
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
                    "Release notes module count may differ from canonical baseline ($moduleCount)",
                ),
            )
        }
    }

    // ─── Output ───

    private fun writeVerificationReport(diagnostics: List<VerificationDiagnostic>) {
        reportDir.mkdirs()

        val executionSha =
            try {
                ctx.runGit("rev-parse", "HEAD")
            } catch (_: Exception) {
                "unknown"
            }

        val report =
            mapOf(
                "schemaVersion" to "1",
                "description" to "TramAI 0.6.0 maintainability verification report",
                "executionHeadSha" to executionSha,
                "passed" to diagnostics.none { it.severity == DiagnosticSeverity.FAILURE },
                "diagnostics" to
                    diagnostics.map { diag ->
                        mapOf(
                            "code" to diag.code.name,
                            "severity" to diag.severity.name,
                            "message" to diag.message,
                            "modulePath" to diag.modulePath,
                            "findingId" to diag.findingId,
                            "deviationId" to diag.deviationId,
                            "baselineValue" to diag.baselineValue,
                            "currentValue" to diag.currentValue,
                        ).filter { it.value != null }
                    },
            )
        ReportNormalizer.writeJson(report, File(reportDir, "verification-report.json"))
    }

    private fun diagnosticsToReport(diagnostics: List<VerificationDiagnostic>): VerificationReport {
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }.map { "${it.code}: ${it.message}" }
        val warnings = diagnostics.filter { it.severity == DiagnosticSeverity.WARNING }.map { "${it.code}: ${it.message}" }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }.map { "${it.code}: ${it.message}" }
        return VerificationReport(failures.isEmpty(), failures, warnings, accepted)
    }
}
