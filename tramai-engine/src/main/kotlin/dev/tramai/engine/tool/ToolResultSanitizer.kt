package dev.tramai.engine.tool

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolResult
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.ToolResultFilteringSettings
import kotlinx.coroutines.CancellationException

internal class ToolResultSanitizer(
    private val toolRegistry: ToolRegistry,
    private val dlpInterceptor: DlpInterceptor,
    private val dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
    private val toolResultFilteringSettings: ToolResultFilteringSettings,
    private val engineEventObserver: EngineEventObserver,
) {
    suspend fun sanitize(message: Message, operation: OperationDefinition, toolName: String, correlationId: String, securityContext: ExecutionSecurityContext): Message {
        if (dlpInterceptor === NoOpDlpInterceptor) return message
        return sanitizeToolMessageContent(message, toolReinjectionDlpScope(operation, toolName, correlationId, securityContext))
    }

    fun format(toolResult: ToolResult, toolCallId: String): Message = when (toolResult) {
        is ToolResult.Success -> createToolSuccessMessage(toolResult, toolCallId)
        is ToolResult.InvalidInput -> Message(role = MessageRole.TOOL, content = "Error: ${toolResult.message}", toolCallId = toolCallId)
        is ToolResult.PermanentFailure -> Message(role = MessageRole.TOOL, content = "Permanent error: ${toolResult.message}", toolCallId = toolCallId)
        is ToolResult.TransientFailure -> error("TransientFailure should be resolved inside executeTool")
    }

    private data class ToolReinjectionDlpScope(val canonicalToolName: String, val safeToolLabel: String, val dlpContext: DlpContext, val aggregateTextLimit: Long, val correlationId: String)

    private fun toolReinjectionDlpScope(operation: OperationDefinition, toolName: String, correlationId: String, securityContext: ExecutionSecurityContext): ToolReinjectionDlpScope {
        val resolvedTool = toolRegistry.resolve(toolName)
        val canonicalToolName = resolvedTool?.name ?: UNREGISTERED_LABEL
        val safeToolLabel = canonicalToolName.take(MAX_SAFE_TOOL_NAME_LENGTH)
        return ToolReinjectionDlpScope(canonicalToolName, safeToolLabel, DlpContext(
            contentType = DlpContentType.TOOL_RESULT, contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
            operationInterface = operation.method.declaringClass.name, operationMethod = operation.method.name,
            toolName = canonicalToolName, correlationId = correlationId, dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
        ), toolResultFilteringSettings.maxAggregateTextLengthForTool(toolName), correlationId)
    }

    private suspend fun sanitizeToolMessageContent(message: Message, scope: ToolReinjectionDlpScope): Message {
        val contentParts = message.contentParts
        if (contentParts.isNullOrEmpty()) {
            if (message.content.isEmpty()) return message
            accumulateToolTextLength(scope, 0L, message.content)
            return message.copy(content = sanitizeToolText(scope, message.content, DlpContentLocation.TOOL_MESSAGE_CONTENT, authoritative = true))
        }
        return sanitizeToolContentParts(message, contentParts, scope)
    }

    private fun emitEngineEventSafely(name: String, attributes: Map<String, Any?>) {
        try { engineEventObserver.onEngineEvent(name, attributes) } catch (error: Exception) {
            System.getLogger("dev.tramai.engine.TramaiEngine").log(System.Logger.Level.WARNING, "Engine event observer failed for '$name': ${error::class.simpleName}")
        }
    }

    private fun rejectAggregateTextLength(scope: ToolReinjectionDlpScope, actualLength: Long): Nothing {
        emitEngineEventSafely(DLP_TOOL_REJECTED_METRIC, mapOf("reasonCode" to "aggregate_text_limit_exceeded", "aggregateTextLength" to actualLength, "configuredLimit" to scope.aggregateTextLimit, "correlationId" to scope.correlationId, "toolName" to scope.safeToolLabel))
        throw DlpInspectionException("Tool result from '${scope.safeToolLabel}' exceeds aggregate input limit ($actualLength > ${scope.aggregateTextLimit})")
    }
    private fun rejectSanitizedTextLimit(scope: ToolReinjectionDlpScope, actualLength: Long): Nothing {
        emitEngineEventSafely(DLP_TOOL_REJECTED_METRIC, mapOf("reasonCode" to "sanitized_text_limit_exceeded", "aggregateTextLength" to actualLength, "configuredLimit" to scope.aggregateTextLimit, "correlationId" to scope.correlationId, "toolName" to scope.safeToolLabel))
        throw DlpInspectionException("Sanitized tool result from '${scope.safeToolLabel}' exceeds aggregate limit ($actualLength > ${scope.aggregateTextLimit})")
    }
    private fun rejectCrossBoundarySensitiveText(scope: ToolReinjectionDlpScope): Nothing {
        emitEngineEventSafely(DLP_TOOL_REJECTED_METRIC, mapOf("reasonCode" to "cross_boundary_sensitive_text_detected", "correlationId" to scope.correlationId, "toolName" to scope.safeToolLabel))
        throw DlpInspectionException("Tool result from '${scope.safeToolLabel}' contains sensitive text spanning non-text boundaries")
    }

    private suspend fun sanitizeToolText(scope: ToolReinjectionDlpScope, text: String, contentLocation: DlpContentLocation, authoritative: Boolean): String = try {
        val effectiveContext = scope.dlpContext.copy(contentLocation = contentLocation)
        val result = if (authoritative) inspectDlpAuthoritatively(effectiveContext, text) else inspectDlpForDetectionOnly(effectiveContext, text)
        result.sanitizedText.also { if (it.length.toLong() > scope.aggregateTextLimit) rejectSanitizedTextLimit(scope, it.length.toLong()) }
    } catch (e: DlpInspectionException) { throw e
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        emitEngineEventSafely("tramai.dlp.inspection_failed", mapOf("toolName" to scope.safeToolLabel, "correlationId" to scope.correlationId))
        throw DlpInspectionException("DLP inspection failed for tool result from tool '${scope.safeToolLabel}'", e)
    }

    private fun accumulateToolTextLength(scope: ToolReinjectionDlpScope, currentLength: Long, text: String): Long {
        val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) Long.MAX_VALUE else currentLength + text.length.toLong()
        if (nextLength > scope.aggregateTextLimit) rejectAggregateTextLength(scope, nextLength)
        return nextLength
    }

    private suspend fun sanitizeToolContentParts(message: Message, contentParts: List<ContentPart>, scope: ToolReinjectionDlpScope): Message {
        var aggregateLength = 0L; val sanitizedParts = mutableListOf<ContentPart>(); val textRun = mutableListOf<String>(); val sanitizedTextRuns = mutableListOf<String>(); var sanitizedAggregateLength = 0L
        suspend fun flushTextRun() {
            if (textRun.isEmpty()) return
            val combinedText = buildString { textRun.forEach(::append) }
            val sanitizedText = sanitizeToolText(scope, combinedText, DlpContentLocation.TOOL_MESSAGE_TEXT_RUN, authoritative = true)
            sanitizedAggregateLength = accumulateSanitizedToolTextLength(scope, sanitizedAggregateLength, sanitizedText)
            sanitizedTextRuns += sanitizedText
            if (sanitizedText.isNotEmpty()) sanitizedParts += ContentPart.TextPart(sanitizedText)
            textRun.clear()
        }
        contentParts.forEach { part -> when (part) {
            is ContentPart.TextPart -> { aggregateLength = accumulateToolTextLength(scope, aggregateLength, part.text); textRun += part.text }
            else -> { flushTextRun(); sanitizedParts += part }
        } }
        flushTextRun()
        val allTextParts = contentParts.mapNotNull { (it as? ContentPart.TextPart)?.text }
        if (allTextParts.size > 1) {
            val projectedResult = sanitizeToolText(scope, allTextParts.joinToString(""), DlpContentLocation.TOOL_MESSAGE_CONTENT, authoritative = false)
            val individualCombined = buildString { sanitizedTextRuns.forEach(::append) }
            val combinedResanitized = sanitizeToolText(scope, individualCombined, DlpContentLocation.TOOL_MESSAGE_CONTENT, authoritative = false)
            if (projectedResult != individualCombined && combinedResanitized != individualCombined) rejectCrossBoundarySensitiveText(scope)
        }
        return message.copy(content = "", contentParts = sanitizedParts.ifEmpty { null })
    }

    private fun accumulateSanitizedToolTextLength(scope: ToolReinjectionDlpScope, currentLength: Long, text: String): Long {
        val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) Long.MAX_VALUE else currentLength + text.length.toLong()
        if (nextLength > scope.aggregateTextLimit) rejectSanitizedTextLimit(scope, nextLength)
        return nextLength
    }

    private fun createToolSuccessMessage(toolResult: ToolResult.Success, toolCallId: String): Message {
        val textContent = toolResult.value.toString(); val contentParts = toolResult.contentParts
        return if (!contentParts.isNullOrEmpty()) Message(role = MessageRole.TOOL, content = "", contentParts = buildList { add(ContentPart.TextPart(textContent)); addAll(contentParts) }, toolCallId = toolCallId)
        else Message(role = MessageRole.TOOL, content = textContent, toolCallId = toolCallId)
    }

    private suspend fun inspectDlpAuthoritatively(context: DlpContext, text: String) = dlpInterceptor.inspect(context, text).also { result ->
        val sanitizedTextChanged = result.sanitizedText != text; val hasRedactionEvidence = result.redactions.isNotEmpty()
        if (sanitizedTextChanged && !hasRedactionEvidence && dlpRedactionAuditEmitter !== NoOpDlpRedactionAuditEmitter) throw DlpInspectionException("DLP modified output without redaction evidence")
        if (!sanitizedTextChanged && hasRedactionEvidence) throw DlpInspectionException("DLP redactions reported without modifying output")
        if (result.redactions.isNotEmpty()) {
            try {
                dlpRedactionAuditEmitter.emit(context, result.redactions)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                throw DlpInspectionException("DLP redaction audit emission failed", error)
            }
        }
    }
    private fun inspectDlpForDetectionOnly(context: DlpContext, text: String) = dlpInterceptor.inspect(context, text)
}
