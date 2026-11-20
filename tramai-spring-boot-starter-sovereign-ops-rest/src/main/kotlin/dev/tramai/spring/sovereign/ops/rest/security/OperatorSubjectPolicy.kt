package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.approval.SafeActorIdPolicy

/**
 * Subject compatibility policy for operator identities (0.7.6c, resolves audit limitation A4).
 *
 * Identity is the exact pair `(issuer, subject)`. This policy **never transforms** a subject: it either
 * accepts it unchanged or rejects the token. That is what makes collisions impossible by construction —
 * two distinct verified subjects cannot become equal, because no character is stripped, no case is
 * folded and no separator is rewritten. Two issuers asserting the same subject string also stay
 * distinct, since [dev.tramai.core.operator.OperatorPrincipal] includes the issuer in equality.
 *
 * The accepted shape is [SafeActorIdPolicy]'s actor-id rule
 * (`[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}`), deliberately not widened here. Real identity-provider
 * subjects that fall outside it — Auth0-style `auth0|64f1c2`, URL-shaped `https://idp.example/alice` —
 * are **rejected** rather than mapped. A future deterministic, issuer-aware, collision-resistant
 * mapping is a separate decision; inventing one inside the authentication adapter would put a second
 * identity authority next to the approval store.
 *
 * A top-level function rather than an `object`: the rule is stateless, and the repository's architecture
 * gate treats a Kotlin `object` in production code as global state.
 *
 * @throws UnsupportedOperatorSubjectException when the subject is blank, malformed, too long, or uses
 *   characters the actor-id rule does not allow.
 */
internal fun recordableOperatorSubject(
    issuer: String,
    subject: String,
): String =
    try {
        SafeActorIdPolicy.validateActorId(subject, "subject")
    } catch (rejected: IllegalArgumentException) {
        throw UnsupportedOperatorSubjectException(
            "operator subject from issuer '$issuer' cannot be recorded as an approval actor id: " +
                (rejected.message ?: "rejected by the actor-id rule"),
            rejected,
        )
    }
