package dev.tramai.core.approval

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One approval-resume admission request (E9).
 *
 * Everything the authority needs and nothing it could guess: the approval, the tool being resumed, the governed run,
 * and the checkpoint row that is claimed to own it. The binding is data supplied by the suspension record, never
 * derived inside the authority.
 */
data class ApprovalResumeAdmissionRequest(
    val approvalId: String,
    val toolName: String,
    val runIdentity: GovernedRunIdentity,
    val checkpointBinding: WorkflowCheckpointBinding,
)

/**
 * Workflow-bound approval continuation admission (E9) as a capability distinct from approval authorization.
 *
 * **Approval is not permission to bypass authoritative workflow cancellation.** The approval decision says a human
 * allowed this tool call; this capability says the owning workflow may still execute anything at all. Both must hold
 * before a resumed invocation is dispatched, and neither implies the other.
 *
 * **Scope, stated narrowly.** A grant means *this resumed executor invocation was admitted by its owning workflow's
 * durable authority*. It does not establish that any effect inside the executor happened, and it says nothing about a
 * governed engine invocation that no workflow owns — a standalone engine user has a [GovernedRunIdentity] without a
 * checkpoint, and no workflow-level cancellation guarantee applies to it.
 *
 * Implementations live where the durable authority lives (the workflow module), and are injected at composition time;
 * the engine only consumes this interface.
 */
fun interface ApprovalResumeAdmission {
    /**
     * Take a fresh, durable, single-use grant for one resumed invocation.
     *
     * @throws dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException when the authority refuses.
     */
    suspend fun admitResume(request: ApprovalResumeAdmissionRequest): ApprovalResumePermit
}

/**
 * One durable grant for one resumed approval invocation, consumable exactly once.
 *
 * [admissionSequence] is the durable part of the identity: it is the only component the authority issues. Consumption
 * is local to this attempt and deliberately not durable — a worker that dies after the invocation admits again on
 * restart, which must never be read as exactly-once execution.
 */
@ExperimentalTramaiInternalApi
class ApprovalResumePermit(
    val approvalId: String,
    val checkpointBinding: WorkflowCheckpointBinding,
    val admissionSequence: Long,
) {
    private val consumed = AtomicBoolean(false)

    /**
     * Consume the permit: true exactly once. A second call is a programming error and fails rather than re-dispatching,
     * because one permit authorizes at most one resumed invocation.
     */
    fun tryConsume(): Boolean {
        check(!consumed.getAndSet(true)) {
            "approval resume permit for '${checkpointBinding.workflowName}/${checkpointBinding.workflowId}' " +
                "approval '$approvalId' (admission $admissionSequence) was already consumed; " +
                "one permit authorizes at most one resumed invocation"
        }
        return true
    }
}
