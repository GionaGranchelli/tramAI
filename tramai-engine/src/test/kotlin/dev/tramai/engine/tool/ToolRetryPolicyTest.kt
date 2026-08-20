package dev.tramai.engine.tool

import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolRetryPolicyTest {
    private val policy = ToolRetryPolicy()

    @Test
    fun `retryable and repeat safe and attempt available retries`() {
        assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = true), 0, 2))
            .isEqualTo(ToolRetryDecision.Retry)
    }

    @Test
    fun `retryable but repetition unsafe stops with execution failed even when attempts remain`() {
        assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = false), 0, 3))
            .isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.EXECUTION_FAILED))
    }

    @Test
    fun `retryable and repeat safe but attempts exhausted stops with retry exhausted`() {
        assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = true), 1, 2))
            .isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.RETRY_EXHAUSTED))
    }

    @Test
    fun `retryable and repeat safe on last attempt stops with retry exhausted`() {
        assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = true), 2, 3))
            .isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.RETRY_EXHAUSTED))
    }

    @Test
    fun `cancellation is never classified and escapes`() {
        val cancellation = CancellationException("stop")
        assertThatThrownBy { policy.decide(ToolResult.TransientFailure(cancellation), testTool(idempotent = true), 0, 2) }
            .isSameAs(cancellation)
    }

    @Test
    fun `retryable non repeat safe on first attempt never retries`() {
        assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = false), 0, 2))
            .isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.EXECUTION_FAILED))
    }
}
