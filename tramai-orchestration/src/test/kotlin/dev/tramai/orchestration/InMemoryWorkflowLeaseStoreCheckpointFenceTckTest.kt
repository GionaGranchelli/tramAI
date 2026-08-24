package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseCheckpointFenceTck

/**
 * Epic 8.1g: InMemoryWorkflowLeaseStore must also satisfy the companion
 * WorkflowLeaseCheckpointFence compatibility contract (tramai-testing
 * testFixtures): the fence is the worker's ownership check + checkpoint
 * mutation, one atomic fencing operation.
 */
class InMemoryWorkflowLeaseStoreCheckpointFenceTckTest : WorkflowLeaseCheckpointFenceTck() {

    override fun newHarness(): Harness {
        val clock = MutableMillisClock()
        val leaseStore = InMemoryWorkflowLeaseStore(clock)
        return Harness(
            clock = clock,
            leaseStore = leaseStore,
            fence = leaseStore,
            checkpointStore = InMemoryWorkflowCheckpointStore(),
        )
    }
}
