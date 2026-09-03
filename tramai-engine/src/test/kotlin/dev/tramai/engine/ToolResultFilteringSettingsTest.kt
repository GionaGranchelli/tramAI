package dev.tramai.engine

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Direct contract tests for [ToolResultFilteringSettings].
 *
 * The settings gate how much tool output is retained for the model, so the
 * default ceiling and per-tool overrides are behavioral contracts.
 */
class ToolResultFilteringSettingsTest {
    @Test
    fun `defaults to the documented aggregate ceiling`() {
        val settings = ToolResultFilteringSettings()

        assertThat(settings.defaultMaxAggregateTextLength).isEqualTo(100_000L)
        assertThat(settings.maxAggregateTextLengthByTool).isEmpty()
    }

    @Test
    fun `per-tool limit falls back to the default for unknown tools`() {
        val settings =
            ToolResultFilteringSettings(
                defaultMaxAggregateTextLength = 10L,
                maxAggregateTextLengthByTool = mapOf("search" to 5L),
            )

        assertThat(settings.maxAggregateTextLengthForTool("search")).isEqualTo(5L)
        assertThat(settings.maxAggregateTextLengthForTool("other")).isEqualTo(10L)
    }

    @Test
    fun `rejects non-positive default ceiling`() {
        assertThatThrownBy { ToolResultFilteringSettings(defaultMaxAggregateTextLength = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be positive")
        assertThatThrownBy { ToolResultFilteringSettings(defaultMaxAggregateTextLength = -1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects blank tool names`() {
        assertThatThrownBy {
            ToolResultFilteringSettings(maxAggregateTextLengthByTool = mapOf(" " to 5L))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be blank")
    }

    @Test
    fun `rejects tool names with surrounding whitespace`() {
        assertThatThrownBy {
            ToolResultFilteringSettings(maxAggregateTextLengthByTool = mapOf(" search " to 5L))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("surrounding whitespace")
    }

    @Test
    fun `rejects non-positive per-tool limits`() {
        assertThatThrownBy {
            ToolResultFilteringSettings(maxAggregateTextLengthByTool = mapOf("search" to 0L))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be positive")
    }

    @Test
    fun `defensively copies the supplied per-tool map`() {
        val original = HashMap<String, Long>()
        original["search"] = 5L
        val settings = ToolResultFilteringSettings(maxAggregateTextLengthByTool = original)
        original["search"] = 999L

        assertThat(settings.maxAggregateTextLengthByTool["search"]).isEqualTo(5L)
    }
}
