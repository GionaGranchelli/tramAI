package dev.tramai.core.approval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ApprovalTokenTest {

    @Test
    fun `toString returns redacted marker`() {
        val token = ApprovalToken.parsePresented("secret-token")

        assertThat(token.toString()).isEqualTo("[REDACTED]")
    }

    @Test
    fun `blank token rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ApprovalToken.parsePresented("   ") }
            .withMessage("Approval token must not be blank")
    }

    @Test
    fun `control characters rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ApprovalToken.parsePresented("secret\nvalue") }
            .withMessage("Approval token must not contain control characters")
    }

    @Test
    fun `oversized token rejected`() {
        val oversized = "a".repeat(513)

        assertThatIllegalArgumentException()
            .isThrownBy { ApprovalToken.parsePresented(oversized) }
            .withMessage("Approval token exceeds maximum length")
    }

    @Test
    fun `reveal returns raw token`() {
        val token = ApprovalToken.parsePresented("secret-token")

        assertThat(token.reveal()).isEqualTo("secret-token")
    }

    @Test
    fun `generated token shape accepts url safe base64`() {
        val token = ApprovalToken.parsePresented("abcDEF123_-")

        assertThat(token.reveal()).matches("^[A-Za-z0-9_-]+$")
    }
}
