package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.testing.persistence.approval.ApprovalStoreTck
import dev.tramai.testing.persistence.approval.ApprovalStoreTckHarness
import dev.tramai.testing.persistence.approval.MutableClock

/**
 * Epic 8.1a: InMemoryApprovalStore must satisfy the shared ApprovalStore
 * compatibility contract (tramai-testing testFixtures).
 */
class InMemoryApprovalStoreTckTest : ApprovalStoreTck() {

    override val harness = object : ApprovalStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalStore =
            InMemoryApprovalStore(clock = clock)
    }
}
