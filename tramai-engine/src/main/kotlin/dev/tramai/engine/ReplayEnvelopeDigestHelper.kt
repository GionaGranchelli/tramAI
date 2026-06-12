package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Computes a deterministic digest over the [SensitiveReplayEnvelope] content.
 *
 * Canonicalises the messages deterministically using the same conventions as
 * [buildRequestDigest] so the digest can be compared at resume time.
 *
 * Used in [SuspendedInvocationMetadata.replayEnvelopeDigest] to detect
 * replay-envelope tampering after claim.
 */
internal object ReplayEnvelopeDigestHelper {

    fun compute(
        operationReference: ResumeOperationReference,
        messages: List<Message>,
    ): Sha256Digest {
        val canonical = buildString {
            appendField("service_interface", operationReference.serviceInterface)
            append("method=").append(operationReference.methodName).append('\n')
            append("jvm_descriptor=").append(operationReference.jvmMethodDescriptor).append('\n')
            append("digest=").append(operationReference.resumeDefinitionDigest.value).append('\n')

            // Messages are the primary content — canonicalise deterministically
            messages.forEachIndexed { index, message ->
                if (index > 0) {
                    append("---\n")
                }
                append("role=")
                append(message.role.name)
                append('\n')
                appendField("content", message.content)
                append("parts_count=").append(message.contentParts?.size ?: 0).append('\n')
                message.contentParts.orEmpty().forEachIndexed { partIndex, part ->
                    append("part_index=").append(partIndex).append('\n')
                    when (part) {
                        is ContentPart.TextPart -> {
                            append("part_type=text\n")
                            appendField("text", part.text)
                        }
                        is ContentPart.ImagePart -> {
                            append("part_type=image\n")
                            appendField("mime", part.mimeType)
                            appendField("data_b64", Base64.getEncoder().encodeToString(part.data))
                        }
                        is ContentPart.ImageUrlContent -> {
                            append("part_type=image_url\n")
                            appendField("url", part.url)
                            appendField("mime", part.mimeType)
                        }
                    }
                }
                if (message.toolCallId != null) {
                    appendField("tool_call_id", message.toolCallId)
                }
                message.toolCalls?.let { toolCalls ->
                    append("tool_calls_count=").append(toolCalls.size).append('\n')
                    toolCalls.forEachIndexed { toolIndex, toolCall ->
                        append("tool_call_index=").append(toolIndex).append('\n')
                        appendField("tool_call_id", toolCall.id)
                        appendField("tool_call_name", toolCall.name)
                        appendField("tool_call_args", toolCall.argumentsJson)
                    }
                }
            }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    private fun StringBuilder.appendField(name: String, value: String?) {
        if (value == null) {
            append(name).append("_null\n")
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        append(name).append("_len=").append(bytes.size).append('\n')
        append(value).append('\n')
    }
}
