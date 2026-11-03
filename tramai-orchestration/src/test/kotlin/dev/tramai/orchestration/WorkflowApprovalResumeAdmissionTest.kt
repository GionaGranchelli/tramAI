package dev.tramai.orchestration

import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E9: the workflow-side authority behind [WorkflowApprovalResumeAdmission].
 *
 * The claim under test is narrow and deliberately so: a resumed approval invocation may only dispatch when the durable
 * row named by its checkpoint binding still admits work. Approval authorization is a different decision taken elsewhere
 * and is not re-tested here.
 */
class WorkflowApprovalResumeAdmissionTest {
    private val store = InMemoryWorkflowCheckpointStore()
    private val binding = WorkflowCheckpointBinding(workflowName = "checkout", workflowId = "run-1")
    private val identity = runIdentity("run-1")

    @Test
    fun `an active run issues one grant per resume`() {
        val rows = AdmitCountingStore(store)
        save(rows)
        val authority = WorkflowApprovalResumeAdmission(rows)

        val first = runBlocking { authority.admitResume(request()) }
        val second = runBlocking { authority.admitResume(request()) }

        assertEquals(APPROVAL_RESUME_EFFECT_KIND.let { 2 }, rows.admitCount, "each resume asks once")
        assertTrue(first.admissionSequence != second.admissionSequence, "a repeated attempt takes a fresh sequence")
    }

    @Test
    fun `a cancelled run refuses the resume`() {
        save(store)
        cancelSavedRow()
        val rows = AdmitCountingStore(store)

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { WorkflowApprovalResumeAdmission(rows).admitResume(request()) }
            }

        assertTrue(refusal.message!!.contains("cannot be resumed"), refusal.message!!)
        assertEquals(1, rows.admitCount, "the authority was asked and said no")
    }

    @Test
    fun `a cancellation racing the admission refuses the resume`() {
        save(store)
        // The authority reads a live row, and the run is cancelled before its conditional write lands: the grant must
        // be refused rather than taken against a revision that no longer admits work.
        val racing =
            object : WorkflowCheckpointStore by store {
                override suspend fun load(
                    workflowName: String,
                    workflowId: String,
                ): WorkflowCheckpoint? {
                    val snapshot = store.load(workflowName, workflowId)
                    cancelSavedRow()
                    return snapshot
                }
            }

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { WorkflowApprovalResumeAdmission(racing).admitResume(request()) }
            }

        assertTrue(refusal.message!!.contains("cannot be resumed"), refusal.message!!)
    }

    @Test
    fun `a binding that names no durable row refuses the resume`() {
        val authority = WorkflowApprovalResumeAdmission(store)

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(request()) }
            }

        assertTrue(refusal.message!!.contains("no durable checkpoint exists"), refusal.message!!)
    }

    @Test
    fun `a binding whose row belongs to another identity refuses the resume`() {
        save(store)
        val authority = WorkflowApprovalResumeAdmission(store)
        val substituted = runIdentity("run-2")

        assertFailsWith<ApprovalResumeAdmissionRefusedException> {
            runBlocking { authority.admitResume(request().copy(runIdentity = substituted)) }
        }
    }

    @Test
    fun `an ungoverned row cannot be resumed as a governed one`() {
        runBlocking {
            store.save(
                WorkflowCheckpoint(
                    workflowName = binding.workflowName,
                    workflowId = binding.workflowId,
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "state",
                ),
            )
        }
        val authority = WorkflowApprovalResumeAdmission(store)

        assertFailsWith<ApprovalResumeAdmissionRefusedException> {
            runBlocking { authority.admitResume(request()) }
        }
    }

    @Test
    fun `the permit names the approval and its binding`() {
        save(store)

        val permit = runBlocking { WorkflowApprovalResumeAdmission(store).admitResume(request()) }

        assertEquals("approval-1", permit.approvalId)
        assertEquals(binding, permit.checkpointBinding)
        assertTrue(permit.tryConsume(), "the first consumption authorizes one dispatch")
        assertFailsWith<IllegalStateException> { permit.tryConsume() }
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun request() =
        ApprovalResumeAdmissionRequest(
            approvalId = "approval-1",
            toolName = "charge-card",
            runIdentity = identity,
            checkpointBinding = binding,
        )

    private fun cancelSavedRow() =
        runBlocking {
            val current = store.load(binding.workflowName, binding.workflowId)!!
            store.cancel(
                WorkflowCancellationRequest(
                    workflowName = binding.workflowName,
                    workflowId = binding.workflowId,
                    expectedRevision = current.revision,
                    expectedGeneration = current.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped",
                    cancelledAtEpochMillis = 0L,
                ),
            )
        }

    private fun save(target: WorkflowCheckpointStore) =
        runBlocking {
            target.save(
                WorkflowCheckpoint(
                    workflowName = binding.workflowName,
                    workflowId = binding.workflowId,
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = "state",
                    metadata = encodeGovernedRunAttribution(identity),
                ),
            )
        }

    private fun runIdentity(runId: String): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("checkout"),
                    configuration = WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                    environmentId = EnvironmentId("test"),
                    deploymentId = DeploymentId("dep"),
                ),
            runId = RunId(runId),
        )

    /** Counts admissions so "the authority was asked exactly once" is a number, not an impression. */
    private class AdmitCountingStore(
        private val delegate: WorkflowCheckpointStore,
    ) : WorkflowCheckpointStore by delegate {
        private val mutex = Mutex()
        private var attempts = AtomicInteger(0)

        val admitCount: Int
            get() = attempts.get()

        override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
            mutex.withLock {
                attempts.incrementAndGet()
                delegate.admit(request)
            }
    }
}
