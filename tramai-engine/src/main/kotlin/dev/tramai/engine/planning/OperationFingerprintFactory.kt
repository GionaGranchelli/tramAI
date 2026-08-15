package dev.tramai.engine.planning

import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ToolDefinition
import dev.tramai.engine.sha256Hex

/** Builds the canonical operation-cache fingerprint without runtime state. */
internal class OperationFingerprintFactory {
    fun create(toolDefinitions: List<ToolDefinition>, operation: Operation): String {
        val canonical = buildString {
            append("tools_count=").append(toolDefinitions.size).append('\n')
            toolDefinitions.forEachIndexed { index, tool ->
                append("tool_").append(index).append("_name_len=").append(tool.name.length).append('\n')
                append(tool.name).append('\n')
                append("tool_").append(index).append("_schema_len=").append(tool.inputSchemaJson.length).append('\n')
                append(tool.inputSchemaJson).append('\n')
            }
            append("timeout_millis=").append(operation.timeoutMillis).append('\n')
            append("cacheable=").append(operation.cacheable).append('\n')
            append("cache_ttl_millis=").append(operation.cacheTtlMillis).append('\n')
        }
        return sha256Hex(canonical)
    }
}
