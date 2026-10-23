package dev.tramai.security.governance.query

import dev.tramai.controlplane.WorkloadControlPlaneQueries
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.governance.JdbcGovernanceProjectionStore
import dev.tramai.security.governance.StoreBackedGovernanceQueryService
import javax.sql.DataSource

/*
 * 0.7.4e — public governance query contract: service, request, result, and classification enums.
 *
 * The query surface is a read-only window over a previously materialized governance projection. It
 * never mutates storage, never evaluates a permission, and never widens or drops the authority,
 * freshness, currency or completeness classifications that the underlying projection established.
 */

/**
 * Read-only query port over the durable governance projection read model.
 *
 * A query addresses a single workload/environment/deployment scope (and optional workflow run) and
 * returns a [GovernanceQueryResult] that distinguishes a valid projection ([GovernanceQueryResult.Found]),
 * a scope that has never been materialized ([GovernanceQueryResult.NotMaterialized]), an unrecognized
 * persisted format ([GovernanceQueryResult.UnsupportedFormat]), and a corrupt or contract-violating
 * stored row ([GovernanceQueryResult.InvalidStoredProjection]).
 *
 * Infrastructure and storage transport failures are not modeled as result variants: consistent with
 * the module's persistence convention, an unexpected backend or JDBC failure propagates as an
 * [IllegalStateException] with a sanitized message and without exposing SQL, schema or vendor detail.
 */
public fun interface GovernanceQueryService {
    /**
     * Queries the materialized governance projection for the scope named by [request].
     *
     * This call blocks the calling thread while reading from the projection store and any configured
     * fresh-observation ports. Blocking is deliberate so a Java consumer needs no coroutine machinery,
     * and a suspending variant is deliberately not part of this slice.
     *
     * @throws IllegalStateException if the underlying projection store or an optional fresh-observation
     *   read fails due to an unexpected infrastructure error.
     */
    public fun query(request: GovernanceQueryRequest): GovernanceQueryResult

    public companion object {
        /**
         * Creates a read-only JDBC-backed [GovernanceQueryService] over [dataSource].
         *
         * Schema expectation: [dataSource] is expected to already carry the governance projection read-model
         * schema (`V1__governance_projection_read_model.sql`). This factory does not migrate or create tables,
         * and querying never writes, rebuilds, deletes or refreshes stored rows.
         *
         * Optional fresh-observation ports:
         * - When [controlPlaneQueries] is omitted (`null`), registration currency cannot be compared against a
         *   fresh authoritative read and is reported as [SourceCurrency.UNKNOWN], never [SourceCurrency.CURRENT].
         * - When [auditStore] is omitted (`null`), audit currency cannot be compared against a fresh audit head
         *   and is reported as [SourceCurrency.UNKNOWN], never [SourceCurrency.CURRENT].
         *
         * Audit stream association and its limit: when [auditStore] is supplied, fresh audit-head observation
         * associates a run-scoped query (`workflowRunId != null`) with the audit stream named directly by
         * `workflowRunId` (matching `DefaultAuditStreamIdResolver` when the context carries a run id). Deployments
         * that customize audit stream resolution or multiplex multiple runs into a single stream cannot establish
         * fresh audit currency through this default association.
         *
         * Blocking behaviour: [GovernanceQueryService.query] on the returned service blocks the calling thread
         * while reading from [dataSource] and any configured observation ports.
         */
        @JvmStatic
        @JvmOverloads
        public fun jdbc(
            dataSource: DataSource,
            controlPlaneQueries: WorkloadControlPlaneQueries? = null,
            auditStore: AuditStore? = null,
        ): GovernanceQueryService =
            StoreBackedGovernanceQueryService(
                store = JdbcGovernanceProjectionStore(dataSource),
                controlPlaneQueries = controlPlaneQueries,
                auditStore = auditStore,
            )
    }
}

/**
 * Immutable scope selector for a governance projection query.
 *
 * Every identifier must be a non-blank, bounded safe token (`[A-Za-z0-9._:-]{1,200}`). Declared
 * configuration values or caller-supplied labels are not accepted here: the query addresses only the
 * canonical workload/environment/deployment scope and an optional workflow run identifier.
 */
public data class GovernanceQueryRequest
    @JvmOverloads
    constructor(
        public val workloadId: String,
        public val environmentId: String,
        public val deploymentId: String,
        public val workflowRunId: String? = null,
    ) {
        init {
            requireSafeIdentifier(workloadId, "workloadId")
            requireSafeIdentifier(environmentId, "environmentId")
            requireSafeIdentifier(deploymentId, "deploymentId")
            if (workflowRunId != null) {
                requireSafeIdentifier(workflowRunId, "workflowRunId")
            }
        }
    }

/**
 * The outcome of querying a materialized governance projection.
 *
 * A missing projection ([NotMaterialized]) is a distinct state from an unsupported storage format
 * ([UnsupportedFormat]) and from a corrupted or contract-violating row ([InvalidStoredProjection]),
 * so a consumer cannot mistake corruption or format drift for an unmaterialized workload.
 */
