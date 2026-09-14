@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SuspendedInvocationStore

/**
 * 0.7.1d: provenance of the run behind an approval decision, resolved from record existence first.
 *
 * A `null` governed identity is ambiguous on its own — the contract uses it for a legacy suspension,
 * and stores also return it when no suspension exists at all. So provenance is classified by asking
 * whether the suspension record exists before asking for its identity, and an absent record is never
 * silently interpreted as legacy: whether that is acceptable depends on whether the calling path
 * requires a suspension, which is the caller's decision, not this lookup's.
 */
internal sealed interface ApprovalRunAttribution {
    /** A suspension exists and carries the complete canonical identity. */
    data class Governed(
        val identity: GovernedRunIdentity,
    ) : ApprovalRunAttribution

    /** A suspension exists with no governed attribution: a genuine V1/legacy suspension. */
    data object LegacySuspension : ApprovalRunAttribution

    /** No suspension exists. Provenance is unresolved; the caller must decide the policy. */
    data object NoSuspension : ApprovalRunAttribution
}

/**
 * Resolves [ApprovalRunAttribution] for [approvalId].
 *
 * Record existence is checked first because [SuspendedInvocationStore.get] is the only call that can
 * distinguish "legacy suspension" from "no suspension". The governed identity is read from the same
 * store's governed capability, which is the canonical authority — never reconstructed from
 * `ApprovalRequest.binding.workflowRunId`, which carries a run identifier and not an authority.
 */
internal suspend fun resolveApprovalRunAttribution(
    suspendedInvocations: SuspendedInvocationStore,
    approvalId: String,
): ApprovalRunAttribution =
    if (suspendedInvocations.get(approvalId) == null) {
        ApprovalRunAttribution.NoSuspension
    } else {
        val identity = (suspendedInvocations as? GovernedSuspendedInvocationStore)?.governedRunIdentity(approvalId)
        if (identity == null) {
            ApprovalRunAttribution.LegacySuspension
        } else {
            ApprovalRunAttribution.Governed(identity)
        }
    }
