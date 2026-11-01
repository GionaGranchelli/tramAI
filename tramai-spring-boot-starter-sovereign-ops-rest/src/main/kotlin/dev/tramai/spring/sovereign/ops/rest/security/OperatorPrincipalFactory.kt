package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.operator.OperatorPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant

/**
 * The single conversion path from a **verified** JWT to an [OperatorPrincipal] (0.7.6c).
 *
 * ```text
 * bearer token -> JwtDecoder (signature, issuer, audience, expiry) -> verified Jwt -> this -> OperatorPrincipal
 * ```
 *
 * The input type is [Jwt], which a caller cannot obtain from a request: Spring produces it only after
 * [org.springframework.security.oauth2.jwt.JwtDecoder] accepted the token's signature and claims. No
 * request body, query parameter, header or caller-built object is an input here — which is the whole
 * point, given audit finding F1 where the approval endpoints trusted `actorId` from the request body.
 *
 * [verifiedAt] is when *this boundary* verified the credential, taken from the injected [Clock]. It is
 * deliberately **not** the token's `iat`: issuance is what the identity provider claims, verification
 * is what we did, and evidence must record the latter. Tests pin the clock and assert the two differ.
 *
 * Failures are explicit: an issuer that does not equal the configured issuer, or a subject the actor-id
 * rule cannot record, throws rather than producing a partial or rewritten principal.
 */
class OperatorPrincipalFactory(
    private val properties: OperatorSecurityProperties,
    private val capabilityMapping: OperatorCapabilityMapping,
    private val approverRoleMapping: OperatorApproverRoleMapping,
    private val clock: Clock,
) {
    /** Converts a verified [jwt] into an immutable operator principal. */
    fun fromVerified(
        jwt: Jwt,
        verifiedAt: Instant = clock.instant(),
    ): OperatorPrincipal {
        val (issuer, subject) = verifiedIdentity(jwt)
        val recordableSubject = recordableOperatorSubject(issuer, subject)
        return OperatorPrincipal(
            subject = recordableSubject,
            issuer = issuer,
            authenticatedAt = verifiedAt,
            capabilities = capabilityMapping.capabilitiesFrom(jwt.claims),
            approverRoles = approverRoleMapping.approverRolesFrom(jwt.claims),
        )
    }

    /**
     * Extracts the verified identity, re-checking the issuer as defence in depth: the decoder's validator
     * already enforces it, so this only fires if the decoder is mis-wired — in which case minting a
     * principal for a foreign issuer would be the worse outcome.
     */
    private fun verifiedIdentity(jwt: Jwt): Pair<String, String> {
        val rawIssuer = jwt.issuer ?: throw UnsupportedOperatorSubjectException("tramai-operator-token-missing-issuer")
        val issuer = rawIssuer.toString()
        check(issuer == properties.issuer) { "tramai-operator-token-unexpected-issuer" }
        val subject = jwt.subject ?: throw UnsupportedOperatorSubjectException("tramai-operator-token-missing-subject")
        return issuer to subject
    }
}
