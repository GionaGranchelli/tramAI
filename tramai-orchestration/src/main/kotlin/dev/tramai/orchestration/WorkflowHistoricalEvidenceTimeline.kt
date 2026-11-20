package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuation
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
 * Authoritative non-mutating continuation lookup mechanism for forensic inspection (D7-F).
 *
 * Guarantees zero side effects on durable continuation state: inspection of an expired
 * [ApprovalContinuationStatus.PENDING] continuation must NOT trigger lazy expiry, CAS updates,
 * version increments, or payload scrubbing.
 */
internal fun interface ReadOnlyApprovalContinuationLookup {
    suspend fun getContinuation(approvalId: String): ApprovalContinuation?
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
 *   Callers seeking strict side-effect-free continuation inspection can provide a
 *   [ReadOnlyApprovalContinuationLookup] to inspect continuation metadata without triggering
 *   lazy lifecycle mutations. Passing an [ApprovalContinuationStore] directly delegates to the
 *   store's [ApprovalContinuationStore.get] contract, which may perform lazy expiry on expired
 *   pending continuations.
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
    ): WorkflowHistoricalEvidenceTimeline =
        readTimeline(
            checkpointStore = checkpointStore,
            evidenceStore = evidenceStore,
            workflowName = workflowName,
            workflowId = workflowId,
            continuationLookup =
                continuationStore?.let { store ->
                    ReadOnlyApprovalContinuationLookup { store.get(it) }
                },
        )

    suspend fun readTimeline(
        checkpointStore: WorkflowCheckpointStore,
        evidenceStore: WorkflowEffectEvidenceRecordStore,
        workflowName: String,
        workflowId: String,
        continuationLookup: ReadOnlyApprovalContinuationLookup?,
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
                continuationLookup = continuationLookup,
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
        val presentSeqs = sequences.toSet()
        val totalGaps = targetMaxSeq - presentSeqs.size
        val isTruncated = totalGaps > maxGapLimit
        var expected = 1L
        val gaps =
            buildList {
                for (seq in sequences) {
                    while (expected < seq && size < maxGapLimit) {
                        add(expected)
                        expected++
                    }
                    if (size >= maxGapLimit) break
                    expected = seq + 1L
                }
                while (expected <= targetMaxSeq && size < maxGapLimit) {
                    add(expected)
                    expected++
                }
            }
        return Pair(gaps, isTruncated)
    }

    private suspend fun checkReconciliationRequired(
        lifecycle: WorkflowLifecycleState?,
        records: List<WorkflowEffectEvidenceRecord>,
        completeness: TimelineCompleteness,
        continuationLookup: ReadOnlyApprovalContinuationLookup?,
    ): Boolean {
        if (completeness != TimelineCompleteness.COMPLETE) {
            return true
        }
        return lifecycle?.status == WorkflowLifecycleStatus.CANCELLED_UNCERTAIN ||
            records.any { it.state != WorkflowEffectEvidenceState.COMPLETED } ||
            hasUnreconciledE9(records, continuationLookup)
    }

    private suspend fun hasUnreconciledE9(
        records: List<WorkflowEffectEvidenceRecord>,
        continuationLookup: ReadOnlyApprovalContinuationLookup?,
    ): Boolean {
        val e9Records = records.filter { it.parent is ParentProvenance.ApprovalContinuation }
        if (e9Records.isEmpty()) return false

        return continuationLookup == null ||
            e9Records.any { record ->
                val parent = record.parent as ParentProvenance.ApprovalContinuation
                val continuation = continuationLookup.getContinuation(parent.approvalId)
                continuation == null || !isContinuationReconciled(record, parent, continuation)
            }
    }

    private fun isContinuationReconciled(
        record: WorkflowEffectEvidenceRecord,
        parent: ParentProvenance.ApprovalContinuation,
        continuation: ApprovalContinuation,
    ): Boolean {
        if (continuation.status != ApprovalContinuationStatus.COMPLETED) return false
        val lineageCompatible = continuation.version == parent.continuationVersion + 1L
        return continuation.workflowRunId == record.key.workflowId &&
            continuation.toolName == parent.toolName &&
            lineageCompatible
    }
}
