package dev.tramai.controlplane

import dev.tramai.controlplane.testing.WorkloadRegistrationStoreTck
import dev.tramai.controlplane.testing.WorkloadRegistrationStoreTckHarness

/**
 * The InMemory reference implementation must satisfy the shared
 * [WorkloadRegistrationStore] contract.
 */
class InMemoryWorkloadRegistrationStoreTckTest : WorkloadRegistrationStoreTck() {
    override val harness =
        object : WorkloadRegistrationStoreTckHarness {
            override fun createStore(): WorkloadRegistrationStore = InMemoryWorkloadRegistrationStore()
        }
}
