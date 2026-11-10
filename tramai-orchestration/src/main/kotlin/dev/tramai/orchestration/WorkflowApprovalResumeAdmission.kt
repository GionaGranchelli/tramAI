@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)

package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumeEvidenceHandle
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.WorkflowEvidenceDiagnosticCategory
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.exception.ApprovalResumeEvidencePersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val approvalResumeEvidenceEnabled: Boolean,
    private val clock: Clock,
    private val evidenceTimeoutMillis: Long = 5_000L,
) : ApprovalResumeAdmission {
    constructor(checkpointStore: WorkflowCheckpointStore) : this(
        checkpointStore = checkpointStore,
        continuationStore = null,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        approvalResumeEvidenceEnabled = false,
        clock = Clock.systemUTC(),
    )

    constructor(
        checkpointStore: WorkflowCheckpointStore,
        approvalResumeEvidenceEnabled: Boolean,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = null,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        approvalResumeEvidenceEnabled = approvalResumeEvidenceEnabled,
        clock = Clock.systemUTC(),
    )

    constructor(
        checkpointStore: WorkflowCheckpointStore,
        continuationStore: ApprovalContinuationStore,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = continuationStore,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        approvalResumeEvidenceEnabled = false,
        clock = Clock.systemUTC(),
    )

    constructor(
        checkpointStore: WorkflowCheckpointStore,
        continuationStore: ApprovalContinuationStore,
        approvalResumeEvidenceEnabled: Boolean,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = continuationStore,
        evidenceStore = checkpointStore.resolveEvidenceStore(),
        approvalResumeEvidenceEnabled = approvalResumeEvidenceEnabled,
        clock = Clock.systemUTC(),
    )

    internal constructor(
        checkpointStore: WorkflowCheckpointStore,
        continuationStore: ApprovalContinuationStore?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
        approvalResumeEvidenceEnabled: Boolean = (evidenceStore != null),
        evidenceTimeoutMillis: Long = 5_000L,
    ) : this(
        checkpointStore = checkpointStore,
        continuationStore = continuationStore,
        evidenceStore = evidenceStore,
        approvalResumeEvidenceEnabled = approvalResumeEvidenceEnabled,
        clock = Clock.systemUTC(),
        evidenceTimeoutMillis = evidenceTimeoutMillis,
    )

    override suspend fun admitResume(request: ApprovalResumeAdmissionRequest): ApprovalResumePermit {
        val binding = request.checkpointBinding
        val checkpoint = loadAndValidateCheckpoint(request, binding)

        val effectiveEvidenceStore = if (approvalResumeEvidenceEnabled) evidenceStore else null
        if (effectiveEvidenceStore != null) {
            validateEvidencePreconditions(request, binding)
        }

        val admission = admitEffect(request, binding, checkpoint)
        val evidenceHandle =
            if (effectiveEvidenceStore != null) {
                recordInitialEvidence(
                    request = request,
                    binding = binding,
                    checkpointGeneration = checkpoint.checkpointGeneration.orEmpty(),
                    admissionSequence = admission.sequence,
                    store = effectiveEvidenceStore,
                )
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

    private suspend fun loadAndValidateCheckpoint(
        request: ApprovalResumeAdmissionRequest,
        binding: dev.tramai.core.identity.WorkflowCheckpointBinding,
    ): WorkflowCheckpoint {
        val checkpoint =
            checkpointStore.load(binding.workflowName, binding.workflowId)
                ?: throw ApprovalResumeAdmissionRefusedException(
                    "approval '${request.approvalId}' claims workflow '${binding.workflowName}' run " +
                        "'${binding.workflowId}' but no durable checkpoint exists for it, so the resume has " +
                        "nothing to be admitted by",
                )
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
        return checkpoint
    }

    private suspend fun admitEffect(
        request: ApprovalResumeAdmissionRequest,
        binding: dev.tramai.core.identity.WorkflowCheckpointBinding,
        checkpoint: WorkflowCheckpoint,
    ): WorkflowAdmission =
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
            throw ApprovalResumeAdmissionRefusedException(
                "approval '${request.approvalId}' cannot be resumed: ${refused.message}",
            ).apply { initCause(refused) }
        } catch (unsupported: WorkflowCancellationUnsupportedException) {
            throw ApprovalResumeAdmissionRefusedException(
                "approval '${request.approvalId}' cannot be admitted: this checkpoint store does not support " +
                    "admission (${unsupported.message})",
            ).apply { initCause(unsupported) }
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
                "approval '${request.approvalId}' evidence is required but no continuationStore is configured " +
                    "(continuationStore is required when evidence tracking is enabled)"
            }

            request.continuationVersion <= 0L -> {
                "approval '${request.approvalId}' evidence is required but continuationVersion must be positive " +
                    "(was ${request.continuationVersion})"
            }

            stored == null -> {
                "approval '${request.approvalId}' has no durable continuation in store (continuation not found)"
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
        store: WorkflowEffectEvidenceRecordStore,
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
            store.recordEvidence(initialRecord)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Throwable,
        ) {
            ex.rethrowIfCancellation()
            throw ApprovalResumeEvidencePersistenceException(
                "approval '${request.approvalId}' admitted against checkpoint (sequence $admissionSequence) " +
                    "but failed to record initial evidence",
                ex,
            )
        }
        return WorkflowApprovalResumeEvidenceHandle(store, evidenceKey, clock, evidenceTimeoutMillis)
    }
}

