package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore

/**
 * Additive durable capability: governed approvals (0.7.1d1).
 *
 * Deliberately a separate interface rather than new methods on [ApprovalStore]: third parties
 * implement that SPI, and adding a required method would break them. A governed approval therefore
 * REQUIRES this capability and fails closed when the configured store does not provide it — before
 * any approval or continuation state is durably created. The degradation this prevents is "looks
 * governed until the first approval boundary, then silently continues with run-id-only semantics".
 *
 * Implementations must persist the approval and its attribution snapshot as ONE durable record (one
 * transaction, one file write): two independently written records could leave a governed approval
 * carrying only partial attribution after a crash.
 *
 * The attribution is a framework-owned immutable snapshot, never a second identity authority: the
 * canonical full governed identity of an active suspension is the governed suspension record, and
 * wherever both exist they must agree exactly.
 */
public interface GovernedApprovalStore : ApprovalStore {
    /**
     * Create a governed approval carrying the canonical attribution snapshot.
     *
     * @throws IllegalArgumentException when the attribution's run id does not match the request
     *   binding's `workflowRunId` — a caller must not be able to nominate run A with identity B.
     */
    public suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution.Governed,
    ): ApprovalRequest

    /**
     * Attribution snapshot of an existing approval, decoded from durable state.
     *
     * Returns [ApprovalRunAttribution.Ungoverned] for a legacy approval with no reserved keys.
     *
     * @throws ApprovalAttributionCorruptionException when the persisted set is partial or unusable.
     * @throws dev.tramai.core.exception.ApprovalStoreNotFoundException when no such approval exists.
     */
    public suspend fun approvalAttribution(approvalId: String): ApprovalRunAttribution
}

/**
 * Rejects a governed creation whose attribution describes a different run than the request binding.
 *
 * The run id has exactly one durable source, `ApprovalBinding.workflowRunId`; the attribution must
 * describe the same run, so "run A + workload/configuration/deployment B" is unrepresentable.
 */
public fun requireAttributionMatchesBinding(
    request: ApprovalRequest,
    attribution: ApprovalRunAttribution,
) {
    val identity = (attribution as? ApprovalRunAttribution.Governed)?.identity ?: return
    require(identity.runId.value == request.binding.workflowRunId) {
        "Governed approval attribution is inconsistent with its binding: identity runId " +
            "'${identity.runId.value}' != binding workflowRunId '${request.binding.workflowRunId}'"
    }
}
