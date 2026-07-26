package dev.tramai.build.quality

/**
 * Pure, testable comparison of cancellation catch findings by risk population matching,
 * not position-based identity.
 *
 * For each (module, file, function, catchType) group:
 *   1. Build multiset of risk values from base catches
 *   2. Build multiset of risk values from current catches
 *   3. Match identical risk values first (remove matched pairs)
 *   4. Sort remaining base and current DESCENDING by risk order
 *   5. Pair 1:1 by sorted position — if current > base → worsened
 *   6. Any left-over unmatched current entries → truly new (fail if critical/high)
 *   7. Any left-over base entries → removed (pass)
 *   8. Source line position used ONLY for diagnostic display, never identity
 */
object CancellationDeltaComparator {

    data class Result(
        val newCriticalHigh: List<CancellationCatchFinding>,
        val worsened: List<Worsening>,
        val unchanged: Int,
        val diagnostics: List<String>
    )

    data class Worsening(
        val base: CancellationCatchFinding,
        val current: CancellationCatchFinding
    )

    fun compare(baseCatches: List<CancellationCatchFinding>, currentCatches: List<CancellationCatchFinding>): Result {
        val newCriticalHigh = mutableListOf<CancellationCatchFinding>()
        val worsened = mutableListOf<Worsening>()
        var unchanged = 0
        val diagnostics = mutableListOf<String>()

        diagnostics.add("verifyCancellationSafety: ${currentCatches.size} current, ${baseCatches.size} base findings")

        // Group by (module, file, function, catchType) — same coarse group
        val groupKey: (CancellationCatchFinding) -> String = {
            "${it.module}::${it.file}::${it.function}::${it.catchType}"
        }

        val baseByGroup = baseCatches.groupBy(groupKey)
        val currentByGroup = currentCatches.groupBy(groupKey)

        val allGroups = (baseByGroup.keys + currentByGroup.keys).toSet()

        for (group in allGroups.sorted()) {
            val baseForGroup = baseByGroup[group] ?: emptyList()
            val currentForGroup = currentByGroup[group] ?: emptyList()

            // Work directly with finding objects throughout
            val baseRemaining = baseForGroup.toMutableList()
            val currentRemaining = currentForGroup.toMutableList()

            // 1. Exact match: remove identical risk pairs 1:1
            val matchedCurrentIdxs = mutableSetOf<Int>()
            val matchedBaseIdxs = mutableSetOf<Int>()
            for (ci in currentRemaining.indices) {
                if (ci in matchedCurrentIdxs) continue
                for (bi in baseRemaining.indices) {
                    if (bi in matchedBaseIdxs) continue
                    if (currentRemaining[ci].risk == baseRemaining[bi].risk) {
                        matchedCurrentIdxs.add(ci)
                        matchedBaseIdxs.add(bi)
                        break
                    }
                }
            }

            val baseUnmatched = baseRemaining.filterIndexed { i, _ -> i !in matchedBaseIdxs }
            val currentUnmatched = currentRemaining.filterIndexed { i, _ -> i !in matchedCurrentIdxs }

            // 2. Sort remaining DESCENDING by risk order (highest risk first)
            val baseSorted = baseUnmatched.sortedByDescending { riskOrder(it.risk) }
            val currentSorted = currentUnmatched.sortedByDescending { riskOrder(it.risk) }

            // 3. Pair at each index: if current > base → worsened
            val pairCount = minOf(baseSorted.size, currentSorted.size)
            for (i in 0 until pairCount) {
                val baseFinding = baseSorted[i]
                val currentFinding = currentSorted[i]
                if (riskOrder(currentFinding.risk) > riskOrder(baseFinding.risk)) {
                    worsened.add(Worsening(baseFinding, currentFinding))
                } else {
                    unchanged++
                }
            }

            // 4. Extra current entries are truly new (fail if critical/high)
            for (i in pairCount until currentSorted.size) {
                val finding = currentSorted[i]
                if (finding.risk == "critical" || finding.risk == "high") {
                    newCriticalHigh.add(finding)
                }
            }
            // Base surplus (entries at i >= pairCount in baseSorted) are improvements — pass silently

            unchanged += matchedCurrentIdxs.size
        }

        // Build diagnostics
        if (newCriticalHigh.isNotEmpty()) {
            diagnostics.add("${newCriticalHigh.size} new critical/high cancellation catch(es):")
            newCriticalHigh.forEach { f ->
                diagnostics.add("  ${f.module}:${f.file}:${f.sourceLine} -> ${f.function} (${f.catchType}, risk=${f.risk})")
            }
        }
        if (worsened.isNotEmpty()) {
            diagnostics.add("${worsened.size} risk worsening(s):")
            worsened.forEach { w ->
                diagnostics.add("  ${w.base.risk} → ${w.current.risk} at ${w.current.module}:${w.current.file}:${w.current.sourceLine} (${w.current.function})")
            }
        }

        return Result(
            newCriticalHigh = newCriticalHigh,
            worsened = worsened,
            unchanged = unchanged,
            diagnostics = diagnostics
        )
    }

    private fun riskOrder(risk: String): Int = when (risk) {
        "accepted" -> 1
        "low" -> 2
        "medium" -> 3
        "high" -> 4
        "critical" -> 5
        else -> 0
    }
}
