package dev.tramai.engine

data class ToolResultFilteringSettings(
    val defaultMaxAggregateTextLength: Long = 100_000L,
    val maxAggregateTextLengthByTool: Map<String, Long> = emptyMap(),
) {
    init {
        require(defaultMaxAggregateTextLength > 0) {
            "defaultMaxAggregateTextLength must be positive, got $defaultMaxAggregateTextLength"
        }
        maxAggregateTextLengthByTool.forEach { (tool, limit) ->
            require(tool.isNotBlank()) { "Tool name in maxAggregateTextLengthByTool must not be blank" }
            require(limit > 0) { "Limit for tool '$tool' must be positive, got $limit" }
        }
    }

    fun maxAggregateTextLengthForTool(toolName: String): Long =
        maxAggregateTextLengthByTool[toolName] ?: defaultMaxAggregateTextLength
}
