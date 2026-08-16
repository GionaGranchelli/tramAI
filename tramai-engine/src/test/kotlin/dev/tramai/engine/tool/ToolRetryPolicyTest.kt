package dev.tramai.engine.tool

import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolRetryPolicyTest {
    private val policy = ToolRetryPolicy()
    @Test fun `transient idempotent with attempt remains retries`() { assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = true), 0, 2)).isEqualTo(ToolRetryDecision.Retry) }
    @Test fun `transient idempotent exhausted stops`() { assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(idempotent = true), 1, 2)).isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.RETRY_EXHAUSTED)) }
    @Test fun `transient non idempotent stops execution failed`() { assertThat(policy.decide(ToolResult.TransientFailure(RuntimeException()), testTool(), 0, 1)).isEqualTo(ToolRetryDecision.Stop(ToolFailureCode.EXECUTION_FAILED)) }
    @Test fun `cancellation is never classified`() { val cancellation = CancellationException("stop"); assertThatThrownBy { policy.decide(ToolResult.TransientFailure(cancellation), testTool(idempotent = true), 0, 2) }.isSameAs(cancellation) }
}
