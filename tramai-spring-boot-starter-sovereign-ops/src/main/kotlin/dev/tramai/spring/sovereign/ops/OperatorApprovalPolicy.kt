package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole

/**
 * Per-approval approver-role requirement, resolved from server configuration (0.7.6e).
 *
 * No required role is persisted on an approval — `ApprovalRequest` and `ApprovalBinding` carry none — so
 * the authoritative requirement for this task is configuration keyed by the approval's **own persisted
 * `policyVersion`**. That is a deliberate, documented limitation
 * (`TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md` §2 L3): the requirement is only as per-approval as the
 * key is, and a durable requirement captured at approval-request time needs a persistence change and an
 * ADR.
 *
 * Semantics, fixed by the contract:
 *
 * - the authority holds the **union** of approver roles its verified role claims grant;
 * - a policy entry is the **set of roles that may decide** that policy version, so the decision is
 *   permitted when the verified roles **intersect** it — an approval that accepts `medical-reviewer` is
 *   decidable by a medical reviewer, and an operator holding only `finance-reviewer` is refused;
 * - a policy version **absent** from the map is `APPROVAL_POLICY_UNAVAILABLE`, never "allow any verified
 *   approver" — an unknown requirement is a refusal, not a default;
 * - an entry with an **empty** role set is rejected at construction: it can never be satisfied, so it is
 *   a configuration error rather than a silent deny.
 *
 * @throws IllegalArgumentException when the map is empty, contains a blank policy version, or maps a
 *   policy version to an empty role set.
 */
class OperatorApprovalPolicy(
    requiredRolesByPolicyVersion: Map<String, Set<ApproverRole>>,
) {
    private val requiredRoles: Map<String, Set<ApproverRole>>

    init {
        require(requiredRolesByPolicyVersion.isNotEmpty()) {
            "tramai-operator-approval-policy-empty: at least one policy-version requirement must be configured"
        }
        requiredRolesByPolicyVersion.forEach { (policyVersion, roles) ->
            require(policyVersion.isNotBlank()) {
                "tramai-operator-approval-policy-blank-version: a policy version key must not be blank"
            }
            require(roles.isNotEmpty()) {
                "tramai-operator-approval-policy-empty-roles: policy version '$policyVersion' " +
                    "accepts no approver role, so no decision could ever satisfy it"
            }
        }
        requiredRoles =
            java.util.Collections.unmodifiableMap(
                requiredRolesByPolicyVersion.mapValues { (_, roles) -> roles.toSet() },
            )
    }

    /** The policy versions this deployment has a requirement for. */
    fun configuredPolicyVersions(): Set<String> = requiredRoles.keys

    /**
     * Evaluate [policyVersion] against [authority].
     *
     * Returns `null` when the decision is permitted, or the reason it is refused — the refusal is
     * returned rather than thrown so the caller can report it as a typed control-plane outcome.
     */
    fun evaluate(
        policyVersion: String,
        authority: OperatorAuthority,
    ): ApprovalUnauthorizedReason? {
        val accepted = requiredRoles[policyVersion] ?: return ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE
        val permitted = authority.approverRoles.any { it in accepted }
        return if (permitted) null else ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED
    }
}
