package dev.tramai.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolResultCompatibilityTest {

    @Test
    fun `the four stable variants remain exhaustively matchable`() {
        fun describe(result: ToolResult): String = when (result) {
            is ToolResult.Success -> "s"
            is ToolResult.InvalidInput -> "i"
            is ToolResult.TransientFailure -> "t"
            is ToolResult.PermanentFailure -> "p"
        }

        assertThat(
            listOf(
                ToolResult.Success("ok"),
                ToolResult.InvalidInput("invalid"),
                ToolResult.TransientFailure(RuntimeException("retry")),
                ToolResult.PermanentFailure("failed"),
            ).map(::describe),
        ).containsExactly("s", "i", "t", "p")
    }

    @Test
    fun `safe factories use trusted text or fixed defaults`() {
        assertThat(ToolResult.safeInvalidInput().message)
            .isEqualTo(ToolFailureCode.INVALID_INPUT.defaultModelMessage)
        assertThat(ToolResult.safePermanentFailure().message)
            .isEqualTo(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage)

        assertThat(
            ToolResult.safeInvalidInput(ModelVisibleToolMessage.trusted("Input rejected")).message,
        ).isEqualTo("Input rejected")
        assertThat(
            ToolResult.safePermanentFailure(ModelVisibleToolMessage.trusted("Execution rejected")).message,
        ).isEqualTo("Execution rejected")
    }

    @Test
    fun `trusted message rejects supplementary format code points`() {
        assertThatThrownBy { ModelVisibleToolMessage.trusted("\uDB40\uDC01") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
