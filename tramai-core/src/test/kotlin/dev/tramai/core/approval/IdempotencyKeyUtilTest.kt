package dev.tramai.core.approval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdempotencyKeyUtilTest {

    @Test
    fun `same approvalId toolCallId and digest produce same key`() {
        val digest = Sha256Digest.of(
            "sha256:1111111111111111111111111111111111111111111111111111111111111111",
        )

        val first = IdempotencyKeyUtil.deriveApprovalKey("approval-1", "tool-call-1", digest)
        val second = IdempotencyKeyUtil.deriveApprovalKey("approval-1", "tool-call-1", digest)

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `different digest produces different key`() {
        val first = IdempotencyKeyUtil.deriveApprovalKey(
            "approval-1",
            "tool-call-1",
            Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111"),
        )
        val second = IdempotencyKeyUtil.deriveApprovalKey(
            "approval-1",
            "tool-call-1",
            Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
        )

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `different toolCallId produces different key`() {
        val digest = Sha256Digest.of(
            "sha256:1111111111111111111111111111111111111111111111111111111111111111",
        )

        val first = IdempotencyKeyUtil.deriveApprovalKey("approval-1", "tool-call-1", digest)
        val second = IdempotencyKeyUtil.deriveApprovalKey("approval-1", "tool-call-2", digest)

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `length-prefixed encoding prevents ambiguous concatenation`() {
        val digest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")

        val first = IdempotencyKeyUtil.deriveApprovalKey("a:b", "c", digest)
        val second = IdempotencyKeyUtil.deriveApprovalKey("a", "b:c", digest)

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `raw arguments never appear in key output`() {
        val rawArguments = """{"secret":"never-in-key"}"""
        val digester = ToolArgumentsDigester { args ->
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(args.reveal().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val hex = bytes.joinToString("") { "%02x".format(it) }
            Sha256Digest.of("sha256:$hex")
        }
        val digest = digester.digest(SensitiveToolArguments.of(rawArguments))
        val key = IdempotencyKeyUtil.deriveApprovalKey(
            "approval-1",
            "tool-call-1",
            digest,
        )

        assertThat(key).doesNotContain(rawArguments)
        assertThat(key).matches("[0-9a-f]{64}")
    }
}
