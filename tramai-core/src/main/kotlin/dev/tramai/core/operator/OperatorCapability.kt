package dev.tramai.core.operator

/**
 * The operator/control-plane operations an authenticated operator may be granted (0.7.6b).
 *
 * This vocabulary is deliberately **closed** and **operator-only**. Every member corresponds to a
 * privileged operation found in the 0.7.6a control-path audit
 * (`docs/roadmap/0.7.6/AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md` §3.1); a member without such an
 * operation is a design error, not a placeholder.
 *
 * It must never describe an AI execution resource. Tools, MCP operations, remote agents, models,
 * providers and network destinations are governed by runtime capability metadata
 * (`ToolSecurityMetadata`, tool-permission evidence) and are a *different authority*: holding
 * [ADMINISTER_WORKLOAD] must not imply any runtime resource permission, and no runtime permission
 * may be expressed, converted or widened into a member of this enum.
 *
 * There is no wildcard or super-capability. `ApiKeyScope.ADMIN` in `tramai-platform` shows the
 * failure mode being avoided: one value that silently satisfies every other check, mixing operator
 * administration with runtime-affecting configuration. A caller that needs four operations is
 * granted four capabilities.
 *
 * [wireName] is the stable external spelling (bearer-claim mapping in 0.7.6c). Unknown wire names
 * are rejected by [fromWireName] rather than defaulted — an unrecognised capability must never
 * resolve to a grant.
 */
enum class OperatorCapability(
    val wireName: String,
) {
    /** Read governance state, timeline and reconstruction surfaces. */
    INSPECT_GOVERNANCE("inspect-governance"),

    /** Register, update or transition governed workloads. */
    ADMINISTER_WORKLOAD("administer-workload"),

    /** Approve or deny a pending approval request. */
    DECIDE_APPROVAL("decide-approval"),

    /** Cancel a governed workflow run. */
    CANCEL_RUN("cancel-run"),
    ;

    companion object {
        /**
         * Resolves a capability from its [wireName].
         *
         * @throws IllegalArgumentException when [value] is not a known capability. Failing loudly is
         *   the point: a token asserting a capability this build does not know must not be treated
         *   as an unscoped or partially-scoped grant.
         */
        fun fromWireName(value: String): OperatorCapability =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unsupported operator capability '$value'")
    }
}
