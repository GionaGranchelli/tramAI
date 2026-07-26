package dev.tramai.build.quality

/**
 * Pure, testable comparison of cancellation catch findings by risk population matching,
 * not position-based identity.
 *
 * For each (module, file, function, catchType) group:
 *   1. Build multiset of risk values from base catches
 *   2. Build multiset of risk values from current catches
 *   3. Match identical risk values first (remove matched pairs)
 *   4. Sort remaining by risk order, pair 1:1 by sorted position
 *   5. If a current paired entry has higher risk than the base → worsened
 *   6. Any left-over unmatched current entries → truly new
 *   7. Only flag new/worsened findings that are "critical" or "high" risk
 *   8. Source line position used ONLY for diagnostic display, never identity
 */
object CancellationDeltaComparator {

    data class Result(
        val newCriticalHigh: List<CancellationCatchFinding>,
        val worsened: List<Pair<String, String>>, // oldRisk -> newRisk
        val unchanged: Int,
        val diagnostics: List<String>
    )

    fun compare(baseCatches: List<CancellationCatchFinding>, currentCatches: List<CancellationCatchFinding>): Result {
        val newCriticalHigh = mutableListOf<CancellationCatchFinding>()
        val worsened = mutableListOf<Pair<String, String>>()
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
            val baseRisks = baseForGroup.map { it.risk }.toMutableList()
            val currentRisks = currentForGroup.map { it.risk }.toMutableList()

            // Match identical risk values first (remove matched pairs)
            val matchedPairs = mutableListOf<Pair<String, String>>()
            val baseUnmatched = baseRisks.toMutableList()
            val currentUnmatched = currentRisks.toMutableList()

            // 1:1 matching by exact risk level
            val matchedIdxs = mutableSetOf<Int>()
            val baseMatchedIdxs = mutableSetOf<Int>()
            for (ci in currentUnmatched.indices) {
                if (ci in matchedIdxs) continue
                for (bi in baseUnmatched.indices) {
                    if (bi in baseMatchedIdxs) continue
                    if (currentUnmatched[ci] == baseUnmatched[bi]) {
                        matchedPairs.add(baseUnmatched[bi] to currentUnmatched[ci])
                        matchedIdxs.add(ci)
                        baseMatchedIdxs.add(bi)
                        break
                    }
                }
            }

            val remainingBase = baseUnmatched.filterIndexed { i, _ -> i !in baseMatchedIdxs }
            val remainingCurrent = currentUnmatched.filterIndexed { i, _ -> i !in matchedIdxs }

            // Sort remaining by risk order
            val sortedBase = remainingBase.sortedBy(::riskOrder)
            val sortedCurrent = remainingCurrent.sortedBy(::riskOrder)

            // Pair remaining 1:1 by sorted position
            val pairCount = minOf(sortedBase.size, sortedCurrent.size)
            for (i in 0 until pairCount) {
                if (riskOrder(sortedBase[i]) < riskOrder(sortedCurrent[i])) {
                    worsened.add(sortedBase[i] to sortedCurrent[i])
                } else {
                    unchanged++
                }
            }

            // Any left-over current entries are truly new
            for (i in pairCount until sortedCurrent.size) {
                val risk = sortedCurrent[i]
                if (risk == "critical" || risk == "high") {
                    // Find the actual finding object for diagnostic context
                    val finding = currentForGroup.firstOrNull { it.risk == risk }
                    if (finding != null) {
                        newCriticalHigh.add(finding)
                    }
                }
            }

            unchanged += matchedPairs.size
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
            worsened.forEach { (oldRisk, newRisk) ->
                diagnostics.add("  $oldRisk → $newRisk")
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
