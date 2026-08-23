package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.testing.persistence.approval.MutableClock

/**
 * Storage-technology hook for the ApprovalContinuationStore TCK.
 *
 * The runner owns everything implementation-specific: temp directories,
 * encryption keys, codecs, datasources, table creation, store construction,
 * cleanup. Each call to [createStore] must return a FRESH, isolated store
 * wired to the given [MutableClock] (fixed at construction; advanced only by
 * the TCK) — previous cases' records must never leak into the next case.
 */
interface ApprovalContinuationStoreTckHarness {

    fun createStore(clock: MutableClock): ApprovalContinuationStore

    suspend fun closeStore(store: ApprovalContinuationStore) = Unit
}
