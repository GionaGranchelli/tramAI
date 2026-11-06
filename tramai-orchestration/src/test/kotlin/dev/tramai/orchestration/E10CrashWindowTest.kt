@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

// The identity fixture and the case name read better long; .editorconfig enforces no line length.

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E10, crash window W1: a worker that dies *after* taking a durable admission and *before* dispatching the effect.
 *
 * The claim under test is not exactly-once execution — nothing here proves whether an effect landed. It is that the next
 * attempt obtains a **fresh** admission: there is no durable permit to replay, so recovery cannot reuse the orphaned
 * grant, and the durable counter moves again rather than being satisfied by the first one.
 *
 * Deterministic by construction: the "crash" is a thrown error from the step body, not a killed worker.
 */
class E10CrashWindowTest {
    private class CountingStore(
        private val delegate: WorkflowCheckpointStore,
    ) : WorkflowCheckpointStore by delegate {
        val sequences = mutableListOf<Long>()

        override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
            delegate.admit(request).also { if (it.admitted) sequences += it.sequence }
    }

    private companion object {
        val IDENTITY: GovernedRunIdentity =
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("claims"),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId("claims-prod"),
                                version = ConfigurationVersion("17"),
                            ),
                        environmentId = EnvironmentId("production"),
                        deploymentId = DeploymentId("eu-west-amsterdam-01"),
                    ),
                runId = RunId("run-w1"),
            )
    }

    private object PlainCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    @Test
    fun `an attempt that dies after its admission gets a fresh admission on resume`() {
        runBlocking<Unit> {
            val checkpoints = CountingStore(InMemoryWorkflowCheckpointStore())
            var bodies = 0
            var permits = mutableListOf<WorkflowProcessPermit>()

            val workflow =
                workflow<String>("crash-window") {
                    localStep("dispatch") { state, _ ->
                        bodies++
                        val admission =
                            assertNotNull(
                                currentProcessAdmission(),
                                "the runner must install the process admission capability for a step body",
                            )
                        permits += admission.admitProcess("dispatch", "PROCESS")
                        error("worker dies after the admission and before the dispatch")
                    }
                }.build { it }

            val persistence = WorkflowPersistence(checkpointStore = checkpoints, stateCodec = PlainCodec)

            // First attempt: the step takes its admission, then dies.
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    workflow.run(
                        "s",
                        GovernedRun(WorkflowContext(workflowId = "run-w1"), IDENTITY),
                        NoOpWorkflowObserver,
                        persistence,
                    )
                }
            }
            assertEquals(1, bodies, "the step body ran once before the failure")
            // Two grants: the runner's own step admission, then the effect admission this body takes explicitly.
            assertEquals(2, checkpoints.sequences.size, "the first attempt took its step and effect admissions")

            // Recovery: the checkpoint never advanced, so the same step is replayed — and must admit again.
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    workflow.resume(
                        run = GovernedRun(WorkflowContext(workflowId = "run-w1"), IDENTITY),
                        observer = NoOpWorkflowObserver,
                        persistence = persistence,
                    )
                }
            }

            assertEquals(2, bodies, "recovery re-executes the step that never checkpointed")
            assertEquals(
                4,
                checkpoints.sequences.size,
                "the resumed attempt takes fresh admissions instead of replaying the orphaned grant: " +
                    checkpoints.sequences,
            )
            assertEquals(
                checkpoints.sequences.distinct().size,
                checkpoints.sequences.size,
                "every admission carries its own sequence; nothing was reused",
            )
            assertTrue(permits[0] !== permits[1], "each attempt holds a distinct permit; no durable permit is replayed")
            assertTrue(
                permits[1].admissionSequence > permits[0].admissionSequence,
                "the fresh grant carries a higher durable sequence: ${permits.map { it.admissionSequence }}",
            )
        }
    }
}
