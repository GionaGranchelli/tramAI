package dev.tramai.build.quality

/**
 * Pure fail-closed verifier for the Epic 8.3d nondeterminism authority contract.
 *
 * Matching is one-to-one on the semantic identity (module, file, source) —
 * line numbers never participate, so line movement cannot invalidate an
 * otherwise unchanged allowlist entry.
 *
 * Failure families (deterministic, sorted):
 *  - [NondeterminismAllowlistParser.ParseResult] diagnostics (schema/disposition/
 *    rationale/duplicate) are reported as-is;
 *  - UNCLASSIFIED_FINDING — a scanner finding whose identity has no allowlist entry;
 *  - STALE_ALLOWLIST_ENTRY — an allowlist entry whose identity no longer exists;
 *  - MISMATCHED_CLASSIFICATION — identity matches but category/scannerClassification
 *    differ between the entry and the finding;
 *  - OCCURRENCE_MISMATCH — the same identity appears more (or fewer) times than the
 *    entry's `occurrences`.
 *
 * Never throws from comparison logic — returns typed diagnostics and lets the
 * Gradle task convert failures into a GradleException.
 */
class NondeterminismAllowlistVerifier(
    private val findings: List<NondeterminismFinding>,
    private val entries: List<NondeterminismAllowlistParser.AllowlistEntry>
) {

    data class VerificationSummary(
        val totalFindings: Int,
        val findingsByCategory: Map<String, Int>,
        val findingsByDisposition: Map<String, Int>,
        val unclassifiedCount: Int,
        val staleCount: Int,
        val passed: Boolean
    )

    fun verify(): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()

        val findingsByKey = findings.groupBy { it.identityKey() }
        val entriesByKey = entries.groupBy { it.identityKey }

        // 1. Every finding must be classified exactly once.
        for ((key, group) in findingsByKey) {
            val covering = entriesByKey[key].orEmpty()
            if (covering.isEmpty()) {
                val f = group.first()
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING,
                    "Unclassified nondeterminism: ${f.file}:${f.line} ${f.source} " +
                        "(module ${f.module}, category ${f.category}) — add an entry to " +
                        "config/quality/runtime-nondeterminism.yml",
                    modulePath = f.module,
                    findingId = key
                ))
            } else {
                val entry = covering.first()
                val f = group.first()
                // 2. Classification mismatch: category or scanner classification differ.
                if (entry.category != f.category || entry.scannerClassification != f.classification) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_MISMATCHED_CLASSIFICATION,
                        "Mismatched classification for ${f.file} ${f.source}: " +
                            "entry category=${entry.category} scannerClassification=${entry.scannerClassification}; " +
                            "scanner category=${f.category} classification=${f.classification}",
                        modulePath = f.module,
                        findingId = key
                    ))
                }
                // 3. Occurrence count must match.
                if (group.size != entry.occurrences) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NONDETERMINISM_OCCURRENCE_MISMATCH,
                        "Occurrence mismatch for ${f.file} ${f.source}: " +
                            "scanner reports ${group.size}, allowlist declares ${entry.occurrences}",
                        modulePath = f.module,
                        findingId = key
                    ))
                }
            }
        }

        // 4. Every allowlist entry must correspond to a live finding (fail closed on stale).
        for ((key, group) in entriesByKey) {
            if (!findingsByKey.containsKey(key)) {
                val entry = group.first()
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NONDETERMINISM_STALE_ENTRY,
                    "Stale allowlist entry: ${entry.file} ${entry.source} " +
                        "(module ${entry.module}) — no matching scanner finding; remove or update the entry",
                    modulePath = entry.module,
                    findingId = key
                ))
            }
        }

        // 5. Deterministic ordering: code, then module, then file, then source.
        return diagnostics.sortedWith(
            compareBy(
                { it.code.ordinal },
                { it.modulePath ?: "" },
                { it.findingId ?: "" }
            )
        )
    }

    fun summary(verificationDiagnostics: List<VerificationDiagnostic>): VerificationSummary {
        val byCategory = findings.groupBy { it.category }.mapValues { it.value.size }
        val classified = findings.filter { f ->
            entries.any { it.identityKey == f.identityKey() }
        }
        val byDisposition = classified
            .mapNotNull { f -> entries.firstOrNull { it.identityKey == f.identityKey() }?.disposition }
            .groupingBy { it }
            .eachCount()
        return VerificationSummary(
            totalFindings = findings.size,
            findingsByCategory = byCategory,
            findingsByDisposition = byDisposition,
            unclassifiedCount = verificationDiagnostics.count {
                it.code == DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING
            },
            staleCount = verificationDiagnostics.count {
                it.code == DiagnosticCode.NONDETERMINISM_STALE_ENTRY
            },
            passed = verificationDiagnostics.none { it.severity == DiagnosticSeverity.FAILURE }
        )
    }
}
