package dev.tramai.spring.sovereign.ops.rest.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Fail-closed configuration behaviour (0.7.6c): incomplete security configuration must stop the
 * application, and the boundary must be off unless an operator turns it on.
 */
class OperatorAuthenticationConfigurationTest {
    private companion object {
        const val APPROVAL_BASE = "/tramai/sovereign/approvals"
    }

    private fun properties() =
        OperatorSecurityProperties().apply {
            enabled = true
            issuer = "https://idp.example/"
            audience = "tramai-control-plane"
            jwkSetUri = "https://idp.example/jwks"
        }

    @Test
    fun `a complete configuration passes`() {
        requireOperatorConfiguration(properties(), APPROVAL_BASE)
    }

    @Test
    fun `a missing JWKS URI is a startup failure`() {
        val incomplete = properties().apply { jwkSetUri = "" }

        assertThatThrownBy { requireOperatorConfiguration(incomplete, APPROVAL_BASE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tramai-operator-authentication-incomplete-configuration")
            .hasMessageContaining("jwk-set-uri")
    }

    @Test
    fun `a missing issuer or audience is a startup failure naming every missing value`() {
        val incomplete =
            properties().apply {
                issuer = ""
                audience = " "
            }

        assertThatThrownBy { requireOperatorConfiguration(incomplete, APPROVAL_BASE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("issuer")
            .hasMessageContaining("audience")
    }

    @Test
    fun `moving the control plane outside the protected paths is a startup failure`() {
        assertThatThrownBy { requireOperatorConfiguration(properties(), "/ops/approvals") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tramai-operator-authentication-unprotected-control-plane")
            .hasMessageContaining("/ops/approvals")
    }

    @Test
    fun `an empty protected-path list is a startup failure, not an open boundary`() {
        val empty = properties().apply { protectedPaths = emptyList() }

        assertThatThrownBy { requireOperatorConfiguration(empty, APPROVAL_BASE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tramai-operator-authentication-unprotected-control-plane")
    }

    @Test
    fun `a pattern that covers the base path but not its children is a startup failure`() {
        val narrow = properties().apply { protectedPaths = listOf("/tramai/sovereign") }

        assertThatThrownBy { requireOperatorConfiguration(narrow, APPROVAL_BASE) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tramai-operator-authentication-unprotected-control-plane")
    }

    @Test
    fun `the boundary is disabled unless explicitly enabled`() {
        assertThat(OperatorSecurityProperties().enabled).isFalse()
    }
}
