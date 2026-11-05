package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that carries the active [WorkflowEffectEvidenceRecordStore]
 * into the workflow execution scope.
 */
internal class WorkflowEffectEvidenceStoreScope(
    val store: WorkflowEffectEvidenceRecordStore?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkflowEffectEvidenceStoreScope>
}

/**
 * Reads the active [WorkflowEffectEvidenceRecordStore] from the active coroutine context,
 * or null if evidence tracking is not configured for the run.
 */
internal suspend fun currentEffectEvidenceStore(): WorkflowEffectEvidenceRecordStore? =
    currentCoroutineContext()[WorkflowEffectEvidenceStoreScope]?.store
