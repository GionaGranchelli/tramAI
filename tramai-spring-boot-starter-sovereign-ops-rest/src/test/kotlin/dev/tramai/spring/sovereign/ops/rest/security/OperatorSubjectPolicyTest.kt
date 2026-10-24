package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

/**
 * Subject compatibility and conversion behaviour (0.7.6c, resolves audit limitation A4).
 */
class OperatorSubjectPolicyTest {
    private val issuer = "https://idp.example/"
    private val properties =
        OperatorSecurityProperties().apply {
            enabled = true
            issuer = "https://idp.example/"
            audience = "tramai-control-plane"
            jwkSetUri = "https://idp.example/jwks"
            roleClaim = "roles"
            roleMappings =
                mapOf(
                    "governance-reader" to listOf("inspect-governance"),
                    "approval-reviewer" to listOf("decide-approval"),
                )
        }

    private val verifiedAt = Instant.parse("2026-03-01T10:15:30Z")
    private val clock: Clock = Clock.fixed(verifiedAt, ZoneOffset.UTC)

    private fun factory() =
        OperatorPrincipalFactory(
            properties,
            OperatorCapabilityMapping(properties.roleClaim, properties.roleMappings),
            clock,
        )

    private fun jwt(
        subject: String?,
        issuer: String = this.issuer,
        issuedAt: Instant = Instant.parse("2026-02-28T00:00:00Z"),
        roles: List<String> = listOf("approval-reviewer"),
        extraClaims: Map<String, Any> = emptyMap(),
    ): Jwt {
        val builder =
            Jwt
                .withTokenValue("token")
                .header("alg", "RS256")
                .claim("roles", roles)
                .issuedAt(issuedAt)
                .expiresAt(verifiedAt.plusSeconds(600))
                .issuer(issuer)
        subject?.let { builder.subject(it) }
        extraClaims.forEach { (name, value) -> builder.claim(name, value) }
        return builder.build()
    }

    // ---- A4: which real subject shapes can be recorded -------------------------------------------

    @Test
    fun `a directory object identifier and a service account are recordable unchanged`() {
        val directoryObject = "S-1-5-21-3623811015-3361044348-30300820-1013"
        val serviceAccount = "svc-claims@project-1.iam.gserviceaccount.com"

        assertThat(factory().fromVerified(jwt(directoryObject)).subject).isEqualTo(directoryObject)
        assertThat(factory().fromVerified(jwt(serviceAccount)).subject).isEqualTo(serviceAccount)
    }

    @Test
    fun `an auth0-style subject is rejected instead of being rewritten`() {
        assertThatThrownBy { factory().fromVerified(jwt("auth0|64f1c2")) }
            .isInstanceOf(UnsupportedOperatorSubjectException::class.java)
    }

    @Test
    fun `a url-shaped subject is rejected instead of being rewritten`() {
        assertThatThrownBy { factory().fromVerified(jwt("https://idp.example/alice")) }
            .isInstanceOf(UnsupportedOperatorSubjectException::class.java)
    }

    @Test
    fun `empty and malformed subjects are rejected`() {
        assertThatThrownBy { factory().fromVerified(jwt("")) }
            .isInstanceOf(UnsupportedOperatorSubjectException::class.java)
        assertThatThrownBy { factory().fromVerified(jwt("alice smith")) }
            .isInstanceOf(UnsupportedOperatorSubjectException::class.java)
        // A missing subject is a rejected credential, not a server fault: the resolver no longer
        // translates it, because the factory now throws an authentication failure itself.
        assertThatThrownBy { factory().fromVerified(jwt(null)) }
            .isInstanceOf(UnsupportedOperatorSubjectException::class.java)
            .hasMessageContaining("missing-subject")
    }

    @Test
    fun `no subject is transformed, so distinct verified subjects cannot collide`() {
        val first = factory().fromVerified(jwt("alice.one"))
        val second = factory().fromVerified(jwt("alice_one"))

        // Both are recordable and both are kept verbatim: no separator rewriting, no case folding.
        assertThat(first.subject).isEqualTo("alice.one")
        assertThat(second.subject).isEqualTo("alice_one")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `the same subject string from two issuers stays two operators`() {
        val fromTrusted = factory().fromVerified(jwt("alice"))
        // A second issuer cannot reach this factory at all, but identity equality must not collapse:
        val other = OperatorPrincipal("alice", "https://other-idp.example/", verifiedAt, emptySet())

        assertThat(fromTrusted).isNotEqualTo(other)
        assertThat(fromTrusted.issuer).isNotEqualTo(other.issuer)
    }

    // ---- conversion semantics --------------------------------------------------------------------

    @Test
    fun `capabilities come from server configuration only`() {
        val principal = factory().fromVerified(jwt("alice", roles = listOf("approval-reviewer")))

        assertThat(principal.capabilities).containsExactly(OperatorCapability.DECIDE_APPROVAL)
    }

    @Test
    fun `claims the mapping does not name cannot grant a capability`() {
        val principal =
            factory().fromVerified(
                jwt(
                    "alice",
                    roles = emptyList(),
                    extraClaims =
                        mapOf(
                            "capabilities" to listOf("decide-approval", "administer-workload"),
                            "scope" to "admin",
                        ),
                ),
            )

        assertThat(principal.capabilities).isEmpty()
    }

    @Test
    fun `authenticatedAt is the verification instant and never the token issue time`() {
        val issuedAt = Instant.parse("2026-02-28T00:00:00Z")
        val principal = factory().fromVerified(jwt("alice", issuedAt = issuedAt))

        assertThat(principal.authenticatedAt).isEqualTo(verifiedAt)
        assertThat(principal.authenticatedAt).isNotEqualTo(issuedAt)
    }

    @Test
    fun `a token whose issuer differs from the configured issuer cannot produce a principal`() {
        assertThatThrownBy { factory().fromVerified(jwt("alice", issuer = "https://evil.example/")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unexpected-issuer")
    }

    @Test
    fun `the principal is an immutable snapshot of the resolved grants`() {
        val principal = factory().fromVerified(jwt("alice", roles = listOf("approval-reviewer", "governance-reader")))

        assertThat(principal.capabilities).containsExactlyInAnyOrder(
            OperatorCapability.DECIDE_APPROVAL,
            OperatorCapability.INSPECT_GOVERNANCE,
        )
        assertThatThrownBy {
            @Suppress("UNCHECKED_CAST")
            (principal.capabilities as MutableSet<OperatorCapability>).add(OperatorCapability.CANCEL_RUN)
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }
}
