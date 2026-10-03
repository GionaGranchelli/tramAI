package dev.tramai.server

import dev.tramai.controlplane.WorkloadStateVersion

/**
 * `If-Match` precondition for a control-plane command (0.7.1e).
 *
 * The header can syntactically express more than this contract accepts, so unsupported forms are
 * rejected rather than interpreted. The accepted form is exactly one strong, numeric ETag:
 * `If-Match: "17"` — the authoritative [WorkloadStateVersion] the command is conditioned on.
 *
 * Rejected on purpose:
 * - `*` — satisfies resource-existence semantics without naming a version, which would authorize a
 *   mutation that carries no expected version;
 * - `W/"17"` — a weak tag is not a version-specific precondition;
 * - `"16", "17"` — the authority has exactly one version, so alternatives have no meaning;
 * - unquoted, empty or non-numeric values.
 */
internal sealed interface WorkloadPrecondition {
    /** One strong numeric ETag, i.e. the version the command expects. */
    data class Expected(
        val version: WorkloadStateVersion,
    ) : WorkloadPrecondition

    /** No `If-Match` header at all — a required precondition is missing. */
    data object Missing : WorkloadPrecondition

    /** Present but not an acceptable form — never reinterpreted as "no precondition". */
    data object Unsupported : WorkloadPrecondition
}

/** The `ETag` for an authoritative record: one strong numeric tag. */
internal fun workloadEtag(version: WorkloadStateVersion): String = "\"${version.value}\""

/** Exactly one quoted strong numeric tag, with no leading zeros (`"0"` is not a version). */
private val STRONG_NUMERIC_ETAG = Regex("""^"([1-9][0-9]*)"$""")

/** Parses an `If-Match` header into a precondition outcome. */
internal fun parseWorkloadPrecondition(header: String?): WorkloadPrecondition {
    // Only an ABSENT header is Missing (428). A present value that does not carry exactly one strong
    // numeric ETag — including empty or blank — is Unsupported (400), because "you sent nothing
    // usable" is a malformed precondition, not a missing one, and collapsing the two would report a
    // malformed request as a required-precondition error.
    if (header == null) return WorkloadPrecondition.Missing
    return STRONG_NUMERIC_ETAG
        .matchEntire(header.trim())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.let { WorkloadPrecondition.Expected(WorkloadStateVersion(it)) }
        ?: WorkloadPrecondition.Unsupported
}
