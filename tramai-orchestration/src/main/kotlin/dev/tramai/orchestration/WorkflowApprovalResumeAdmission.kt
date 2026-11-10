@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)

package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumeEvidenceHandle
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.WorkflowEvidenceDiagnosticCategory
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * E9: the workflow-side authority for approval continuations, over the same durable row that governs cancellation
 * (see `docs/roadmap/0.7.7/TASK-0.7.7b-PER-EFFECT-ADMISSION-CONTRACT.md` §10h).
 *
 * It answers exactly one question: may this resumed invocation still execute anything in the workflow named by its
 * checkpoint binding? A completed approval does not answer it, and neither does the engine's own identity — the binding
 * says which row to ask, and the row's current revision and generation are what a fresh conditional admission is taken
 * against. Nothing here retries, caches or reuses a grant: every resume asks once, and a refused ask returns no permit.
 *
 * Deliberately a plain class over [WorkflowCheckpointStore] rather than a second lifecycle authority: it introduces no
 * state of its own, so the only thing that can say "no" is the durable row.
 *
 * @param checkpointStore the durable authority. Same store the workflow itself checkpoints into.
 */
class WorkflowApprovalResumeAdmission internal constructor(
    private val checkpointStore: WorkflowCheckpointStore,
    private val continuationStore: ApprovalContinuationStore?,
    private val evidenceStore: WorkflowEffectEvidenceRecordStore?,
    private val clock: Clock,
) : ApprovalResumeAdmission {
    constructor(checkpointStore: WorkflowCheckpointStore) : this(
        checkpointStore = checkpointStore,
        continuationStore = null,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        clock = Clock.systemUTC(),
    )

    constructor(
        checkpointStore: WorkflowCheckpointStore,
        continuationStore: ApprovalContinuationStore,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = continuationStore,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        clock = Clock.systemUTC(),
    )

    internal constructor(
        checkpointStore: WorkflowCheckpointStore,
        continuationStore: ApprovalContinuationStore?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = continuationStore,
        evidenceStore = evidenceStore,
        clock = Clock.systemUTC(),
    )

    override suspend fun admitResume(request: ApprovalResumeAdmissionRequest): ApprovalResumePermit {
        val binding = request.checkpointBinding
        val checkpoint =
            checkpointStore.load(binding.workflowName, binding.workflowId)
                ?: throw ApprovalResumeAdmissionRefusedException(
                    "approval '${request.approvalId}' claims workflow '${binding.workflowName}' run " +
                        "'${binding.workflowId}' but no durable checkpoint exists for it, so the resume has " +
                        "nothing to be admitted by",
                )
        // The row's own attribution must be the identity that asked, or one execution's work is being attributed to
        // another. A binding that names an ungoverned row fails here too: a governed resume cannot become ungoverned.
        try {
            requireGovernedRunAttributionContinuity(
                workflowName = binding.workflowName,
                workflowId = binding.workflowId,
                persisted = decodeGovernedRunAttribution(checkpoint.workflowId, checkpoint.metadata),
                requested = request.runIdentity,
            )
        } catch (mismatch: WorkflowResumeException) {
            throw ApprovalResumeAdmissionRefusedException(
                "approval '${request.approvalId}' cannot be admitted: ${mismatch.message}",
            ).apply { initCause(mismatch) }
        }

        if (evidenceStore != null) {
            validateEvidencePreconditions(request, binding)
        }

        val admission =
            try {
                checkpointStore.admitEffect(
                    workflowName = binding.workflowName,
                    workflowId = binding.workflowId,
                    currentRevision = checkpoint.revision,
                    currentGeneration = checkpoint.checkpointGeneration,
                    stepName = request.toolName,
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                )
            } catch (refused: WorkflowAdmissionRefusedException) {
                // Same authority rule, different vocabulary: the engine reports an approval refusal, not a workflow
                // step failure, so the type is mapped once, here, instead of teaching the engine the workflow's types.
                throw ApprovalResumeAdmissionRefusedException(
                    "approval '${request.approvalId}' cannot be resumed: ${refused.message}",
                ).apply { initCause(refused) }
            } catch (unsupported: WorkflowCancellationUnsupportedException) {
                // A store that cannot admit is a refused admission, not a failed resume: the executor never ran, so the
                // engine must not report an uncertain outcome. Unrelated failures stay unclassified and propagate.
                throw ApprovalResumeAdmissionRefusedException(
                    "approval '${request.approvalId}' cannot be admitted: this checkpoint store does not support " +
                        "admission (${unsupported.message})",
                ).apply { initCause(unsupported) }
            }

        val evidenceHandle =
            if (evidenceStore != null) {
                recordInitialEvidence(request, binding, checkpoint.checkpointGeneration.orEmpty(), admission.sequence)
            } else {
                null
            }

        return ApprovalResumePermit(
            approvalId = request.approvalId,
            checkpointBinding = binding,
            admissionSequence = admission.sequence,
            checkpointGeneration = checkpoint.checkpointGeneration,
            evidenceHandle = evidenceHandle,
        )
    }

    private suspend fun validateEvidencePreconditions(
        request: ApprovalResumeAdmissionRequest,
        binding: dev.tramai.core.identity.WorkflowCheckpointBinding,
    ) {
        val refusal = checkEvidencePreconditions(request, binding)
        if (refusal != null) {
            throw ApprovalResumeAdmissionRefusedException(refusal)
        }
    }

    private suspend fun checkEvidencePreconditions(
        request: ApprovalResumeAdmissionRequest,
        binding: dev.tramai.core.identity.WorkflowCheckpointBinding,
    ): String? {
        val store = continuationStore
        val stored = store?.get(request.approvalId)
        return when {
            store == null -> {
                "approval '${request.approvalId}' cannot be admitted: " +
                    "continuationStore is required when evidence tracking is enabled"
            }

            request.continuationVersion <= 0L -> {
                "approval '${request.approvalId}' cannot record evidence: continuationVersion must be positive"
            }

            stored == null -> {
                "approval '${request.approvalId}' not found"
            }

            stored.status != ApprovalContinuationStatus.CLAIMED -> {
                "approval '${request.approvalId}' is not in CLAIMED status (was ${stored.status})"
            }

            stored.version != request.continuationVersion -> {
                "approval '${request.approvalId}' continuation version mismatch: " +
                    "store has ${stored.version}, request claimed ${request.continuationVersion}"
            }

            stored.workflowRunId != binding.workflowId -> {
                "approval '${request.approvalId}' workflowRunId mismatch: " +
                    "store has ${stored.workflowRunId}, request claimed ${binding.workflowId}"
            }

            stored.toolName != request.toolName -> {
                "approval '${request.approvalId}' tool mismatch: " +
                    "store has ${stored.toolName}, request claimed ${request.toolName}"
            }

            else -> {
                null
            }
        }
    }

    private suspend fun recordInitialEvidence(
        request: ApprovalResumeAdmissionRequest,
        binding: dev.tramai.core.identity.WorkflowCheckpointBinding,
        checkpointGeneration: String,
        admissionSequence: Long,
    ): ApprovalResumeEvidenceHandle {
        val evidenceKey =
            WorkflowEffectEvidenceKey(
                workflowName = binding.workflowName,
                workflowId = binding.workflowId,
                checkpointGeneration = checkpointGeneration,
                admissionSeq = admissionSequence,
            )
        val initialRecord =
            WorkflowEffectEvidenceRecord(
                key = evidenceKey,
                parent =
                    ParentProvenance.ApprovalContinuation(
                        approvalId = request.approvalId,
                        continuationVersion = request.continuationVersion,
                        toolName = request.toolName,
                    ),
                effectKind = APPROVAL_RESUME_EFFECT_KIND,
                state = WorkflowEffectEvidenceState.ADMITTED,
                createdAtEpochMillis = clock.millis(),
            )
        try {
            checkNotNull(evidenceStore).recordEvidence(initialRecord)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            throw ApprovalResumeAdmissionRefusedException(
                "approval '${request.approvalId}' admitted against checkpoint but " +
                    "failed to record initial evidence: ${ex.message}",
            ).apply { initCause(ex) }
        }
        return WorkflowApprovalResumeEvidenceHandle(evidenceStore, evidenceKey, clock)
    }
}

