package dev.tramai.core.identity

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Execution-scoped carrier of the canonical [GovernedRunIdentity] (0.7.1d).
 *
 * Some subsystems are invoked from *inside* application code that the framework does
 * not own — an AI step's `invoke` lambda calls the engine, and the engine's
 * invocation signature carries no run identity. Without a carrier the engine would
 * have to mint its own workflow run id, and one governed execution would end up with
 * two competing run identities.
 *
 * The framework establishes this scope exactly once, at the governed execution
 * boundary, and the consumers that must not invent identity read it there:
 *
 * - `tramai-engine`: `EngineExecutionIdentity.workflowRunId` uses
 *   [identity]'s run id and does NOT sample its own run id;
 * - `tramai-control-plane` / evidence boundaries: attribution is read, never
 *   re-derived.
 *
 * Scope rules:
 * - it describes the execution the current call belongs to, so it must never be
 *   created by application code to "relabel" a run;
 * - when absent, everything behaves exactly as before (legacy/ungoverned execution
 *   keeps generating a workflow run id);
 * - the scope carries the whole identity, not just the run id: comparing run ids
 *   alone cannot detect a workload/configuration/environment/deployment
 *   substitution.
 *
 * Invariant: [identity]'s run id is the run id of the enclosing governed execution.
 * This type never generates, rewrites or reinterprets identity.
 */
class GovernedRunScope(
    val identity: GovernedRunIdentity,
) : AbstractCoroutineContextElement(GovernedRunScope) {
    /** Correlation-key for [CoroutineContext] lookup. */
    companion object Key : CoroutineContext.Key<GovernedRunScope>

    /** Run id of the enclosing governed execution — the canonical one. */
    val runId: String get() = identity.runId.value
}
