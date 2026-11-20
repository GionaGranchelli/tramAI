package dev.tramai.engine.provider

import dev.tramai.core.identity.GovernedRunIdentity

/**
 * Authoritative outcome of a governed pre-transport provider-input release check.
 */
internal enum class ProviderInputReleaseOutcome {
    RAW_PERMITTED,
    MINIMIZED,
    DENIED,
}

/**
 * Provenance of a provider-input release evidence record.
 */
internal enum class ProviderInputReleaseProvenance {
    TRAMAI_ENFORCED,
}

/**
 * Minimal, content-free evidence record emitted by [ProviderInputTransportGate]
 * for each governed provider-input release evaluation.
 */
internal data class ProviderInputReleaseEvidence(
    val workloadId: String?,
    val runId: String?,
    val selectedDeploymentId: String,
    val outcome: ProviderInputReleaseOutcome,
    val reasonCode: String? = null,
    val redactionCount: Int? = null,
    val attempt: Int? = null,
    val provenance: ProviderInputReleaseProvenance = ProviderInputReleaseProvenance.TRAMAI_ENFORCED,
)

/**
 * Execution correlation for a provider-input release evaluation.
 */
internal data class ProviderInputReleaseCorrelation(
    val run: GovernedRunIdentity? = null,
    val attempt: Int? = null,
)

/**
 * Internal sink for recording [ProviderInputReleaseEvidence] at the transport gate.
 */
internal fun interface ProviderInputReleaseEvidenceRecorder {
    fun record(evidence: ProviderInputReleaseEvidence)
}
