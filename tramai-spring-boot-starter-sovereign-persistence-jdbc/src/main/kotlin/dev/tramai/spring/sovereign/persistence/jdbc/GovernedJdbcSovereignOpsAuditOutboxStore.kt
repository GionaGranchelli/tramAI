@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore

/**
 * 0.7.1d: governed capability over the released JDBC outbox store.
 *
 * Composition rather than modification, for the same reason as the suspension wrapper:
 * [JdbcSovereignOpsAuditOutboxStore] keeps its released interface list untouched and stays baselined
 * as it is.
 *
 * There is no side table and no identity column. A governed record is one row of `audit_outbox` whose
 * encrypted payload is V2, written in the same transaction as the row itself, so a crash can never
 * leave half a governed record and the attribution is never promoted into plaintext columns.
 *
 * Legacy behaviour is delegated transparently, so a caller holding this wrapper sees exactly the
 * released store contract.
 */
@ExperimentalTramaiInternalApi
class GovernedJdbcSovereignOpsAuditOutboxStore(
    private val delegate: JdbcSovereignOpsAuditOutboxStore,
) : SovereignOpsAuditOutboxStore by delegate,
    GovernedSovereignOpsAuditOutboxStore {
    override suspend fun appendGoverned(entry: GovernedSovereignOpsAuditOutboxRecord) {
        delegate.appendGoverned(entry)
    }

    override suspend fun governanceById(id: String): SovereignOpsAuditOutboxGovernance = delegate.governanceById(id)
}
