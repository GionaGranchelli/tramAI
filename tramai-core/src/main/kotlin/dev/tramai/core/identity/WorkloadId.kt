package dev.tramai.core.identity

/**
 * Stable identity of one independently governed workload.
 *
 * A workload is the governed application/business capability that owns
 * workflow executions (e.g. `payments-fraud-review`, `com.acme.claims-assistant`).
 * The identifier is stable and case-preserving; it is not required to be a
 * random UUID. Generation policy belongs to the registration authority
 * (Epic 0.7.1 candidate 0.7.1c), not to this type.
 *
 * Plain JVM class, not an inline class: the identity vocabulary is part of the
 * canonical public tramai-core contract, so every type in this package is
 * constructible and readable from Java (`new WorkloadId("claims")`,
 * `getValue()`). Do not convert these boundary types to `@JvmInline` value
 * classes — that would make the canonical contract Kotlin-only (or dependent
 * on experimental boxed exposure).
 */
data class WorkloadId(
    val value: String,
) {
    init {
        validateIdentity("WorkloadId", value)
    }

    override fun toString(): String = value
}
