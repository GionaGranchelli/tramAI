package dev.tramai.core.identity

/**
 * Stable identity of a governed configuration family.
 *
 * Paired with [ConfigurationVersion] it identifies the exact governed
 * configuration of a workload. The id is opaque: it may encode product/domain
 * meaning (e.g. `customer-support-agent`, `fraud-review-policy`) but must not
 * be parsed by consumers.
 */
@JvmInline
value class ConfigurationId(
    val value: String,
) {
    init {
        validateIdentity("ConfigurationId", value)
    }
}
