package dev.tramai.engine

import dev.tramai.core.model.ResolvedTool

/**
 * Registry of resolved tools available to AI operations.
 *
 * Validates that every registered tool name:
 * - is non-blank
 * - has no surrounding whitespace
 * - does not exceed [MAX_TOOL_NAME_LENGTH]
 * - matches its [ResolvedTool.name]
 *
 * The registry takes a defensive copy of the supplied map to prevent
 * mutation after construction.
 */
class ToolRegistry(
    tools: Map<String, ResolvedTool> = emptyMap(),
) {
    private val tools: Map<String, ResolvedTool> = tools
        .toMap()
        .also { entries ->
            entries.forEach { (key, tool) ->
                validateToolName(key)
                require(tool.name == key) {
                    "Tool registry key '$key' must match ResolvedTool.name '${tool.name}'"
                }
            }
        }

    private fun validateToolName(name: String) {
        require(name.isNotBlank()) { "Tool name must not be blank" }
        require(name == name.trim()) { "Tool name '$name' must not have surrounding whitespace" }
        require(name.length <= MAX_TOOL_NAME_LENGTH) {
            "Tool name '$name' exceeds maximum length of $MAX_TOOL_NAME_LENGTH (${name.length})"
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
