package dev.tramai.orchestration

import java.util.UUID

/**
 * Authority for workflow-lease capability generation (Epic 8.3d).
 *
 * Every newly-created [WorkflowLease.leaseId] originates from this source; no
 * lease store manufactures its own lease identity. Mirrors the engine
 * identity (8.3b2a) and step-attempt identity (8.3b2b) authorities: leaseId is
 * a fencing/capability token, and its generation is owned by exactly one
 * composition-boundary source.
 */
internal fun interface LeaseIdentitySource {
    fun newLeaseId(): String
}

internal object DefaultLeaseIdentitySource : LeaseIdentitySource {
    override fun newLeaseId(): String = UUID.randomUUID().toString()
}
