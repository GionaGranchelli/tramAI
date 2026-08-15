package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.engine.planning.ServiceDefinition
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
            appendField("service", serviceDefinition.serviceType.qualifiedName
                ?: serviceDefinition.serviceType.simpleName.orEmpty())
            appendField("method", operation.method.name)
            appendField("jvm_descriptor", JvmMethodDescriptorHelper.compute(operation.method))

            // Return kind and type description
            append("return_kind=").append(operation.returnKind.name).append('\n')
            appendField("return_type_description", operation.returnTypeDescription)

            // Operation settings — model and provider use appendField for UTF-8 byte length
            appendField("model", operation.operation.model)
            appendField("provider", operation.operation.provider)
            append("timeout_millis=").append(operation.operation.timeoutMillis).append('\n')
            append("max_retries=").append(operation.operation.maxRetries).append('\n')
            append("provider_retries=").append(operation.operation.providerRetries).append('\n')
            append("cacheable=").append(operation.operation.cacheable).append('\n')
            append("cache_ttl_millis=").append(operation.operation.cacheTtlMillis).append('\n')

            // Operation prompt
            appendField("operation_prompt", operation.operation.prompt)

            // Class-level system prompt
            appendField("class_system_prompt", serviceDefinition.systemPrompt)

            // Method-level @System annotations (preserve original order — no sorting)
            append("system_annotations_count=").append(operation.systemAnnotations.size).append('\n')
            operation.systemAnnotations.forEachIndexed { i, annotation ->
                appendField("system_annotation_$i", annotation)
            }

            // Method-level @User annotations (preserve original order — no sorting)
            append("user_annotations_count=").append(operation.userAnnotations.size).append('\n')
            operation.userAnnotations.forEachIndexed { i, annotation ->
                appendField("user_annotation_$i", annotation)
            }

            // Tool definitions (no sorting — preserve declaration order)
            append("tools_count=").append(operation.toolDefinitions.size).append('\n')
            operation.toolDefinitions.forEachIndexed { index, tool ->
                appendField("tool_${index}_name", tool.name)
                appendField("tool_${index}_description", tool.description)
                appendField("tool_${index}_schema", tool.inputSchemaJson)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}
