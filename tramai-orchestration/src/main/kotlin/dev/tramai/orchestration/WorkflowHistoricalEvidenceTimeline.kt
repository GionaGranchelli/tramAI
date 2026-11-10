package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore

/**
 * Historical evidence timeline for one workflow run (D7-F).
 *
 * Correlates durable checkpoint lifecycle state ([WorkflowLifecycleState]) and durable child
 * effect evidence ([WorkflowEffectEvidenceRecord]) to provide a read-only historical inspection
 * view over an executed or cancelled workflow run.
 *
 * Core invariants:
 * - Read-only: never invokes external transports, processes, tools, or workflow steps.
 * - Truthful: missing evidence is reported as gaps, never fabricated or synthesized.
 * - Conservative: committed admissions lacking child evidence are classified as unclassified/uncertain outcomes.
 */
internal data class WorkflowHistoricalEvidenceTimeline(
    val workflowName: String,
    val workflowId: String,
    val lifecycle: WorkflowLifecycleState?,
    val records: List<WorkflowEffectEvidenceRecord>,
    val admissionGaps: List<Long>,
    val requiresReconciliation: Boolean,
) {
    val isCancelled: Boolean get() = lifecycle != null

    val isUncertainCancellation: Boolean
        get() = lifecycle?.status == WorkflowLifecycleStatus.CANCELLED_UNCERTAIN

    val completedEffects: List<WorkflowEffectEvidenceRecord>
        get() = records.filter { it.state == WorkflowEffectEvidenceState.COMPLETED }

    val uncertainEffects: List<WorkflowEffectEvidenceRecord>
        get() = records.filter { it.state == WorkflowEffectEvidenceState.UNCERTAIN }

    val effectsAdmittedBeforeCancellation: List<WorkflowEffectEvidenceRecord>
        get() {
            val cancelledAt = lifecycle?.cancelledAtEpochMillis ?: return records
            return records.filter { it.createdAtEpochMillis <= cancelledAt }
        }
}

internal object WorkflowHistoricalEvidenceReader {
    suspend fun readTimeline(
        checkpointStore: WorkflowCheckpointStore,
        evidenceStore: WorkflowEffectEvidenceRecordStore,
        workflowName: String,
        workflowId: String,
        continuationStore: ApprovalContinuationStore? = null,
    ): WorkflowHistoricalEvidenceTimeline {
        val lifecycle = checkpointStore.lifecycle(workflowName, workflowId)
        val records =
            evidenceStore
                .listEvidence(workflowName, workflowId)
                .sortedBy { it.key.admissionSeq }

        val sequences = records.map { it.key.admissionSeq }
        val gaps = computeAdmissionGaps(sequences)
        val reconciliationRequired = checkReconciliationRequired(records, continuationStore)

        return WorkflowHistoricalEvidenceTimeline(
            workflowName = workflowName,
            workflowId = workflowId,
            lifecycle = lifecycle,
            records = records,
            admissionGaps = gaps,
            requiresReconciliation = reconciliationRequired,
        )
    }

    private fun computeAdmissionGaps(sequences: List<Long>): List<Long> {
        if (sequences.isEmpty()) return emptyList()
        val maxSeq = sequences.last()
        val presentSeqs = sequences.toSet()
        val gaps = mutableListOf<Long>()
        for (seq in 1L..maxSeq) {
            if (seq !in presentSeqs) {
                gaps.add(seq)
            }
        }
        return gaps
    }

    private suspend fun checkReconciliationRequired(
        records: List<WorkflowEffectEvidenceRecord>,
        continuationStore: ApprovalContinuationStore?,
    ): Boolean {
        if (continuationStore == null) return false
        return records.any { isUnreconciled(it, continuationStore) }
    }

    private suspend fun isUnreconciled(
        record: WorkflowEffectEvidenceRecord,
        store: ApprovalContinuationStore,
    ): Boolean {
        val parent = record.parent as? ParentProvenance.ApprovalContinuation
        if (parent == null || record.state != WorkflowEffectEvidenceState.COMPLETED) {
            return false
        }
        val continuation = store.get(parent.approvalId)
        return continuation != null && continuation.status != ApprovalContinuationStatus.COMPLETED
    }
}
