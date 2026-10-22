package dev.tramai.security.reconstruction

import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.security.timeline.AttemptHistoryState
import dev.tramai.security.timeline.CorrelationEvidence
import dev.tramai.security.timeline.GovernanceSection
import dev.tramai.security.timeline.SemanticTimeline
import dev.tramai.security.timeline.TimelineRequest

/**
 * Historical reconstruction *contract* for one governed run (Epic 0.7.5d).
 *
 * This is vocabulary, not machinery: there is no reconstruction engine here, no source is read, and
 * nothing is inferred. A caller reads the stored evidence through the existing 0.7.5c
 * [SemanticTimeline] and wraps it here to get a typed answer to four questions:
 *
 * - what is **known** ([ReconstructionResult.status] and the carried evidence),
 * - what is **missing** ([ReconstructionResult.limitations]),
 * - what **can be reconstructed** from the evidence at hand, and
 * - what **must never be inferred** from it ([ReconstructionLimitation]).
 *
 * Everything it exposes is either reused from an existing contract ([SemanticTimeline],
 * [GovernanceSection], [AttemptHistoryState], [CorrelationEvidence]) or is new classification
 * vocabulary. It duplicates no timeline, governance or attempt model, adds no persistence, and
 * performs no provider, tool, approval or network invocation.
 *
 * **On content:** there is no prompt, response, tool-payload or reasoning *field* anywhere in this
 * surface, so no redaction step can be forgotten for a dedicated payload. That is a structural
 * property of the field set and **not** a guarantee about the string values it does carry:
 * [HistoricalConfigurationReference.Recorded.policyVersion], `workflowDigest` and the identifiers
 * reused from other contracts are opaque values whose safety depends on what wrote them. This
 * contract neither redacts nor verifies them — producer-side handling (0.7.4 audit emission, 0.7.5b
 * observation) remains the boundary that keeps secrets out.
 *
 * Historical identity is *cited*, never substituted: [HistoricalConfigurationReference.NotRecorded]
 * means the run recorded no configuration identity, and no current configuration may be filled in
 * its place.
 */
public data class ReconstructionRequest(
    /** Which run, and which governance scope, the reconstruction is about. */
    public val timeline: TimelineRequest,
    /**
     * The historical configuration/policy identity the reconstruction may cite, or
     * [HistoricalConfigurationReference.NotRecorded]. Required, so a caller cannot silently omit it
     * and let something else stand in for it.
     */
    public val historicalConfiguration: HistoricalConfigurationReference,
)

/**
 * Historical configuration/policy identity of the reconstructed run.
 *
 * A reconstruction may cite what the run recorded. It may never read the configuration in force now
 * and present it as the configuration that ran: those are different facts, and a contract that can
 * confuse them is worse than no contract.
 *
 * **Trust boundary:** the values in [Recorded] are caller-supplied. This type validates their shape
 * (present, non-blank, at least one reference) and validates *nothing* about their provenance — it
 * cannot tell a reference read from the run's own persisted evidence from one taken out of the
 * current configuration. Obtaining the reference from persisted historical evidence, and never from
 * current configuration, is the obligation of the reader that builds a [ReconstructionRequest]
 * (0.7.5e). Reading a `Recorded` value as proof of provenance would be exactly the substitution this
 * type exists to prevent.
 */
public sealed interface HistoricalConfigurationReference {
    /**
     * References the caller states the run recorded. At least one must be present — an empty
     * [Recorded] would be indistinguishable from [NotRecorded] while looking like evidence. The
     * values are taken on trust: shape is validated, provenance is not (see the type's trust
     * boundary).
     */
    public data class Recorded
        @JvmOverloads
        constructor(
            /** The governed configuration family and pinned revision, when the run recorded one. */
            public val configuration: WorkloadConfigurationIdentity? = null,
            /** The policy revision the run recorded, when it recorded one. */
            public val policyVersion: String? = null,
            /** The workflow definition digest the run recorded, when it recorded one. */
            public val workflowDigest: String? = null,
        ) : HistoricalConfigurationReference {
            init {
                require(policyVersion == null || policyVersion.isNotBlank()) {
                    "reconstruction-policy-version-blank"
                }
                require(workflowDigest == null || workflowDigest.isNotBlank()) {
                    "reconstruction-workflow-digest-blank"
                }
                require(configuration != null || policyVersion != null || workflowDigest != null) {
                    "reconstruction-empty-recorded-configuration"
                }
            }
        }

