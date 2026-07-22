package dev.tramai.build.quality

/**
 * Shared aggregate deviation budget evaluator.
 *
 * Every safety-finding metric uses the same budgeting rule:
 *   each deviation's `allowed` ceiling covers ALL current findings
 *   matching its scope, not findings per module.
 *
 * Extracted from BaselineVerifier so it can be tested independently
 * with controlled inputs (no Gradle project, no file I/O).
 */
class DeviationBudgetEvaluator(
    private val deviationParser: DeviationParser
) {

    /**
     * Result of evaluating one deviation against the current findings.
     */
    data class DeviationMatch(
        val deviation: DeviationParser.DeviationEntry,
        val matchingAll: List<Any?>,
        val matchingAdded: List<Any?>
    )

    /**
     * Evaluate the aggregate deviation budget for a safety metric.
     *
     * @param metricName  the metric name to filter deviations by
     * @param deviations  all parsed deviations
     * @param committedIds  identity keys from committed baseline (for delta)
     * @param currentIds    identity keys from current baseline
     * @param allCurrent    all current findings of this type
     * @param riskFilter    optional filter for risk level (e.g. "critical")
     * @param toModuleScope  extracts a FindingScope(modulePath=...) from a finding
     * @param diagnosticCode  code for NEW/added findings
     * @param riskWorseCode   code for risk worsening (or null)
     * @param riskWorseningMetricName  separate metric name for risk-worsening deviations
     * @param committedFindings  committed findings with historical risk (for risk-worsening comparison)
     * @param diagnostics  accumulator
     */
    @Suppress("UNCHECKED_CAST")
    fun evaluateDeviationBudget(
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
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        // Compute committed metric-population IDs (apply riskFilter before delta)
        val hasCommittedFindings = committedFindings != null
        val committedMetricFindings = if (hasCommittedFindings) {
            committedFindings!!
                .filter { riskFilter == null || riskFilter(it) }
        } else {
            // Fallback: convert committedIds to findings-like objects (no risk info available)
            // When committedFindings is null, compute delta from committedIds directly
            emptyList()
        }
        val committedMetricIds = if (hasCommittedFindings) {
            committedMetricFindings.map { findingIdentityKey(it) }
        } else {
            // With no committedFindings, use raw committedIds (identity-only comparison)
            // Risk filter cannot be applied to identity strings — all committed IDs are
            // counted uniformly for delta purposes
            committedIds
        }

        // Compute current metric-population IDs
        val currentMetricFindings = allCurrent
            .filter { riskFilter == null || riskFilter(it) }
        val currentMetricIds = currentMetricFindings.map { findingIdentityKey(it) }

        // Compute deltas within the metric population only
        val committedCounts = committedMetricIds.groupBy { it }.mapValues { it.value.size }
        val currentCounts = currentMetricIds.groupBy { it }.mapValues { it.value.size }

        val deltaMap = mutableMapOf<String, Int>()
        for ((key, count) in currentCounts) {
            val oldCount = committedCounts[key] ?: 0
            if (count > oldCount) deltaMap[key] = count - oldCount
        }

        // Select only the exact number of new metric-population occurrences per identity
        val addedFindings = mutableListOf<Any?>()
        if (deltaMap.isNotEmpty()) {
            val picked = mutableMapOf<String, Int>()
            for (f in currentMetricFindings) {
                val id = findingIdentityKey(f)
                val needed = deltaMap[id] ?: 0
                val have = picked[id] ?: 0
                if (have < needed) {
                    addedFindings.add(f)
                    picked[id] = have + 1
                }
            }
        }

        if (addedFindings.isNotEmpty()) {
            val relevant = deviations.filter { it.metric == metricName }
                .sortedByDescending { it.allowed }

            var remaining = addedFindings.toMutableList()

            for (dev in relevant) {
                val parsedScope = deviationParser.parseScope(dev.scope) ?: continue

                // ALL current findings matching this deviation's scope (with risk filter)
                val matchingAll = allCurrent.filter { f ->
                    val scope = toModuleScope(f)
                    (riskFilter == null || riskFilter(f)) && parsedScope.covers(scope)
                }

                if (matchingAll.size <= dev.allowed) {
                    val covered = remaining.filter { f ->
                        val scope = toModuleScope(f)
                        parsedScope.covers(scope)
                    }
                    if (covered.isNotEmpty()) {
                        diagnostics.add(VerificationDiagnostic.accepted(
                            diagnosticCode,
                            "${covered.size} new finding(s) — accepted by ${dev.id} (${matchingAll.size} total in scope ≤ ${dev.allowed})",
                            deviationId = dev.id))
                        remaining.removeAll(covered.toSet())
                    }
                }
            }

            if (remaining.isNotEmpty()) {
                val byModule = remaining.groupBy { f ->
                    val m = modulePathOf(f)
                    if (m.startsWith(":")) m else ":$m"
                }
                for ((module, findings) in byModule) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        diagnosticCode,
                        "${findings.size} new finding(s) in $module — no covering deviation",
                        modulePath = module))
                }
            }
        }

        // Risk worsening (cancellation-specific)
        if (riskWorseCode != null) {
            evaluateRiskWorsening(
                metricName = riskWorseningMetricName ?: metricName,
                deviations = deviations,
                allCurrent = allCurrent,
                committedIds = committedIds,
                committedFindings = committedFindings,
                toModuleScope = toModuleScope,
                riskWorseCode = riskWorseCode,
                diagnostics = diagnostics
            )
        }
    }

    /** Evaluate risk worsening deviations per scope. */
    fun evaluateRiskWorsening(
        metricName: String,
        deviations: List<DeviationParser.DeviationEntry>,
        allCurrent: List<Any?>,
        committedIds: List<String>,
        committedFindings: List<Any?>?,
        toModuleScope: (Any?) -> DeviationParser.FindingScope,
        riskWorseCode: DiagnosticCode,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        if (allCurrent.isEmpty() || allCurrent.first() !is CancellationCatchFinding) return

        val currentCatches = allCurrent as List<CancellationCatchFinding>

        if (committedFindings == null || committedFindings.isEmpty() || committedFindings.first() !is CancellationCatchFinding) {
            // Fallback: report resolved findings as improvement
            val currentKeys = currentCatches.map { FindingIdentity.fromCancellationCatch(it).toIdentityKey() }.toSet()
            val resolvedIds = committedIds.filter { it !in currentKeys }.toSet()
            if (resolvedIds.isNotEmpty()) {
                diagnostics.add(VerificationDiagnostic.improvement(
                    riskWorseCode,
                    "${resolvedIds.size} cancellation catch(es) resolved — risk worsening check skipped for resolved findings"))
            }
            return
        }

        val committedCatches = committedFindings as List<CancellationCatchFinding>

        // Build multiset: for each identity, collect ALL historical risks sorted ascending
        val committedByIdentity = committedCatches.groupBy {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }.mapValues { (_, catches) ->
            catches.map { it.risk }.sortedBy(::riskOrder)
        }

        // Group current findings by identity with their risks
        val currentByIdentity = currentCatches.groupBy {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }

        // Pair committed and current occurrences 1:1 by sorted risk and detect worsenings
        val worsened = mutableListOf<CancellationCatchFinding>()
        for ((id, currentForId) in currentByIdentity) {
            val committedRisks = committedByIdentity[id] ?: continue // only findings that existed before
            val currentRiskLevels = currentForId.map { it.risk }.sortedBy(::riskOrder)

            // Pair occurrences by ascending sorted position
            // committedRisks[i] vs currentRiskLevels[i] determines if that slot worsened
            for (i in 0 until minOf(committedRisks.size, currentRiskLevels.size)) {
                if (riskOrder(committedRisks[i]) < riskOrder(currentRiskLevels[i])) {
                    val worsenedFinding = currentForId.first { it.risk == currentRiskLevels[i] }
                    worsened.add(worsenedFinding)
                }
            }
        }

        if (worsened.isEmpty()) return

        val riskDeviations = deviations.filter { it.metric == metricName }
            .sortedByDescending { it.allowed }

        var remaining = worsened.toMutableList()

        for (dev in riskDeviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue

            val matching = worsened.count { f ->
                parsedScope.covers(toModuleScope(f))
            }

            if (matching <= dev.allowed) {
                val covered = remaining.filter { f ->
                    parsedScope.covers(toModuleScope(f))
                }
                if (covered.isNotEmpty()) {
                    diagnostics.add(VerificationDiagnostic.accepted(
                        riskWorseCode,
                        "${covered.size} risk worsenings — accepted by ${dev.id} (${matching} total in scope ≤ ${dev.allowed})",
                        deviationId = dev.id))
                    remaining.removeAll(covered.toSet())
                }
            }
        }

        if (remaining.isNotEmpty()) {
            remaining.forEach { f ->
                val ccf = f as CancellationCatchFinding
                diagnostics.add(VerificationDiagnostic.failure(
                    riskWorseCode,
                    "Cancellation catch risk worsened: ${ccf.module}/${ccf.file}:${ccf.function} — no covering deviation",
                    modulePath = ccf.module))
            }
        }
    }

    companion object {
        /** Extract the module path string from an arbitrary finding. */
        fun modulePathOf(finding: Any?): String {
            return when (finding) {
                is CancellationCatchFinding -> finding.module
                is GlobalStateFinding -> finding.module
                is NondeterminismFinding -> finding.module
                else -> ""
            }
        }

        /** Extract a stable identity key from an arbitrary finding. */
        fun findingIdentityKey(finding: Any?): String {
            return when (finding) {
                is CancellationCatchFinding -> FindingIdentity.fromCancellationCatch(finding).toIdentityKey()
                is GlobalStateFinding -> FindingIdentity.fromGlobalState(finding).toIdentityKey()
                is NondeterminismFinding -> FindingIdentity.fromNondeterminism(finding).toIdentityKey()
                else -> ""
            }
        }

        /** Compute the FindingScope for module-based matching from a finding. */
        fun moduleScope(finding: Any?): DeviationParser.FindingScope {
            val m = modulePathOf(finding)
            val norm = if (m.startsWith(":")) m else ":$m"
            return DeviationParser.FindingScope(norm, null, null)
        }

        private fun riskOrder(risk: String): Int = when (risk) {
            "accepted" -> 1; "low" -> 2; "medium" -> 3; "high" -> 4; "critical" -> 5; else -> 0
        }
    }
}
