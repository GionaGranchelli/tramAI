package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Sha256ApprovalTokenDigesterTest {

    private val digester = Sha256ApprovalTokenDigester()

    @Test
    fun `known sha256 vector matches`() {
        val digest = digester.digest(ApprovalToken.parsePresented("hello"))

        assertThat(digest.value)
            .isEqualTo("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    }

    @Test
    fun `same token gives same digest`() {
        val token = ApprovalToken.parsePresented("same-token")

        assertThat(digester.digest(token)).isEqualTo(digester.digest(token))
    }

    @Test
    fun `different tokens give different digests`() {
        val first = digester.digest(ApprovalToken.parsePresented("token-1"))
        val second = digester.digest(ApprovalToken.parsePresented("token-2"))

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `digest format is sha256 lowercase hex`() {
        val digest = digester.digest(ApprovalToken.parsePresented("format-check"))

        assertThat(digest.value).matches("^sha256:[0-9a-f]{64}$")
    }

    @Test
    fun `raw token absent from token and digest string forms`() {
        val rawToken = "top-secret-token"
        val token = ApprovalToken.parsePresented(rawToken)
        val digest = digester.digest(token)

        assertThat(token.toString()).doesNotContain(rawToken)
        assertThat(digest.toString()).doesNotContain(rawToken)
        assertThat(digest.value).doesNotContain(rawToken)
    }
}
