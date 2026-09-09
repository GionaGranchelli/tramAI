package dev.tramai.core.identity

/**
 * Version of one immutable governed-configuration revision.
 *
 * The version is opaque and must not be assumed to be SemVer. Legitimate
 * values include datestamped revisions (`2026-09-09.3`), content digests
 * (`sha256:abcd...`) and monotonic counters. Together with its
 * [ConfigurationId] it names one exact governed configuration.
 */
@JvmInline
value class ConfigurationVersion(
    val value: String,
) {
    init {
        validateIdentity("ConfigurationVersion", value)
    }
}
