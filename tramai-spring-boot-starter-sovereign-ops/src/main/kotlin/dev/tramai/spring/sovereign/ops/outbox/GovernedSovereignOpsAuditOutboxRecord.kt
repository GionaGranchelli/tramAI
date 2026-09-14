package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi

/**
 * 0.7.1d: additive governed capability for the sovereign-ops audit outbox.
 *
 * The legacy [SovereignOpsAuditOutboxRecord] carries only `workflowRunId`, which is a run
 * identifier and not an authority. A governed approval decision must therefore persist the
 * complete canonical [GovernedRunIdentity] with the ordinary outbox state in one logical durable
 * record, or fail closed.
 *
 * This mirrors the suspension precedent: the legacy record and its constructor stay untouched, and
 * the governed form composes them.
 */
@ExperimentalTramaiInternalApi
data class GovernedSovereignOpsAuditOutboxRecord(
    val record: SovereignOpsAuditOutboxRecord,
    val runIdentity: GovernedRunIdentity,
) {
    init {
        require(runIdentity.runId.value == record.workflowRunId) {
            "governed outbox attribution must match the ordinary record: " +
                "'${record.workflowRunId}' != canonical runId '${runIdentity.runId.value}'"
        }
    }
}

/**
 * Additive durable capability: governed outbox records (0.7.1d).
 *
 * A store that does not implement this capability cannot hold governed attribution, so governed
 * approval mutations must fail closed before the approval state changes rather than silently
 * degrading to run-id-only attribution.
 *
 * Every status transition on a governed record must retain [GovernedSovereignOpsAuditOutboxRecord.runIdentity]
 * exactly: transitions address records by identifier and status, so they must never carry identity
 * and must never be able to drop it.
 */
@ExperimentalTramaiInternalApi
interface GovernedSovereignOpsAuditOutboxStore {
    /**
     * Appends a governed record in `PREPARED` state, persisting the ordinary outbox fields and the
     * complete canonical identity in one logical durable write.
     */
    suspend fun appendGoverned(governed: GovernedSovereignOpsAuditOutboxRecord): GovernedSovereignOpsAuditOutboxRecord

    /**
     * Reads the governed form of a record, or `null` when the record does not exist.
     *
     * A record that exists without governed attribution is legacy V1 and is reported as such by
     * [SovereignOpsAuditOutboxGovernance] rather than being mistaken for corruption here.
     */
    suspend fun findGovernedById(outboxId: String): GovernedSovereignOpsAuditOutboxRecord?
}

/**
 * Classification of a record's durable provenance, resolved from record existence first.
 *
 * `null` identity is ambiguous on its own: it means legacy for a record that exists, and it says
 * nothing at all for a record that does not. Callers must not read provenance off the identity
 * lookup alone, and must not treat an absent record as legacy.
 */
@ExperimentalTramaiInternalApi
sealed interface SovereignOpsAuditOutboxGovernance {
    data class Governed(
        val record: GovernedSovereignOpsAuditOutboxRecord,
    ) : SovereignOpsAuditOutboxGovernance

    data class Legacy(
        val record: SovereignOpsAuditOutboxRecord,
    ) : SovereignOpsAuditOutboxGovernance

    data object NoRecord : SovereignOpsAuditOutboxGovernance
}
