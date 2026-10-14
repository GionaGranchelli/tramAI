package dev.tramai.controlplane

/**
 * 0.7.4c1 — authority-aware governance projection: the read contract.
 *
 * A projection may report what an authoritative source established. It may never manufacture
 * authority that the source did not establish. These types make that rule structural rather than
 * editorial: [ProjectedFact] refuses to exist in a shape whose freshness or absence claim exceeds
 * its source authority, and [GovernanceProjection] refuses to hold a decision fact under an
 * authority its producer never had.
 *
 * Reuse, not a second model: temporal classification stays [QueryConsistency] where it already
 * exists, and [FactFreshness] refines it (see [FactFreshness.from]); no parallel consistency type
 * is introduced and [WorkloadControlPlaneQueries] is unchanged. There is deliberately no generic
 * provenance ontology, no metadata map and no extension mechanism here: every field is a named,
 * typed statement with a known meaning.
 *
 * The frozen `runtime-evidence.v1` limitations (0.7.4b) are carried by these types:
 * identity is `declared`, never authenticated; a registered `boundary.id` carries its registered
 * strength and never proves origin; a recorded `tool.permission` ALLOW establishes authorization,
 * never execution; and absent `provider.route` facts mean "not produced", never "no routing".
 */
internal enum class FactAuthority {
    /** A persisted fact read from the store that owns it. */
    AUTHORITATIVE_PERSISTED,

    /** A recorded policy decision. It attests authorization at a boundary, never completion. */
    POLICY_AUTHORIZATION_RECORD,

    /** Identity or boundary a producer declared. Syntax and digests do not authenticate its origin. */
    PRODUCER_DECLARED,

    /** A non-authoritative observation. It never establishes a governance fact. */
    TELEMETRY,

    /** No producer or reader exists for this category. Absence here is not a negative fact. */
    NOT_PRODUCED,
}

/**
 * How a projected fact relates to time. Refines [QueryConsistency] rather than replacing it:
 * `AUTHORITATIVE` maps to [CURRENT_AT_VERSION] and `PROJECTION` to [EVENTUAL_KNOWN_LAG] when the
 * source reports a watermark, and to [STALE_OR_UNKNOWN] when it does not — an unknown lag is
 * reported as unknown, never as zero.
 */
internal enum class FactFreshness {
    /** Current according to the owning authority, at a known version. */
    CURRENT_AT_VERSION,

    /** A known past point, identified by a source version or watermark. */
    HISTORICAL,

    /** Derived state with a known watermark. */
    EVENTUAL_KNOWN_LAG,

    /** Freshness is not established, or is known to be stale. */
    STALE_OR_UNKNOWN,

    /** No fact exists to be fresh or stale. */
    ABSENT,
    ;

    companion object {
        /** Maps the established read classification to a freshness, 0.7.1e -> 0.7.4c1. */
        fun from(
            consistency: QueryConsistency,
            watermark: Long?,
        ): FactFreshness =
            when {
                consistency == QueryConsistency.AUTHORITATIVE -> CURRENT_AT_VERSION
                watermark != null -> EVENTUAL_KNOWN_LAG
                else -> STALE_OR_UNKNOWN
            }
    }
}

/** A governance fact category this projection understands. */
internal enum class DecisionKind {
    POLICY_DECISION,
    TOOL_PERMISSION,
    PROVIDER_ROUTE,
}

/** Whether a fold over source records knows it saw every contributing record. */
internal enum class Completeness {
    /** Contiguous source positions from the source's own basis: the source reports none missing. */
    COMPLETE,

    /** The source's positions show a gap. A partial projection must not claim completeness. */
    GAPS_PRESENT,

    /** The source reports no ordering basis at all, so completeness cannot be claimed. */
    UNKNOWN,
}

/**
 * The identity a producer declared for a run: the frozen `identity.*` tuple plus the canonical run id.
 *
 * Declaring this tuple is not authentication. A syntactically valid, digest-bound tuple is still
 * only declared: nothing ties it to a registered workload (T1, open).
 */
internal data class DeclaredRunIdentity(
    val workloadId: String,
    val configurationId: String,
    val configurationVersion: String,
    val environmentId: String,
    val deploymentId: String,
    val workflowRunId: String,
)

/**
 * The enforcement boundary a record names, with the strength its registration grants it.
 *
 * [registeredStrength] is the framework-derived strength for a declared identifier. It is not
 * evidence that this event originated at that boundary (`RuntimeBoundaryProvenance`, G2).
 */
internal data class DeclaredBoundary(
    val id: String,
    val registeredStrength: String?,
)

/**
 * A decision record as a read source supplies it.
 *
 * There is deliberately no completion, result, payload or provider-traffic field: a read surface
 * must not be able to carry a sensitive payload, and a decision record cannot attest that the
 * operation ran, succeeded, or changed external state.
 */
internal data class RecordedDecision(
    val workflowRunId: String,
    val kind: DecisionKind,
    val decision: String,
    val reasonCode: String? = null,
    val boundary: DeclaredBoundary? = null,
    val sourceSequence: Long? = null,
)

