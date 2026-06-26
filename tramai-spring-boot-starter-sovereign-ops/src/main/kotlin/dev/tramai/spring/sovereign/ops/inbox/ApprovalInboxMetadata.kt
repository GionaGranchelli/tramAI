package dev.tramai.spring.sovereign.ops.inbox

import dev.tramai.core.approval.gateway.ApproverRole

/**
 * Safe, explicit, persisted inbox metadata for approval review work items.
 *
 * This is the only path for reviewer-facing metadata to appear in the inbox.
 * It is intentionally narrow — no arbitrary JSON blobs, no raw tool arguments,
 * no replay envelopes, no sensitive payloads.
 *
 * When serialised into `sanitized_metadata->'inbox'`, these fields are readable
 * by the JDBC inbox query service for filtering and display without exposing
 * any data beyond what the [ApprovalInboxMetadataFactory] explicitly provides.
 */
data class ApprovalInboxMetadata(
    val requiredRole: ApproverRole? = null,
    val riskLevel: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val recommendationType: String? = null,
)
