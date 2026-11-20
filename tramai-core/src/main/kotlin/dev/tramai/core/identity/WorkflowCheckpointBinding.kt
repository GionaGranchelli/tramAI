package dev.tramai.core.identity

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The durable workflow checkpoint a governed execution belongs to: exactly the pair the
 * `WorkflowCheckpointStore` keys its rows by.
 *
 * [GovernedRunIdentity] answers "which governed run is this"; it deliberately does NOT answer "which checkpoint row
 * authorizes it". The row key additionally needs the workflow name, and the identity's `deployment.workloadId` is a
 * workload identity, not a workflow name — assuming the two are interchangeable would be a synthetic key. This type
 * carries the key explicitly, produced only where the workflow is actually in scope.
 *
 * It is transport, never authority: holding one grants nothing. The authority is the durable store that answers an
 * admission request against the row this binding names.
 */
data class WorkflowCheckpointBinding(
    val workflowName: String,
    val workflowId: String,
) {
    init {
        require(workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
    }
}

/**
 * How the provenance of a durable suspension was established. A missing binding is NOT evidence of standalone
 * execution: records written before bindings existed are indistinguishable from standalone ones, so the three cases
 * stay distinct and only [WORKFLOW_BOUND] carries authority.
 */
enum class SuspensionBindingProvenance {
    /** The suspension was created inside a workflow execution context and is bound to that checkpoint. */
    WORKFLOW_BOUND,

    /** The suspension declared, at creation time, that no workflow owns it. */
    STANDALONE,

    /** Written before bindings existed (or by a writer that stated nothing): provenance is unknown. */
    LEGACY_UNKNOWN,
}

/**
 * Execution-scoped TRANSPORT of the [WorkflowCheckpointBinding] for the current call.
 *
 * Installed by the workflow runner at the governed execution boundary, next to [GovernedRunScope], and read by
 * consumers that must bind durable state to the owning checkpoint — the approval suspension path is the first. Absent
 * means "no workflow is in scope", which is a legitimate state for a standalone engine invocation, not permission and
 * not proof.
 *
 * A [ThreadContextElement] for the same reason as [GovernedRunScope]: the blocking proxy path crosses a thread
 * boundary, and the binding must survive it without leaking into nested or parallel executions.
 */
@ExperimentalTramaiInternalApi
class WorkflowCheckpointBindingScope(
    val binding: WorkflowCheckpointBinding,
) : AbstractCoroutineContextElement(WorkflowCheckpointBindingScope),
    ThreadContextElement<WorkflowCheckpointBinding?> {
    override fun updateThreadContext(context: CoroutineContext): WorkflowCheckpointBinding? =
        Key.threadBridge.get().also { Key.threadBridge.set(binding) }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: WorkflowCheckpointBinding?,
    ) {
        if (oldState == null) Key.threadBridge.remove() else Key.threadBridge.set(oldState)
    }

    /** Correlation-key for [CoroutineContext] lookup. */
    @ExperimentalTramaiInternalApi
    companion object Key : CoroutineContext.Key<WorkflowCheckpointBindingScope> {
        private val threadBridge: ThreadLocal<WorkflowCheckpointBinding?> = ThreadLocal()

        /**
         * The binding in force for the current call: the coroutine context is authoritative, the thread bridge is the
         * transport fallback for frames that are not coroutines.
         */
        fun resolve(context: CoroutineContext): WorkflowCheckpointBinding? =
            context[WorkflowCheckpointBindingScope]?.binding ?: threadBridge.get()
    }
}
