@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore

/**
 * 0.7.1d: governed capability over the released file outbox store.
 *
 * Composition rather than modification: [FileSovereignOpsAuditOutboxStore] keeps its released
 * interface list untouched and stays structurally pristine, while this wrapper adds governed append
 * and resolved governance reads on top of it.
 *
 * There is no second file and no identity sidecar. A governed record is the same single encrypted
 * atomic record as a legacy one, and the codec chooses V1 or V2 from the identity's presence, so the
 * durable authority stays in one place.
 *
 * Legacy behaviour is delegated transparently, so a caller holding this wrapper sees exactly the
 * released store contract.
 */
@ExperimentalTramaiInternalApi
class GovernedFileSovereignOpsAuditOutboxStore(
    private val delegate: FileSovereignOpsAuditOutboxStore,
) : SovereignOpsAuditOutboxStore by delegate,
    GovernedSovereignOpsAuditOutboxStore {
    override suspend fun appendGoverned(entry: GovernedSovereignOpsAuditOutboxRecord) {
        delegate.appendGoverned(entry)
    }

    override suspend fun governanceById(id: String): SovereignOpsAuditOutboxGovernance = delegate.governanceById(id)
}
