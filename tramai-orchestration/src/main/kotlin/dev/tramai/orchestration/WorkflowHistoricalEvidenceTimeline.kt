package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore

/**
 * Historical evidence timeline completeness classification (D7-F).
 */
internal enum class TimelineCompleteness {
    /** All committed admissions have corresponding child evidence records. */
    COMPLETE,

    /** One or more committed admission sequence slots lack child evidence records. */
    GAPS_DETECTED,

    /** The authoritative admission sequence counter cannot be derived; completeness cannot be guaranteed. */
    UNKNOWN,
}

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
    val completeness: TimelineCompleteness = TimelineCompleteness.COMPLETE,
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

        val authoritativeMaxSeq = resolveAuthoritativeAdmissionSequence(checkpointStore, workflowName, workflowId)
        val sequences = records.map { it.key.admissionSeq }
        val (gaps, completeness) = computeAdmissionGaps(sequences, authoritativeMaxSeq)
        val reconciliationRequired =
            checkReconciliationRequired(
                lifecycle = lifecycle,
                records = records,
                gaps = gaps,
                completeness = completeness,
                continuationStore = continuationStore,
            )

        return WorkflowHistoricalEvidenceTimeline(
            workflowName = workflowName,
            workflowId = workflowId,
            lifecycle = lifecycle,
            records = records,
            admissionGaps = gaps,
            requiresReconciliation = reconciliationRequired,
            completeness = completeness,
        )
    }

    private suspend fun resolveAuthoritativeAdmissionSequence(
        checkpointStore: WorkflowCheckpointStore,
        workflowName: String,
        workflowId: String,
    ): Long? =
        when (checkpointStore) {
            is JdbcWorkflowCheckpointStore -> checkpointStore.currentAdmissionSequence(workflowName, workflowId)
            is InMemoryWorkflowCheckpointStore -> checkpointStore.currentAdmissionSequence(workflowName, workflowId)
            else -> null
        }

    private fun computeAdmissionGaps(
        sequences: List<Long>,
        authoritativeMaxSeq: Long?,
    ): Pair<List<Long>, TimelineCompleteness> {
        if (authoritativeMaxSeq == null) {
            if (sequences.isEmpty()) {
                return Pair(emptyList(), TimelineCompleteness.UNKNOWN)
            }
            val maxSeq = sequences.last()
            val presentSeqs = sequences.toSet()
            val gaps = (1L..maxSeq).filter { it !in presentSeqs }
            return Pair(gaps, TimelineCompleteness.UNKNOWN)
        }

        if (authoritativeMaxSeq == 0L) {
            return Pair(emptyList(), TimelineCompleteness.COMPLETE)
        }

        val presentSeqs = sequences.toSet()
        val gaps = (1L..authoritativeMaxSeq).filter { it !in presentSeqs }
        val completeness =
            if (gaps.isEmpty()) TimelineCompleteness.COMPLETE else TimelineCompleteness.GAPS_DETECTED
        return Pair(gaps, completeness)
    }

    private suspend fun checkReconciliationRequired(
        lifecycle: WorkflowLifecycleState?,
        records: List<WorkflowEffectEvidenceRecord>,
        gaps: List<Long>,
        completeness: TimelineCompleteness,
        continuationStore: ApprovalContinuationStore?,
    ): Boolean {
        if (lifecycle?.status == WorkflowLifecycleStatus.CANCELLED_UNCERTAIN) {
            return true
        }
        if (gaps.isNotEmpty()) {
            return true
        }
        if (completeness == TimelineCompleteness.UNKNOWN && records.isNotEmpty()) {
            return true
        }
        if (records.any { isRecordUnresolved(it) }) {
            return true
        }
        return checkE9Continuations(records, continuationStore)
    }

    private fun isRecordUnresolved(record: WorkflowEffectEvidenceRecord): Boolean =
        when (record.state) {
            WorkflowEffectEvidenceState.UNCERTAIN -> true

            WorkflowEffectEvidenceState.ADMITTED,
            WorkflowEffectEvidenceState.DISPATCHED,
            -> true

            WorkflowEffectEvidenceState.COMPLETED -> false
        }

    private suspend fun checkE9Continuations(
        records: List<WorkflowEffectEvidenceRecord>,
        continuationStore: ApprovalContinuationStore?,
    ): Boolean {
        val e9Records = records.filter { it.parent is ParentProvenance.ApprovalContinuation }
        if (e9Records.isEmpty()) return false
        if (continuationStore == null) return true

        for (record in e9Records) {
            val parent = record.parent as ParentProvenance.ApprovalContinuation
            val continuation = continuationStore.get(parent.approvalId) ?: return true
            if (continuation.status != ApprovalContinuationStatus.COMPLETED) {
                return true
            }
        }
        return false
    }
}
