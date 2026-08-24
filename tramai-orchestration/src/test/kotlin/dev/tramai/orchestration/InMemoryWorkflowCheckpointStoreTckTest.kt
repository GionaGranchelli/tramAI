package dev.tramai.orchestration

import dev.tramai.testing.persistence.checkpoint.WorkflowCheckpointStoreTck

/**
 * Epic 8.1f: InMemoryWorkflowCheckpointStore — the orchestration module's
 * default checkpoint store — must satisfy the shared checkpoint
 * compatibility contract (tramai-testing testFixtures). A fresh store per
 * case gives full isolation; the store itself is the mutation target for
 * the family.
 */
class InMemoryWorkflowCheckpointStoreTckTest : WorkflowCheckpointStoreTck() {

    override fun createStore(): WorkflowCheckpointStore = InMemoryWorkflowCheckpointStore()
}
