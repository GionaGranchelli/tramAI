package dev.tramai.security.governance
import dev.tramai.controlplane.WorkloadExposure
import dev.tramai.controlplane.WorkloadStateVersion

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
 * What the projection may claim about the *decision records* it reports, within the range the source
 * verified. It answers a different question from [StreamCoverage], which carries the raw-stream
 * claim, and it is never a claim that a decision did not occur: emission of these records is optional
 * in this repository, so a complete record set says nothing about what the runtime did.
 *
 * [COMPLETE] — every position in the verified range that this read was required to classify produced
 * a record. Positions the repository's own producers define as belonging to another family do not
 * count, and one position whose classification is not established prevents this status. Claimable
 * only against a verified range: observed positions alone prove nothing, because positions before,
 * after or between them may exist unobserved.
 *
 * [GAPS_PRESENT] — the verified range contains a position that recorded a decision outcome yet
 * produced no record. That is a hole in the record set, not a filter.
 *
 * [UNKNOWN] — no range was verified, the range holds an event whose classification is not
 * established, a record carries no position, or a record lies outside the verified range.
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
 * A source read: the records it recognised, and the verified stream coverage it established while
 * recognising them.
 *
 * The two are separate fields because they are separate claims — a verified stream does not mean every
 * event in it was a governance decision, and a recognised decision does not mean the read covered the
 * stream. Construction is the guard: coverage that names classified positions without the range they
 * were classified in is refused.
 */
internal data class DecisionFactRead(
    val records: List<RecordedDecision>,
    val coverage: StreamCoverage = StreamCoverage(),
)

/**
 * What a read established about the audit stream itself, separately from what it classified.
 *
 * [verifiedRange] is the raw-stream claim: the positions the source read, verified for chain
 * consistency and anchored to the identity of the head it observed. It is not a decision claim, and a
 * read that was bounded, raced by an append, answered from another stream, or unanchored carries no
 * range.
 *
 * The two position sets are the classification outcome, and they are deliberately *not* the
 * complement of the records: an event a producer defines as belonging to another family — a
 * redaction, an approval lifecycle step — is neither a gap nor an unknown, so it appears in neither
 * set and cannot manufacture a gap in the raw stream. An event whose classification is not
 * established must appear in [unclassifiedPositions], because assuming it is irrelevant would claim
 * completeness the source has not proven.
 */
internal data class StreamCoverage(
    val verifiedRange: LongRange? = null,
    val accountedPositions: Long = 0,
    val unrecordedDecisionPositions: Set<Long> = emptySet(),
    val unclassifiedPositions: Set<Long> = emptySet(),
) {
    init {
        require(accountedPositions >= 0) { "an account cannot be negative" }
        val range = verifiedRange
        if (range == null) {
            require(accountedPositions == 0L && unrecordedDecisionPositions.isEmpty() && unclassifiedPositions.isEmpty()) {
                "a read that verified no range has no positions to account for"
            }
        } else {
            require(range.first >= FIRST_AUDIT_POSITION) { "audit stream positions begin at $FIRST_AUDIT_POSITION" }
            require((unrecordedDecisionPositions + unclassifiedPositions).all { it in range }) {
                "a classified position must lie inside the verified range"
            }
            require(accountedPositions <= range.last - range.first + 1) {
                "a read cannot account for more positions than it verified"
            }
        }
    }
}

/** The first position an audit stream can have, per the store's own contract. */
private const val FIRST_AUDIT_POSITION = 1L

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
 * [verifiedStreamRange] is the raw-stream coverage the read established and [completeness] is the
 * decision-record claim: neither implies the other, and [Completeness.COMPLETE] may only be claimed
 * when [verifiedStreamRange] is present. [duplicatesIgnored] and [staleIgnored] record position-level
 * facts that were seen and not multiplied into the projection.
 */
internal data class DecisionProjection(
    val kind: DecisionKind,
    val latest: ProjectedFact<RecordedDecision>,
    val completeness: Completeness,
    val verifiedStreamRange: LongRange? = null,
    val duplicatesIgnored: Int = 0,
    val staleIgnored: Int = 0,
) {
    init {
        require(completeness != Completeness.COMPLETE || verifiedStreamRange != null) {
            "decision completeness cannot be claimed without the verified stream range it rests on"
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
