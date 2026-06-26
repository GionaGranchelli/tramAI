package dev.tramai.spring.sovereign.ops.inbox

/**
 * Validation policy for [ApprovalInboxMetadata] fields.
 *
 * Keeps the inbox metadata boundary safe by rejecting oversized, blank,
 * or control-character-containing values before they reach the database.
 */
object ApprovalInboxMetadataPolicy {

    private const val MAX_FIELD_LENGTH = 128

    /**
     * Validates all string fields in [metadata].
     *
     * @throws IllegalArgumentException if any field violates the policy
     */
    fun validate(metadata: ApprovalInboxMetadata) {
        validateSafeField(metadata.requiredRole?.value, "requiredRole")
        validateSafeField(metadata.riskLevel, "riskLevel")
        validateSafeField(metadata.subjectType, "subjectType")
        validateSafeField(metadata.subjectId, "subjectId")
        validateSafeField(metadata.recommendationType, "recommendationType")
    }

    private fun validateSafeField(value: String?, fieldName: String) {
        if (value == null) return
        require(value.isNotBlank()) { "approval-inbox-metadata-$fieldName-blank" }
        require(value.length <= MAX_FIELD_LENGTH) {
            "approval-inbox-metadata-$fieldName-too-long: ${value.length} > $MAX_FIELD_LENGTH"
        }
        require(value.none { it.isISOControl() }) {
            "approval-inbox-metadata-$fieldName-control-characters"
        }
        require(!value.contains("\\N{") && !value.contains("<<") && !value.contains(">>")) {
            "approval-inbox-metadata-$fieldName-suspicious-pattern"
        }
    }
}