private class WorkflowApprovalResumeEvidenceHandle(
    private val evidenceStore: WorkflowEffectEvidenceRecordStore,
    private val evidenceKey: WorkflowEffectEvidenceKey,
    private val clock: Clock,
) : ApprovalResumeEvidenceHandle {
    override suspend fun recordDispatched() {
        withContext(NonCancellable) {
            val existing =
                checkNotNull(evidenceStore.getEvidence(evidenceKey)) {
                    "Evidence record missing for key: $evidenceKey"
                }
            val updated = existing.copy(state = WorkflowEffectEvidenceState.DISPATCHED)
            evidenceStore.updateEvidence(updated)
        }
    }

    override suspend fun recordCompleted() {
        withContext(NonCancellable) {
            val existing =
                checkNotNull(evidenceStore.getEvidence(evidenceKey)) {
                    "Evidence record missing for key: $evidenceKey"
                }
            val updated =
                existing.copy(
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    completedAtEpochMillis = clock.millis(),
                )
            evidenceStore.updateEvidence(updated)
        }
    }

    override suspend fun recordUncertain(
        error: Throwable,
        category: WorkflowEvidenceDiagnosticCategory,
    ) {
        withContext(NonCancellable) {
            val existing = evidenceStore.getEvidence(evidenceKey)
            if (existing != null &&
                existing.state != WorkflowEffectEvidenceState.UNCERTAIN &&
                existing.state != WorkflowEffectEvidenceState.COMPLETED
            ) {
                val updated =
                    existing.copy(
                        state = WorkflowEffectEvidenceState.UNCERTAIN,
                        completedAtEpochMillis = clock.millis(),
                        diagnosticSummary = category.name,
                    )
                evidenceStore.updateEvidence(updated)
            }
        }
    }
}

/** The effect kind E9 grants are taken for. */
const val APPROVAL_RESUME_EFFECT_KIND: String = "APPROVAL_RESUME"
