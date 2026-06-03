package dev.tramai.engine

import dev.tramai.core.model.ResolvedTool

/**
 * Registry of resolved tools available to AI operations.
 */
class ToolRegistry(
    private val tools: Map<String, ResolvedTool> = emptyMap()
) {
    init {
        tools.keys.forEach { name ->
            require(name.isNotBlank()) { "Tool name must not be blank" }
            require(name == name.trim()) { "Tool name '$name' must not have surrounding whitespace" }
            require(name.length <= MAX_TOOL_NAME_LENGTH) {
                "Tool name '$name' exceeds maximum length of $MAX_TOOL_NAME_LENGTH (${name.length})"
            }
        }
    }

    /**
     * Resolves a tool by its unique name.
     */
    fun resolve(name: String): ResolvedTool? = tools[name]

    /**
     * Lists all registered tool names.
     */
    fun registeredToolNames(): Set<String> = tools.keys

    companion object {
        const val MAX_TOOL_NAME_LENGTH = 256
    }
}
