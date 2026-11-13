package dev.tramai.core.operator

import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.gateway.ApproverRole
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashSet

/**
 * An authenticated operator principal: who is calling and what they may command (0.7.6b).
 *
 * **This type is data. It is not proof of authentication.**
 *
 * Constructing an [OperatorPrincipal] grants nothing. Every privileged operation must obtain it from
 * a trusted authentication boundary — the 0.7.6c adapter that validated a credential and resolved
 * [capabilities] server-side — and must never build one from request-supplied fields. The 0.7.6a
 * audit (`AUDIT-0.7.6a-SECURITY-CONTROL-BASELINE.md`, finding F1) records exactly what happens
 * without that discipline: the approval endpoints take `actorId` and `actorRole` from the request
 * body, so the caller decides who they are and what role they hold. A type cannot stop a caller from
 * constructing a principal; the enforcement point (0.7.6e) is what refuses to accept one from an
 * unauthenticated path, and 0.7.6g proves it does.
 *
 * Equality includes [issuer] on purpose: a `subject` is only unique **within** an issuer, so the same
 * subject string from two identity providers names two different operators, and one cannot
 * impersonate the other by claiming a shared subject.
 *
 * [capabilities] is an immutable snapshot, not the caller's collection: a `MutableSet` passed to this
 * constructor is copied, so mutating it afterwards cannot change authority. That is why this is not a
 * data class — its generated `copy` and the raw constructor property would both have carried the
 * caller's set by reference, and a grant that can change after the authentication boundary produced
 * it is not a snapshot of anything.
 *
 * @property subject the identity-provider subject. Shape-validated with
 *   [SafeActorIdPolicy.validateActorId] — the same rule the approval path applies to actor
 *   identifiers, because this subject becomes the recorded actor there. That rule allows an
 *   alphanumeric start followed by alphanumerics and `._:@+-`, so **some real IdP subjects are
 *   rejected**: Auth0-style `auth0|abc123` (pipe) and URL-shaped subjects cannot be used directly.
 *   This is a deliberate fail-closed limit, not an IdP-neutral guarantee; 0.7.6c must map such a
 *   subject into a recordable form or report the conflict rather than widening this rule.
 * @property issuer the identity-provider issuer. Not restricted to actor-id characters: issuers are
 *   URLs.
 * @property authenticatedAt when the authentication boundary verified the credential. No freshness
 *   policy is applied here; callers that need one compare it to their own clock.
 * @property capabilities the operator capabilities granted server-side, as an immutable snapshot. An
 *   empty set is valid and means "authenticated, authorized for nothing".
 * @property approverRoles the approver roles granted server-side, resolved from the same verified claims by
 *   the same boundary (0.7.6e). They live here rather than on a wrapper for one reason: capabilities and
 *   approver roles are both *grants the boundary resolved*, and keeping them apart invited a second
 *   collection that could disagree with this one. An empty set is valid and means "authenticated, approver
 *   for nothing", which refuses every per-approval role requirement rather than permitting it.
 */
class OperatorPrincipal(
    val subject: String,
    val issuer: String,
    val authenticatedAt: Instant,
    capabilities: Set<OperatorCapability>,
    approverRoles: Set<ApproverRole>,
) {
    val capabilities: Set<OperatorCapability> = Collections.unmodifiableSet(LinkedHashSet(capabilities))

    val approverRoles: Set<ApproverRole> = Collections.unmodifiableSet(LinkedHashSet(approverRoles))

    /**
     * Compatibility constructor for the 0.7.6c published API — a real four-argument JVM constructor, not a
     * Kotlin default-argument bridge, so an already-compiled Java or Kotlin consumer keeps linking.
     *
     * An operator built this way holds no approver role and is therefore refused by every per-approval role
     * requirement: the 0.7.6c shape can express capabilities only, and an absent grant must never be read as
     * "no requirement".
     */
    constructor(
        subject: String,
        issuer: String,
        authenticatedAt: Instant,
        capabilities: Set<OperatorCapability>,
    ) : this(subject, issuer, authenticatedAt, capabilities, emptySet())

    init {
        SafeActorIdPolicy.validateActorId(subject, "subject")
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(issuer.none { it.isWhitespace() || it.isISOControl() }) { "issuer must not contain whitespace" }
    }

    /**
     * Typed authorization decision for [capability]: [OperatorAuthorizationResult.Authorized], or
     * [OperatorAuthorizationResult.Denied] carrying the missing capability, so a caller — and a test —
     * can name what was missing instead of inferring it from a failed call.
     *
     * This is the only authorization accessor on purpose. A boolean shortcut loses the denial reason
     * the epic requires for evidence and for adversarial tests.
     */
    fun authorize(capability: OperatorCapability): OperatorAuthorizationResult =
        if (capability in capabilities) {
            OperatorAuthorizationResult.Authorized
        } else {
            OperatorAuthorizationResult.Denied(capability)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OperatorPrincipal) return false
        return subject == other.subject &&
            issuer == other.issuer &&
            authenticatedAt == other.authenticatedAt &&
            capabilities == other.capabilities &&
            approverRoles == other.approverRoles
    }

    override fun hashCode(): Int {
        var result = subject.hashCode()
        result = 31 * result + issuer.hashCode()
        result = 31 * result + authenticatedAt.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + approverRoles.hashCode()
        return result
    }

    override fun toString(): String =
        "OperatorPrincipal(subject=$subject, issuer=$issuer, authenticatedAt=$authenticatedAt, " +
            "capabilities=$capabilities, approverRoles=$approverRoles)"
}
