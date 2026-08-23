package dev.tramai.testing.persistence.approval

import dev.tramai.core.approval.ApprovalStore

/**
 * Storage-technology hook for the ApprovalStore TCK.
 *
 * The runner owns everything implementation-specific: temp directories,
 * encryption keys, datasources, table creation, store construction, cleanup.
 * Each call to [createStore] must return a FRESH, isolated store wired to the
 * given [MutableClock] (fixed at construction; advanced only by the TCK).
 */
interface ApprovalStoreTckHarness {

    fun createStore(clock: MutableClock): ApprovalStore

    suspend fun closeStore(store: ApprovalStore) = Unit
}
