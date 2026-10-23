package dev.tramai.core.operator

import dev.tramai.core.approval.SafeActorIdPolicy
import java.time.Instant

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
 * body, so the caller decides who they are and what role they hold. A data class cannot stop a
 * caller from constructing a principal; the enforcement point (0.7.6e) is what refuses to accept one
 * from an unauthenticated path, and 0.7.6g proves it does.
 *
 * Equality includes [issuer] on purpose: a `subject` is only unique **within** an issuer, so the same
 * subject string from two identity providers names two different operators, and one cannot
 * impersonate the other by claiming a shared subject.
 *
 * @property subject the identity-provider subject. Shape-validated with
 *   [SafeActorIdPolicy.validateActorId] — the same rule the approval path applies to actor
 *   identifiers, because this subject becomes the recorded actor there. Rejecting an id the approval
 *   path could not record is deliberate.
 * @property issuer the identity-provider issuer. Not restricted to actor-id characters: issuers are
 *   URLs.
 * @property authenticatedAt when the authentication boundary verified the credential. No freshness
 *   policy is applied here; callers that need one compare it to their own clock.
 * @property capabilities the operator capabilities granted server-side. An empty set is valid and
 *   means "authenticated, authorized for nothing".
 */
data class OperatorPrincipal(
    val subject: String,
    val issuer: String,
    val authenticatedAt: Instant,
    val capabilities: Set<OperatorCapability>,
) {
    init {
        val validatedSubject = SafeActorIdPolicy.validateActorId(subject, "subject")
        require(validatedSubject == subject) { "subject must not be padded with whitespace" }
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(issuer.none { it.isWhitespace() || it.isISOControl() }) { "issuer must not contain whitespace" }
    }

    /** True when [capability] was granted to this operator. */
    fun hasCapability(capability: OperatorCapability): Boolean = capability in capabilities

    /**
     * Typed authorization decision for [capability]: [OperatorAuthorizationResult.Authorized], or
     * [OperatorAuthorizationResult.Denied] carrying the missing capability, so a caller — and a test —
     * can name what was missing instead of inferring it from a failed call.
     */
    fun authorize(capability: OperatorCapability): OperatorAuthorizationResult =
        if (hasCapability(capability)) {
            OperatorAuthorizationResult.Authorized
        } else {
            OperatorAuthorizationResult.Denied(capability)
        }
}
