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
import java.net.http.HttpClient
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E11 through the real runner, including nested steps.
 *
 * The session-level tests prove the governor's contract; these prove the wiring. A nested step is not a checkpoint
 * boundary, so the authority has to reach it as a capability rather than through a session it does not have.
 */
class WorkflowRunnerStepAdmissionTest {
    private val store = InMemoryWorkflowCheckpointStore()

    @Test
    fun `a nested step is refused after cancellation commits, and its body never runs`() {
        val charged = AtomicBoolean(false)
        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    branchWorkflow(charged).run(
                        "s",
                        GovernedRun(WorkflowContext(workflowId = "run-1"), GOVERNED),
                        NoOpWorkflowObserver,
                        WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec),
                    )
                }
            }

        assertTrue(error.message!!.contains("charge-card"), "the refusal names the nested step: ${error.message}")
        assertFalse(charged.get(), "the refused step's body must not run")
    }

    @Test
    fun `an ordinary run executes its nested steps after the same cancellation`() {
        val charged = AtomicBoolean(false)

        // No governed identity: the run never claimed the durable guarantee, so the cancelled row is not its business
        // and the nested step runs. This is the compatibility half of E11, at execution level.
        runBlocking {
            branchWorkflow(charged).run(
                "s",
                observer = NoOpWorkflowObserver,
                persistence = WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec),
            )
        }

        assertTrue(charged.get(), "an ordinary run keeps executing nested steps")
    }

    private fun branchWorkflow(charged: AtomicBoolean) =
        workflow<String>("checkout") {
            branchStep(name = "route", select = { "a" }) {
                branch("a") {
                    localStep("cancel-run") { _, _ ->
                        cancelRun()
                        "s"
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

    private object PlainCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }
}
