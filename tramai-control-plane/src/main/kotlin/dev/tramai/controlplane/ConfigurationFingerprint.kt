package dev.tramai.controlplane

/**
 * Opaque witness over the complete governed configuration of a workload
 * deployment.
 *
 * The registration authority cannot parse a governed configuration (policy,
 * routing, provider deployment, tool governance and workflow composition all
 * contribute later); it only needs a deterministic fingerprint to enforce the
 * 0.7.1b configuration invariant:
 *
 * ```text
 * (configurationId, version) -> exactly one fingerprint, ever
 * ```
 *
 * `C/17/AAA` may be re-registered; `C/17/BBB` must be rejected as
 * configuration rebinding. Fingerprint values are opaque, non-blank, bounded,
 * case-preserving and never silently normalized — mirroring the identity
 * validation rules. How the complete governed configuration is canonicalized
 * into this fingerprint is deliberately unspecified here.
 */
data class ConfigurationFingerprint(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConfigurationFingerprint must not be blank" }
        require(value == value.trim()) {
            "ConfigurationFingerprint must not contain leading or trailing whitespace"
        }
        require(value.length <= FINGERPRINT_MAX_LENGTH) {
            "ConfigurationFingerprint must not exceed $FINGERPRINT_MAX_LENGTH characters"
        }
        require(value.none(Char::isISOControl)) {
            "ConfigurationFingerprint must not contain control characters"
        }
    }

    override fun toString(): String = value

    companion object {
        const val FINGERPRINT_MAX_LENGTH: Int = 128
    }
}
