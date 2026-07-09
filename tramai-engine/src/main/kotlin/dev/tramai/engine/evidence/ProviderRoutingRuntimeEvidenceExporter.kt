package dev.tramai.engine.evidence

import dev.tramai.security.evidence.EvidenceDigest
import dev.tramai.security.evidence.RuntimeEvidenceDecision
import dev.tramai.security.evidence.RuntimeEvidenceDigests
import dev.tramai.security.evidence.RuntimeEvidenceRecord
import dev.tramai.security.evidence.RuntimeEvidenceSource
import java.time.Instant

/**
 * Source record for a provider route decision that can be exported as
 * runtime evidence. This is an explicit, testable model — the exporter
 * does not parse raw observer events.
 */
data class ProviderRouteDecisionEvidenceSource(
    val eventId: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val createdAt: Instant,
    val decisionKind: ProviderRouteDecisionKind,
    val requestedModelName: String,
    val selectedProviderName: String?,
    val selectedModelName: String?,
    val previousProviderName: String? = null,
    val previousModelName: String? = null,
    val fallbackReason: String? = null,
    val routeIndex: Int? = null,
    val attempt: Int? = null,
)

enum class ProviderRouteDecisionKind {
    SELECTED,
    FALLBACK,
    BLOCKED,
}

/**
 * Allowlisted fallback/block reason codes for provider route evidence.
 *
 * Only codes in this set may appear in the exported metadata.
 * Anything else is normalized to the generic `"provider-fallback"`
 * or `"provider-blocked"` constant.
 */
internal val ALLOWED_ROUTING_REASON_CODES = setOf(
    "provider-failure",
    "streaming-startup-failure",
    "circuit-breaker-open",
    "model-registry-blocked",
    "policy-blocked",
    "no-route",
)

/**
 * Converts provider route decision sources into [RuntimeEvidenceRecord]s
 * following the runtime-evidence.v1 schema.
 *
 * Every source (SELECTED, FALLBACK, or BLOCKED) is exported.
 * No filtering is applied — if you need skip logic, filter upstream.
 *
 * ## Privacy boundary
 * Raw provider names and model names are never exported.
 * Only [EvidenceDigest] form is preserved in metadata to avoid
 * leaking deployment topology through evidence records.
 */
class ProviderRoutingRuntimeEvidenceExporter {

    fun export(
        sources: List<ProviderRouteDecisionEvidenceSource>,
    ): List<RuntimeEvidenceRecord> =
        sources.map { it.toRuntimeEvidenceRecord() }
}

private fun ProviderRouteDecisionEvidenceSource.toDecisionKind(): String =
    decisionKind.name

private fun ProviderRouteDecisionEvidenceSource.toReasonCode(): String =
    when (decisionKind) {
        ProviderRouteDecisionKind.SELECTED -> "provider-selected"
        ProviderRouteDecisionKind.FALLBACK -> "provider-fallback"
        ProviderRouteDecisionKind.BLOCKED -> "provider-blocked"
    }

private fun ProviderRouteDecisionEvidenceSource.safeFallbackReason(): String? {
    if (decisionKind == ProviderRouteDecisionKind.SELECTED) return null
    val raw = fallbackReason
    if (raw != null && raw in ALLOWED_ROUTING_REASON_CODES) return raw
    val fallback = when (decisionKind) {
        ProviderRouteDecisionKind.FALLBACK -> "provider-fallback"
        ProviderRouteDecisionKind.BLOCKED -> "provider-blocked"
        ProviderRouteDecisionKind.SELECTED -> return null
    }
    return fallback
}

private fun ProviderRouteDecisionEvidenceSource.toRuntimeEvidenceRecord(): RuntimeEvidenceRecord {
    val safeMetadata = buildSafeMetadata()
    return RuntimeEvidenceRecord(
        eventId = eventId,
        eventType = "provider.route",
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        actor = actor,
        createdAt = createdAt,
        source = RuntimeEvidenceSource(
            component = "provider-router",
            module = "tramai-engine",
        ),
        decision = RuntimeEvidenceDecision(
            kind = toDecisionKind(),
            reasonCode = toReasonCode(),
        ),
        digests = RuntimeEvidenceDigests(
            subjectDigest = computeSubjectDigest(),
            payloadDigest = computePayloadDigest(safeMetadata),
        ),
        metadata = safeMetadata,
    )
}

private fun ProviderRouteDecisionEvidenceSource.buildSafeMetadata(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    map["requestedModelDigest"] = EvidenceDigest.sha256(requestedModelName)
    selectedProviderName?.let { map["selectedProviderDigest"] = EvidenceDigest.sha256(it) }
    selectedModelName?.let { map["selectedModelDigest"] = EvidenceDigest.sha256(it) }
    previousProviderName?.let { map["previousProviderDigest"] = EvidenceDigest.sha256(it) }
    previousModelName?.let { map["previousModelDigest"] = EvidenceDigest.sha256(it) }
    routeIndex?.let { map["routeIndex"] = it.toString() }
    attempt?.let { map["attempt"] = it.toString() }
    safeFallbackReason()?.let { map["fallbackReason"] = it }
    return map
}

/**
 * Subject digest: canonical JSON over requested model + optional
 * selected provider/model. Uses JSON-style field encoding so that
 * pipe characters in provider/model names cannot create collisions.
 */
private fun ProviderRouteDecisionEvidenceSource.computeSubjectDigest(): String {
    val canonical = buildString {
        append('{')
        appendJsonField("requestedModelName", requestedModelName)
        appendJsonNullableField("selectedProviderName", selectedProviderName)
        appendJsonNullableField("selectedModelName", selectedModelName)
        removeTrailingComma()
        append('}')
    }
    return EvidenceDigest.sha256(canonical)
}

private fun ProviderRouteDecisionEvidenceSource.computePayloadDigest(
    safeMetadata: Map<String, String>,
): String {
    val canonical = buildString {
        append('{')
        appendJsonField("eventType", "provider.route")
        appendJsonField("decision", toDecisionKind())
        appendJsonNullableField("reasonCode", toReasonCode())
        appendJsonField("sourceComponent", "provider-router")
        appendJsonField("sourceModule", "tramai-engine")
        appendJsonMetadataField("metadata", safeMetadata)
        removeTrailingComma()
        append('}')
    }
    return EvidenceDigest.sha256(canonical)
}

// Minimal canonical JSON helper for payload digest

private fun StringBuilder.appendJsonField(name: String, value: String) {
    appendComma().appendJsonString(name).append(':').appendJsonString(value)
}

private fun StringBuilder.appendJsonNullableField(name: String, value: String?) {
    appendComma().appendJsonString(name).append(':')
    if (value != null) appendJsonString(value) else append("null")
}

private fun StringBuilder.appendJsonMetadataField(name: String, value: Map<String, String>) {
    appendComma().appendJsonString(name).append(':').append('{')
    var first = true
    for ((k, v) in value.toSortedMap()) {
        if (!first) append(',')
        appendJsonString(k).append(':').appendJsonString(v)
        first = false
    }
    append('}')
}

private fun StringBuilder.appendComma(): StringBuilder {
    if (length > 1 && last() != '{') append(',')
    return this
}

private fun StringBuilder.removeTrailingComma() {
    if (isNotEmpty() && last() == ',') setLength(length - 1)
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                when {
                    c.code in 0xD800..0xDFFF || c < ' ' -> {
                        append("\\u")
                        append(c.code.toString(16).padStart(4, '0'))
                    }
                    else -> append(c)
                }
            }
        }
    }
    append('"')
    return this
}
