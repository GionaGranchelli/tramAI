package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseStoreTck

/**
 * Epic 8.1g: InMemoryWorkflowLeaseStore — the orchestration module's default
 * lease store — must satisfy the shared lease compatibility contract
 * (tramai-testing testFixtures). A fresh store + deterministic clock per
 * case; the store itself is the mutation target for the family.
 */
class InMemoryWorkflowLeaseStoreTckTest : WorkflowLeaseStoreTck() {

    override fun createStore(clock: MutableMillisClock): WorkflowLeaseStore =
        InMemoryWorkflowLeaseStore(clock)
}
