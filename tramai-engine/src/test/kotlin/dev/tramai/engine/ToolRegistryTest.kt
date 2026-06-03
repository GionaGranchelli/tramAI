package dev.tramai.engine

import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private val validTool = object : ResolvedTool {
        override val name = "lookup"
        override val description = "Looks up data"
        override val inputSchemaJson = """{"type":"object"}"""
        override val idempotent = false
        override val sideEffectLevel = SideEffectLevel.READ_ONLY
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
            ToolResult.Success("ok")
    }

    private val overlongName = "x".repeat(ToolRegistry.MAX_TOOL_NAME_LENGTH + 1)

    @Test
    fun `mutating original map after construction does not affect registry`() {
        val mutableMap: MutableMap<String, ResolvedTool> = mutableMapOf("lookup" to validTool)
        val registry = ToolRegistry(mutableMap)

        mutableMap["x".repeat(10_000)] = object : ResolvedTool {
            override val name = "malicious"
            override val description = ""
            override val inputSchemaJson = ""
            override val idempotent = false
            override val sideEffectLevel = SideEffectLevel.READ_ONLY
            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
                ToolResult.Success("hack")
        }

        assertThat(registry.registeredToolNames()).containsExactly("lookup")
    }

    @Test
    fun `key mismatch with ResolvedTool name is rejected`() {
        val toolWithDifferentName = object : ResolvedTool {
            override val name = "different-long-name-here"
            override val description = ""
            override val inputSchemaJson = """{"type":"object"}"""
            override val idempotent = false
            override val sideEffectLevel = SideEffectLevel.READ_ONLY
            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
                ToolResult.Success("ok")
        }

        assertThatThrownBy {
            ToolRegistry(mapOf("short-key" to toolWithDifferentName))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("short-key")
            .hasMessageContaining("different-long-name-here")
    }

    @Test
    fun `overlong ResolvedTool name hidden behind short key is rejected`() {
        val hiddenOverlongTool = object : ResolvedTool {
            override val name = overlongName
            override val description = ""
            override val inputSchemaJson = """{"type":"object"}"""
            override val idempotent = false
            override val sideEffectLevel = SideEffectLevel.READ_ONLY
            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult =
                ToolResult.Success("ok")
        }

        // Key passes validation but resolvedTool.name doesn't match key
        assertThatThrownBy {
            ToolRegistry(mapOf("short-key" to hiddenOverlongTool))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("short-key")
    }

    @Test
    fun `valid tool registration succeeds`() {
        val registry = ToolRegistry(mapOf("lookup" to validTool))
        assertThat(registry.resolve("lookup")).isSameAs(validTool)
        assertThat(registry.registeredToolNames()).containsExactly("lookup")
    }

    @Test
    fun `resolve returns null for unregistered tool`() {
        val registry = ToolRegistry(mapOf("lookup" to validTool))
        assertThat(registry.resolve("unknown")).isNull()
    }
}
