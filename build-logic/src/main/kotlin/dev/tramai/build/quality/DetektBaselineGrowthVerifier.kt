package dev.tramai.build.quality

/**
 * Pure comparison of the Detekt baseline at the certified PR base against the
 * current baseline. No Gradle, no git — operates on XML content strings.
 *
 * Growth contract (Epic 10.1b, section G):
 *  1. current entry removed      -> PASS (debt paid down; removals always allowed)
 *  2. current unchanged          -> PASS
 *  3. new baseline entry added   -> FAIL with DETEKT_BASELINE_GROWTH (unless baseline-migration)
 *  4. malformed baseline         -> FAIL
 *  5. duplicate baseline ID      -> FAIL
 *  6. baseline file deleted      -> FAIL
 *  7. baseline replaced with an empty document -> FAIL
 *
 * Bootstrap (section I): base baseline ABSENT + current present is permitted
 * only for the initial static-analysis adoption: no TramAI runtime production
 * source changes and an explicit non-runtime change class. Because the check
 * keys on the BASE file's absence, once master carries a Detekt baseline the
 * bootstrap path can never be reactivated by delete-and-recreate.
 */
data class DetektGrowthInput(
    val baseBaselineXml: String?,
    val currentBaselineXml: String?,
    val changeClass: String?,
    val runtimeSourceChanged: Boolean,
)

data class DetektGrowthVerdict(
    val passed: Boolean,
    val code: String?,
    val added: List<String>,
    val removed: List<String>,
    val baseTotal: Int,
    val currentTotal: Int,
    val message: String,
) {
    companion object {
        const val GROWTH = "DETEKT_BASELINE_GROWTH"
        const val DELETED = "DETEKT_BASELINE_DELETED"
        const val EMPTIED = "DETEKT_BASELINE_EMPTIED"
        const val MALFORMED = "DETEKT_BASELINE_MALFORMED"
        const val BOOTSTRAP_ABUSE = "DETEKT_BASELINE_BOOTSTRAP_ABUSE"
    }
}

object DetektBaselineGrowthVerifier {
    fun verify(input: DetektGrowthInput): DetektGrowthVerdict {
        val base =
            when (val r = DetektBaselineParser.parse(input.baseBaselineXml)) {
                is BaselineParseResult.Success -> {
                    r.document
                }

                BaselineParseResult.NotFound -> {
                    null
                }

                is BaselineParseResult.Invalid -> {
                    return fail(DetektGrowthVerdict.MALFORMED, "base Detekt baseline is malformed: ${r.reason}")
                }
            }
        val current =
            when (val r = DetektBaselineParser.parse(input.currentBaselineXml)) {
                is BaselineParseResult.Success -> {
                    r.document
                }

                BaselineParseResult.NotFound -> {
                    null
                }

                is BaselineParseResult.Invalid -> {
                    return fail(DetektGrowthVerdict.MALFORMED, "current Detekt baseline is malformed: ${r.reason}")
                }
            }

        // Rule 6: deletion — never allowed, not even for migrations.
        if (base != null && current == null) {
            return fail(
                DetektGrowthVerdict.DELETED,
                "The Detekt baseline file was deleted. The baseline freezes pre-existing debt; " +
                    "removal requires an explicit, reviewed migration.",
            )
        }

        // Bootstrap (rule I): base absent + current present = initial adoption only.
        if (base == null && current != null) {
            val initialAdoption =
                !input.runtimeSourceChanged &&
                    input.changeClass != null &&
                    input.changeClass.isNotBlank() &&
                    input.changeClass != "runtime-behaviour"
            if (initialAdoption) {
                return pass(
                    code = null,
                    added = current.currentIssueIds.toList().sorted(),
                    removed = emptyList(),
                    baseTotal = 0,
                    currentTotal = current.currentIssueIds.size,
                    message =
                        "Initial Detekt baseline adoption: ${current.currentIssueIds.size} finding(s) frozen. " +
                            "This bootstrap path is one-time; it is keyed on the base file's absence.",
                )
            }
            return fail(
                DetektGrowthVerdict.BOOTSTRAP_ABUSE,
                "A Detekt baseline appeared but this is not the initial static-analysis adoption " +
                    "(runtime production source changed or change class is runtime-behaviour). " +
                    "Baselines may only be introduced by the adoption PR.",
            )
        }

        // Both absent: nothing to verify (pre-adoption state).
        if (base == null) {
            return pass(null, emptyList(), emptyList(), 0, 0, "No Detekt baseline at base or current ref.")
        }
        val baseDoc = requireNotNull(base)
        val currentDoc = requireNotNull(current)

        // Rule 7: replaced with an empty document.
        if (currentDoc.currentIssueIds.isEmpty() && baseDoc.currentIssueIds.isNotEmpty()) {
            return fail(
                DetektGrowthVerdict.EMPTIED,
                "The Detekt baseline was emptied (${baseDoc.currentIssueIds.size} base entries, 0 current). " +
                    "Debt is paid down entry-by-entry; mass-emptying the baseline requires explicit review.",
            )
        }

        val added = (currentDoc.currentIssueIds - baseDoc.currentIssueIds).sorted()
        val removed = (baseDoc.currentIssueIds - currentDoc.currentIssueIds).sorted()
        val migrationAuthorized = input.changeClass == "baseline-migration"

        if (added.isNotEmpty() && !migrationAuthorized) {
            val detail = added.take(10).joinToString("\n    ") { "  + $it" }
            return fail(
                DetektGrowthVerdict.GROWTH,
                "Detekt baseline grew by ${added.size} entry/entries " +
                    "(base ${baseDoc.currentIssueIds.size} -> current ${currentDoc.currentIssueIds.size}).\n" +
                    "New baseline entries require an explicit baseline-migration PR " +
                    "(-PchangeClass=baseline-migration) with the analyzer/configuration change that justifies them.\n" +
                    "Added entries:\n$detail",
            )
        }

        return pass(
            code = null,
            added = added,
            removed = removed,
            baseTotal = baseDoc.currentIssueIds.size,
            currentTotal = currentDoc.currentIssueIds.size,
            message =
                "Detekt baseline OK: base ${baseDoc.currentIssueIds.size} -> current " +
                    "${currentDoc.currentIssueIds.size}; " +
                    "${removed.size} removed, ${added.size} added.",
        )
    }

    private fun pass(
        code: String?,
        added: List<String>,
        removed: List<String>,
        baseTotal: Int,
        currentTotal: Int,
        message: String,
    ) = DetektGrowthVerdict(
        passed = true,
        code = code,
        added = added,
        removed = removed,
        baseTotal = baseTotal,
        currentTotal = currentTotal,
        message = message,
    )

    private fun fail(
        code: String,
        message: String,
    ) = DetektGrowthVerdict(
        passed = false,
        code = code,
        added = emptyList(),
        removed = emptyList(),
        baseTotal = 0,
        currentTotal = 0,
        message = message,
    )
}
