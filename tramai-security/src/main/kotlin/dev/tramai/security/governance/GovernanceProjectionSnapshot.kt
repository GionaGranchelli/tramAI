package dev.tramai.security.governance

import java.security.MessageDigest

/*
 * 0.7.4d — the materialization contract.
 *
 * A materialized projection preserves what authoritative sources established. It creates no
 * authority, completeness, freshness or evidence of its own: every field below is either read from a
 * source, derived from the coverage those sources reported, or a statement about what may not be
 * inferred. There is no field an implementation could set to make the read model look stronger than
 * the evidence behind it.
 *
 * The two coverage claims stay separate, as they are in the read itself — raw audit-stream coverage
 * says which stream state was verified, decision-record coverage says what was recognized inside it.
 * Neither is derived from the other.
 */

/** The persisted format. A stored row carrying anything else is refused, never upgraded. */
internal const val GOVERNANCE_READ_MODEL_FORMAT_VERSION = "governance-read-model.v1"

/**
 * What a projection is about, and the key a stored snapshot is addressed by.
 *
 * The key is built from source-established identity only: the canonical workload/environment/
 * deployment scope, the run, and the audit stream the decisions were read from. A *declared*
 * configuration identity is deliberately not part of it — declared values are reported as declared,
 * and a value a producer asserted may not decide which authoritative facts a read addresses.
 */
internal class ProjectionIdentity(
    val workloadId: String,
    val environmentId: String,
    val deploymentId: String,
    val workflowRunId: String? = null,
    val auditStreamId: String? = null,
) {
    init {
        listOf(workloadId, environmentId, deploymentId).forEach {
            require(it.isNotBlank()) { "a projection identity must name its scope" }
            require(SAFE_IDENTITY.matches(it)) { "a projection identity component must be a bounded token" }
        }
        listOfNotNull(workflowRunId, auditStreamId).forEach {
            require(SAFE_IDENTITY.matches(it)) { "a projection identity component must be a bounded token" }
        }
    }

    // Deterministic and unambiguous: every component is length-prefixed, so no two distinct
    // identities can produce the same key, and absent components are marked rather than blank.
    val key: String
        get() =
            listOf(workloadId, environmentId, deploymentId, workflowRunId ?: ABSENT, auditStreamId ?: ABSENT)
                .joinToString(separator = "|") { "${it.length}:$it" }

    override fun equals(other: Any?): Boolean = other is ProjectionIdentity && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    private companion object {
        /** No separators, control characters or whitespace: identity is not a free-text channel. */
        val SAFE_IDENTITY = Regex("^[A-Za-z0-9._:-]{1,200}$")
        const val ABSENT = "\\absent"
    }
}

/**
 * One projected fact, in the form that is persisted.
 *
 * [valuePresent] records whether the source reported a value at all, which is not the same as
 * reporting an absent one: absence is a fact about the read, and the authority and freshness
 * classifications say what it is evidence of. The value itself is not stored here — see the note on
 * [GovernanceProjectionSnapshot] about registration content.
 */
