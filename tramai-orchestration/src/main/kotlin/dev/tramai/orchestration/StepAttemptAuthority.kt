package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Authoritative persisted attempt identity for one step execution.
 *
 * Returned by [StepAttemptAuthority.beginAttempt] only after the attempt has been
 * recorded to durable storage.
 */
internal data class PersistedStepAttempt(
    val workflowName: String,
    val workflowId: String,
    val stepName: String,
    val attemptId: String,
)

/**
 * Internal orchestration authority for establishing synchronous step-attempt identity.
 *
 * Invoked by [WorkflowStepExecutor] before entering a top-level governed step.
 * Returns null for ungoverned executions or untracked steps.
 */
internal fun interface StepAttemptAuthority {
    suspend fun beginAttempt(
        workflowName: String,
        workflowId: String,
        stepName: String,
    ): PersistedStepAttempt?
}

/**
 * Coroutine context element that carries the authoritative [PersistedStepAttempt]
 * established for the enclosing step execution.
 */
internal class StepAttemptScope(
    val attempt: PersistedStepAttempt,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StepAttemptScope>
}

/**
 * Coroutine context element that carries the active [StepAttemptAuthority] capability
 * into the workflow runner and step executor.
 */
internal class StepAttemptAuthorityScope(
    val authority: StepAttemptAuthority?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StepAttemptAuthorityScope>
}

/**
 * Reads the authoritative [PersistedStepAttempt] from the active coroutine context,
 * or null if execution is running outside a governed step attempt.
 */
internal suspend fun currentStepAttempt(): PersistedStepAttempt? = currentCoroutineContext()[StepAttemptScope]?.attempt
