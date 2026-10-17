package dev.tramai.observability.governance

import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceDecisionFact
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.SourceCurrency

/**
 * What a governance query read, reduced to the information that may leave the governance boundary.
 *
 * This type is the exposure policy, not a convenience view. It is built by reading named properties of a
 * [GovernanceQueryResult] and nothing else: no `toString()`, no reflection, no object serialization and no
 * `Map<String, Any?>` forwarding, so a field that is not named here cannot reach telemetry by accident.
 *
 * ## One construction path
 *
 * This is an interface, so the public type has no constructor at all, and its only implementation is private to
 * this file. [GovernanceExposure.from] is the only way to obtain an instance: an integration that wants
 * authoritative-looking telemetry has to obtain a [GovernanceQueryResult] from the governance query service, and
 * that service applies the projection contract's own coherence rules before returning one. A caller cannot
 * assemble a stronger claim than a real read produced - not with a constructor, not with `copy()`, and not
 * through Java, which would otherwise see a Kotlin `internal` constructor as public.
 *
 * ## Included, and why
 *
 * - [GovernanceDecisionExposure.kind] - a fixed enum identifying the decision family.
 * - [GovernanceDecisionExposure.authority] - the evidence classification the repository already uses
 *   ([FactAuthority]). This is the classification the whole slice exists to preserve.
 * - [GovernanceDecisionExposure.freshness] - categorical freshness, per fact.
 * - [GovernanceDecisionExposure.recordedDecision] - the recorded decision category, already bounded by the
 *   projection contract to a safe token. It is the one bounded token that carries what was decided; it is a
 *   span attribute only, never a metric label.
 * - [GovernanceDecisionExposure.hasVerifiedStreamRange] - whether a verified raw range exists. The bound
 *   values themselves are deliberately reduced to this boolean: positions and hashes are identifiers.
 * - [GovernanceDecisionExposure.recordCompleteness] - decision-record completeness, kept independent of
 *   [GovernanceDecisionExposure.hasVerifiedStreamRange]. A verified range never implies complete recognition.
 * - [GovernanceDecisionExposure.reasonCodeExposure] - whether a reason code was exposed, withheld or not
 *   recorded. The code text itself is never carried, so a withheld value cannot be re-opened here.
 * - [registrationCurrency] and [auditCurrency] - the two sources, independently. There is no combined
 *   freshness flag: a source with no comparable fresh observation is [SourceCurrency.UNKNOWN], which is not the
 *   same claim as [SourceCurrency.CURRENT] and must not be collapsed into it.
 *
 * ## Excluded, and why
 *
 * - Reason code text, prompts, completions, tool arguments and results, document content, credentials,
 *   tokens, raw provider responses and raw exception text: protected payloads.
 * - `established`, `mustNotInfer`, the payload behind a present value, and every currency note: free-text
 *   strings authored for humans, unbounded in length and content.
 * - Workload, environment, deployment, run and stream identifiers, and hashes and digests: identifiers are
 *   not automatically safe - they are correlatable and high-cardinality, and no consumer need was established.
 * - The stored boundary strength. `RuntimeBoundaryProvenance` documents that resolving a registered
 *   boundary's strength does not authenticate the origin of the event naming it, so exporting that strength
 *   alongside telemetry would invite exactly the promotion this task forbids, and no event-origin proof exists
 *   that could justify it. The evidence classification in [GovernanceDecisionExposure.authority] is exported
 *   instead, and only where the source establishes it.
 */
public interface GovernanceExposure {
    public val state: GovernanceExposureState
    public val registrationCurrency: SourceCurrency
    public val auditCurrency: SourceCurrency
    public val decisions: List<GovernanceDecisionExposure>

    public companion object {
        /**
         * Maps a query result to its exposure, preserving which result state was returned.
         *
         * A missing, unsupported or corrupt projection never becomes an available, current or complete
         * signal: each maps to its own state with no decision facts and no currency claim. Infrastructure
         * failure is not a state here - a backend failure is an exception from the query, and it must not be
         * dressed up as evidence.
         */
        public fun from(result: GovernanceQueryResult): GovernanceExposure =
            when (result) {
                is GovernanceQueryResult.Found -> {
                    of(
                        state = GovernanceExposureState.EVIDENCE_AVAILABLE,
                        registrationCurrency = result.view.currency.registration,
                        auditCurrency = result.view.currency.audit,
                        decisions =
                            result.view.decisions.map { decision ->
                                GovernanceDecisionExposure.of(decision)
                            },
                    )
                }

                is GovernanceQueryResult.NotMaterialized -> {
                    unavailable(GovernanceExposureState.NOT_MATERIALIZED)
                }

                is GovernanceQueryResult.UnsupportedFormat -> {
                    unavailable(GovernanceExposureState.UNSUPPORTED_FORMAT)
                }

                is GovernanceQueryResult.InvalidStoredProjection -> {
                    unavailable(GovernanceExposureState.INVALID_STORED_PROJECTION)
                }
            }

        /**
         * Module-internal construction, for tests that need a field combination no real read produces. The
         * implementation class is private and [UnavailableExposure] is the only other implementation.
         */
        internal fun of(
            state: GovernanceExposureState,
            registrationCurrency: SourceCurrency,
            auditCurrency: SourceCurrency,
            decisions: List<GovernanceDecisionExposure>,
        ): GovernanceExposure = DefaultGovernanceExposure(state, registrationCurrency, auditCurrency, decisions)

        private fun unavailable(state: GovernanceExposureState): GovernanceExposure = unavailableExposure(state)
    }
}

