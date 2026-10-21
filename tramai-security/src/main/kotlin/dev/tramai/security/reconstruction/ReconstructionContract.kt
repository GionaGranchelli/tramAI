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
 * vocabulary. It duplicates no timeline, governance or attempt model, adds no persistence, performs
 * no provider, tool, approval or network invocation, and carries no content: prompts, responses,
 * tool payloads and reasoning are not expressible in these types.
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
 */
public sealed interface HistoricalConfigurationReference {
    /**
     * References the run recorded. At least one must be present — an empty [Recorded] would be
     * indistinguishable from [NotRecorded] while looking like evidence.
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
 * The three values describe evidence, not success: [SUPPORTED] means both evidence sources answered
 * *and* the historical configuration identity is known, so the reconstruction can state what happened
 * without filling a gap. It is not a claim that the run succeeded, or that a decision caused anything.
 */
public enum class ReconstructionStatus {
    /** Historical evidence is sufficient for the declared scope. */
    SUPPORTED,

    /** Some historical evidence is missing; what is present is still reported, with the gap named. */
    PARTIAL,

    /** Reconstruction cannot be established from the available sources at all. */
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
     * Governance recorded decisions, but no attempt was observed. A recorded decision — including an
     * `ALLOW` — is permission, never proof that anything executed.
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
        ): Set<ReconstructionLimitation> {
            val snapshot = timeline.governance as? GovernanceSection.Snapshot
            return buildSet {
                addAll(governanceLimitations(timeline.governance))
                if (timeline.history == AttemptHistoryState.UNINSTRUMENTED) {
                    add(ReconstructionLimitation.ATTEMPTS_UNINSTRUMENTED)
                }
                if (historicalConfiguration !is HistoricalConfigurationReference.Recorded) {
                    add(ReconstructionLimitation.HISTORICAL_CONFIGURATION_NOT_RECORDED)
                }
                if (snapshot != null && claimsExecutionWithoutObservation(timeline)) {
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

        /**
         * Whether the evidence holds decisions that a reader could mistake for evidence of execution.
         *
         * The condition is deliberately broader than an `ALLOW` token: this contract does not parse
         * decision tokens at all, so *no* recorded decision is treated as execution evidence when no
         * attempt was observed in a recording-capable history.
         */
        private fun claimsExecutionWithoutObservation(timeline: SemanticTimeline): Boolean {
            val decisions = (timeline.governance as? GovernanceSection.Snapshot)?.view?.decisions
            return !decisions.isNullOrEmpty() &&
                timeline.history != AttemptHistoryState.RECORDED
        }
    }
}
