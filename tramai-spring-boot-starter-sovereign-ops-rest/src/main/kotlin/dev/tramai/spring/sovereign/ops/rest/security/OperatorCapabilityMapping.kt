package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.operator.OperatorCapability

/**
 * Server-controlled role-to-capability resolution for authenticated operators (0.7.6c).
 *
 * The rule the whole mapping exists to enforce: **a token's claims are input, never authority**. A
 * signed token may assert `roles: ["approval-reviewer"]`, but the set of capabilities that produces is
 * whatever this mapping says, and only that. Consequences that are tested, not assumed:
 *
 * - a missing or unrecognised role value grants **nothing** (an operator can be authenticated and
 *   still hold an empty capability set);
 * - a client-asserted `"admin"` or `"*"` role grants nothing unless a server-side mapping names that
 *   exact role key, and even then only the capabilities listed for it — there is no wildcard
 *   capability, because [OperatorCapability] has no such member;
 * - a capability wire name the running build does not know is a **configuration error** raised at
 *   construction (startup), never a silently dropped entry;
 * - several recognised roles produce the union of their explicitly listed capabilities;
 * - runtime resources (tools, MCP operations, models, providers, network destinations) are not
 *   expressible here at all: the enum cannot name them.
 *
 * @throws IllegalArgumentException when [roleClaim] is blank or an entry in [roleMappings] names an
 *   unknown capability.
 */
class OperatorCapabilityMapping(
    private val roleClaim: String,
    roleMappings: Map<String, List<String>>,
) {
    private val capabilitiesByRole: Map<String, Set<OperatorCapability>>

    init {
        require(roleClaim.isNotBlank()) { "operator role claim must not be blank" }
        capabilitiesByRole =
            roleMappings.mapValues { (_, wireNames) ->
                wireNames.map { wireName -> OperatorCapability.fromWireName(wireName) }.toSet()
            }
    }

    /** Capabilities granted by the roles asserted in [claims]. Absent or unknown roles grant nothing. */
    fun capabilitiesFrom(claims: Map<String, Any?>): Set<OperatorCapability> {
        val assertedRoles =
            when (val raw = claims[roleClaim]) {
                is String -> listOf(raw)
                is Collection<*> -> raw.filterIsInstance<String>()
                else -> emptyList()
            }
        return assertedRoles
            .asSequence()
            .flatMap { role -> capabilitiesByRole[role].orEmpty().asSequence() }
            .toSet()
    }
}