private class WorkflowApprovalResumeEvidenceHandle(
    private val evidenceStore: WorkflowEffectEvidenceRecordStore,
    private val evidenceKey: WorkflowEffectEvidenceKey,
    private val clock: Clock,
    private val timeoutMillis: Long = 5_000L,
) : ApprovalResumeEvidenceHandle {
    override suspend fun recordDispatched() {
        withContext(NonCancellable) {
            val updated =
                try {
                    withTimeoutOrNull(timeoutMillis) {
                        val existing =
                            checkNotNull(evidenceStore.getEvidence(evidenceKey)) {
                                "Evidence record missing for key: $evidenceKey"
                            }
                        evidenceStore.updateEvidence(existing.copy(state = WorkflowEffectEvidenceState.DISPATCHED))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") ex: Throwable,
                ) {
                    ex.rethrowIfCancellation()
                    throw ApprovalResumeEvidencePersistenceException(
                        "Failed to record DISPATCHED evidence for key: $evidenceKey",
                        ex,
                    )
                }
            if (updated == null) {
                throw ApprovalResumeEvidencePersistenceException(
                    "Timed out after ${timeoutMillis}ms waiting to record DISPATCHED evidence for key: $evidenceKey",
                )
            }
        }
    }

    override suspend fun recordCompleted() {
        withContext(NonCancellable) {
            val updated =
                try {
                    withTimeoutOrNull(timeoutMillis) {
                        val existing =
                            checkNotNull(evidenceStore.getEvidence(evidenceKey)) {
                                "Evidence record missing for key: $evidenceKey"
                            }
                        evidenceStore.updateEvidence(
                            existing.copy(
                                state = WorkflowEffectEvidenceState.COMPLETED,
                                completedAtEpochMillis = clock.millis(),
                            ),
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught") ex: Throwable,
                ) {
                    ex.rethrowIfCancellation()
                    throw ApprovalResumeEvidencePersistenceException(
                        "Failed to record COMPLETED evidence for key: $evidenceKey",
                        ex,
                    )
                }
            if (updated == null) {
                throw ApprovalResumeEvidencePersistenceException(
                    "Timed out after ${timeoutMillis}ms waiting to record COMPLETED evidence for key: $evidenceKey",
                )
            }
        }
    }

    override suspend fun recordUncertain(
        error: Throwable,
        category: WorkflowEvidenceDiagnosticCategory,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeoutOrNull(timeoutMillis) {
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
        } catch (
            @Suppress("TooGenericExceptionCaught") suppressed: Throwable,
        ) {
            error.suppressEvidenceDiagnostic(suppressed)
        }
    }
}

/** The effect kind E9 grants are taken for. */
const val APPROVAL_RESUME_EFFECT_KIND: String = "APPROVAL_RESUME"
