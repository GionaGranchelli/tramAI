package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal

/**
 * The verified operator authority a privileged command is performed under (0.7.6e).
 *
 * **This type is not proof of authentication, and the contract says so.** Authority in TramAI is a
 * *path* guarantee inside a shared Spring/JVM process: identity never travels on the wire, exactly one
 * component — the 0.7.6c authentication adapter — produces this value inside the request path, and the
 * control plane refuses to act without one. Code already running in the process can construct an
 * [OperatorAuthority] or inject the adapter; that is inside the trust domain and outside the guarantee,
 * recorded rather than defended with cryptography (`TASK-0.7.6e-RUNTIME-AUTHORIZATION-CONTRACT.md` §1).
 *
 * [principal] carries the identity — `(issuer, subject)`, never a bare subject — and the capabilities the
 * authentication boundary resolved server-side. [approverRoles] carries the approver roles the same
 * boundary resolved for this operator, which is what a per-approval role requirement is checked against.
 *
 * Both collections are copied into immutable snapshots: a grant that can change after the boundary
 * produced it is not a snapshot of anything.
 */
class OperatorAuthority(
    val principal: OperatorPrincipal,
    approverRoles: Set<ApproverRole>,
) {
    val approverRoles: Set<ApproverRole> = java.util.Collections.unmodifiableSet(LinkedHashSet(approverRoles))

    /** The recorded actor for everything this authority decides: the verified identity provider subject. */
    val subject: String get() = principal.subject

    /** The verified identity provider, kept alongside the subject so policy can distinguish two issuers. */
    val issuer: String get() = principal.issuer

    /** Whether the boundary granted [capability] to this operator. */
    fun holds(capability: OperatorCapability): Boolean = capability in principal.capabilities

    /** Typed capability decision, so a refusal can name what was missing. */
    fun authorize(capability: OperatorCapability) = principal.authorize(capability)

    override fun toString(): String = "OperatorAuthority(issuer=$issuer, subject=$subject)"
}
