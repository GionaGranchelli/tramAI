package dev.tramai.controlplane

/**
 * 0.7.4c1 — authority-aware governance projection: the read contract.
 *
 * A projection may report what an authoritative source established. It may never manufacture
 * authority that the source did not establish. That rule is enforced by the constructors below
 * rather than by review: a fact cannot be built in a shape whose freshness, completeness or absence
 * claim exceeds what its source authority can support.
 *
 * The frozen `runtime-evidence.v1` limitations travel with the facts: declared identity stays
 * declared (T1), a registered boundary's strength never proves origin (G2), an authorization record
 * is never completion (G3), and a family with no producer is never reported as absent-on-observation
 * (G4).
 */
internal enum class FactAuthority {
    /** Read from the store that owns the fact. */
    AUTHORITATIVE_PERSISTED,

    /** A recorded policy decision: attestation of authorization, never of execution. */
    POLICY_AUTHORIZATION_RECORD,

    /** Declared by a producer: identity or boundary, with origin unverified. */
    PRODUCER_DECLARED,

    /** A non-authoritative observation. Cannot establish a governance fact. */
    TELEMETRY,

    /**
     * The source was read and returned nothing for this fact.
     *
     * An empty read is evidence about the read, not about the world: it does not establish that the
     * fact was never produced. Emission may be optional, and the reader's coverage may be partial.
     */
    NOT_OBSERVED,

    /**
     * The fact family has no producer at all, so no read can cover it.
     *
     * Reserved for families where absence of a producer is itself established (for example
     * `provider.route`, which has no live production producer in this repository). Distinct from
     * [NOT_OBSERVED] precisely so that an empty query cannot be read as "no producer exists".
     */
    NOT_PRODUCED,
}

/** What the projection may claim about when a fact held. */
internal enum class FactFreshness {
    /** Current per the authority that owns it, at [ProjectedFact.observedVersion]. */
    CURRENT_AT_VERSION,

    /** A recorded point in the past, identified by a source position. */
    HISTORICAL,

    /** Derived state with a known watermark. */
    EVENTUAL_KNOWN_LAG,

    /** Freshness is not established, or not established as current. */
    STALE_OR_UNKNOWN,

    /** No fact. */
    ABSENT,
}

/** The decision families the projection can report. */
internal enum class DecisionKind {
    POLICY_DECISION,
    TOOL_PERMISSION,

    /** Source-declared at best: no engine producer exists for this family. */
    PROVIDER_ROUTE,
}

/**
 * Whether the projection may claim the read covered everything it reports.
 *
 * [COMPLETE] is claimable only against a source-attested range: a contiguous window of observed
 * positions proves nothing on its own, because positions before, after or between the window may
 * exist unobserved.
 */
internal enum class Completeness {
    COMPLETE,
    GAPS_PRESENT,
    UNKNOWN,
}

/**
 * The identity a producer declared for a run, as supplied to the read.
 *
 * Not authenticated: a complete and syntactically valid tuple does not establish workload origin.
 */
internal data class DeclaredRunIdentity(
    val workloadId: String,
    val configurationId: String,
    val configurationVersion: String,
    val environmentId: String,
    val deploymentId: String,
    val workflowRunId: String,
)

/** A boundary named by a producer, with the enforcement strength registered for it. */
internal data class DeclaredBoundary(
    val id: String,
    val registeredStrength: String?,
)

/**
 * A recorded decision, as a source supplied it.
 *
 * Deliberately narrow: no prompt, provider or model name, credential, tool argument or result
 * payload has a field here, so none can reach a generic read surface. The absent fields are the
 * point — this type is what an authorization record looks like at a read boundary.
 */
internal data class RecordedDecision(
    val workflowRunId: String,
    val kind: DecisionKind,
    val decision: String,
    val reasonCode: String? = null,
    val boundary: DeclaredBoundary? = null,
    val sourceSequence: Long? = null,
)

/** A non-authoritative observation. Never a governance fact. */
internal data class TelemetryObservation(
    val name: String,
    val sourceSequence: Long? = null,
)

/**
 * What a source read returned, together with what the source says the read covered.
 *
 * [assertedRange] is the stream positions the source attests it read. Without it the projection can
 * report observed positions but may not claim completeness.
 */
internal data class DecisionFactRead(
    val records: List<RecordedDecision>,
    val assertedRange: LongRange? = null,
)

/**
 * One projected fact: the value, the authority behind it, and what may not be inferred from it.
 *
 * Construction is the guard. The invariants are the deliverable: a caller cannot label a declared
 * identity as current at an authority's version, cannot carry a freshness claim without the version
 * that pins it, cannot claim a known lag without a watermark, cannot dress absence as a record, and
 * cannot state a declared or recorded fact without stating its limit.
 */
