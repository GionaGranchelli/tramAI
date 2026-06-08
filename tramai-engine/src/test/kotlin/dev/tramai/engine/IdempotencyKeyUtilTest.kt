package dev.tramai.engine

import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.core.approval.Sha256Digest
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
    fun `raw arguments never appear in key output`() {
        val rawArguments = """{"secret":"never-in-key"}"""
        val key = IdempotencyKeyUtil.deriveApprovalKey(
            "approval-1",
            "tool-call-1",
            Sha256Digest.of("sha256:3333333333333333333333333333333333333333333333333333333333333333"),
        )

        assertThat(key).doesNotContain(rawArguments)
        assertThat(key).matches("[0-9a-f]{64}")
    }
}
