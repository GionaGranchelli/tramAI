package dev.tramai.security.audit

import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.policy.*

class AuditEnginePolicyDecisionAuditEmitter(
    private val auditEngine: AuditEngine,
    private val streamIdResolver: AuditStreamIdResolver = DefaultAuditStreamIdResolver,
) : PolicyDecisionAuditEmitter {

    companion object {
        private const val MAX_METADATA_ENTRIES = 16
        private const val MAX_KEY_LENGTH = 64
        private const val MAX_VALUE_LENGTH = 256

        /**
         * Explicit allowlist of safe [PolicyContext.attributes] keys that may
         * be persisted into durable hash-chained audit metadata.
         *
         * Only keys in this set are exported. All other attributes are dropped
         * to prevent accidental data exfiltration through the audit trail.
         */
        private val ALLOWED_ATTRIBUTE_KEYS = setOf(
            "cacheReuse",
            "fallbackReason",
        )

        /**
         * Strict token pattern for [PolicyDecision.Deny.reasonCode].
         *
         * Custom policy engines supply arbitrary strings. Normalizing through
         * this pattern prevents sensitive values (secrets, prompts, API keys)
         * from becoming durable hash-chained audit evidence.
         *
         * Pattern: starts with a lowercase alphanumeric, followed by up to 127
         * lowercase alphanumeric, dots, underscores, colons, or hyphens.
         * Hyphen is placed at the end of the character class to be literal.
         */
        private val SAFE_REASON_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

        private fun safeReasonCode(raw: String): String =
            raw.takeIf(SAFE_REASON_CODE::matches) ?: "policy_denied"
    }

    override suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    ) {
        val streamId = resolveSafeStreamId(context)
        val (decisionStr, reasonCode) = when (decision) {
            is PolicyDecision.Allow -> Pair("ALLOW", "policy_allowed")
            is PolicyDecision.Deny -> Pair("DENY", safeReasonCode(decision.reasonCode))
            is PolicyDecision.RequireApproval -> Pair("REQUIRE_APPROVAL", "policy_requires_approval")
        }

        val metadata = buildSafeMetadata(context)

        auditEngine.emit(
            auditStreamId = streamId,
            workflowRunId = context.workflowRunId,
            correlationId = context.correlationId,
            actor = SafeActorIdPolicy.safeActorId(context.actorId),
            enforcementPoint = enforcementPoint.name,
            decision = decisionStr,
            policyVersion = context.policyVersion,
            workflowDigest = context.workflowDigest,
            reasonCode = reasonCode,
            metadata = metadata,
        )
    }

    private fun resolveSafeStreamId(context: PolicyContext): String {
        val raw = streamIdResolver.resolve(context).trim()
        require(raw.isNotEmpty()) {
            "Audit stream ID must not be blank"
        }
        require(raw.length <= 256) {
            "Audit stream ID exceeds maximum length of 256"
        }
        return raw
    }

    private fun buildSafeMetadata(context: PolicyContext): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // Safe allowlisted fields only — never raw prompts, tool arguments,
        // secrets, arbitrary destinations, or unbounded model-generated strings.
        context.providerId?.let { map["providerName"] = bounded(it) }
        context.modelName?.let { map["modelName"] = bounded(it) }
        context.toolName?.let { map["toolName"] = bounded(it) }
        context.dataClassification?.let { map["classification"] = bounded(it.name) }
        context.classificationSource?.let { map["classificationSource"] = bounded(it.name) }
        context.toolSecurity?.risk?.let { map["riskLevel"] = bounded(it.name) }
        context.fallbackProviderId?.let { map["fallbackProviderName"] = bounded(it) }

        // Explicit attribute allowlist — only known safe keys are exported.
        // Unknown keys (e.g. "prompt", "toolArguments", "secret") are dropped.
        val remainingSlots = MAX_METADATA_ENTRIES - map.size
        if (remainingSlots > 0) {
            context.attributes
                .asSequence()
                .filter { (key, _) -> key in ALLOWED_ATTRIBUTE_KEYS }
                .sortedBy { (key, _) -> key }
                .take(remainingSlots)
                .forEach { (key, value) ->
                    map["attr_${bounded(key, MAX_KEY_LENGTH)}"] =
                        bounded(value, MAX_VALUE_LENGTH)
                }
        }

        return map
    }

    private fun bounded(value: String): String {
        if (value.length <= MAX_VALUE_LENGTH) return value
        return value.take(MAX_VALUE_LENGTH)
    }

    private fun bounded(value: String, maxLen: Int): String {
        if (value.length <= maxLen) return value
        return value.take(maxLen)
    }
}
