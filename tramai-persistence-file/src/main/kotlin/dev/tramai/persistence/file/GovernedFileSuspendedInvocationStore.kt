package dev.tramai.persistence.file

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationStore

/**
 * Governed suspension capability for the file-backed store (0.7.1d).
 *
 * Composition, not a second durable authority: [createGoverned] forwards into the same
 * single atomic encrypted create the store already uses, so suspension metadata, the
 * replay envelope and the whole [GovernedRunIdentity] are one write to one file. There is
 * no interleaving window in which a suspension exists without its attribution, and no
 * second file to reconcile.
 *
 * [FileSuspendedInvocationStore] deliberately does not declare this interface: widening a
 * released type changes the analyzer identity it is baselined under, and unmasking
 * unrelated legacy debt to satisfy a new feature is not a trade this module should make.
 */
class GovernedFileSuspendedInvocationStore internal constructor(
    private val delegate: FileSuspendedInvocationStore,
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
