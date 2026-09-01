package dev.tramai.build.quality

/**
 * Typed severity for verification diagnostics.
 * Severity is derived from the diagnostic code and finding data,
 * not from parsing human-readable message text.
 */
enum class DiagnosticSeverity {
    FAILURE,
    WARNING,
    ACCEPTED,
    IMPROVEMENT,
}

/**
 * Typed diagnostic codes for every verifier gate.
 * Each represents one specific class of finding that the verifier can emit.
 */
enum class DiagnosticCode {
    // Identity & provenance
    BASELINE_IDENTITY_MISMATCH,
    ANALYZER_COMMIT_NOT_ANCESTOR,
    MEASURED_TREE_MISMATCH,
    TAG_COMMIT_MISMATCH,
    TAG_TREE_MISMATCH,
    DIRTY_WORKTREE,

    // Module catalogue
    MODULE_CATALOG_MISSING_ENTRY,
    MODULE_CATALOG_UNKNOWN_ENTRY,
    MODULE_CATALOG_DUPLICATE_PATH,
    MODULE_CATALOG_INVALID_LAYER,
    MODULE_CATALOG_MISSING_API_STABILITY,
    MODULE_CATALOG_EXAMPLE_PUBLISHABLE,
    MODULE_CATALOG_DISAGREEMENT,
    MODULE_CATALOG_INVALID_SCHEMA,
    MODULE_CATALOG_INVALID_MATURITY,
    MODULE_CATALOG_INVALID_PUBLISHABILITY,
    MODULE_CATALOG_INVALID_VISIBILITY,
    MODULE_CATALOG_INVALID_RELEASE_INCLUSION,
    MODULE_CATALOG_INVALID_POLICY,
    MODULE_CATALOG_BLANK_OWNER,
    MODULE_CATALOG_BLANK_RATIONALE,
    MODULE_CATALOG_MISSING_DESCRIPTION,
    MODULE_CATALOG_INVALID_COMBINATION,
    MODULE_CATALOG_BOM_DRIFT,
    MODULE_CATALOG_PUBLISHING_DRIFT,

    // Dependency & architecture
    NEW_DEPENDENCY_CYCLE,
    FORBIDDEN_LAYER_EDGE,
    SELF_DEPENDENCY,

    // Public API baseline
    API_BASELINE_EMPTY,
    API_DUMP_MISSING,
    API_DUMP_DUPLICATE,
    API_MODULE_UNCLASSIFIED,
    API_VALIDATION_NOT_CONFIGURED,
    API_COMPATIBILITY_FAILED,
    API_HASH_CHANGED, // NEW: aggregate hash changed (compatible change)
    API_DUMP_NONDETERMINISTIC, // NEW: dump contains absolute paths/timestamps

    // Resolved external dependency baseline
    DEPENDENCY_BASELINE_EMPTY,
    DEPENDENCY_RESOLUTION_FAILED,
    DYNAMIC_DEPENDENCY_VERSION,
    SNAPSHOT_DEPENDENCY,
    DEPENDENCY_CONVERGENCE_FAILURE,
    DEPENDENCY_ADDED,
    DEPENDENCY_REMOVED,
    DEPENDENCY_VERSION_CHANGED,

    // Test quality
    TEST_QUALITY_CONFIGURATION_INVALID,
    COVERAGE_REPORT_MISSING,
    COVERAGE_REPORT_MALFORMED,
    COVERAGE_COUNTER_MISSING,
    COVERAGE_PATH_LEAK,
    COVERAGE_REGRESSION,
    COVERAGE_FAMILY_EMPTY,
    COVERAGE_EXCLUSION_UNDOCUMENTED,
    COVERAGE_CRITICAL_MODULE_REMOVED,
    COVERAGE_TOLERANCE_WEAKENED,
    COVERAGE_BASELINE_WEAKENED,
    COVERAGE_NEW_MODULE_UNMEASURED,
    COVERAGE_BASELINE_INCONSISTENT,
    MUTATION_REPORT_MISSING,
    MUTATION_REPORT_MALFORMED,
    MUTATION_TARGET_EMPTY,
    MUTATION_REGRESSION,
    MUTATION_SURVIVOR_UNCLASSIFIED,
    MUTATION_MISSING_TEST_UNTRACKED,
    TEST_REPORT_MISSING,
    TEST_PERFORMANCE_REGRESSION,
    CRITICAL_TEST_REGRESSION,
    CRITICAL_TEST_NEWLY_SKIPPED,
    TEST_QUALITY_STATUS_PENDING,

