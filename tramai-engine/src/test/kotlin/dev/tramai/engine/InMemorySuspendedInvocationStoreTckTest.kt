package dev.tramai.engine

import dev.tramai.testing.persistence.engine.SuspendedInvocationStoreTck

/**
 * Epic 8.1c: the engine's default in-memory store must satisfy the shared
 * SuspendedInvocationStore compatibility contract (tramai-testing
 * testFixtures). It is a production implementation — TramaiEngine defaults
 * every constructor to it — so it is enrolled like any other store, and it
 * doubles as the mutation target for the contract's mutation evidence.
 */
class InMemorySuspendedInvocationStoreTckTest : SuspendedInvocationStoreTck() {
    override fun createStore(): SuspendedInvocationStore = inMemorySuspendedInvocationStore()
}
