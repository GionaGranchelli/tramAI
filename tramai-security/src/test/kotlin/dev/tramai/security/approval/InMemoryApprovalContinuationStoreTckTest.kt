package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.testing.persistence.approval.MutableClock
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTck
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTckHarness

/**
 * Epic 8.1b: InMemoryApprovalContinuationStore must satisfy the shared
 * ApprovalContinuationStore compatibility contract (tramai-testing
 * testFixtures).
 */
class InMemoryApprovalContinuationStoreTckTest : ApprovalContinuationStoreTck() {

    override val harness = object : ApprovalContinuationStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalContinuationStore =
            InMemoryApprovalContinuationStore(clock = clock)
    }
}