internal data class ProjectedFact<out T>(
    val value: T?,
    val authority: FactAuthority,
    val freshness: FactFreshness,
    val established: String,
    val mustNotInfer: List<String> = emptyList(),
    val observedVersion: WorkloadStateVersion? = null,
    val sourceVersion: Long? = null,
    val watermark: Long? = null,
) {
    init {
        require(established.isNotBlank()) { "a projected fact must state what was established" }
        require(value == null == (freshness == FactFreshness.ABSENT)) {
            "absence is exactly the case where no value is reported"
        }
        require(
            freshness != FactFreshness.ABSENT ||
                authority == FactAuthority.NOT_PRODUCED ||
                authority == FactAuthority.NOT_OBSERVED ||
                authority == FactAuthority.AUTHORITATIVE_PERSISTED,
        ) {
            "absence may be reported as no-producer, unobserved, or as the owning authority's answer"
        }
        require(authority != FactAuthority.NOT_PRODUCED || freshness == FactFreshness.ABSENT) {
            "a family with no producer cannot carry a freshness claim"
        }
        require(authority != FactAuthority.NOT_OBSERVED || freshness == FactFreshness.ABSENT) {
            "an unobserved fact cannot carry a freshness claim"
        }
        require(
            freshness != FactFreshness.CURRENT_AT_VERSION ||
                (authority == FactAuthority.AUTHORITATIVE_PERSISTED && observedVersion != null),
        ) {
            "only the owning authority's state is current, and it must name the version"
        }
        require(freshness != FactFreshness.HISTORICAL || (observedVersion != null || sourceVersion != null)) {
            "a historical fact must identify the point it reflects"
        }
        require(freshness != FactFreshness.EVENTUAL_KNOWN_LAG || watermark != null) {
            "a lag claim requires a watermark; unknown lag is not zero lag"
        }
        require(!DECLARED_AUTHORITIES.contains(authority) || mustNotInfer.isNotEmpty()) {
            "a declared or recorded fact must state what may not be inferred from it"
        }
    }

    private companion object {
        val DECLARED_AUTHORITIES = setOf(FactAuthority.PRODUCER_DECLARED, FactAuthority.POLICY_AUTHORIZATION_RECORD)
    }
}

/**
 * One decision family as projected, with the ordering evidence it rests on.
 *
 * [coverage] is the source-attested range this projection's completeness rests on; [Completeness]
 * may only claim [Completeness.COMPLETE] when it is present. [duplicatesIgnored] and [staleIgnored]
 * record position-level facts that were seen and not multiplied into the projection.
 */
internal data class DecisionProjection(
    val kind: DecisionKind,
    val latest: ProjectedFact<RecordedDecision>,
    val completeness: Completeness,
    val coverage: LongRange? = null,
    val duplicatesIgnored: Int = 0,
    val staleIgnored: Int = 0,
) {
    init {
        require(completeness != Completeness.COMPLETE || coverage != null) {
            "completeness cannot be claimed without the source-attested range it rests on"
        }
    }
}

/**
 * The projected view over a governed workload.
 *
 * Facts are never merged across authorities, and the registration read keeps its own temporal
 * classification, so current registration state cannot be substituted for a missing decision fact.
 */
internal data class GovernanceProjection(
    val registration: ProjectedFact<WorkloadExposure>,
    val declaredRunIdentity: ProjectedFact<DeclaredRunIdentity>,
    val decisions: List<DecisionProjection>,
    val telemetry: ProjectedFact<List<TelemetryObservation>>,
) {
    init {
        require(decisions.map { it.kind }.distinct().size == decisions.size) {
            "one projected fact per decision family: a duplicated kind would report two authorities for one fact"
        }
        require(decisions.none { it.routingUpgradedToEngineEstablished() }) {
            "provider.route has no live production producer: routing records are source-declared at best"
        }
        require(decisions.none { it.authorizationUpgradedToRuntimeState() }) {
            "a recorded policy or tool decision is an authorization record, not authoritative runtime state"
        }
    }

    private fun DecisionProjection.routingUpgradedToEngineEstablished(): Boolean =
        kind == DecisionKind.PROVIDER_ROUTE &&
            latest.value != null &&
            latest.authority != FactAuthority.PRODUCER_DECLARED

    private fun DecisionProjection.authorizationUpgradedToRuntimeState(): Boolean =
        kind.attestsAuthorization && latest.isAuthoritativeRuntimeState()

    private fun ProjectedFact<RecordedDecision>.isAuthoritativeRuntimeState(): Boolean =
        authority == FactAuthority.AUTHORITATIVE_PERSISTED && value != null

    private val DecisionKind.attestsAuthorization: Boolean
        get() = this == DecisionKind.POLICY_DECISION || this == DecisionKind.TOOL_PERMISSION
}
