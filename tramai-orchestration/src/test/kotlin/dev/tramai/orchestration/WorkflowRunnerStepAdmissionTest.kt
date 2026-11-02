package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E11 through the real runner, including nested steps.
 *
 * The session-level tests prove the governor's contract; these prove the wiring. A nested step is not a checkpoint
 * boundary, so the authority reaches it as a capability carried by the execution services rather than through a
 * session it must not have.
 */
class WorkflowRunnerStepAdmissionTest {
    private val store = InMemoryWorkflowCheckpointStore()

    @Test
    fun `a nested step is refused after cancellation commits, and its body never runs`() {
        val charged = AtomicBoolean(false)

        val error = assertFailsWith<WorkflowAdmissionRefusedException> { governedRun(charged, cancel = true) }

        assertTrue(error.message!!.contains("charge-card"), "the refusal names the nested step: ${error.message}")
        assertFalse(charged.get(), "the refused step's body must not run")
    }

    @Test
    fun `an ordinary run completes its nested steps normally`() {
        val charged = AtomicBoolean(false)

        // The compatibility half: a run that never claimed durable cancellation authority is not fenced at all.
        ordinaryRun(charged, cancel = false)

        assertTrue(charged.get(), "an ordinary run keeps executing nested steps")
    }

    @Test
    fun `an ordinary run executes the nested step after cancellation, then fails at the terminal row`() {
        val charged = AtomicBoolean(false)

        // Cancellation still commits for an ordinary run and still refuses the run's later checkpoint write. What it
        // must not do is become an admission refusal for a run that never claimed the guarantee: the nested body runs,
        // and the run then fails on the terminal row instead of pretending to finish.
        assertFailsWith<WorkflowCheckpointConflictException> { ordinaryRun(charged, cancel = true) }
        assertTrue(charged.get(), "the nested step runs for an ordinary run, even after cancellation")
    }

    private fun governedRun(
        charged: AtomicBoolean,
        cancel: Boolean,
    ) = runBlocking {
        branchWorkflow(charged, cancel).run(
            "s",
            GovernedRun(WorkflowContext(workflowId = "run-1"), GOVERNED),
            NoOpWorkflowObserver,
            WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec),
        )
    }

    private fun ordinaryRun(
        charged: AtomicBoolean,
        cancel: Boolean,
    ) = runBlocking {
        branchWorkflow(charged, cancel).run(
            "s",
            WorkflowContext(workflowId = "run-1"),
            NoOpWorkflowObserver,
            WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec),
        )
    }

    /**
     * A branch whose nested steps optionally cancel the run first.
     *
     * [cancel] is explicit because a test that means to cancel and silently does not cancels nothing at all: that is
     * how the first version of the compatibility case passed without proving anything.
     */
    private fun branchWorkflow(
        charged: AtomicBoolean,
        cancel: Boolean,
    ) = workflow<String>("checkout") {
        branchStep(name = "route", select = { "a" }) {
            branch("a") {
                if (cancel) {
                    localStep("cancel-run") { _, _ ->
                        cancelRun()
                        "s"
                    }
                }
                localStep("charge-card") { state, _ ->
                    charged.set(true)
                    state
                }
            }
        }
    }.build { it }

    /** Cancels the run mid-flight: the outer branch is already admitted, the next nested step is not. */
    private suspend fun cancelRun() {
        val current = store.load("checkout", "run-1") ?: return
        store.cancel(
            WorkflowCancellationRequest(
                workflowName = "checkout",
                workflowId = current.workflowId,
                expectedRevision = current.revision,
                expectedGeneration = current.checkpointGeneration,
                actor = "operator",
                reason = "stopped",
                cancelledAtEpochMillis = 0L,
            ),
        )
    }

    private object PlainCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private companion object {
        private val GOVERNED =
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("checkout"),
                        configuration =
                            WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                        environmentId = EnvironmentId("test"),
                        deploymentId = DeploymentId("dep"),
                    ),
                runId = RunId("run-1"),
            )
    }
}
