package dev.tramai.security.governance

import java.time.Instant

/*
 * 0.7.4d — the durable read-model contract.
 *
 * The store holds a derived view. It answers "what did the authoritative sources establish, as of the
 * versions recorded here" and nothing else: it cannot label itself current, it cannot fill a gap it
 * did not observe, and it cannot turn a stored row into authority that the sources did not provide.
 */

/** What a publish attempt did. Every outcome is reported, including the ones that changed nothing. */
internal enum class MaterializationOutcome {
    /** The candidate was strictly newer than the stored snapshot and replaced it. */
    PUBLISHED,

    /** The candidate described exactly the stored source state and content: nothing to do. */
    ALREADY_CURRENT,

    /**
     * The candidate was built from older source versions than the stored snapshot, so it was not
     * applied. A stale read that finishes later does not get to overwrite a newer one.
     */
    REJECTED_STALE,

    /**
     * The candidate described the same source versions as the stored snapshot but disagreed with it
     * on content — including a different head hash at the same purported position. Divergent evidence
     * at one source state is refused rather than resolved by arrival order.
     */
    REJECTED_CONFLICT,
}

/** A head observed by a fresh read, supplied by the caller that just performed it. */
internal data class ObservedAuditHead(
    val position: Long,
    val hash: String? = null,
) {
    init {
        require(position >= FIRST_OBSERVED_POSITION) { "audit positions start at one" }
    }
}

/** Whether one source's contribution to a stored snapshot still matches a fresh observation. */
internal enum class SourceCurrency {
    /** A fresh authoritative observation agrees with what the snapshot recorded for this source. */
    CURRENT,

    /** A fresh authoritative observation disagrees: the snapshot is behind for this source. */
    STALE,

    /**
     * No fresh observation of this source was supplied, or the snapshot never established it.
     * Not the same as stale, and never reported as current.
     */
    UNKNOWN,
}

/**
 * Per-source currency, assessed against a fresh observation.
 *
 * There is no overall "fresh" flag, because there is no single source: registration and audit are read
 * independently, and a snapshot may be current for one and behind for the other.
 */
internal data class ProjectionFreshnessAssessment(
    val audit: SourceCurrency,
    val registration: SourceCurrency,
    val note: String,
) {
    val anyStale: Boolean get() = audit == SourceCurrency.STALE || registration == SourceCurrency.STALE
}

/**
 * A snapshot as it came back from durable storage.
 *
 * The decision-record claim is reported through [completenessFor] rather than straight off the row,
 * because it is a claim about a recognition contract: if the persisted revision is not the revision
 * this build classifies under, the stored decision completeness is not a statement about this build's
 * vocabulary and is reported as unknown. The *raw* coverage survives that — which stream was verified
 * is a fact about the stream, not about the vocabulary — and so does the decision content, which a
 * caller can still see as historical evidence.
 */
internal class StoredGovernanceProjection(
    val snapshot: GovernanceProjectionSnapshot,
    val materializedAt: Instant,
) {
    val identity: ProjectionIdentity get() = snapshot.identity

    /** Whether the classification behind the stored decision coverage is this build's. */
    val classificationRevisionIsCurrent: Boolean
        get() = snapshot.provenance.classificationRevision == GOVERNANCE_CLASSIFICATION_REVISION

    fun decision(kind: DecisionKind): SnapshotDecision? = snapshot.decisions.firstOrNull { it.kind == kind }

    /** The decision-record claim, or [Completeness.UNKNOWN] when the revision is not this build's. */
    fun completenessFor(kind: DecisionKind): Completeness {
        val stored = decision(kind) ?: return Completeness.UNKNOWN
        return if (classificationRevisionIsCurrent) stored.completeness else Completeness.UNKNOWN
    }

    /** The raw-stream claim, which a classification change does not invalidate. */
    fun auditCoverageFor(kind: DecisionKind): SnapshotCoverage? = decision(kind)?.coverage

    /**
     * Currency, against a fresh observation supplied by the caller.
     *
     * A stored row cannot establish its own currency: only a fresh authoritative read can, and this
     * method can therefore be no more current than the arguments it is given. Nothing observed means
     * nothing claimed.
     */
    fun assessAgainst(
        auditHead: ObservedAuditHead?,
        registrationVersion: Long?,
    ): ProjectionFreshnessAssessment {
        val provenance = snapshot.provenance

        val audit =
            when {
                auditHead == null || provenance.auditHeadPosition == NOT_ESTABLISHED -> {
                    SourceCurrency.UNKNOWN
                }

                auditHead.position != provenance.auditHeadPosition -> {
                    SourceCurrency.STALE
                }

                auditHead.hash != null && provenance.auditHeadHash != null -> {
                    if (auditHead.hash == provenance.auditHeadHash) SourceCurrency.CURRENT else SourceCurrency.STALE
                }

                // Same position, but one side has no hash to compare: agreement on a position is not
                // agreement on the stream, so currency is not established.
                else -> {
                    SourceCurrency.UNKNOWN
                }
            }

        val registration =
            when {
                registrationVersion == null || provenance.registrationVersion == NOT_ESTABLISHED -> {
                    SourceCurrency.UNKNOWN
                }

                registrationVersion == provenance.registrationVersion -> {
                    SourceCurrency.CURRENT
                }

                else -> {
                    SourceCurrency.STALE
                }
            }

        return ProjectionFreshnessAssessment(
            audit = audit,
            registration = registration,
            note =
                "currency is established per source by a fresh authoritative read; a stored row never " +
                    "declares itself current, and one source being current says nothing about the other",
        )
    }
}

/** Per-source version zero means "not established": neither source can produce a zero. */
internal const val NOT_ESTABLISHED = 0L

internal const val FIRST_OBSERVED_POSITION = 1L

/**
 * The write side: what a materializer uses.
 *
 * Deliberately not a general repository. There is no delete, no update-in-place and no scan: a
 * projection is published for an identity or nothing is.
 */
internal interface GovernanceProjectionStore {
    /**
     * The stored snapshot for [identity], or `null` when none was ever published.
     *
     * Throws [UnsupportedProjectionFormatException] for a format this build does not write and
     * [ProjectionCorruptionException] for a row that contradicts its structure or digest: a caller
     * gets either a snapshot it can trust or a refusal, never a repaired one.
     */
    suspend fun load(identity: ProjectionIdentity): StoredGovernanceProjection?

    /**
     * Publishes [snapshot] if it is newer than what is stored for the same identity, and reports what
     * happened. Applying the same snapshot twice is [MaterializationOutcome.ALREADY_CURRENT].
     */
    suspend fun publish(snapshot: GovernanceProjectionSnapshot): MaterializationOutcome
}

/** The read side a consumer gets: loading, and nothing that can change what is stored. */
internal interface GovernanceProjectionReadModel {
    suspend fun load(identity: ProjectionIdentity): StoredGovernanceProjection?
}

/**
 * Read-only access to a store.
 *
 * A consumer holds this instead of the store itself, so "the read path cannot write" is a property of
 * the type it was handed rather than of the code that happens to call it.
 */
internal class ReadOnlyGovernanceProjections(
    private val store: GovernanceProjectionStore,
) : GovernanceProjectionReadModel {
    override suspend fun load(identity: ProjectionIdentity): StoredGovernanceProjection? = store.load(identity)
}

internal fun GovernanceProjectionStore.readOnly(): GovernanceProjectionReadModel = ReadOnlyGovernanceProjections(this)
