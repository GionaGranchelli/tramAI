package dev.tramai.spring.sovereign.ops.rest.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Operator authentication configuration for the sovereign-ops REST control plane (0.7.6c).
 *
 * Every value here is **server-controlled**. Nothing in this object may be derived from a request:
 * a client presents a token, the token's claims are only *input* to [roleMappings], and only the
 * capability wire names listed here can be granted.
 *
 * Example:
 * ```yaml
 * tramai:
 *   sovereign:
 *     ops:
 *       security:
 *         enabled: true
 *         issuer: "https://idp.example/"
 *         audience: "tramai-control-plane"
 *         jwk-set-uri: "https://idp.example/.well-known/jwks.json"
 *         role-claim: "roles"
 *         role-mappings:
 *           governance-reader: [inspect-governance]
 *           approval-reviewer: [decide-approval]
 * ```
 *
 * [enabled] is deliberately false by default: no control-plane route becomes protected — or reachable
 * behind a half-configured boundary — until an operator turns authentication on with a complete
 * configuration. Missing configuration with [enabled] true fails startup rather than degrading.
 */
@ConfigurationProperties("tramai.sovereign.ops.security")
class OperatorSecurityProperties {
    /** Turns the operator authentication boundary on. */
    var enabled: Boolean = false

    /** Expected `iss` value. Validated on every token; never discovered from request data. */
    var issuer: String = ""

    /** Required `aud` member. A token without it is rejected. */
    var audience: String = ""

    /**
     * JWKS endpoint used for signature verification. Configured, never taken from the token's `iss`
     * discovery — a token cannot choose the keys that verify it.
     */
    var jwkSetUri: String = ""

    /**
     * Base64 (X.509 SubjectPublicKeyInfo) RSA public key, for deployments that pin a key instead of
     * publishing a JWKS. Server-controlled configuration like every other value here: it is a property, not
     * a bean, so a host application cannot substitute its own trust material.
     */
    var publicKey: String = ""

    /** Claim carrying the operator's roles. Absent or unrecognised claim values grant nothing. */
    var roleClaim: String = "roles"

    /**
     * Server-controlled role-to-capability mapping. Keys are role values the identity provider may
     * assert; values are [dev.tramai.core.operator.OperatorCapability] wire names.
     *
     * An unknown wire name is a configuration error (startup failure), not a silently ignored entry.
     * There is no wildcard entry: a role must list every capability it grants.
     */
    var roleMappings: Map<String, List<String>> = emptyMap()

    /**
     * Request paths protected by the operator boundary. Defaults to every sovereign-ops route the
     * REST starter publishes. Narrowing this list is a security decision, not a convenience.
     */
    var protectedPaths: List<String> = listOf("/tramai/sovereign/**")
}
