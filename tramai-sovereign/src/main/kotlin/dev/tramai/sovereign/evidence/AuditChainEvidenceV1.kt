package dev.tramai.sovereign.evidence

/**
 * Aggregate audit-chain validation result.
 *
 * Captures whether the hash chain of audit events is valid and
 * how many events were in the chain at the time of generation.
 *
 * @property isValid Whether the hash chain verified successfully.
 * @property totalEvents Number of audit events in the chain.
 */
data class AuditChainEvidenceV1(
    val isValid: Boolean,
    val totalEvents: Int,
)