    // Safety findings
    NEW_CANCELLATION_FINDING,
    NEW_GLOBAL_STATE_FINDING,
    NEW_NONDETERMINISM_FINDING,
    CANCELLATION_RISK_WORSENED,

    // Nondeterminism authority contract (Epic 8.3d PR 2)
    NONDETERMINISM_UNCLASSIFIED_FINDING,
    NONDETERMINISM_STALE_ENTRY,
    NONDETERMINISM_MISMATCHED_CLASSIFICATION,
    NONDETERMINISM_OCCURRENCE_MISMATCH,
    NONDETERMINISM_DUPLICATE_ENTRY,
    NONDETERMINISM_INVALID_DISPOSITION,
    NONDETERMINISM_MISSING_RATIONALE,
    NONDETERMINISM_INVALID_SCHEMA,

    // Protocol
    STABLE_PROTOCOL_CONTRACT_REMOVED,

    // Hotspots
    HOTSPOT_REGRESSION,
    NEW_TOP_FIVE_HOTSPOT,
    FILE_GROWTH_EXCEEDED,

    // Deviations
    INVALID_DEVIATION_SCOPE,
    ORPHANED_DEVIATION,
    EXPIRED_DEVIATION,
    DUPLICATE_DEVIATION,
    MALFORMED_DEVIATION,
    DEVIATION_BASELINE_MISMATCH,
    DEVIATION_COVERAGE_EXCEEDED,

    // Generated artifact drift
    GENERATED_DOCUMENT_DRIFT,

    // Mandatory section
    EMPTY_SECTION,

    // Module documentation contract (Epic 11.2b3)
    MODULE_CARD_MISSING,
    MODULE_CARD_ORPHAN,
    MODULE_CARD_HEADING_MISSING,
    MODULE_CARD_LINK_BROKEN,
    MODULE_CARD_INLINE_PATH_BROKEN,
    MODULE_CARD_LEGACY_CLASSIFICATION,
    MODULE_CARD_VERSIONLESS_DEPENDENCY,
    MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT,
    MODULE_CARD_COVERAGE_MISMATCH,
}

/**
 * A single typed verification diagnostic.
 * Severity is determined by code and context, not by parsing message text.
 */
data class VerificationDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val message: String,
    val modulePath: String? = null,
    val findingId: String? = null,
    val deviationId: String? = null,
    val baselineValue: String? = null,
    val currentValue: String? = null,
) {
    companion object {
        fun failure(
            code: DiagnosticCode,
            message: String,
            modulePath: String? = null,
            findingId: String? = null,
            deviationId: String? = null,
            baselineValue: String? = null,
            currentValue: String? = null,
        ): VerificationDiagnostic =
            VerificationDiagnostic(
                code,
                DiagnosticSeverity.FAILURE,
                message,
                modulePath = modulePath,
                findingId = findingId,
                deviationId = deviationId,
                baselineValue = baselineValue,
                currentValue = currentValue,
            )

        fun warning(
            code: DiagnosticCode,
            message: String,
        ): VerificationDiagnostic = VerificationDiagnostic(code, DiagnosticSeverity.WARNING, message)

        fun accepted(
            code: DiagnosticCode,
            message: String,
            deviationId: String? = null,
        ): VerificationDiagnostic =
            VerificationDiagnostic(
                code,
                DiagnosticSeverity.ACCEPTED,
                message,
                deviationId = deviationId,
            )

        fun improvement(
            code: DiagnosticCode,
            message: String,
        ): VerificationDiagnostic = VerificationDiagnostic(code, DiagnosticSeverity.IMPROVEMENT, message)
    }
}
