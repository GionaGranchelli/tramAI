package dev.tramai.security.audit

import dev.tramai.core.policy.*

class AuditEnginePolicyDecisionAuditEmitter(
    private val auditEngine: AuditEngine,
    private val streamIdResolver: AuditStreamIdResolver = DefaultAuditStreamIdResolver,
) : PolicyDecisionAuditEmitter {

    companion object {
        private const val MAX_METADATA_ENTRIES = 16
        private const val MAX_KEY_LENGTH = 64
        private const val MAX_VALUE_LENGTH = 256
    }

    override suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    ) {
        val streamId = streamIdResolver.resolve(context)
        val (decisionStr, reasonCode) = when (decision) {
            is PolicyDecision.Allow -> Pair("ALLOW", "policy_allowed")
            is PolicyDecision.Deny -> Pair("DENY", decision.reasonCode)
            is PolicyDecision.RequireApproval -> Pair("REQUIRE_APPROVAL", "policy_requires_approval")
        }

        val metadata = buildSafeMetadata(context)

        auditEngine.emit(
            auditStreamId = streamId,
            workflowRunId = context.workflowRunId,
            correlationId = context.correlationId,
            actor = context.actorId,
            enforcementPoint = enforcementPoint.name,
            decision = decisionStr,
            policyVersion = context.policyVersion,
            workflowDigest = context.workflowDigest,
            reasonCode = reasonCode,
            metadata = metadata,
        )
    }

    private fun buildSafeMetadata(context: PolicyContext): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // Safe allowlisted fields only
        context.providerId?.let { map["providerName"] = bounded(it) }
        context.modelName?.let { map["modelName"] = bounded(it) }
        context.toolName?.let { map["toolName"] = bounded(it) }
        context.dataClassification?.let { map["classification"] = bounded(it.name) }
        context.classificationSource?.let { map["classificationSource"] = bounded(it.name) }
        context.toolSecurity?.risk?.let { map["riskLevel"] = bounded(it.name) }
        context.fallbackProviderId?.let { map["fallbackProviderName"] = bounded(it) }
        context.targetDestination?.let { map["targetDestination"] = bounded(it) }
        context.attributes.entries.take(MAX_METADATA_ENTRIES - map.size).forEach { (k, v) ->
            map["attr_${bounded(k)}"] = bounded(v)
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
