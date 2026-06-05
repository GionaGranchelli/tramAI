package dev.tramai.security.approval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SecureRandomApprovalTokenGeneratorTest {

    @Test
    fun `generated token is non blank`() {
        val generator = SecureRandomApprovalTokenGenerator()

        val token = generator.generate()

        assertThat(token.reveal()).isNotBlank()
    }

    @Test
    fun `default 32 byte token has 256 bit entropy encoded without padding`() {
        val generator = SecureRandomApprovalTokenGenerator()

        val token = generator.generate()

        assertThat(token.reveal()).hasSize(43)
        assertThat(token.reveal()).doesNotContain("=")
    }

    @Test
    fun `tokens differ across calls`() {
        val generator = SecureRandomApprovalTokenGenerator()

        val first = generator.generate()
        val second = generator.generate()

        assertThat(first.reveal()).isNotEqualTo(second.reveal())
    }

    @Test
    fun `output is url safe`() {
        val generator = SecureRandomApprovalTokenGenerator()

        val token = generator.generate()

        assertThat(token.reveal()).matches("^[A-Za-z0-9_-]+$")
    }

    @Test
    fun `tokenBytes below 16 rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { SecureRandomApprovalTokenGenerator(tokenBytes = 15) }
            .withMessage("tokenBytes must be at least 16 (128 bits)")
    }
}