/**
 * Which result a query produced, stated as telemetry-visible evidence.
 *
 * The four cases are distinct on purpose. Collapsing them into "queried successfully" would let a missing or
 * corrupt projection read as a healthy governance observation.
 */
public enum class GovernanceExposureState {
    /** A stored projection was read and its decision facts are reported. */
    EVIDENCE_AVAILABLE,

    /** No projection was materialized for this scope. Nothing is claimed about what happened. */
    NOT_MATERIALIZED,

    /** A projection exists but its storage format is not one this build reads. */
    UNSUPPORTED_FORMAT,

    /** A projected row failed digest or contract verification and was refused. */
    INVALID_STORED_PROJECTION,
}

/**
 * One decision family, reduced to exportable evidence.
 *
 * Both coverage claims are kept as separate properties: a verified raw stream range and complete decision
 * recognition are different facts, and neither may stand in for the other.
 *
 * An interface for the same reason as [GovernanceExposure]: the public type has no constructor and no `copy()`,
 * and in practice only [GovernanceExposure.from] produces instances.
 */
public interface GovernanceDecisionExposure {
    public val kind: DecisionKind
    public val authority: FactAuthority
    public val freshness: FactFreshness
    public val recordedDecision: String?
    public val hasVerifiedStreamRange: Boolean
    public val recordCompleteness: Completeness
    public val reasonCodeExposure: ReasonCodeExposure

    public companion object {
        /**
         * Module-internal factory, reading the seven permitted properties of a decision fact and nothing else.
         *
         * Kept as the single place the allowlist is spelled out: a field not named here cannot reach the
         * exposure, and the exposure copies values rather than retaining the fact, so no free text or reason
         * code travels with it.
         */
        internal fun of(decision: GovernanceDecisionFact): GovernanceDecisionExposure =
            DefaultGovernanceDecisionExposure(
                decision.kind,
                decision.fact.authority,
                decision.fact.freshness,
                decision.decision,
                decision.rawCoverage.verifiedRange != null,
                decision.recordCoverage.completeness,
                decision.reasonExposure,
            )
    }
}

private class DefaultGovernanceExposure(
    override val state: GovernanceExposureState,
    override val registrationCurrency: SourceCurrency,
    override val auditCurrency: SourceCurrency,
    override val decisions: List<GovernanceDecisionExposure>,
) : GovernanceExposure {
    init {
        require(state == GovernanceExposureState.EVIDENCE_AVAILABLE || decisions.isEmpty()) {
            "decision facts are exportable only when a projection was actually readable"
        }
        require(
            state == GovernanceExposureState.EVIDENCE_AVAILABLE ||
                (registrationCurrency == SourceCurrency.UNKNOWN && auditCurrency == SourceCurrency.UNKNOWN),
        ) {
            "currency is reportable only when a projection was actually readable"
        }
    }
}

/** The single shape every unreadable result takes: its own state, no decision facts, no currency claim. */
private class UnavailableExposure(
    override val state: GovernanceExposureState,
) : GovernanceExposure {
    init {
        require(state != GovernanceExposureState.EVIDENCE_AVAILABLE) {
            "an unavailable exposure cannot claim available evidence"
        }
    }

    override val registrationCurrency: SourceCurrency = SourceCurrency.UNKNOWN
    override val auditCurrency: SourceCurrency = SourceCurrency.UNKNOWN
    override val decisions: List<GovernanceDecisionExposure> = emptyList()
}

private fun unavailableExposure(state: GovernanceExposureState): GovernanceExposure = UnavailableExposure(state)

// A data class, so `ignoreDataClasses` keeps the length rule satisfied without a suppression. The generated
// `copy()` is harmless here: the class itself is private, so no consumer can name it.
private data class DefaultGovernanceDecisionExposure(
    override val kind: DecisionKind,
    override val authority: FactAuthority,
    override val freshness: FactFreshness,
    override val recordedDecision: String?,
    override val hasVerifiedStreamRange: Boolean,
    override val recordCompleteness: Completeness,
    override val reasonCodeExposure: ReasonCodeExposure,
) : GovernanceDecisionExposure
