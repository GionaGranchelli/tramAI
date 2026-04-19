package dev.tramai.engine

import dev.tramai.core.model.ResolvedTool

/**
 * Registry of resolved tools available to AI operations.
 */
class ToolRegistry(
    private val tools: Map<String, ResolvedTool> = emptyMap()
) {
    /**
     * Resolves a tool by its unique name.
     */
    fun resolve(name: String): ResolvedTool? = tools[name]

    /**
     * Lists all registered tool names.
     */
    fun registeredToolNames(): Set<String> = tools.keys
}
