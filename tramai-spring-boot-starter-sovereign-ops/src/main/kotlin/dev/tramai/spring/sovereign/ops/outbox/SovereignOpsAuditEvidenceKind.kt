package dev.tramai.spring.sovereign.ops.outbox

/**
 * 0.7.6f: what an outbox record is evidence of.
 *
 * Persisted with the record, not derived at dispatch time, so an emitted event can never be labelled from
 * a guess. A record written before this field existed has no kind and is classified from its persisted
 * approval status by the reader (0.7.6f slice 4), never assumed here.
 *
 * The three kinds are the contract's whole vocabulary:
 * - [APPROVAL_APPROVED] / [APPROVAL_DENIED] -- a decision the decision plane made, carrying the approval
 *   status and version it produced.
 * - [AUTHORIZATION_REFUSAL] -- a refusal, which has no business transition, so it carries no approval
 *   status and is inserted directly in a dispatchable state.
 */
enum class SovereignOpsAuditEvidenceKind {
    APPROVAL_APPROVED,
    APPROVAL_DENIED,
    AUTHORIZATION_REFUSAL,
}
