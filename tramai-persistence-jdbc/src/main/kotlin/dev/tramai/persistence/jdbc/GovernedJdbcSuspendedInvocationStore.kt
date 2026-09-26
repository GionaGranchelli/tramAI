package dev.tramai.persistence.jdbc

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationStore

/**
 * Governed suspension capability for the JDBC store (0.7.1d).
 *
 * Composition, not a second durable authority: [createGoverned] forwards into the same
 * single-row insert the store already uses, with the whole [GovernedRunIdentity] inside
 * that row's encrypted payload. One row, one transaction — a crash can never leave a
 * suspension without its attribution, and attribution is never promoted into plaintext
 * columns.
 *
 * [JdbcSuspendedInvocationStore] deliberately does not declare this interface: widening a
 * released type changes the analyzer identity it is baselined under.
 */
class GovernedJdbcSuspendedInvocationStore(
    private val delegate: JdbcSuspendedInvocationStore,
) : SuspendedInvocationStore by delegate,
    GovernedSuspendedInvocationStore {
    override suspend fun createGoverned(
        suspended: GovernedSuspendedInvocation,
        replayEnvelope: SensitiveReplayEnvelope,
    ) = delegate.createGoverned(suspended, replayEnvelope)

    override suspend fun governedRunIdentity(approvalId: String): GovernedRunIdentity? {
        val identity = delegate.governedRunIdentity(approvalId)
        return identity
    }
}