internal data class SnapshotFact(
    val authority: FactAuthority,
    val freshness: FactFreshness,
    val established: String,
    val mustNotInfer: List<String>,
    val valuePresent: Boolean,
    val observedVersion: Long? = null,
    val sourceVersion: Long? = null,
    val watermark: Long? = null,
) {
    /*
     * The authority model, applied to stored content as well as to fresh reads.
     *
     * These are the same rules [ProjectedFact] enforces, expressed over "was a value reported" instead
     * of "is there a value". They run on construction, which means they run again when a row is
     * rebuilt from durable storage: a tampered or migrated row cannot become a valid authoritative
     * fact just by being read back, because the type will not hold it.
     */
    init {
        require(established.isNotBlank()) { "a stored fact must state what was established" }
        require(valuePresent == (freshness != FactFreshness.ABSENT)) {
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

    companion object {
        private val DECLARED_AUTHORITIES =
            setOf(FactAuthority.PRODUCER_DECLARED, FactAuthority.POLICY_AUTHORIZATION_RECORD)

        fun of(fact: ProjectedFact<*>): SnapshotFact =
            SnapshotFact(
                authority = fact.authority,
                freshness = fact.freshness,
                established = fact.established,
                mustNotInfer = fact.mustNotInfer,
                valuePresent = fact.value != null,
                observedVersion = fact.observedVersion?.value,
                sourceVersion = fact.sourceVersion,
                watermark = fact.watermark,
            )
    }
}

/**
 * Raw audit-stream coverage, as persisted.
 *
 * [observedHeadHash] is the identity of the head the range was anchored to. A stored snapshot that
 * matched positions but not hashes would be comparing two different streams by arithmetic.
 */
internal data class SnapshotCoverage(
    val verifiedRange: LongRange?,
    val observedHeadHash: String?,
    val accountedPositions: Long,
    val unrecordedDecisionPositions: Set<Long>,
    val unclassifiedPositions: Set<Long>,
    val classificationRevision: String,
) {
    /*
     * The same guards [StreamCoverage] applies to a fresh read, applied again to stored content.
     *
     * A row that names positions outside the range it claims to have verified, or accounts for
     * positions no range covered, is not a slightly wrong snapshot: it is a contradiction, and it is
     * refused here rather than reported as coverage.
     */
    init {
        require(accountedPositions >= 0) { "an account cannot be negative" }
        val range = verifiedRange
        if (range == null) {
            require(
                accountedPositions == 0L &&
                    unrecordedDecisionPositions.isEmpty() &&
                    unclassifiedPositions.isEmpty() &&
                    observedHeadHash == null,
            ) {
                "a read that verified no range has no positions to account for, and no head to name"
            }
        } else {
            require(range.first >= 1L) { "audit stream positions begin at 1" }
            require((unrecordedDecisionPositions + unclassifiedPositions).all { it in range }) {
                "a classified position must lie inside the verified range"
            }
            require(accountedPositions <= range.last - range.first + 1) {
                "a read cannot account for more positions than it verified"
            }
        }
    }

    companion object {
        fun of(coverage: StreamCoverage): SnapshotCoverage =
            SnapshotCoverage(
                verifiedRange = coverage.verifiedRange,
                observedHeadHash = coverage.observedHeadHash,
                accountedPositions = coverage.accountedPositions,
                unrecordedDecisionPositions = coverage.unrecordedDecisionPositions,
                unclassifiedPositions = coverage.unclassifiedPositions,
                classificationRevision = coverage.classificationRevision,
            )
    }
}

/** One decision family: its fact, and the two coverage claims that qualify it. */
@Suppress("LongParameterList")
internal data class SnapshotDecision(
    val kind: DecisionKind,
    val completeness: Completeness,
    val coverage: SnapshotCoverage,
    val latest: SnapshotFact,
    val latestDecision: String?,
    val latestReasonCode: String?,
    val latestBoundaryId: String?,
    val latestBoundaryStrength: String?,
    val duplicatesIgnored: Int,
    val staleIgnored: Int,
) {
    init {
        require(completeness != Completeness.COMPLETE || coverage.verifiedRange != null) {
            "decision completeness cannot be stored without the verified range it rests on"
        }
    }

    companion object {
        fun of(projection: DecisionProjection): SnapshotDecision =
            SnapshotDecision(
                kind = projection.kind,
                completeness = projection.completeness,
                coverage = SnapshotCoverage.of(projection.coverage),
                latest = SnapshotFact.of(projection.latest),
                latestDecision = projection.latest.value?.decision,
                latestReasonCode = projection.latest.value?.reasonCode,
                latestBoundaryId =
                    projection.latest.value
                        ?.boundary
                        ?.id,
                latestBoundaryStrength =
                    projection.latest.value
                        ?.boundary
                        ?.registeredStrength,
                duplicatesIgnored = projection.duplicatesIgnored,
                staleIgnored = projection.staleIgnored,
            )
    }
}

/**
 * Provenance of the read that produced the snapshot, per source.
 *
 * The registration source and the audit source are read independently, so their versions are
 * recorded separately and never combined into a single notional instant:
 * [readConsistency] says exactly that, and nothing in this type claims otherwise.
 *
 * A zero in [registrationVersion] or [auditHeadPosition] means "not established" rather than
 * "position zero": authoritative registration versions start at one, and audit positions start at
 * one, so zero is not a value either source can produce.
 */
internal data class SourceProvenance(
    val registrationVersion: Long,
    val auditHeadPosition: Long,
    val auditHeadHash: String?,
    val classificationRevision: String,
    val readConsistency: ReadConsistency = ReadConsistency.INDEPENDENT_SOURCES,
)

/** How the sources behind a snapshot were read. Only one answer exists today. */
internal enum class ReadConsistency {
    /**
     * Each source was read on its own; their versions are recorded separately and the pair is an
     * ordering key, not a claim that both were observed at one instant. An atomic cross-store
     * snapshot is not available from these sources and is not claimed.
     */
    INDEPENDENT_SOURCES,
}

/**
 * A materialized governance projection, in the shape that is persisted.
 *
 * What is deliberately absent: registration *content*. The read model keeps the registration fact's
 * provenance — authority, freshness, version, and what may not be inferred — and never a duplicate of
 * the authoritative registration record, which remains the only place registration state can be read
 * from. A second copy here would be a second answer to an authoritative question, and one of the two
 * would eventually be wrong.
 *
 * [semanticDigest] covers the semantic content only. Write timestamps are excluded, so rebuilding the
 * same source facts twice produces the same digest and a rebuild can be compared instead of inspected.
 * It is re-derived on construction, so a snapshot whose stated digest does not match its content
 * cannot exist.
 */
@Suppress("LongParameterList")
internal class GovernanceProjectionSnapshot(
    val formatVersion: String,
    val identity: ProjectionIdentity,
    val registration: SnapshotFact,
    val declaredRunIdentity: SnapshotFact,
    val telemetry: SnapshotFact,
    val decisions: List<SnapshotDecision>,
    val provenance: SourceProvenance,
) {
    init {
        require(formatVersion == GOVERNANCE_READ_MODEL_FORMAT_VERSION) {
            "a snapshot must carry the format this build writes"
        }
        require(decisions.map { it.kind }.distinct().size == decisions.size) {
            "one decision family appears once: two rows for one kind would report two authorities"
        }
        require(decisions.all { it.coverage.classificationRevision == provenance.classificationRevision }) {
            "a family cannot be classified under a different revision than the read stated"
        }
        require(
            decisions.none {
                it.completeness == Completeness.COMPLETE && it.coverage.verifiedRange == null
            },
        ) {
            "decision completeness cannot be stored without the verified range it rests on"
        }
        require(provenance.auditHeadHash == null || provenance.auditHeadPosition > 0L) {
            "a head hash must name a position, and positions start at one"
        }
    }

    val semanticDigest: String = digestOf(canonicalForm())

    /**
     * The ordering pair for this snapshot, compared lexicographically: audit head first, registration
     * version second. The decision records derive from the audit stream, so a candidate built from an
     * older head is strictly weaker evidence even if registration has moved on. This orders snapshots
     * for one key; it does not make the two sources one snapshot point.
     */
    val ordering: Pair<Long, Long> get() = provenance.auditHeadPosition to provenance.registrationVersion

    /** Deterministic rendering: fixed field order, no timestamps, no maps. */
    fun canonicalForm(): String =
        buildString {
            append(formatVersion).append('\n')
            append(identity.key).append('\n')
            append(provenance.registrationVersion).append('\n')
            append(provenance.auditHeadPosition).append('\n')
            append(provenance.auditHeadHash ?: "").append('\n')
            append(provenance.classificationRevision).append('\n')
            append(provenance.readConsistency.name).append('\n')
            listOf(registration, declaredRunIdentity, telemetry).forEach { append(factForm(it)) }
            decisions.sortedBy { it.kind.name }.forEach { append(decisionForm(it)) }
        }

    private fun factForm(fact: SnapshotFact): String =
        listOf(
            fact.authority.name,
            fact.freshness.name,
            fact.valuePresent.toString(),
            fact.observedVersion?.toString() ?: "",
            fact.sourceVersion?.toString() ?: "",
            fact.watermark?.toString() ?: "",
            fact.established,
            fact.mustNotInfer.joinToString(separator = LINE_SEPARATOR),
        ).joinToString(separator = "\u001f") + "\n"

    private fun decisionForm(decision: SnapshotDecision): String =
        listOf(
            decision.kind.name,
            decision.completeness.name,
            decision.coverage.verifiedRange
                ?.first
                ?.toString() ?: "",
            decision.coverage.verifiedRange
                ?.last
                ?.toString() ?: "",
            decision.coverage.observedHeadHash ?: "",
            decision.coverage.accountedPositions.toString(),
            encodePositions(decision.coverage.unrecordedDecisionPositions),
            encodePositions(decision.coverage.unclassifiedPositions),
            decision.coverage.classificationRevision,
            decision.duplicatesIgnored.toString(),
            decision.staleIgnored.toString(),
            decision.latestDecision ?: "",
            decision.latestReasonCode ?: "",
            decision.latestBoundaryId ?: "",
            decision.latestBoundaryStrength ?: "",
            factForm(decision.latest),
        ).joinToString(separator = "\u001f")

    companion object {
        private const val LINE_SEPARATOR = "\n"

        /**
         * Builds the persisted form from a read.
         *
         * Pure: no clock, no ambient state, no iteration over unordered collections that could vary
         * between runs. Two materializations of the same source facts produce the same bytes.
         */
        fun of(
            identity: ProjectionIdentity,
            projection: GovernanceProjection,
            provenance: SourceProvenance,
        ): GovernanceProjectionSnapshot =
            GovernanceProjectionSnapshot(
                formatVersion = GOVERNANCE_READ_MODEL_FORMAT_VERSION,
                identity = identity,
                registration = SnapshotFact.of(projection.registration),
                declaredRunIdentity = SnapshotFact.of(projection.declaredRunIdentity),
                telemetry = SnapshotFact.of(projection.telemetry),
                decisions = projection.decisions.map { SnapshotDecision.of(it) },
                provenance = provenance,
            )

        fun digestOf(canonicalForm: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalForm.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }

        /** Positions as a compact, sorted, deterministic list. */
        fun encodePositions(positions: Set<Long>): String = positions.sorted().joinToString(separator = ",")

        /** Strict: a malformed list is a corrupt row, not an empty set. */
        fun decodePositions(encoded: String): Set<Long> {
            if (encoded.isEmpty()) return emptySet()
            return encoded
                .split(',')
                .map { token ->
                    token.toLongOrNull()
                        ?: throw ProjectionCorruptionException("stored positions are malformed")
                }.toSet()
        }

        /** One statement per line, order preserved; entries may not contain the separator. */
        fun encodeLines(lines: List<String>): String {
            require(lines.none { it.contains(LINE_SEPARATOR) }) { "a statement may not span lines" }
            return lines.joinToString(separator = LINE_SEPARATOR)
        }

        fun decodeLines(encoded: String): List<String> {
            if (encoded.isEmpty()) return emptyList()
            return encoded.split(LINE_SEPARATOR)
        }
    }
}

/**
 * A stored row that this build cannot use.
 *
 * Refused rather than repaired: silently upgrading an unknown format or a malformed row into a valid
 * authoritative fact is the failure this exception exists to prevent.
 */
internal class UnsupportedProjectionFormatException(
    message: String,
) : IllegalStateException(message)

/** A stored row whose content contradicts its own structure or digest. */
internal class ProjectionCorruptionException(
    message: String,
) : IllegalStateException(message)