public sealed interface GovernanceQueryResult {
    /** A valid materialized projection was found and verified for the requested scope. */
    public data class Found(
        public val view: GovernanceProjectionView,
    ) : GovernanceQueryResult

    /** No projection has been materialized for the requested scope. */
    public data object NotMaterialized : GovernanceQueryResult

    /**
     * A stored row exists for the requested scope, but its format version is not supported by this
     * build and is refused rather than silently upgraded.
     */
    public data class UnsupportedFormat(
        public val reason: String,
    ) : GovernanceQueryResult {
        init {
            require(reason.isNotBlank()) { "an unsupported-format refusal must state a reason" }
        }
    }

    /**
     * A stored row exists for the requested scope, but its digest or content violates the projection
     * contract and is refused rather than partially returned.
     */
    public data class InvalidStoredProjection(
        public val reason: String,
    ) : GovernanceQueryResult {
        init {
            require(reason.isNotBlank()) { "an invalid-projection refusal must state a reason" }
        }
    }
}

/** The authority classification of a projected governance fact. */
public enum class FactAuthority {
    /** Read from the authoritative store that owns the fact. */
    AUTHORITATIVE_PERSISTED,

    /** A recorded policy or tool permission decision: attestation of authorization, never of execution. */
    POLICY_AUTHORIZATION_RECORD,

    /** Declared by a producer (identity, boundary or route) with unverified origin. */
    PRODUCER_DECLARED,

    /** A non-authoritative telemetry observation. Cannot establish a governance fact. */
    TELEMETRY,

    /**
     * The source was read and returned nothing for this fact.
     *
     * An empty read is evidence about the read, not about the world: emission may be optional and
     * coverage may be partial, so this never establishes that a decision did not occur.
     */
    NOT_OBSERVED,

    /**
     * The fact family has no live production producer in this repository (for example
     * [DecisionKind.PROVIDER_ROUTE]), so no read can cover it.
     */
    NOT_PRODUCED,
}

/** What a projected fact may claim about when it held. */
public enum class FactFreshness {
    /** Current per the owning authority at the recorded observed version. */
    CURRENT_AT_VERSION,

    /** A recorded point in the past, identified by a source version or sequence position. */
    HISTORICAL,

    /** Derived state with a known watermark. */
    EVENTUAL_KNOWN_LAG,

    /** Freshness is not established, or not established as current. */
    STALE_OR_UNKNOWN,

    /** No fact value is present. */
    ABSENT,
}

/** The decision families a governance projection reports. */
public enum class DecisionKind {
    POLICY_DECISION,
    TOOL_PERMISSION,

    /** Source-declared at best: no live engine producer exists for this family. */
    PROVIDER_ROUTE,
}

/**
 * What the projection may claim about the decision records it reports within a verified audit range.
 *
 * This is a claim about decision-record recognition, separate from [RawStreamCoverage], and is never
 * a claim that a decision did not occur.
 */
public enum class Completeness {
    /**
     * Every position in the verified range that this read was required to classify produced a record,
     * and the classification revision matches this build's vocabulary.
     */
    COMPLETE,

    /**
     * The verified range contains at least one position at a decision-producing boundary that recorded
     * a decision outcome without producing a recognized decision record.
     */
    GAPS_PRESENT,

    /**
     * No range was verified, the range holds an unclassified position, a record lacks an ordering
     * position, the record set is empty, or the snapshot's classification revision differs from this
     * build's revision.
     */
    UNKNOWN,
}

/** Whether one source's contribution to a stored projection still matches a fresh observation. */
public enum class SourceCurrency {
    /** A fresh authoritative observation agrees with what the snapshot recorded for this source. */
    CURRENT,

    /** A fresh authoritative observation disagrees: the snapshot is behind or diverged for this source. */
    STALE,

    /**
     * No comparable fresh observation of this source was available, or the snapshot never established
     * a version/hash for it. Never reported as [CURRENT].
     */
    UNKNOWN,
}

/** How the sources behind a materialized snapshot were read. */
public enum class ReadConsistency {
    /**
     * Registration and audit were read independently; their versions are recorded separately and do
     * not represent an atomic cross-store instant.
     */
    INDEPENDENT_SOURCES,
}

internal fun requireSafeIdentifier(
    value: String,
    fieldName: String,
) {
    require(value.isNotBlank() && SAFE_IDENTIFIER_REGEX.matches(value)) {
        "$fieldName must be a non-blank bounded identifier token"
    }
}

internal fun requireOptionalSafeToken(
    value: String?,
    fieldName: String,
) {
    if (value != null) {
        require(value.isNotBlank() && SAFE_IDENTIFIER_REGEX.matches(value)) {
            "$fieldName must be a non-blank bounded token when present"
        }
    }
}

private val SAFE_IDENTIFIER_REGEX = Regex("^[A-Za-z0-9._:-]{1,200}$")
