package dev.tramai.testing.persistence.lease

import dev.tramai.orchestration.WorkflowLease

/**
 * Epic 8.1g: fixtures for the shared
 * [dev.tramai.orchestration.WorkflowLeaseStore] and
 * [dev.tramai.orchestration.WorkflowLeaseCheckpointFence] compatibility
 * contracts. Deterministic only — explicit identities, fixed durations and
 * clock-controlled timestamps. No sleeps, no real clock.
 */
object WorkflowLeaseFixtures {

    const val T0: Long = 1_800_000_000_000L

    const val DURATION: Long = 1_000

    const val WORKFLOW_NAME: String = "invoice-review"

    const val WORKFLOW_ID: String = "run-001"

    const val OWNER: String = "worker-7"

    fun lease(
        workflowName: String = WORKFLOW_NAME,
        workflowId: String = WORKFLOW_ID,
        leaseId: String = "lease-1",
        ownerId: String = OWNER,
        checkpointRevision: Long? = 3,
        acquiredAtEpochMillis: Long = T0,
        expiresAtEpochMillis: Long = T0 + DURATION,
    ): WorkflowLease = WorkflowLease(
        workflowName = workflowName,
        workflowId = workflowId,
        leaseId = leaseId,
        ownerId = ownerId,
        checkpointRevision = checkpointRevision,
        acquiredAtEpochMillis = acquiredAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )
}
