package dev.tramai.security.evidence

/**
 * Validates the complete [RuntimeEvidenceRecord] contract so the writer
 * can never produce a section that the verifier would reject.
 *
 * ## What is validated
 *
 * | Rule | Scope |
 * |------|-------|
 * | schemaVersion | All records |
 * | eventType in known set | All records |
 * | decision.kind in allowed set | All records |
 * | eventId is not blank | All records |
 * | eventId globally unique | All records (across families) |
 * | digests match ^sha256:[0-9a-f]{64}$ | All records |
 * | source.component matches expected value | All records |
 * | metadata keys allowlisted per family | All records |
 * | metadata digest values match digest pattern | Approval + routing families |
 * | reasonCode matches code format | All records |
 * | numeric metadata values are valid | Routing family (routeIndex, attempt) |
 */
internal object RuntimeEvidenceContractValidator {
    private val APPROVAL_REASON_CODES = setOf("approval-approved", "approval-denied")
    private val ROUTING_REASON_CODES = setOf(
        "provider-selected",
        "provider-fallback",
        "provider-blocked",
    )
    private val ALLOWED_FALLBACK_REASONS = setOf(
        "provider-failure", "streaming-startup-failure", "circuit-breaker-open",
        "model-registry-blocked", "policy-blocked", "no-route",
    )

    fun validate(records: List<RuntimeEvidenceRecord>) {
        val seenEventIds = mutableSetOf<String>()

        for (record in records) {
            validateSchemaVersion(record)
            validateEventType(record)
            validateEventId(record, seenEventIds)
            validateDecisionKind(record)
            validateSourceComponent(record)
            validateDigests(record)
            validateMetadata(record)
            validateReasonCode(record)
        }
    }

    private fun validateSchemaVersion(record: RuntimeEvidenceRecord) {
        require(record.schemaVersion == RuntimeEvidenceBundleWriter.SCHEMA_VERSION) {
            "Unsupported schemaVersion: ${record.schemaVersion}. " +
                "Expected: ${RuntimeEvidenceBundleWriter.SCHEMA_VERSION}"
        }
    }

    private fun validateEventType(record: RuntimeEvidenceRecord) {
        require(record.eventType in RuntimeEvidenceBundleWriter.EVENT_FILES) {
            "Unknown event type: ${record.eventType}. " +
                "Supported: ${RuntimeEvidenceBundleWriter.EVENT_FILES.keys}"
        }
    }

    private fun validateEventId(record: RuntimeEvidenceRecord, seenEventIds: MutableSet<String>) {
        require(record.eventId.isNotBlank()) {
            "eventId must not be blank"
        }
        require(record.eventId !in seenEventIds) {
            "Duplicate runtime evidence eventId: ${record.eventId}"
        }
        seenEventIds.add(record.eventId)
    }

    private fun validateDecisionKind(record: RuntimeEvidenceRecord) {
        val allowedKinds = requireNotNull(
            RuntimeEvidenceBundleWriter.ALLOWED_DECISION_KINDS[record.eventType]
        ) {
            "No allowed decision kinds defined for event type: ${record.eventType}"
        }
        require(record.decision.kind in allowedKinds) {
            "Invalid decision.kind '${record.decision.kind}' for event type " +
                "'${record.eventType}'. Allowed: $allowedKinds"
        }
    }

    private fun validateSourceComponent(record: RuntimeEvidenceRecord) {
        val expectedComponent = requireNotNull(
            RuntimeEvidenceBundleWriter.EXPECTED_SOURCE_COMPONENTS[record.eventType]
        ) {
            "No expected source component defined for event type: ${record.eventType}"
        }
        require(record.source.component == expectedComponent) {
            "source.component must be '$expectedComponent' " +
                "for event type '${record.eventType}', " +
                "got '${record.source.component}'"
        }
    }

    private fun validateDigests(record: RuntimeEvidenceRecord) {
        val regex = RuntimeEvidenceBundleWriter.DIGEST_REGEX
        require(regex.matches(record.digests.subjectDigest)) {
            "subjectDigest must match ^sha256:[0-9a-f]{64}$: ${record.digests.subjectDigest}"
        }
        require(regex.matches(record.digests.payloadDigest)) {
            "payloadDigest must match ^sha256:[0-9a-f]{64}$: ${record.digests.payloadDigest}"
        }
    }

