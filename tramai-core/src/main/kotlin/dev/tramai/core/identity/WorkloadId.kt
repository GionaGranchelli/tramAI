package dev.tramai.core.identity

/**
 * Stable identity of one independently governed workload.
 *
 * A workload is the governed application/business capability that owns
 * workflow executions (e.g. `payments-fraud-review`, `com.acme.claims-assistant`).
 * The identifier is stable and case-preserving; it is not required to be a
 * random UUID. Generation policy belongs to the registration authority
 * (Epic 0.7.1 candidate 0.7.1c), not to this type.
 */
@JvmInline
value class WorkloadId(
    val value: String,
) {
    init {
        validateIdentity("WorkloadId", value)
    }
}