/** A non-authoritative observation from telemetry. Held apart from [RecordedDecision] on purpose. */
internal data class TelemetryObservation(
    val name: String,
    val sourceSequence: Long? = null,
)

/**
 * A fact together with its authority, its temporal standing, and its claim boundary.
 *
 * The `init` block is the enforcement point for the whole contract: a fact whose freshness exceeds
 * its authority, that claims a current version without one, that reports a known lag without a
 * watermark, or that is declared or recorded without stating what may not be inferred, cannot be
 * constructed.
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
        require(value == null == (freshness == FactFreshness.ABSENT)) {
            "a fact exists exactly when its freshness is not ABSENT"
        }
        require(freshness != FactFreshness.ABSENT || authority in ABSENT_AUTHORITIES) {
            "only NOT_PRODUCED or the owning authority may report absence"
        }
        require(authority != FactAuthority.NOT_PRODUCED || freshness == FactFreshness.ABSENT) {
            "a category with no producer cannot carry a fresh fact"
        }
        require(freshness != FactFreshness.CURRENT_AT_VERSION || authority == FactAuthority.AUTHORITATIVE_PERSISTED) {
            "current-at-version is reserved for a fact its owning authority established"
        }
        require(freshness != FactFreshness.CURRENT_AT_VERSION || observedVersion != null) {
            "a current fact must name the version it is current at"
        }
        require(freshness != FactFreshness.HISTORICAL || observedVersion != null || sourceVersion != null) {
            "a historical fact must identify the point it reflects"
        }
        require(freshness != FactFreshness.EVENTUAL_KNOWN_LAG || watermark != null) {
            "an eventually consistent fact must carry its watermark; unknown lag is not zero"
        }
        require(authority !in LIMIT_STATING_AUTHORITIES || mustNotInfer.isNotEmpty()) {
            "a declared or recorded fact must state what may not be inferred from it"
        }
        require(established.isNotBlank()) { "a fact must state what its source established" }
    }

    internal companion object {
        private val ABSENT_AUTHORITIES = setOf(FactAuthority.NOT_PRODUCED, FactAuthority.AUTHORITATIVE_PERSISTED)
        private val LIMIT_STATING_AUTHORITIES =
            setOf(FactAuthority.PRODUCER_DECLARED, FactAuthority.POLICY_AUTHORIZATION_RECORD)
    }
}

/**
 * One decision kind folded into a projection.
 *
 * [latest] is the highest source position seen for the kind; a lower position arriving afterwards
 * is counted in [staleIgnored] and never replaces it. [duplicatesIgnored] counts records that
 * repeated a fact already present, so a re-delivered event does not become a second fact.
 */
internal data class DecisionProjection(
    val kind: DecisionKind,
    val latest: ProjectedFact<RecordedDecision>,
    val completeness: Completeness,
    val duplicatesIgnored: Int = 0,
    val staleIgnored: Int = 0,
) {
    init {
        require(
            completeness != Completeness.COMPLETE ||
                latest.value == null ||
                latest.value.sourceSequence != null,
        ) { "a COMPLETE fold must be based on the source's own positions" }
        require(duplicatesIgnored >= 0 && staleIgnored >= 0) { "counters are not negative" }
    }
}

/**
 * The governance projection for one governed run in one registered scope.
 *
 * [decisions] holds at most one entry per [DecisionKind]; the invariant below refuses the two
 * authority upgrades this task exists to prevent: routing facts presented as engine-established
 * (there is no live producer), and recorded decisions presented as authoritative runtime state
 * (a record attests authorization, not execution).
 */
internal data class GovernanceProjection(
    val registration: ProjectedFact<WorkloadExposure>,
    val declaredRunIdentity: ProjectedFact<DeclaredRunIdentity>,
    val decisions: List<DecisionProjection>,
    val telemetry: ProjectedFact<List<TelemetryObservation>>,
) {
    init {
        require(decisions.map { it.kind }.distinct().size == decisions.size) {
            "a decision kind may appear at most once; two entries would be two authorities for one fact"
        }
        require(decisions.none { it.routingUpgradedToEngineEstablished() }) {
            "provider.route has no live production producer: routing records are source-declared at best"
        }
        require(decisions.none { it.kind.attestsAuthorization && it.latest.isAuthoritativeRuntimeState() }) {
            "a recorded policy or tool decision is an authorization record, not authoritative runtime state"
        }
    }

    /**
     * A routing fact carrying a value under any authority other than source-declared is an upgrade:
     * no engine producer exists, so routing records are source-declared or absent, never established.
     */
    private fun DecisionProjection.routingUpgradedToEngineEstablished(): Boolean =
        kind == DecisionKind.PROVIDER_ROUTE &&
            latest.value != null &&
            latest.authority != FactAuthority.PRODUCER_DECLARED

    private fun ProjectedFact<RecordedDecision>.isAuthoritativeRuntimeState(): Boolean =
        authority == FactAuthority.AUTHORITATIVE_PERSISTED && value != null

    private val DecisionKind.attestsAuthorization: Boolean
        get() = this == DecisionKind.POLICY_DECISION || this == DecisionKind.TOOL_PERMISSION
}
