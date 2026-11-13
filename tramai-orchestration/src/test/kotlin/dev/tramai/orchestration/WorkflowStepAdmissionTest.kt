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
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E11: a governed run asks the durable authority before it enters a step, and an ordinary run is not touched.
 *
 * These are session-level tests on purpose: the governor question ("may this run enter a step?") is decided there,
 * and testing it through a finished workflow would prove less about the refusal itself.
 */
class WorkflowStepAdmissionTest {
    private val store = InMemoryWorkflowCheckpointStore()

    @Test
    fun `a cancelled governed run cannot enter a step`() {
        val saved = runBlocking { store.save(checkpoint("checkout")) }
        runBlocking {
            store.cancel(
                WorkflowCancellationRequest(
                    workflowName = "checkout",
                    workflowId = saved.workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped",
                    cancelledAtEpochMillis = 0L,
                ),
            )
        }
        val session = session(governed = true, revision = saved.revision, generation = saved.checkpointGeneration)

        val refusal =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking { session.admitStep("charge-card") }
            }
        assertTrue(
            refusal.message!!.contains("charge-card"),
            "the refusal names the step it stopped: ${refusal.message}",
        )
    }

    @Test
    fun `an ordinary run is not fenced by a cancelled row`() {
        val saved = runBlocking { store.save(checkpoint("checkout")) }
        runBlocking {
            store.cancel(
                WorkflowCancellationRequest(
                    workflowName = "checkout",
                    workflowId = saved.workflowId,
                    expectedRevision = saved.revision,
                    expectedGeneration = saved.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped",
                    cancelledAtEpochMillis = 0L,
                ),
            )
        }
        val session = session(governed = false, revision = saved.revision, generation = saved.checkpointGeneration)

        // No governed identity means no durable cancellation claim was made, so nothing is being over-promised and
        // ordinary execution modes keep working. Fencing here would break runs that never asked for the guarantee.
        runBlocking { session.admitStep("charge-card") }
    }

    @Test
    fun `a governed run with no persisted row is not fenced yet`() {
        val session = session(governed = true, revision = null, generation = null)

        // Cancellation is a write to a row. A run with no row cannot have been cancelled, so admission becomes
        // mandatory from the first checkpoint onwards rather than blocking the run before it has an identity.
        runBlocking { session.admitStep("charge-card") }
    }

    @Test
    fun `a store that cannot admit refuses a governed step`() {
        val directory = Files.createTempDirectory("tramai-admission").toFile()
        val ungovernedStore = MarkdownWorkflowCheckpointStore(directory.toPath())
        val saved = runBlocking { ungovernedStore.save(checkpoint("checkout")) }
        val session =
            session(
                governed = true,
                revision = saved.revision,
                generation = saved.checkpointGeneration,
                store = ungovernedStore,
            )

        // Fail closed: a profile that cannot decide admission must not let governed execution proceed unfenced.
        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking { session.admitStep("charge-card") }
        }
    }

    private fun session(
        governed: Boolean,
        revision: Long?,
        generation: String?,
        store: WorkflowCheckpointStore = this.store,
    ): WorkflowPersistenceSession<String> =
        WorkflowPersistenceSession(
            persistence = WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec),
            inputs =
                WorkflowSessionInputs(
                    workflowName = "checkout",
                    context = WorkflowContext(workflowId = "run-1"),
                    observer = NoOpWorkflowObserver,
                    workflowDefinitionCompatibility =
                        WorkflowDefinitionCompatibility(version = "v1", digest = "digest", digestAlgorithm = "SHA-256"),
                    clock = Clock.systemUTC(),
                    governedRunIdentity = if (governed) GOVERNED else null,
                ),
            lease = null,
            initialRevision = revision,
            initialGeneration = generation,
        )

    private fun checkpoint(workflowName: String) =
        WorkflowCheckpoint(
            workflowName = workflowName,
            workflowId = "run-1",
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "state",
            metadata = emptyMap(),
            savedAtEpochMillis = 0L,
            checkpointGeneration = null,
        )

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
