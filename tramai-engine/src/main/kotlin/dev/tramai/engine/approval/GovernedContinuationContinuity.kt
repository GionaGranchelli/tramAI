package dev.tramai.engine.approval

import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.GovernedRunIdentity

/**
 * Continuity precondition for an approval continuation (0.7.1d).
 *
 * The persisted suspension record is the AUTHORITY for a standalone continuation. A
 * caller-supplied [GovernedRunIdentity] (the governed scope the resume runs inside, when
 * there is one) is only a consistency precondition — never replacement authority, and
 * never a second source of truth to reconcile against the record.
 *
 * | persisted | requested | outcome |
 * |---|---|---|
 * | none | none | legacy suspension, legacy behaviour |
 * | none | governed | reject: cannot prove a legacy suspension belongs to a governed run |
 * | governed | none | recover the persisted identity (standalone resume) |
 * | governed | same | proceed |
 * | governed | different | reject before claim/execution: attribution was substituted |
 *
 * The whole identity is compared, not the run id: a run id can be replayed against
 * another workload, configuration, environment or deployment.
 */
internal fun requireGovernedContinuity(
    approvalId: String,
    persisted: GovernedRunIdentity?,
    requested: GovernedRunIdentity?,
) {
    when {
        persisted == null && requested == null -> Unit

        persisted == null -> throw GovernedRunContinuityException(
            "Approval '$approvalId' was suspended by an ungoverned execution and cannot be " +
                "resumed from governed run '${requested?.runId}'",
        )

        requested == null -> Unit

        persisted != requested -> throw GovernedRunContinuityException(
            "Approval '$approvalId' governed run attribution was substituted: " +
                "persisted=$persisted requested=$requested",
        )

        else -> Unit
    }
}
