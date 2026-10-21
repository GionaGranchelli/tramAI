package dev.tramai.core.observation.attempt

import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.OperationObserver

/**
 * An [OperationObserver] that can be bound to the invocation an attempt belongs to (Epic 0.7.5b).
 *
 * The engine knows the invocation identity; a recording observer needs it to key an attempt record.
 * Identity is not carried on [dev.tramai.core.observation.OperationCallContext] because that type is
 * stable API: adding a field would break `copy` and `componentN` for compiled consumers. Asking for
 * a bound observer keeps the bridge additive and keeps persistence out of the engine.
 *
 * An observer that cannot attribute attempts simply does not implement this interface, and the
 * engine uses it unchanged.
 */
interface AttemptIdentifyingObserver : OperationObserver {
    /** Returns an observer bound to [identity] for the next attempt, or this observer if unbound. */
    fun forAttempt(identity: AttemptIdentity): OperationObserver
}
