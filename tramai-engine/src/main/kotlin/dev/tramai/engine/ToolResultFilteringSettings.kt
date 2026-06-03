package dev.tramai.engine

class ToolResultFilteringSettings(
    val defaultMaxAggregateTextLength: Long = 100_000L,
    maxAggregateTextLengthByTool: Map<String, Long> = emptyMap(),
) {
    val maxAggregateTextLengthByTool: Map<String, Long>

    init {
        require(defaultMaxAggregateTextLength > 0) {
            "defaultMaxAggregateTextLength must be positive, got $defaultMaxAggregateTextLength"
        }
        maxAggregateTextLengthByTool.forEach { (tool, limit) ->
            require(tool.isNotBlank()) { "Tool name in maxAggregateTextLengthByTool must not be blank" }
            require(tool == tool.trim()) {
                "Tool name '$tool' in maxAggregateTextLengthByTool must not have surrounding whitespace"
            }
            require(limit > 0) { "Limit for tool '$tool' must be positive, got $limit" }
        }
        this.maxAggregateTextLengthByTool = HashMap(maxAggregateTextLengthByTool).toMap()
    }

    fun maxAggregateTextLengthForTool(toolName: String): Long =
        maxAggregateTextLengthByTool[toolName] ?: defaultMaxAggregateTextLength
}
