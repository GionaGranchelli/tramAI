@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)

package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException

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
class WorkflowApprovalResumeAdmission(
    private val checkpointStore: WorkflowCheckpointStore,
) : ApprovalResumeAdmission {
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
        return ApprovalResumePermit(
            approvalId = request.approvalId,
            checkpointBinding = binding,
            admissionSequence = admission.sequence,
        )
    }
}

/** The effect kind E9 grants are taken for. */
const val APPROVAL_RESUME_EFFECT_KIND: String = "APPROVAL_RESUME"
