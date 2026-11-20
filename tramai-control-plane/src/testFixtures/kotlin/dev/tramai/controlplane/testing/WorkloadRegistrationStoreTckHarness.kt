package dev.tramai.controlplane.testing

import dev.tramai.controlplane.WorkloadRegistrationStore

/**
 * Storage-technology hook for the [WorkloadRegistrationStore] contract.
 *
 * The runner owns everything implementation-specific: datasources, schema,
 * store construction, cleanup. Every call to [createStore] must return a
 * FRESH, isolated store.
 */
interface WorkloadRegistrationStoreTckHarness {
    fun createStore(): WorkloadRegistrationStore

    suspend fun closeStore(store: WorkloadRegistrationStore) = Unit
}
