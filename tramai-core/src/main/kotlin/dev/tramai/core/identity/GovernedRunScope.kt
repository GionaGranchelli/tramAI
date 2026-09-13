package dev.tramai.core.identity

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Execution-scoped TRANSPORT of the canonical [GovernedRunIdentity] (0.7.1d).
 *
 * Some subsystems are invoked from inside application code the framework does not own:
 * an AI step's `invoke` lambda calls the engine, and the engine's invocation signature
 * carries no run identity. Without a carrier the engine would mint its own workflow run
 * id and one governed execution would end up with two competing run identities.
 *
 * This type is transport, never authority:
 *
 * - the framework installs it at the governed execution boundary;
 * - consumers that must not invent identity read it and fail closed when it is absent;
 * - it is never inferred, reconstructed or regenerated from runtime state — durable
 *   resume paths rebuild the identity from their persisted witness and install it.
 *
 * It is a [ThreadContextElement], so it also bridges the thread boundary that
 * `runBlocking` creates for the blocking proxy path: [updateThreadContext] installs the
 * identity for the duration of the coroutine on that thread and
 * [restoreThreadContext] restores the previous value — including on cancellation and
 * exceptions, which is why the bridge is implemented through the sanctioned mechanism
 * instead of a hand-managed ThreadLocal. Nested and sequential executions therefore
 * cannot leak identity into each other, and parallel executions on different threads
 * have independent bridges.
 *
 * Invariant: [identity] is the identity of the governed execution the current call
 * belongs to. Marked with the internal API marker on purpose: it is a plumbing type,
 * not application vocabulary.
 */
@ExperimentalTramaiInternalApi
class GovernedRunScope(
    val identity: GovernedRunIdentity,
) : AbstractCoroutineContextElement(GovernedRunScope),
    ThreadContextElement<GovernedRunIdentity?> {
    override fun updateThreadContext(context: CoroutineContext): GovernedRunIdentity? =
        Key.threadBridge.get().also { Key.threadBridge.set(identity) }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: GovernedRunIdentity?,
    ) {
        if (oldState == null) Key.threadBridge.remove() else Key.threadBridge.set(oldState)
    }

    /** Correlation-key for [CoroutineContext] lookup. */
    @ExperimentalTramaiInternalApi
    companion object Key : CoroutineContext.Key<GovernedRunScope> {
        private val threadBridge: ThreadLocal<GovernedRunIdentity?> = ThreadLocal()

        /**
         * The identity in force for the current call: the coroutine context is
         * authoritative, and the thread bridge is the transport fallback that covers
         * call frames that are not coroutines (the blocking proxy path).
         *
         * Returns null — never a regenerated identity — when no governed execution is in
         * force.
         */
        fun resolve(context: CoroutineContext): GovernedRunIdentity? = context[GovernedRunScope]?.identity ?: threadBridge.get()

        /**
         * Identity of the governed execution currently running on THIS thread, if any.
         *
         * Used by the invoking adapter to capture attribution before it starts its own
         * `runBlocking` context, and by tests to prove the bridge does not leak.
         */
        fun currentThreadIdentity(): GovernedRunIdentity? = threadBridge.get()
    }
}
