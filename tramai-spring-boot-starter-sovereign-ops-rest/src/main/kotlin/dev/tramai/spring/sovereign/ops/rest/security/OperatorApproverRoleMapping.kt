package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.approval.gateway.ApproverRole

/**
 * Server-controlled role-to-approver-role resolution (0.7.6e).
 *
 * Mirrors [OperatorCapabilityMapping] for the second vocabulary a decision needs, and inherits the same
 * rule: **a token's claims are input, never authority**. A signed token may assert
 * `roles: ["approval-reviewer"]`; the approver roles that produces are whatever this mapping says, and only
 * that. Consequences:
 *
 * - a missing or unrecognised role value grants **no** approver role, and the operator is refused by the
 *   per-approval role requirement rather than defaulted into it;
 * - an approver role value that is blank is a configuration error raised at construction (startup), never a
 *   silently dropped entry — the value class itself only rejects blank, so blank is all this layer can
 *   detect: [ApproverRole] has no closed vocabulary to validate against, which the contract records;
 * - several recognised roles produce the **union** of their approver roles.
 *
 * @throws IllegalArgumentException when [roleClaim] is blank or an entry in [roleMappings] is blank.
 */
class OperatorApproverRoleMapping(
    private val roleClaim: String,
    roleMappings: Map<String, List<String>>,
) {
    private val approverRolesByRole: Map<String, Set<ApproverRole>>

    init {
        require(roleClaim.isNotBlank()) { "operator role claim must not be blank" }
        roleMappings.forEach { (role, approverRoles) ->
            require(role.isNotBlank()) { "approver-role mapping key must not be blank" }
            approverRoles.forEach { value ->
                require(value.isNotBlank()) { "approver role for role '$role' must not be blank" }
            }
        }
        approverRolesByRole =
            roleMappings.mapValues { (_, values) -> values.map { ApproverRole(it) }.toSet() }
    }

    /** Approver roles granted by the roles asserted in [claims]. Absent or unknown roles grant nothing. */
    fun approverRolesFrom(claims: Map<String, Any?>): Set<ApproverRole> {
        val assertedRoles =
            when (val raw = claims[roleClaim]) {
                is String -> listOf(raw)
                is Collection<*> -> raw.filterIsInstance<String>()
                else -> emptyList()
            }
        return assertedRoles
            .asSequence()
            .flatMap { role -> approverRolesByRole[role].orEmpty().asSequence() }
            .toSet()
    }
}