    private fun validateMetadata(record: RuntimeEvidenceRecord) {
        val allowedKeys = requireNotNull(
            RuntimeEvidenceBundleWriter.ALLOWED_METADATA_KEYS[record.eventType]
        ) {
            "No allowed metadata keys defined for event type: ${record.eventType}"
        }

        for (key in record.metadata.keys) {
            require(key in allowedKeys) {
                "Metadata key '$key' is not allowlisted " +
                    "for event type '${record.eventType}'. " +
                    "Allowed: $allowedKeys"
            }
        }

        // Family-specific metadata validation
        when (record.eventType) {
            "approval.decision" -> {
                val reasonDigest = record.metadata["reasonDigest"]
                if (reasonDigest != null) {
                    require(RuntimeEvidenceBundleWriter.DIGEST_REGEX.matches(reasonDigest)) {
                        "Metadata reasonDigest must match ^sha256:[0-9a-f]{64}$: $reasonDigest"
                    }
                }
                val eventKeyDigest = record.metadata["eventKeyDigest"]
                if (eventKeyDigest != null) {
                    require(RuntimeEvidenceBundleWriter.DIGEST_REGEX.matches(eventKeyDigest)) {
                        "Metadata eventKeyDigest must match ^sha256:[0-9a-f]{64}$: $eventKeyDigest"
                    }
                }
                val reasonLength = record.metadata["reasonLength"]
                if (reasonLength != null) {
                    val parsed = reasonLength.toIntOrNull()
                    require(parsed != null && parsed >= 0) {
                        "Metadata reasonLength must be a non-negative integer: $reasonLength"
                    }
                }
                val approvalVersion = record.metadata["approvalVersion"]
                if (approvalVersion != null) {
                    val parsed = approvalVersion.toIntOrNull()
                    require(parsed != null && parsed >= 0) {
                        "Metadata approvalVersion must be a non-negative integer: $approvalVersion"
                    }
                }
            }
            "provider.route" -> {
                for (digestKey in listOf(
                    "requestedModelDigest", "selectedProviderDigest", "selectedModelDigest",
                    "previousProviderDigest", "previousModelDigest",
                )) {
                    val value = record.metadata[digestKey]
                    if (value != null) {
                        require(RuntimeEvidenceBundleWriter.DIGEST_REGEX.matches(value)) {
                            "Metadata $digestKey must match ^sha256:[0-9a-f]{64}$: $value"
                        }
                    }
                }
                val routeIndex = record.metadata["routeIndex"]
                if (routeIndex != null) {
                    val parsed = routeIndex.toIntOrNull()
                    require(parsed != null && parsed >= 0) {
                        "Metadata routeIndex must be a non-negative integer: $routeIndex"
                    }
                }
                val attempt = record.metadata["attempt"]
                if (attempt != null) {
                    val parsed = attempt.toIntOrNull()
                    require(parsed != null && parsed >= 0) {
                        "Metadata attempt must be a non-negative integer: $attempt"
                    }
                }
                val fallbackReason = record.metadata["fallbackReason"]
                if (fallbackReason != null) {
                    require(fallbackReason in ALLOWED_FALLBACK_REASONS) {
                        "Metadata fallbackReason must be one of $ALLOWED_FALLBACK_REASONS: $fallbackReason"
                    }
                }
            }
        }
    }

    private fun validateReasonCode(record: RuntimeEvidenceRecord) {
        val reasonCode = record.decision.reasonCode
        if (reasonCode == null) return

        when (record.eventType) {
            "approval.decision" -> {
                require(reasonCode in APPROVAL_REASON_CODES) {
                    "decision.reasonCode must be one of $APPROVAL_REASON_CODES " +
                        "for approval.decision: $reasonCode"
                }
            }
            "provider.route" -> {
                require(reasonCode in ROUTING_REASON_CODES) {
                    "decision.reasonCode must be one of $ROUTING_REASON_CODES " +
                        "for provider.route: $reasonCode"
                }
            }
            "policy.decision" -> {
                require(RuntimeEvidenceBundleWriter.REASON_CODE_REGEX.matches(reasonCode)) {
                    "decision.reasonCode must match ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$: " +
                        reasonCode
                }
            }
        }
    }
}
