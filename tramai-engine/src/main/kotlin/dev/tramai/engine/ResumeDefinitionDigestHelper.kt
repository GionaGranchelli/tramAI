package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Computes a deterministic digest over the full resume definition: service definition
 * and operation definition including all canonical fields.
 *
 * The digest is used in [ResumeOperationReference.resumeDefinitionDigest] and must be
 * stable across JVM restarts for the same definition. It is stricter than
 * [WorkflowDigestHelper] because it includes more fields (annotations, prompts, cache
 * settings, operation prompt, class-level system prompt).
 */
internal object ResumeDefinitionDigestHelper {

    fun compute(
        serviceDefinition: ServiceDefinition,
        operation: OperationDefinition,
    ): Sha256Digest {
        val canonical = buildString {
            // Service identity
            append("service=").append(
                serviceDefinition.serviceType.qualifiedName
                    ?: serviceDefinition.serviceType.simpleName.orEmpty()
            ).append('\n')
            append("method=").append(operation.method.name).append('\n')
            append("jvm_descriptor=").append(JvmMethodDescriptorHelper.compute(operation.method)).append('\n')

            // Return kind and type description
            append("return_kind=").append(operation.returnKind.name).append('\n')
            append("return_type_description=").append(operation.returnTypeDescription).append('\n')

            // Operation settings
            append("model=").append(operation.operation.model).append('\n')
            append("provider=").append(operation.operation.provider).append('\n')
            append("timeout_millis=").append(operation.operation.timeoutMillis).append('\n')
            append("max_retries=").append(operation.operation.maxRetries).append('\n')
            append("provider_retries=").append(operation.operation.providerRetries).append('\n')
            append("cacheable=").append(operation.operation.cacheable).append('\n')
            append("cache_ttl_millis=").append(operation.operation.cacheTtlMillis).append('\n')

            // Operation prompt
            appendField("operation_prompt", operation.operation.prompt)

            // Class-level system prompt
            appendField("class_system_prompt", serviceDefinition.systemPrompt)

            // Method-level @System annotations (pre-sorted for determinism)
            append("system_annotations_count=").append(operation.systemAnnotations.size).append('\n')
            operation.systemAnnotations.sorted().forEachIndexed { i, annotation ->
                appendField("system_annotation_$i", annotation)
            }

            // Method-level @User annotations (pre-sorted for determinism)
            append("user_annotations_count=").append(operation.userAnnotations.size).append('\n')
            operation.userAnnotations.sorted().forEachIndexed { i, annotation ->
                appendField("user_annotation_$i", annotation)
            }

            // Tool definitions with stable ordering (sorted by name)
            append("tools_count=").append(operation.toolDefinitions.size).append('\n')
            operation.toolDefinitions.sortedBy { it.name }.forEachIndexed { index, tool ->
                append("tool_").append(index).append("_name=").append(tool.name).append('\n')
                append("tool_").append(index).append("_description_len=").append(tool.description.length).append('\n')
                append(tool.description).append('\n')
                append("tool_").append(index).append("_schema_len=").append(tool.inputSchemaJson.length).append('\n')
                append(tool.inputSchemaJson).append('\n')
            }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    private fun StringBuilder.appendField(name: String, value: String?) {
        if (value == null) {
            append(name).append("_null").append('\n')
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        append(name).append("_len=").append(bytes.size).append('\n')
        append(value).append('\n')
    }
}
