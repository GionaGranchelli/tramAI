package dev.tramai.build.quality

/**
 * Pure comparison of the compiler-warning baseline at the certified PR base
 * against the current working-tree baseline. No Gradle, no git — operates on
 * baseline JSON content strings (parsed via [CompilerWarningsBaselineIo]).
 *
 * Growth contract (Epic 10.1c, mirrors 10.1b section G):
 *  1. current entry removed       -> PASS (debt paid down; removals always allowed)
 *  2. current unchanged           -> PASS
 *  3. current count decreased     -> PASS
 *  4. new baseline identity added -> FAIL (COMPILER_BASELINE_GROWTH) — expanding the
 *                                    baseline alongside new warnings is the primary
 *                                    allowance-file attack; it must never pass.
 *  5. current count increased     -> FAIL (same attack, multiplicity flavour)
 *  6. base malformed              -> FAIL
 *  7. current malformed           -> FAIL
 *  8. both absent                 -> FAIL
 *  9. base absent + current valid -> PASS only as bootstrap: the baseline is being
 *                                    introduced by this PR. Once master carries the
 *                                    file this path can never reactivate (delete-and-
 *                                    recreate hits case 8/current-malformed instead).
 * 10. base valid + current absent -> FAIL (deleted baseline)
 */
data class CompilerWarningsGrowthInput(
    val baseBaselineJson: String?,
    val currentBaselineJson: String?,
)

data class CompilerWarningsGrowthVerdict(
    val passed: Boolean,
    val code: String?,
    val added: List<String>,
    val removed: List<String>,
    val grown: List<String>,
    val baseTotal: Int,
    val currentTotal: Int,
    val message: String,
) {
    companion object {
        const val GROWTH = "COMPILER_BASELINE_GROWTH"
        const val DELETED = "COMPILER_BASELINE_DELETED"
        const val MALFORMED = "COMPILER_BASELINE_MALFORMED"
    }
}

object CompilerWarningsBaselineGrowthVerifier {
    fun verify(input: CompilerWarningsGrowthInput): CompilerWarningsGrowthVerdict =
        when {
            input.currentBaselineJson == null -> {
                fail(CompilerWarningsGrowthVerdict.DELETED, "current compiler-warning baseline is absent/deleted")
            }

            input.baseBaselineJson == null -> {
                pass("bootstrap: base baseline absent, current present (first adoption).")
            }

            isLegacySchema(input.baseBaselineJson) -> {
                pass(
                    "base baseline uses the legacy v1 fingerprint schema; this one-time schema-migration " +
                        "rewrite is allowed (the full compile still validates the current inventory).",
                )
            }

            else -> {
                compareBaselines(input.baseBaselineJson, input.currentBaselineJson)
            }
        }

    /** True only for an explicit v1 baseline; anything else falls through to fail-closed parsing. */
    private fun isLegacySchema(baseJson: String): Boolean {
        val tree = runCatching { CompilerWarningsBaselineIo.readTree(baseJson) }.getOrNull() ?: return false
        val entries = tree.get("entries")
        val schema = tree.get("schemaVersion")
        return entries != null && entries.isArray &&
            schema != null && schema.isIntegralNumber && schema.asInt() == LEGACY_SCHEMA_VERSION
    }

    private const val LEGACY_SCHEMA_VERSION = 1

    private fun compareBaselines(
        baseJson: String,
        currentJson: String,
    ): CompilerWarningsGrowthVerdict {
        val baseEntries = CompilerWarningsBaselineIo.fromJson(baseJson)
        val currentEntries = CompilerWarningsBaselineIo.fromJson(currentJson)
        if (baseEntries == null || currentEntries == null) {
            return fail(
                CompilerWarningsGrowthVerdict.MALFORMED,
                if (baseEntries == null) {
                    "base compiler-warning baseline is malformed"
                } else {
                    "current compiler-warning baseline is malformed"
                },
            )
        }
        val baseByIdentity = baseEntries.associateBy { identity(it) }
        val currentByIdentity = currentEntries.associateBy { identity(it) }

        val added = currentEntries.filter { identity(it) !in baseByIdentity }.map { identity(it).toString() }
        val removed = baseEntries.filter { identity(it) !in currentByIdentity }.map { identity(it).toString() }
        val grown =
            currentEntries
                .filter { entry ->
                    val baseEntry = baseByIdentity[identity(entry)]
                    baseEntry != null && entry.count > baseEntry.count
                }.map { entry ->
                    val baseEntry = baseByIdentity.getValue(identity(entry))
                    "${identity(entry)}: ${baseEntry.count} -> ${entry.count}"
                }

        return if (added.isEmpty() && grown.isEmpty()) {
            CompilerWarningsGrowthVerdict(
                passed = true,
                code = null,
                added = emptyList(),
                removed = removed,
                grown = emptyList(),
                baseTotal = baseEntries.size,
                currentTotal = currentEntries.size,
                message = "baseline only shrank or stayed flat.",
            )
        } else {
            val lines =
                added.map { "  ADDED    $it" } +
                    grown.map { "  GROWN    $it" } +
                    removed.map { "  REMOVED  $it" }
            CompilerWarningsGrowthVerdict(
                passed = false,
                code = CompilerWarningsGrowthVerdict.GROWTH,
                added = added,
                removed = removed,
                grown = grown,
                baseTotal = baseEntries.size,
                currentTotal = currentEntries.size,
                message =
                    "compiler-warning baseline grew: the baseline is a ceiling, not an allowance file.\n" +
                        lines.joinToString("\n"),
            )
        }
    }

    private fun identity(entry: WarningEntry) = Triple(entry.path, entry.diagnostic, entry.message)

    // spotless re-joins single-expression functions; the resulting line exceeds
    // 120 cols, so detekt is told to look the other way.
    @Suppress("MaxLineLength")
    private fun pass(message: String): CompilerWarningsGrowthVerdict =
        CompilerWarningsGrowthVerdict(true, null, emptyList(), emptyList(), emptyList(), 0, 0, message)

    private fun fail(
        code: String,
        message: String,
    ) = CompilerWarningsGrowthVerdict(false, code, emptyList(), emptyList(), emptyList(), 0, 0, message)
}
