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
            requireApproverRoleShape(role, "an approver-role-mappings key")
            approverRoles.forEach { value ->
                requireApproverRoleShape(value, "an approver-role-mappings value for role '$role'")
            }
        }
        approverRolesByRole =
            roleMappings.mapValues { (_, values) -> values.map { ApproverRole(it) }.toSet() }
    }

    /**
     * Every approver role this configuration can grant, whatever role the token asserts.
     *
     * Used for start-up self-consistency (§7.2): a role named in `approval-policies` that appears in no
     * mapping value is a requirement no verified operator could ever satisfy.
     */
    fun grantableApproverRoles(): Set<ApproverRole> = approverRolesByRole.values.flatten().toSet()

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

/**
 * Bounded length for a configured approver role.
 *
 * A label, not a document: the longest role name any identity provider in this epic's examples emits is
 * well under this, and an unbounded value would end up in audit records and outbox rows.
 */
internal const val MAX_APPROVER_ROLE_LENGTH: Int = 64

/**
 * Shape policy for configured approver-role values (§7.2).
 *
 * [ApproverRole] itself only rejects blank, and there is no registry of valid roles, so "unknown role" is
 * not decidable. This is what *is* decidable from configuration alone, and it is enforced at start-up
 * rather than at decision time:
 *
 * - non-blank;
 * - no whitespace — a role typed as `medical reviewer` would never equal the `medical-reviewer` a token
 *   asserts, so the configuration would deny everything while looking correct;
 * - no control characters — those cannot come from a claim and only ever arrive from a malformed paste;
 * - at most [MAX_APPROVER_ROLE_LENGTH] characters.
 *
 * The rule is stated here, where it is implemented, because it is a task-local policy rather than
 * something the value type guarantees.
 */
internal fun requireApproverRoleShape(
    value: String,
    where: String,
) {
    require(value.isNotBlank()) { "tramai-approver-role-malformed: $where must not be blank" }
    require(value.length <= MAX_APPROVER_ROLE_LENGTH) {
        "tramai-approver-role-malformed: $where exceeds $MAX_APPROVER_ROLE_LENGTH characters"
    }
    require(value.none { it.isWhitespace() }) { "tramai-approver-role-malformed: $where '$value' contains whitespace" }
    require(value.none { it.isISOControl() }) {
        "tramai-approver-role-malformed: $where '$value' contains a control character"
    }
}
