package dev.tramai.security.governance

import dev.tramai.core.coroutines.rethrowIfCancellation
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.CancellationException
import javax.sql.DataSource

/**
 * 0.7.4d — the durable projection store.
 *
 * A publish is one transaction: the snapshot header, its facts and its decision rows commit together
 * or not at all, so an interrupted write leaves the previous snapshot whole rather than a half-written
 * one that a later read would have to interpret. Nothing here retries, refreshes or repairs: a
 * rejected candidate is reported, not merged, and a corrupt row is refused, not upgraded.
 *
 * Out-of-order and concurrent publishes are ordered in SQL rather than in the caller. The ordering
 * pair is (audit head position, registration version), compared lexicographically: decision records
 * derive from the audit stream, so a candidate built from an older head is weaker evidence even when
 * registration has moved on. The pair orders snapshots for one key; it is not a claim that the two
 * sources were read at one instant, and [ReadConsistency] records that distinction in the row itself.
 */
internal class JdbcGovernanceProjectionStore(
    private val dataSource: DataSource,
) : GovernanceProjectionStore {
    override suspend fun load(identity: ProjectionIdentity): StoredGovernanceProjection? =
        withSafeJdbc {
            dataSource.connection.use { connection -> loadWithin(connection, identity) }
        }

    /**
     * Reading, with stored content mapped to a refusal.
     *
     * Everything read here is data, not a caller argument: when a contract guard rejects it, that is
     * corruption to report rather than an error to blame on the caller. The guard firing *before* this
     * wrap was a defect the corrupt-fact discriminator found, seeing a raw IllegalArgumentException
     * where the contract promises a refusal.
     */
    private fun loadWithin(
        connection: Connection,
        identity: ProjectionIdentity,
    ): StoredGovernanceProjection? =
        try {
            val header = selectHeader(connection, identity.key) ?: return null
            if (header.formatVersion != GOVERNANCE_READ_MODEL_FORMAT_VERSION) {
                throw UnsupportedProjectionFormatException(
                    "stored projection format ${header.formatVersion} is not " +
                        "$GOVERNANCE_READ_MODEL_FORMAT_VERSION",
                )
            }
            val snapshot =
                rebuildSnapshot(identity, header, selectFacts(connection, identity.key), selectDecisions(connection, identity.key))
            if (snapshot.semanticDigest != header.semanticDigest) {
                // The row disagrees with itself. Refusing is the only safe answer: repairing it would
                // be inventing content, and trusting it would be trusting a contradiction.
                throw ProjectionCorruptionException(
                    "stored projection content does not match its recorded digest",
                )
            }
            StoredGovernanceProjection(snapshot, header.materializedAt)
        } catch (e: IllegalArgumentException) {
            throw ProjectionCorruptionException("stored projection violates the contract: ${e.message}")
        }

    override suspend fun publish(snapshot: GovernanceProjectionSnapshot): MaterializationOutcome =
        withSafeJdbc {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                var primary: Exception? = null
                try {
                    val outcome = publishWithin(connection, snapshot)
                    connection.commit()
                    outcome
                } catch (e: Exception) {
                    primary = e
                    rollbackSuppressing(connection, e)
                    throw e
                } finally {
                    restoreAutoCommitSuppressing(connection, previousAutoCommit, primary)
                }
            }
        }

    private fun publishWithin(
        connection: Connection,
        snapshot: GovernanceProjectionSnapshot,
    ): MaterializationOutcome {
        val key = snapshot.identity.key
        if (upsertHeader(connection, snapshot)) {
            // Replacement is whole-row: the children of the previous snapshot are removed in the same
            // transaction, so no reader can observe a mixture of two snapshots.
            deleteChildren(connection, key)
            insertFacts(connection, snapshot)
            insertDecisions(connection, snapshot)
            return MaterializationOutcome.PUBLISHED
        }

        val stored =
            selectHeader(connection, key)
                ?: throw ProjectionCorruptionException("a stored projection vanished during publish")

        val comparison = compareOrdering(snapshot.ordering, stored.ordering)
        return when {
            comparison > 0L -> {
                // Storage refused a candidate that is newer than what it holds. That is a storage
                // defect, and guessing an outcome would hide it behind an authoritative-looking answer.
                throw ProjectionCorruptionException("storage refused a candidate newer than the stored snapshot")
            }

            comparison < 0L -> {
                MaterializationOutcome.REJECTED_STALE
            }

            snapshot.semanticDigest == stored.semanticDigest -> {
                MaterializationOutcome.ALREADY_CURRENT
            }

            // Same source versions, different content. One of the two is not what the sources say, and
            // arrival order is not evidence about which.
            else -> {
                MaterializationOutcome.REJECTED_CONFLICT
            }
        }
    }

    /**
     * The conditional upsert that decides whether this candidate may replace what is stored.
     *
     * Returns whether a row was written. The row comparison is the whole concurrency contract: equal
     * ordering with equal content is a no-op, and anything older never applies.
     */
    private fun upsertHeader(
        connection: Connection,
        snapshot: GovernanceProjectionSnapshot,
    ): Boolean =
        connection
            .prepareStatement(UPSERT_HEADER)
            .use { statement ->
                val provenance = snapshot.provenance
                statement.setString(1, snapshot.identity.key)
                statement.setString(2, snapshot.formatVersion)
                statement.setString(3, provenance.classificationRevision)
                statement.setString(4, snapshot.identity.workloadId)
                statement.setString(5, snapshot.identity.environmentId)
                statement.setString(6, snapshot.identity.deploymentId)
                statement.setString(7, snapshot.identity.workflowRunId)
                statement.setString(8, snapshot.identity.auditStreamId)
                statement.setLong(9, provenance.registrationVersion)
                statement.setLong(10, provenance.auditHeadPosition)
                statement.setString(11, provenance.auditHeadHash)
                statement.setString(12, snapshot.semanticDigest)
                statement.executeQuery().use { rows -> return rows.next() }
            }

    private fun deleteChildren(
        connection: Connection,
        key: String,
    ) {
        connection.prepareStatement(DELETE_FACTS).use {
            it.setString(1, key)
            it.executeUpdate()
        }
        connection.prepareStatement(DELETE_DECISIONS).use {
            it.setString(1, key)
            it.executeUpdate()
        }
    }

    private fun insertFacts(
        connection: Connection,
        snapshot: GovernanceProjectionSnapshot,
    ) {
        connection.prepareStatement(INSERT_FACT).use { statement ->
            factsOf(snapshot).forEach { (name, fact) ->
                statement.setString(1, snapshot.identity.key)
                statement.setString(2, name)
                statement.setString(3, fact.authority.name)
                statement.setString(4, fact.freshness.name)
                statement.setString(5, fact.established)
                statement.setString(6, GovernanceProjectionSnapshot.encodeLines(fact.mustNotInfer))
                statement.setBoolean(7, fact.valuePresent)
                statement.setNullableLong(8, fact.observedVersion)
                statement.setNullableLong(9, fact.sourceVersion)
                statement.setNullableLong(10, fact.watermark)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertDecisions(
        connection: Connection,
        snapshot: GovernanceProjectionSnapshot,
    ) {
        connection.prepareStatement(INSERT_DECISION).use { statement ->
            snapshot.decisions.sortedBy { it.kind.name }.forEach { decision ->
                statement.setString(1, snapshot.identity.key)
                statement.setString(2, decision.kind.name)
                statement.setNullableLong(3, decision.coverage.verifiedRange?.first)
                statement.setNullableLong(4, decision.coverage.verifiedRange?.last)
                statement.setString(5, decision.coverage.observedHeadHash)
                statement.setLong(6, decision.coverage.accountedPositions)
                statement.setString(7, GovernanceProjectionSnapshot.encodePositions(decision.coverage.unrecordedDecisionPositions))
                statement.setString(8, GovernanceProjectionSnapshot.encodePositions(decision.coverage.unclassifiedPositions))
                statement.setString(9, decision.coverage.classificationRevision)
                statement.setString(10, decision.completeness.name)
                statement.setInt(11, decision.duplicatesIgnored)
                statement.setInt(12, decision.staleIgnored)
                statement.setString(13, decision.latestDecision)
                statement.setString(14, decision.latestReasonCode)
                statement.setString(15, decision.latestBoundaryId)
                statement.setString(16, decision.latestBoundaryStrength)
                statement.setString(17, decision.latest.authority.name)
                statement.setString(18, decision.latest.freshness.name)
                statement.setString(19, decision.latest.established)
                statement.setString(20, GovernanceProjectionSnapshot.encodeLines(decision.latest.mustNotInfer))
                statement.setNullableLong(21, decision.latest.observedVersion)
                statement.setNullableLong(22, decision.latest.sourceVersion)
                statement.setNullableLong(23, decision.latest.watermark)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** Rebuilds the snapshot through its own constructors, so stored rows meet the same guards. */
    private fun rebuildSnapshot(
        identity: ProjectionIdentity,
        header: HeaderRow,
        facts: Map<String, SnapshotFact>,
        decisions: List<SnapshotDecision>,
    ): GovernanceProjectionSnapshot {
        val registration = facts[FACT_REGISTRATION] ?: corrupt("the registration fact row is missing")
        val declared = facts[FACT_DECLARED_IDENTITY] ?: corrupt("the declared identity fact row is missing")
        val telemetry = facts[FACT_TELEMETRY] ?: corrupt("the telemetry fact row is missing")
        val revision =
            header.classificationRevision.takeIf { it.isNotBlank() } ?: corrupt("the classification revision is missing")
        return try {
            GovernanceProjectionSnapshot(
                formatVersion = header.formatVersion,
                identity = identity,
                registration = registration,
                declaredRunIdentity = declared,
                telemetry = telemetry,
                decisions = decisions,
                provenance =
                    SourceProvenance(
                        registrationVersion = header.registrationVersion,
                        auditHeadPosition = header.auditHeadPosition,
                        auditHeadHash = header.auditHeadHash,
                        classificationRevision = revision,
                    ),
            )
        } catch (e: IllegalArgumentException) {
            // A guard rejected the stored content: that is corruption, not a caller error.
            throw ProjectionCorruptionException("stored projection violates the contract: ${e.message}")
        }
    }

    private fun selectHeader(
        connection: Connection,
        key: String,
    ): HeaderRow? =
        connection.prepareStatement(SELECT_HEADER).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    HeaderRow(
                        formatVersion = rows.getString("format_version"),
                        classificationRevision = rows.getString("classification_revision"),
                        registrationVersion = rows.getLong("registration_version"),
                        auditHeadPosition = rows.getLong("audit_head_position"),
                        auditHeadHash = rows.getString("audit_head_hash"),
                        semanticDigest = rows.getString("semantic_digest"),
                        materializedAt = rows.getTimestamp("materialized_at").toInstant(),
                    )
                }
            }
        }

    private fun selectFacts(
        connection: Connection,
        key: String,
    ): Map<String, SnapshotFact> =
        connection.prepareStatement(SELECT_FACTS).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val name = rows.getString("fact")
                        if (name in this) corrupt("duplicate fact row for $name")
                        put(name, rows.toSnapshotFact())
                    }
                }
            }
        }

    private fun selectDecisions(
        connection: Connection,
        key: String,
    ): List<SnapshotDecision> =
        connection.prepareStatement(SELECT_DECISIONS).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.toSnapshotDecision())
                    }
                }
            }
        }

    private fun ResultSet.toSnapshotFact(): SnapshotFact =
        SnapshotFact(
            authority = enumValue(getString("authority")),
            freshness = enumValue(getString("freshness")),
            established = getString("established"),
            mustNotInfer = GovernanceProjectionSnapshot.decodeLines(getString("must_not_infer")),
            valuePresent = getBoolean("value_present"),
            observedVersion = getNullableLong("observed_version"),
            sourceVersion = getNullableLong("source_version"),
            watermark = getNullableLong("watermark"),
        )

    private fun ResultSet.toSnapshotDecision(): SnapshotDecision {
        val first = getNullableLong("verified_range_first")
        val last = getNullableLong("verified_range_last")
        val range =
            when {
                first == null && last == null -> null
                first != null && last != null -> first..last
                else -> corrupt("a verified range must have both ends")
            }
        return SnapshotDecision(
            kind = enumValue(getString("kind")),
            completeness = enumValue(getString("completeness")),
            coverage =
                SnapshotCoverage(
                    verifiedRange = range,
                    observedHeadHash = getString("observed_head_hash"),
                    accountedPositions = getLong("accounted_positions"),
                    unrecordedDecisionPositions =
                        GovernanceProjectionSnapshot.decodePositions(getString("unrecorded_positions")),
                    unclassifiedPositions =
                        GovernanceProjectionSnapshot.decodePositions(getString("unclassified_positions")),
                    classificationRevision = getString("classification_revision"),
                ),
            latest =
                SnapshotFact(
                    authority = enumValue(getString("latest_authority")),
                    freshness = enumValue(getString("latest_freshness")),
                    established = getString("latest_established"),
                    mustNotInfer = GovernanceProjectionSnapshot.decodeLines(getString("latest_must_not_infer")),
                    valuePresent = getString("latest_decision") != null,
                    observedVersion = getNullableLong("latest_observed_version"),
                    sourceVersion = getNullableLong("latest_source_version"),
                    watermark = getNullableLong("latest_watermark"),
                ),
            latestDecision = getString("latest_decision"),
            latestReasonCode = getString("latest_reason_code"),
            latestBoundaryId = getString("latest_boundary_id"),
            latestBoundaryStrength = getString("latest_boundary_strength"),
            duplicatesIgnored = getInt("duplicates_ignored"),
            staleIgnored = getInt("stale_ignored"),
        )
    }

    private fun factsOf(snapshot: GovernanceProjectionSnapshot): List<Pair<String, SnapshotFact>> =
        listOf(
            FACT_REGISTRATION to snapshot.registration,
            FACT_DECLARED_IDENTITY to snapshot.declaredRunIdentity,
            FACT_TELEMETRY to snapshot.telemetry,
        )

    private fun compareOrdering(
        candidate: Pair<Long, Long>,
        stored: Pair<Long, Long>,
    ): Long =
        when {
            candidate.first != stored.first -> candidate.first - stored.first
            else -> candidate.second - stored.second
        }

    private fun rollbackSuppressing(
        connection: Connection,
        primary: Exception,
    ) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Exception) {
            primary.addSuppressed(rollbackFailure)
        }
    }

    private fun restoreAutoCommitSuppressing(
        connection: Connection,
        previousAutoCommit: Boolean,
        primary: Exception?,
    ) {
        try {
            connection.autoCommit = previousAutoCommit
        } catch (restoreFailure: Exception) {
            if (primary != null) primary.addSuppressed(restoreFailure) else throw restoreFailure
        }
    }

    private fun java.sql.PreparedStatement.setNullableLong(
        index: Int,
        value: Long?,
    ) {
        if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?): T =
        try {
            enumValueOf<T>(name ?: corrupt("a stored enum value is missing"))
        } catch (e: IllegalArgumentException) {
            throw ProjectionCorruptionException("a stored enum value is not recognized by this build")
        }

    private fun corrupt(message: String): Nothing = throw ProjectionCorruptionException(message)

    private class HeaderRow(
        val formatVersion: String,
        val classificationRevision: String,
        val registrationVersion: Long,
        val auditHeadPosition: Long,
        val auditHeadHash: String?,
        val semanticDigest: String,
        val materializedAt: java.time.Instant,
    ) {
        val ordering: Pair<Long, Long> get() = auditHeadPosition to registrationVersion
    }

    private companion object {
        const val FACT_REGISTRATION = "registration"
        const val FACT_DECLARED_IDENTITY = "declared_run_identity"
        const val FACT_TELEMETRY = "telemetry"

        val UPSERT_HEADER =
            """
            INSERT INTO governance_projection_snapshot (
                projection_key, format_version, classification_revision, workload_id, environment_id,
                deployment_id, workflow_run_id, audit_stream_id, registration_version,
                audit_head_position, audit_head_hash, semantic_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (projection_key) DO UPDATE SET
                format_version = EXCLUDED.format_version,
                classification_revision = EXCLUDED.classification_revision,
                workload_id = EXCLUDED.workload_id,
                environment_id = EXCLUDED.environment_id,
                deployment_id = EXCLUDED.deployment_id,
                workflow_run_id = EXCLUDED.workflow_run_id,
                audit_stream_id = EXCLUDED.audit_stream_id,
                registration_version = EXCLUDED.registration_version,
                audit_head_position = EXCLUDED.audit_head_position,
                audit_head_hash = EXCLUDED.audit_head_hash,
                semantic_digest = EXCLUDED.semantic_digest,
                materialized_at = now()
            WHERE (
                EXCLUDED.audit_head_position,
                EXCLUDED.registration_version
            ) > (
                governance_projection_snapshot.audit_head_position,
                governance_projection_snapshot.registration_version
            )
            RETURNING projection_key
            """.trimIndent()

        const val SELECT_HEADER =
            """
            SELECT format_version, classification_revision, registration_version, audit_head_position,
                   audit_head_hash, semantic_digest, materialized_at
            FROM governance_projection_snapshot
            WHERE projection_key = ?
            """

        const val DELETE_FACTS = "DELETE FROM governance_projection_fact WHERE projection_key = ?"
        const val DELETE_DECISIONS = "DELETE FROM governance_projection_decision WHERE projection_key = ?"

        const val INSERT_FACT =
            """
            INSERT INTO governance_projection_fact (
                projection_key, fact, authority, freshness, established, must_not_infer, value_present,
                observed_version, source_version, watermark
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """

        const val INSERT_DECISION =
            """
            INSERT INTO governance_projection_decision (
                projection_key, kind, verified_range_first, verified_range_last, observed_head_hash,
                accounted_positions, unrecorded_positions, unclassified_positions,
                classification_revision, completeness, duplicates_ignored, stale_ignored,
                latest_decision, latest_reason_code, latest_boundary_id, latest_boundary_strength,
                latest_authority, latest_freshness, latest_established, latest_must_not_infer,
                latest_observed_version, latest_source_version, latest_watermark
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """

        const val SELECT_FACTS =
            """
            SELECT fact, authority, freshness, established, must_not_infer, value_present,
                   observed_version, source_version, watermark
            FROM governance_projection_fact
            WHERE projection_key = ?
            """

        const val SELECT_DECISIONS =
            """
            SELECT kind, verified_range_first, verified_range_last, observed_head_hash,
                   accounted_positions, unrecorded_positions, unclassified_positions,
                   classification_revision, completeness, duplicates_ignored, stale_ignored,
                   latest_decision, latest_reason_code, latest_boundary_id, latest_boundary_strength,
                   latest_authority, latest_freshness, latest_established, latest_must_not_infer,
                   latest_observed_version, latest_source_version, latest_watermark
            FROM governance_projection_decision
            WHERE projection_key = ?
            """
    }
}

/**
 * Runs a JDBC block, keeping cancellation and contract failures intact and reporting anything else
 * without the query, table or vendor diagnostics that produced it.
 */
private inline fun <T> withSafeJdbc(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        e.rethrowIfCancellation()
        when (e) {
            is ProjectionCorruptionException,
            is UnsupportedProjectionFormatException,
            is IllegalArgumentException,
            -> throw e

            else -> throw GovernanceProjectionStoreException("the governance projection store operation failed", e)
        }
    }

/** A durable store failure with no SQL, schema or vendor detail attached. */
internal class GovernanceProjectionStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
