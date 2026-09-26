package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.GovernedApprovalStore

/**
 * Governed capability for the file-backed approval store (0.7.1d1).
 *
 * Composition, not a second durable authority: every method forwards to the same encrypted record
 * store, so V1/V2 selection, atomicity and schema validation live in exactly one place. The governed
 * operations are exposed here rather than by widening [FileApprovalStore] itself, because storing
 * attribution does not change what an approval store IS — a store without this wrapper simply has no
 * governed capability, and a governed run fails closed against it.
 */
class GovernedFileApprovalStore internal constructor(
    private val delegate: FileApprovalStore,
) : ApprovalStore by delegate,
    GovernedApprovalStore {
    override suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution.Governed,
    ): ApprovalRequest = delegate.createGovernedApproval(request, attribution)

    override suspend fun attributionOf(approvalId: String): ApprovalRunAttribution = delegate.attributionOf(approvalId)
}

/** Wraps this store with the governed approval capability; see [GovernedFileApprovalStore]. */
fun ApprovalStore.asGovernedApprovalStore(): GovernedApprovalStore =
    when (this) {
        is FileApprovalStore -> GovernedFileApprovalStore(this)

        else -> throw IllegalArgumentException(
            "Approval store '${this::class.simpleName}' does not support governed attribution; " +
                "a governed run must fail closed rather than persist an un-attributed approval",
        )
    }
