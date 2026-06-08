package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Helper for computing a deterministic workflow digest (SHA-256) over the canonical
 * operation definition: service interface name, method name, model config, and
 * tool definitions with stable ordering.
 *
 * The digest is used in [EngineExecutionIdentity.workflowDigest] and must be
 * stable across JVM restarts for the same operation definition.
 */
internal object WorkflowDigestHelper {

    fun compute(operation: OperationDefinition, serviceDefinition: ServiceDefinition): Sha256Digest {
        val canonical = buildString {
            append("service=").append(serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty()).append('\n')
            append("method=").append(operation.method.name).append('\n')
            append("model=").append(operation.operation.model).append('\n')
            append("provider=").append(operation.operation.provider).append('\n')
            append("timeout_millis=").append(operation.operation.timeoutMillis).append('\n')
            append("max_retries=").append(operation.operation.maxRetries).append('\n')
            append("provider_retries=").append(operation.operation.providerRetries).append('\n')
            append("cacheable=").append(operation.operation.cacheable).append('\n')
            append("tools_count=").append(operation.toolDefinitions.size).append('\n')
            // Stable ordering: sort tool definitions by name
            operation.toolDefinitions.sortedBy { it.name }.forEachIndexed { index, tool ->
                append("tool_").append(index).append("_name=").append(tool.name).append('\n')
                append("tool_").append(index).append("_description=").append(tool.description).append('\n')
                append("tool_").append(index).append("_schema=").append(tool.inputSchemaJson).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}
