package dev.tramai.security.audit

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpRedaction
import dev.tramai.core.security.DlpRedactionAuditEmitter

fun interface DlpAuditStreamIdResolver {
    fun resolve(context: DlpContext): String
}

object DefaultDlpAuditStreamIdResolver : DlpAuditStreamIdResolver {
    override fun resolve(context: DlpContext): String {
        val workflowRunId = context.workflowRunId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (workflowRunId != null) return workflowRunId

        val id = context.correlationId.trim()
        require(id.isNotEmpty()) { "DLP audit stream ID must not be blank" }
        require(id.length <= 256) { "DLP audit stream ID exceeds maximum length of 256" }
        return id
    }
}

class AuditEngineDlpRedactionAuditEmitter(
    private val auditEngine: AuditEngine,
    private val streamIdResolver: DlpAuditStreamIdResolver = DefaultDlpAuditStreamIdResolver,
) : DlpRedactionAuditEmitter {

    override suspend fun emit(
        context: DlpContext,
        redactions: List<DlpRedaction>,
    ) {
        val streamId = resolveSafeStreamId(context)
        val normalized = normalizeRedactions(redactions)
        val enforcementPoint = enforcementPointFor(context.contentType)
        val sharedMetadata = buildSharedMetadata(context)

        normalized.forEach { redaction ->
            auditEngine.emit(
                auditStreamId = streamId,
                workflowRunId = null,
                correlationId = context.correlationId,
                actor = null,
                enforcementPoint = enforcementPoint.name,
                decision = DECISION_REDACTED,
                policyVersion = null,
                workflowDigest = null,
                reasonCode = REASON_CODE_REDACTION_APPLIED,
                metadata = sharedMetadata + mapOf(
                    METADATA_RULE_ID to redaction.ruleId,
                    METADATA_REPLACEMENT_COUNT to redaction.replacementCount.toString(),
                ),
            )
        }
    }

    private fun resolveSafeStreamId(context: DlpContext): String {
        val raw = streamIdResolver.resolve(context).trim()
        require(raw.isNotEmpty()) {
            "DLP audit stream ID must not be blank"
        }
        require(raw.length <= MAX_STREAM_ID_LENGTH) {
            "DLP audit stream ID exceeds maximum length of 256"
        }
        return raw
    }

    private fun enforcementPointFor(contentType: DlpContentType): EnforcementPoint = when (contentType) {
        DlpContentType.MODEL_OUTPUT -> EnforcementPoint.DLP_MODEL_OUTPUT
        DlpContentType.TOOL_RESULT -> EnforcementPoint.DLP_TOOL_RESULT
    }

    private fun normalizeRedactions(redactions: List<DlpRedaction>): List<NormalizedDlpRedaction> {
        val grouped = linkedMapOf<String, Long>()
        redactions.forEach { redaction ->
            require(redaction.replacementCount > 0) {
                "DLP replacement count must be greater than zero"
            }
            val normalizedRuleId = normalizeRuleId(redaction.ruleId)
            val existing = grouped[normalizedRuleId] ?: 0L
            val updated = existing + redaction.replacementCount.toLong()
            require(updated >= existing) {
                "DLP replacement count overflow"
            }
            grouped[normalizedRuleId] = updated
        }

        return grouped.entries
            .sortedBy { it.key }
            .map { (ruleId, replacementCount) ->
                NormalizedDlpRedaction(
                    ruleId = ruleId,
                    replacementCount = replacementCount,
                )
            }
    }

    private fun normalizeRuleId(ruleId: String): String {
        val normalized = ruleId.trim()
        require(normalized.isNotEmpty()) { "DLP rule ID must not be blank" }
        require(normalized == ruleId) { "DLP rule ID must not contain surrounding whitespace" }
        require(normalized.length <= MAX_RULE_ID_LENGTH) { "DLP rule ID exceeds maximum length of 128" }
        require(SAFE_RULE_ID.matches(normalized)) { "DLP rule ID is invalid" }
        return normalized
    }

    private fun buildSharedMetadata(context: DlpContext): Map<String, String> {
        val entries = linkedMapOf<String, String>()

        putMetadata(entries, METADATA_CONTENT_TYPE, context.contentType.name)
        putMetadata(entries, METADATA_CONTENT_LOCATION, context.contentLocation.name)
        putMetadata(entries, METADATA_OPERATION_INTERFACE, context.operationInterface)
        putMetadata(entries, METADATA_OPERATION_METHOD, context.operationMethod)
        context.providerId?.let { putMetadata(entries, METADATA_PROVIDER_NAME, it) }
        context.modelName?.let { putMetadata(entries, METADATA_MODEL_NAME, it) }
        context.toolName?.let { putMetadata(entries, METADATA_TOOL_NAME, it) }
        context.dataClassification?.let { putMetadata(entries, METADATA_CLASSIFICATION, it.name) }
        context.classificationSource?.let { putMetadata(entries, METADATA_CLASSIFICATION_SOURCE, it.name) }

        require(entries.size <= MAX_METADATA_ENTRIES) {
            "DLP audit metadata exceeds maximum entry count of $MAX_METADATA_ENTRIES"
        }
        return entries.toMap()
    }

    private fun putMetadata(
        entries: MutableMap<String, String>,
        key: String,
        value: String,
    ) {
        require(entries.size < MAX_METADATA_ENTRIES || key in entries) {
            "DLP audit metadata exceeds maximum entry count of $MAX_METADATA_ENTRIES"
        }
        require(key.length <= MAX_KEY_LENGTH) {
            "DLP audit metadata key exceeds maximum length of $MAX_KEY_LENGTH"
        }
        require(value.length <= MAX_VALUE_LENGTH) {
            "DLP audit metadata value exceeds maximum length of $MAX_VALUE_LENGTH"
        }
        entries[key] = value
    }

    private data class NormalizedDlpRedaction(
        val ruleId: String,
        val replacementCount: Long,
    )

    companion object {
        private const val DECISION_REDACTED = "REDACTED"
        private const val REASON_CODE_REDACTION_APPLIED = "dlp_redaction_applied"
        private const val MAX_STREAM_ID_LENGTH = 256
        private const val MAX_RULE_ID_LENGTH = 128
        private const val MAX_METADATA_ENTRIES = 16
        private const val MAX_KEY_LENGTH = 64
        private const val MAX_VALUE_LENGTH = 256
        private const val METADATA_CONTENT_TYPE = "contentType"
        private const val METADATA_CONTENT_LOCATION = "contentLocation"
        private const val METADATA_RULE_ID = "ruleId"
        private const val METADATA_REPLACEMENT_COUNT = "replacementCount"
        private const val METADATA_OPERATION_INTERFACE = "operationInterface"
        private const val METADATA_OPERATION_METHOD = "operationMethod"
        private const val METADATA_PROVIDER_NAME = "providerName"
        private const val METADATA_MODEL_NAME = "modelName"
        private const val METADATA_TOOL_NAME = "toolName"
        private const val METADATA_CLASSIFICATION = "classification"
        private const val METADATA_CLASSIFICATION_SOURCE = "classificationSource"
        val SAFE_RULE_ID = Regex("[a-z0-9][a-z0-9._:-]{0,127}")
    }
}