    /** The run recorded no configuration identity. Unknown, never "the current configuration". */
    public data object NotRecorded : HistoricalConfigurationReference
}

/**
 * Whether the available historical evidence supports reconstructing the declared scope.
 *
 * The three values describe **evidence coverage, not truth**: they say whether the sources answered
 * for the declared scope, never what happened.
 *
 * [SUPPORTED] therefore does **not** certify historical authenticity, exhaustive event coverage,
 * successful execution or complete metrics — a result can be [SUPPORTED] and still carry limitations
 * (an unauthenticated configuration reference, an execution history that observed no attempt). Read
 * [ReconstructionResult.limitations] together with the status; the status alone is not a clean bill.
 */
public enum class ReconstructionStatus {
    /** The required sources answered sufficiently for the declared scope. Not a claim of truth. */
    SUPPORTED,

    /**
     * Some evidence is available, but one or more required elements or source conditions are
     * unresolved. What is present is still reported, with the gap named.
     */
    PARTIAL,

    /** The available evidence cannot establish the requested reconstruction at all. */
    UNSUPPORTED,
}

/**
 * What this reconstruction must not claim, derived from the evidence it carries.
 *
 * Each value names a gap or a false inference that a reader could otherwise make. Absence of a
 * limitation means only that the specific doubt does not apply — never that the evidence is complete
 * in some broader sense.
 */
public enum class ReconstructionLimitation {
    /** Governance was not consulted, is not configured, or has no materialized projection. */
    GOVERNANCE_NOT_OBSERVED,

    /** A governance projection exists but is unsupported or violates its contract and is refused. */
    GOVERNANCE_SOURCE_REFUSED,

    /** No attempt observation persistence is configured, so attempts cannot be established either way. */
    ATTEMPTS_UNINSTRUMENTED,

    /** The run recorded no configuration identity, and no current configuration substitutes for it. */
    HISTORICAL_CONFIGURATION_NOT_RECORDED,

    /**
     * A configuration reference was supplied, but nothing in this contract authenticated its origin.
     *
     * [HistoricalConfigurationReference.Recorded] validates shape only. A correctly shaped reference
     * can have been read from the run's persisted evidence or taken from the current configuration,
     * and the two are indistinguishable here — so a [ReconstructionStatus.SUPPORTED] result says the
     * sources answered, not that this identity is authentic.
     */
    HISTORICAL_CONFIGURATION_UNVERIFIED,

    /**
     * No attempt was observed for the run, so execution history is unknown.
     *
     * Raised whenever the attempt store recorded nothing — whether it reported no attempts
     * ([AttemptHistoryState.EMPTY]) or was never instrumented
     * ([AttemptHistoryState.UNINSTRUMENTED]). Absence of an observation is not proof that nothing ran,
     * and a recorded decision beside it — including an `ALLOW` — is permission, never proof of
     * execution.
     */
    EXECUTION_NOT_ESTABLISHED,

    /** Attempts were observed but none reported token counts, so usage stays unknown, not zero. */
    USAGE_UNKNOWN,

    /** Both sources answered, but nothing in the stored evidence links them beyond the run identity. */
    CORRELATION_UNRESOLVED,
}

/**
 * What the available historical evidence supports for one run.
 *
 * The result is a pair of findings, not a narrative: [governance] and [attempts] are reported
 * independently so one can never stand in for the other, and [status] summarizes how much of the
 * scope the evidence covers. It carries the 0.7.5c timeline unchanged, so authoritative governance
 * facts keep their authority and observed attempts keep
 * [dev.tramai.core.observation.attempt.AttemptProvenance.OBSERVED_IN_PROCESS].
 *
 * [status] and [limitations] are derived from the carried evidence, never stored: a result cannot
 * say `SUPPORTED` while carrying an uninstrumented history, because there is no field to disagree.
 */
