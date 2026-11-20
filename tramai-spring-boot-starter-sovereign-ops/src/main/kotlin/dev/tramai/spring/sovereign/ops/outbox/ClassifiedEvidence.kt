package dev.tramai.spring.sovereign.ops.outbox

/**
 * 0.7.6f §6: a record's evidence kind, and whether that kind was derived rather than persisted.
 *
 * [legacy] is true when the record predates the `evidenceKind` field. It is reported so the emitted event
 * can carry `legacyEvidence = true`: a reconstructed kind is still evidence, but it is not the same claim as
 * a kind written by the decision plane.
 */
internal data class ClassifiedEvidence(
    val kind: SovereignOpsAuditEvidenceKind,
    val legacy: Boolean,
)

/**
 * Resolves what a record is evidence of, from its persisted fields only.
 *
 * A persisted kind is authoritative. Without one the record is legacy, and its kind comes from the
 * `approvalStatus` the decoder read out of the payload -- the one fact a pre-0.7.6f record actually
 * recorded. Missing, inconsistent or unrecognized state fails closed: defaulting to
 * [SovereignOpsAuditEvidenceKind.APPROVAL_DENIED] is exactly the false attribution 0.7.6f exists to remove.
 */
internal fun classifyEvidence(record: SovereignOpsAuditOutboxRecord): ClassifiedEvidence {
    record.evidenceKind?.let { return ClassifiedEvidence(it, legacy = false) }
    return when (record.approvalStatus?.uppercase()) {
        LEGACY_APPROVED -> ClassifiedEvidence(SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED, legacy = true)
        LEGACY_DENIED -> ClassifiedEvidence(SovereignOpsAuditEvidenceKind.APPROVAL_DENIED, legacy = true)
        else -> throw EvidenceFailure.UndecodableLegacy()
    }
}

private const val LEGACY_APPROVED = "APPROVED"
private const val LEGACY_DENIED = "DENIED"
