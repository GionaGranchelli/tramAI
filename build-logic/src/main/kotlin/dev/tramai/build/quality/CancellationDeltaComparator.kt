package dev.tramai.build.quality

/**
 * Pure, testable comparison of cancellation catch findings by risk population matching,
 * not position-based identity.
 *
 * Phase 1 — normal matching, file-aware:
 *
 * For each (module, file, function, catchType) group:
 *   1. Build multiset of risk values from base catches
 *   2. Build multiset of risk values from current catches
 *   3. Match identical risk values first (remove matched pairs)
 *   4. Sort remaining base and current DESCENDING by risk order
 *   5. Pair 1:1 by sorted position — if current > base → worsened
 *   6. Left-over current entries become relocation candidates
 *   7. Left-over base entries → removed (pass)
 *   8. Source line position used ONLY for diagnostic display, never identity
 *
 * Phase 2 — conservative relocation pass over UNMATCHED findings only:
 *
 * A finding that moved to a different file (same module, function, catchType,
 * risk) is a relocation, not a new finding. This is deliberately conservative:
 *   - It runs only over findings left unmatched by the file-aware phase.
 *   - A relocation is accepted only when it is uniquely attributable AND
 *     backed by source-content evidence:
 *       • exactly one base candidate and exactly one current candidate share
 *         the (module, function, catchType, risk) key across different files
 *       • both candidates carry a non-blank normalized source fingerprint of
 *         their catch site (computed at scan time from the actual source text)
 *       • the fingerprints match — the same code text is present at both sites
 *   - A unique 1:1 semantic match WITHOUT matching content is NOT a relocation:
 *     it is an unrelated replacement with coincidentally identical metadata,
 *     and the current finding is flagged. Source line position is used ONLY
 *     for diagnostic display, never identity.
 *   - If any current candidate is ambiguous (multiple base candidates, or
 *     vice versa), NONE of the candidates in that key are relocated — the
 *     safe bias is to flag the current finding rather than silently accept.
 *
 * This keeps the primary identity file-aware (a new critical catch in the
 * same function of a different file is still flagged) while allowing pure
 * refactor relocations to pass when content proves the move.
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

        // ── Phase 1: file-aware normal matching ──
        // Group by (module, file, function, catchType) — file is part of the
        // primary identity so a genuinely new catch in a different file is
        // never masked by an existing catch with the same function name.
        val groupKey: (CancellationCatchFinding) -> String = {
            "${it.module}::${it.file}::${it.function}::${it.catchType}"
        }

        val baseByGroup = baseCatches.groupBy(groupKey)
        val currentByGroup = currentCatches.groupBy(groupKey)

        val allGroups = (baseByGroup.keys + currentByGroup.keys).toSet()

        // Collect every finding that Phase 1 could not match, for Phase 2.
        val baseRelocationCandidates = mutableListOf<CancellationCatchFinding>()
        val currentRelocationCandidates = mutableListOf<CancellationCatchFinding>()

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

            // 4. Left-over current entries are relocation candidates (Phase 2),
            //    not immediately "new" — they may have moved across files.
            currentRelocationCandidates += currentSorted.drop(pairCount)
            // Base surplus (entries at i >= pairCount in baseSorted) are
            // improvements/removals — pass silently, but keep them available
            // as relocation candidates for Phase 2.
            baseRelocationCandidates += baseSorted.drop(pairCount)

            unchanged += matchedCurrentIdxs.size
        }

        // ── Phase 2: conservative relocation pass ──
        // Only findings left unmatched by Phase 1 may be relocated. A relocation
        // must be uniquely attributable: exactly one base and one current
        // candidate per (module, function, catchType, risk) key, in different
        // files. Ambiguity on either side → flag the current candidate.
        val relocationKey: (CancellationCatchFinding) -> String = {
            "${it.module}::${it.function}::${it.catchType}::${it.risk}"
        }

        val baseByRelocationKey = baseRelocationCandidates.groupBy(relocationKey)
        val currentByRelocationKey = currentRelocationCandidates.groupBy(relocationKey)

        val allRelocationKeys = (baseByRelocationKey.keys + currentByRelocationKey.keys).toSet()

        for (key in allRelocationKeys.sorted()) {
            val baseForKey = baseByRelocationKey[key] ?: emptyList()
            val currentForKey = currentByRelocationKey[key] ?: emptyList()

            val baseCandidate = baseForKey.singleOrNull()
            val currentCandidate = currentForKey.singleOrNull()

            // A relocation is accepted only when it is uniquely attributable
            // AND backed by source-content evidence: exactly one base and one
            // current candidate per key, in different files, with matching
            // non-blank normalized source fingerprints. The fingerprint is the
            // proof the same code text moved; without it a unique 1:1 semantic
            // match is an unrelated replacement (coincidental metadata), and
            // the safe bias is to flag the current finding.
            val baseFingerprint = baseCandidate?.sourceFingerprint.orEmpty().trim()
            val currentFingerprint = currentCandidate?.sourceFingerprint.orEmpty().trim()
            val fingerprintsProveMove = baseFingerprint.isNotEmpty() &&
                baseFingerprint == currentFingerprint

            val isUniqueRelocation = baseCandidate != null &&
                currentCandidate != null &&
                baseCandidate.file != currentCandidate.file &&
                fingerprintsProveMove

            if (isUniqueRelocation) {
                unchanged++
                diagnostics.add(
                    "relocated: ${baseCandidate.file}:${baseCandidate.sourceLine} → " +
                        "${currentCandidate.file}:${currentCandidate.sourceLine} (${currentCandidate.function}, risk=${currentCandidate.risk}, fingerprint match)"
                )
            } else {
                // Ambiguous (multiple candidates on either side, or same file) →
                // treat every current candidate as new. Safe bias.
                currentForKey.forEach { finding ->
                    if (finding.risk == "critical" || finding.risk == "high") {
                        newCriticalHigh.add(finding)
                    }
                }
            }
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