public data class ReconstructionResult(
    /** The question this result answers, including the historical identity it was allowed to cite. */
    public val request: ReconstructionRequest,
    /** The stored evidence for that run, exactly as the 0.7.5c read API returned it. */
    public val timeline: SemanticTimeline,
) {
    init {
        require(timeline.workflowRunId == request.timeline.workflowRunId) {
            "reconstruction-evidence-run-mismatch: ${timeline.workflowRunId}"
        }
    }

    /** The governance evidence behind this result, with its own completeness classification. */
    public val governance: GovernanceSection get() = timeline.governance

    /** What the attempt store could establish, independent of the governance section. */
    public val attempts: AttemptHistoryState get() = timeline.history

    /** The historical identity this reconstruction may cite. */
    public val historicalConfiguration: HistoricalConfigurationReference
        get() = request.historicalConfiguration

    /** How much of the declared scope the available evidence supports. */
    public val status: ReconstructionStatus
        get() = statusOf(timeline, request.historicalConfiguration)

    /** What this result must not be read as claiming. Empty is not a claim of completeness. */
    public val limitations: Set<ReconstructionLimitation>
        get() = limitationsOf(timeline, request.historicalConfiguration)

    public companion object {
        /** Wraps already-read evidence. Performs no read, no inference and no side effect. */
        public fun of(
            request: ReconstructionRequest,
            timeline: SemanticTimeline,
        ): ReconstructionResult = ReconstructionResult(request, timeline)

        /**
         * The status the evidence supports.
         *
         * Governance and attempt completeness are judged independently: a refused projection with a
         * fully recorded attempt history is [PARTIAL], never [UNSUPPORTED], and an uninstrumented
         * attempt history beside a verified snapshot is [PARTIAL], never [SUPPORTED].
         */
        public fun statusOf(
            timeline: SemanticTimeline,
            historicalConfiguration: HistoricalConfigurationReference,
        ): ReconstructionStatus {
            val governanceAnswered = timeline.governance is GovernanceSection.Snapshot
            val attemptsAnswered = timeline.history != AttemptHistoryState.UNINSTRUMENTED
            val configurationRecorded =
                historicalConfiguration is HistoricalConfigurationReference.Recorded
            return when {
                !governanceAnswered && !attemptsAnswered -> {
                    ReconstructionStatus.UNSUPPORTED
                }

                governanceAnswered && attemptsAnswered && configurationRecorded -> {
                    ReconstructionStatus.SUPPORTED
                }

                else -> {
                    ReconstructionStatus.PARTIAL
                }
            }
        }

        /** The doubts that apply to this evidence. Each entry is derived, never supplied. */
        public fun limitationsOf(
            timeline: SemanticTimeline,
            historicalConfiguration: HistoricalConfigurationReference,
        ): Set<ReconstructionLimitation> =
            buildSet {
                addAll(governanceLimitations(timeline.governance))
                if (timeline.history == AttemptHistoryState.UNINSTRUMENTED) {
                    add(ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED)
                }
                if (historicalConfiguration is HistoricalConfigurationReference.Recorded) {
                    add(ReconstructionLimitation.HISTORICAL_CONFIGURATION_UNVERIFIED)
                } else {
                    add(ReconstructionLimitation.HISTORICAL_CONFIGURATION_NOT_RECORDED)
                }
                if (timeline.history != AttemptHistoryState.RECORDED) {
                    // No observation, so execution is unknown - whatever governance did or did not say.
                    add(ReconstructionLimitation.EXECUTION_NOT_ESTABLISHED)
                }
                val recorded = timeline.history == AttemptHistoryState.RECORDED
                if (recorded && timeline.usage == null) {
                    add(ReconstructionLimitation.USAGE_UNKNOWN)
                }
                if (recorded && timeline.correlation == CorrelationEvidence.NONE) {
                    add(ReconstructionLimitation.CORRELATION_UNRESOLVED)
                }
            }

        private fun governanceLimitations(governance: GovernanceSection): Set<ReconstructionLimitation> =
            when (governance) {
                is GovernanceSection.Snapshot -> emptySet()

                is GovernanceSection.UnsupportedFormat,
                is GovernanceSection.InvalidStoredProjection,
                -> setOf(ReconstructionLimitation.GOVERNANCE_SOURCE_REFUSED)

                GovernanceSection.NotQueried,
                GovernanceSection.NotConfigured,
                GovernanceSection.NotMaterialized,
                -> setOf(ReconstructionLimitation.GOVERNANCE_NOT_OBSERVED)
            }
    }
}
