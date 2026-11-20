package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.GovernedApprovalStore

/**
 * Governed capability for the JDBC-backed approval store (0.7.1d1).
 *
 * Composition, not a second durable authority: every method forwards to the same `approvals` row, so
 * the reserved-key encoding, the shared attribution codec and the single-write atomicity live in
 * exactly one place. See [dev.tramai.persistence.file.GovernedFileApprovalStore] for the rationale on
 * exposing the capability as a wrapper rather than widening the store itself.
 */
class GovernedJdbcApprovalStore internal constructor(
    private val delegate: JdbcApprovalStore,
) : ApprovalStore by delegate,
    GovernedApprovalStore {
    override suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution.Governed,
    ): ApprovalRequest = delegate.createGovernedApproval(request, attribution)

    override suspend fun attributionOf(approvalId: String): ApprovalRunAttribution = delegate.attributionOf(approvalId)
}

/** Wraps this approval store with the governed capability; see [GovernedJdbcApprovalStore]. */
fun JdbcApprovalStore.asGovernedApprovalStore(): GovernedApprovalStore = GovernedJdbcApprovalStore(this)
