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

    /** Evidence sequences are out-of-bounds or conflict with the authoritative checkpoint admission counter. */
    INTEGRITY_VIOLATION,
}

internal const val DEFAULT_MAX_GAP_LIMIT = 1000

internal data class BoundedGapsResult(
    val gaps: List<Long>,
    val completeness: TimelineCompleteness,
    val isTruncated: Boolean = false,
)

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
    val isGapsTruncated: Boolean = false,
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
        val boundedResult = computeAdmissionGaps(sequences, authoritativeMaxSeq)
        val reconciliationRequired =
            checkReconciliationRequired(
                lifecycle = lifecycle,
                records = records,
                completeness = boundedResult.completeness,
                continuationStore = continuationStore,
            )

        return WorkflowHistoricalEvidenceTimeline(
            workflowName = workflowName,
            workflowId = workflowId,
            lifecycle = lifecycle,
            records = records,
            admissionGaps = boundedResult.gaps,
            requiresReconciliation = reconciliationRequired,
            completeness = boundedResult.completeness,
            isGapsTruncated = boundedResult.isTruncated,
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

    internal fun computeAdmissionGaps(
        sequences: List<Long>,
        authoritativeMaxSeq: Long?,
        maxGapLimit: Int = DEFAULT_MAX_GAP_LIMIT,
    ): BoundedGapsResult {
        if (authoritativeMaxSeq == null) {
            return computeGapsWithoutAuthority(sequences, maxGapLimit)
        }
        return computeGapsWithAuthority(sequences, authoritativeMaxSeq, maxGapLimit)
    }

    private fun computeGapsWithoutAuthority(
        sequences: List<Long>,
        maxGapLimit: Int,
    ): BoundedGapsResult {
        if (sequences.isEmpty()) {
            return BoundedGapsResult(emptyList(), TimelineCompleteness.UNKNOWN, false)
        }
        val outOfBounds = sequences.any { it < 1L }
        val completeness =
            if (outOfBounds) TimelineCompleteness.INTEGRITY_VIOLATION else TimelineCompleteness.UNKNOWN
        val (gaps, isTruncated) =
            if (outOfBounds) {
                Pair(emptyList(), false)
            } else {
                collectGaps(sequences, sequences.last(), maxGapLimit)
            }
        return BoundedGapsResult(gaps, completeness, isTruncated)
    }

    private fun computeGapsWithAuthority(
        sequences: List<Long>,
        authoritativeMaxSeq: Long,
        maxGapLimit: Int,
    ): BoundedGapsResult {
        val outOfBounds =
            sequences.any { it < 1L || it > authoritativeMaxSeq } ||
                (authoritativeMaxSeq == 0L && sequences.isNotEmpty())
        if (outOfBounds) {
            return BoundedGapsResult(emptyList(), TimelineCompleteness.INTEGRITY_VIOLATION, false)
        }
        val (gaps, isTruncated) = collectGaps(sequences, authoritativeMaxSeq, maxGapLimit)
        val completeness =
            if (gaps.isEmpty() && !isTruncated) TimelineCompleteness.COMPLETE else TimelineCompleteness.GAPS_DETECTED
        return BoundedGapsResult(gaps, completeness, isTruncated)
    }

    private fun collectGaps(
        sequences: List<Long>,
        targetMaxSeq: Long,
        maxGapLimit: Int,
    ): Pair<List<Long>, Boolean> {
        var expected = 1L
        var truncated = false
        val gaps =
            buildList {
                for (seq in sequences) {
                    while (expected < seq && size < maxGapLimit) {
                        add(expected)
                        expected++
                    }
                    if (size >= maxGapLimit) {
                        truncated = expected < seq || expected <= targetMaxSeq
                        break
                    }
                    expected = seq + 1L
                }
                if (size < maxGapLimit) {
                    while (expected <= targetMaxSeq && size < maxGapLimit) {
                        add(expected)
                        expected++
                    }
                    if (expected <= targetMaxSeq) {
                        truncated = true
                    }
                }
            }
        return Pair(gaps, truncated)
    }

    private suspend fun checkReconciliationRequired(
        lifecycle: WorkflowLifecycleState?,
        records: List<WorkflowEffectEvidenceRecord>,
        completeness: TimelineCompleteness,
        continuationStore: ApprovalContinuationStore?,
    ): Boolean {
        if (completeness != TimelineCompleteness.COMPLETE) {
            return true
        }
        return lifecycle?.status == WorkflowLifecycleStatus.CANCELLED_UNCERTAIN ||
            records.any { isRecordUnresolved(it) } ||
            hasUnreconciledE9(records, continuationStore)
    }

    private fun isRecordUnresolved(record: WorkflowEffectEvidenceRecord): Boolean =
        when (record.state) {
            WorkflowEffectEvidenceState.UNCERTAIN -> true

            WorkflowEffectEvidenceState.ADMITTED,
            WorkflowEffectEvidenceState.DISPATCHED,
            -> true

            WorkflowEffectEvidenceState.COMPLETED -> false
        }

    private suspend fun hasUnreconciledE9(
        records: List<WorkflowEffectEvidenceRecord>,
        continuationStore: ApprovalContinuationStore?,
    ): Boolean {
        val e9Records = records.filter { it.parent is ParentProvenance.ApprovalContinuation }
        if (e9Records.isEmpty()) {
            return false
        }
        return continuationStore == null ||
            e9Records.any { record ->
                val parent = record.parent as ParentProvenance.ApprovalContinuation
                val continuation = continuationStore.get(parent.approvalId)
                continuation == null || continuation.status != ApprovalContinuationStatus.COMPLETED
            }
    }
}
