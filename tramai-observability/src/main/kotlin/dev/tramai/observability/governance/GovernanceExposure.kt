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
 * ## What this guarantees, and what it does not
 *
 * **Content safety.** The type has no field for a payload: only the properties below can ever be rendered, no
 * `toString()`, reflection, serialization or `Map<String, Any?>` forwarding is involved, and the mapping copies
 * claims verbatim instead of inventing or upgrading them. A value that is not named here cannot reach telemetry
 * through this path.
 *
 * **Not authentication.** This is a safe *representation*, not proof of a real authoritative read. Neither this
 * type nor [from] authenticates that a governance query occurred: `GovernanceQueryResult` and its DTOs are
 * publicly constructible, and the interfaces below can be implemented by any JVM consumer (Kotlin's `sealed`
 * restricts Kotlin implementors to this module, but it is a compile-time rule, not a JVM security boundary).
 * A caller that wants authoritative-looking telemetry can therefore describe whatever it likes, and the mapping
 * will faithfully report that claim.
 *
 * Consequently **no consumer may treat these attributes as authenticated enforcement evidence**. They attest what
 * the source claimed, and the value of that claim depends on the source being the governance query service. This
 * is consistent with OpenTelemetry's status here: a representation of evidence, never evidence of enforcement.
 *
 * Implementations are private to this file, so a Kotlin consumer cannot construct one and must call [from].
 *
 * ## Included, and why
 *
 * - [GovernanceDecisionExposure.kind] - a fixed enum identifying the decision family.
 * - [GovernanceDecisionExposure.authority] - the evidence classification the repository already uses
 *   ([FactAuthority]). This is the classification the whole slice exists to preserve.
 * - [GovernanceDecisionExposure.freshness] - categorical freshness, per fact.
 * - [GovernanceDecisionExposure.recordedDecision] - the recorded decision category, but only when it is one of
 *   the values the repository's audit readers already recognize (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`). The query
 *   contract guarantees only a syntactically safe token, and a credential-shaped string satisfies that, so any
 *   other value is replaced by a fixed non-disclosing classification instead of being exported. It is a span
 *   attribute only, never a metric label.
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
public sealed interface GovernanceExposure {
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
 * A sealed interface for the same reasons as [GovernanceExposure]: no constructor, no `copy()`, and Kotlin
 * implementations restricted to this module. The same limit applies - it is a compile-time rule, not a JVM
 * security boundary, and a value of this type is a claim to be reported, not an authenticated fact.
 */
public sealed interface GovernanceDecisionExposure {
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
                // The query DTO only guarantees a syntactically safe token, which a credential-shaped string can
                // satisfy. Only the vocabulary the repository's audit readers already recognize is disclosed;
                // anything else becomes a fixed, non-disclosing classification.
                approvedDecisionOrUnrecognized(decision.decision),
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

/**
 * The decision values this repository's audit readers already treat as a closed vocabulary.
 *
 * Duplicated deliberately rather than shared: those sets are private to `:tramai-security`'s evidence and audit
 * readers, and this module must not reach into them. The cost is that a new decision value has to be added in
 * both places - the safe direction, since an unlisted value is withheld here rather than disclosed.
 */
private val APPROVED_DECISION_TOKENS = setOf("ALLOW", "DENY", "REQUIRE_APPROVAL")

/** Fixed, non-disclosing stand-in for a recorded decision outside the approved vocabulary. */
internal const val UNRECOGNIZED_DECISION: String = "UNRECOGNIZED"

private fun approvedDecisionOrUnrecognized(token: String?): String? =
    when {
        token == null -> null
        token in APPROVED_DECISION_TOKENS -> token
        else -> UNRECOGNIZED_DECISION
    }
