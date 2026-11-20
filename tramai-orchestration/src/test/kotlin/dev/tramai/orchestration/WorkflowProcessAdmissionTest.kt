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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E5-E7: every instrumented process dispatch takes its own durable, single-use grant.
 *
 * Two fences stand in front of a process: the step admission (E11) and the effect admission (E5-E7). They fire in that
 * order, so a cancelled run is normally stopped at the step. The cases that decide this slice are the ones where the
 * step *was* admitted: only then does the effect fence decide, and only there can an unfenced dispatch happen.
 *
 * Ordering is forced by a store decorator, never by sleeps: it grants the n-th admission and commits a cancellation
 * immediately after it. In the single-step fixtures admission #1 is the step fence and #2 the process fence; the branch
 * fixture asks once per nested step before it asks for the process.
 *
 * Where a case must prove a process did NOT run, its command is an executable that does not exist: a dispatch attempt
 * would surface as `START_FAILED`, so getting an authoritative refusal instead is the positive discriminator. Where a
 * case must prove a process DID run, the command leaves a marker file behind.
 */
class WorkflowProcessAdmissionTest {
    @Test
    fun `a permit is consumed exactly once`() {
        val permit =
            WorkflowProcessPermit(stepName = "deploy", effectKind = PROCESS_EFFECT_KIND, admissionSequence = 7L)

        assertTrue(permit.tryConsume(), "the first consumption authorizes one dispatch")

        val reuse = assertFailsWith<IllegalStateException> { permit.tryConsume() }
        assertTrue(
            reuse.message!!.contains("already consumed"),
            "reuse must name the programming error, not silently re-dispatch: ${reuse.message}",
        )
    }

    @Test
    fun `an ordinary run dispatches its process without asking any authority`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val output =
            runBlocking {
                echoWorkflow().run("s", WorkflowContext(workflowId = "run-1"), NoOpWorkflowObserver, persistence(store))
            }

        assertTrue(output.contains("dispatched"), "the process really ran: '$output'")
        assertEquals(0, store.admitCount, "an ungoverned run must ask no authority at all")
    }

    @Test
    fun `a cancelled governed run is stopped at the step, before the process`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking { cancellingWorkflow(store).run("s", governed(), NoOpWorkflowObserver, persistence(store)) }
            }

        assertTrue(error.message!!.contains("cannot enter step"), "the step fence refused first: ${error.message}")
        assertEquals(3, store.admitCount, "branch, cancel step, then the refused deploy: the effect was never asked")
        assertEquals(2, store.grantedAdmissions, "both pre-cancellation steps were admitted")
    }

    @Test
    fun `an admitted step does not authorize the process it wants to start`() {
        // The step admission is granted; cancellation commits immediately after it; the process admission is refused.
        val store = RecordingStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking { unstartableWorkflow().run("s", governed(), NoOpWorkflowObserver, persistence(store)) }
            }

        assertTrue(error.message!!.contains("PROCESS"), "the refusal names the effect: ${error.message}")
        assertTrue(error.message!!.contains("step 'deploy'"), "the refusal names the step: ${error.message}")
        assertEquals(2, store.admitCount, "the step was admitted, then the effect was refused")
        assertEquals(1, store.grantedAdmissions, "only the step admission was granted")
    }

    @Test
    fun `an effect admitted before cancellation still dispatches and leaves the run uncertain`() {
        val marker = Files.createTempDirectory("tramai-process-permit").resolve("dispatched")
        // The second admission is the process one: granted, and cancellation commits immediately after it.
        val store = RecordingStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        // The run cannot finish: the checkpoint row is terminal by the time the step completes. What matters is that
        // the process started anyway, because its admission committed before the cancellation did.
        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { touchWorkflow(marker).run("s", governed(), NoOpWorkflowObserver, persistence(store)) }
        }

        assertTrue(Files.exists(marker), "an admitted dispatch is permitted even though cancellation followed it")
        assertEquals(2, store.admitCount, "one step admission and one effect admission")
        assertEquals(
            WorkflowLifecycleStatus.CANCELLED_UNCERTAIN,
            runBlocking { store.lifecycle("checkout", "run-1") }!!.status,
            "an effect was admitted, so the cancellation is conservatively uncertain -- never reported clean",
        )
    }

    @Test
    fun `a store that cannot admit refuses the process`() {
        // The step fence is answered normally; the effect fence meets the store that cannot admit at all.
        val store = RecordingStore(InMemoryWorkflowCheckpointStore(), refuseAdmissionsFromAdmit = 2)

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking { unstartableWorkflow().run("s", governed(), NoOpWorkflowObserver, persistence(store)) }
        }

        assertEquals(1, store.grantedAdmissions, "the step was admitted and the effect was not")
    }

    @Test
    fun `a governed run with no durable session never dispatches`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                // No persistence at all: the run still claims the durable guarantees, so it has nothing to ask.
                runBlocking { unstartableWorkflow().run("s", governed(), NoOpWorkflowObserver, persistence = null) }
            }

        assertTrue(
            error.message!!.contains("no durable checkpoint authority"),
            "fail-closed reason: ${error.message}",
        )
    }

    @Test
    fun `each dispatch takes a fresh admission and never reuses a permit`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val output = runBlocking { twoProcessWorkflow().run("s", governed(), NoOpWorkflowObserver, persistence(store)) }

        assertEquals(4, store.admitCount, "two steps and two dispatches means four admissions")
        assertEquals(4, store.grantedSequences.size, "every admission was granted")
        assertEquals(
            4,
            store.grantedSequences.distinct().size,
            "each dispatch consumes its own grant; no permit is reused across attempts",
        )
        assertTrue(output.contains("step-0") && output.contains("step-1"), "both processes ran: '$output'")
    }

    @Test
    fun `the codex boundary takes its own process grant`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val output =
            runBlocking {
                codexWorkflow(ECHO_EXECUTABLE).run("s", governed(), NoOpWorkflowObserver, persistence(store))
            }

        assertTrue(output.contains("dispatched"), "the CLI process really ran: '$output'")
        assertEquals(2, store.admitCount, "one step admission plus the codex process admission")
    }

    @Test
    fun `the codex boundary refuses the process instead of reporting a failed launch`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        // A CLI path that cannot start: START_FAILED is what a launch attempt would look like, so the refusal proves
        // the process was never acquired.
        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    codexWorkflow("/nonexistent/tramai-codex")
                        .run("s", governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertTrue(error.message!!.contains("PROCESS"), "the refusal names the effect: ${error.message}")
    }

    @Test
    fun `the hermes boundary takes its own process grant`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore())

        val output =
            runBlocking {
                hermesWorkflow(ECHO_EXECUTABLE).run("s", governed(), NoOpWorkflowObserver, persistence(store))
            }

        assertTrue(output.contains("dispatched"), "the CLI process really ran: '$output'")
        assertEquals(2, store.admitCount, "one step admission plus the hermes process admission")
    }

    @Test
    fun `the hermes boundary refuses the process instead of reporting a failed launch`() {
        val store = RecordingStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    hermesWorkflow("/nonexistent/tramai-hermes")
                        .run("s", governed(), NoOpWorkflowObserver, persistence(store))
                }
            }

        assertTrue(error.message!!.contains("PROCESS"), "the refusal names the effect: ${error.message}")
    }
}

/** One process step that really runs, so a dispatched effect is observable in the result. */
private fun echoWorkflow(): Workflow<String, String> =
    workflow<String>("checkout") {
        shellStep(
            name = "echo",
            config = ShellStepConfig(allowedCommands = setOf(ECHO_EXECUTABLE)),
            definition = ShellCommandDefinition(executable = ECHO_EXECUTABLE),
            command = { _, _ -> ShellCommand(command = listOf(ECHO_EXECUTABLE, "dispatched")) },
            merge = { _, result, _ -> result.stdout },
        )
    }.build { it }

/** One process step whose executable cannot start: any launch attempt is START_FAILED, never a refusal. */
private fun unstartableWorkflow(): Workflow<String, String> =
    workflow<String>("checkout") {
        shellStep(
            name = "deploy",
            config = ShellStepConfig(allowedCommands = setOf(UNSTARTABLE_EXECUTABLE)),
            definition = ShellCommandDefinition(executable = UNSTARTABLE_EXECUTABLE),
            command = { _, _ -> ShellCommand(command = listOf(UNSTARTABLE_EXECUTABLE)) },
            merge = { state, _, _ -> state },
        )
    }.build { it }

/** One process step that leaves a durable marker, so "it started" is observable after a run that cannot finish. */
private fun touchWorkflow(marker: Path): Workflow<String, String> =
    workflow<String>("checkout") {
        shellStep(
            name = "deploy",
            config = ShellStepConfig(allowedCommands = setOf(TOUCH_EXECUTABLE)),
            definition = ShellCommandDefinition(executable = TOUCH_EXECUTABLE),
            command = { _, _ -> ShellCommand(command = listOf(TOUCH_EXECUTABLE, marker.toString())) },
            merge = { state, _, _ -> state },
        )
    }.build { it }

/** Two process steps, so the second dispatch must take its own grant. */
private fun twoProcessWorkflow(): Workflow<String, String> =
    workflow<String>("checkout") {
        repeat(2) { index ->
            shellStep(
                name = "echo-$index",
                config = ShellStepConfig(allowedCommands = setOf(ECHO_EXECUTABLE)),
                definition = ShellCommandDefinition(executable = ECHO_EXECUTABLE),
                command = { _, _ -> ShellCommand(command = listOf(ECHO_EXECUTABLE, "step-$index")) },
                merge = { state, result, _ -> state + result.stdout },
            )
        }
    }.build { it }

/**
 * Cancels the run from a nested step, then tries to dispatch from the next nested step.
 *
 * The cancellation has to land inside a nested step: a top-level cancellation is caught by the next checkpoint save, so
 * the run would die there and never reach the following step's fence.
 */
private fun cancellingWorkflow(store: WorkflowCheckpointStore): Workflow<String, String> =
    workflow<String>("checkout") {
        branchStep(name = "route", select = { "a" }) {
            branch("a") {
                localStep("cancel-run") { state, _ ->
                    cancelRun(store)
                    state
                }
                shellStep(
                    name = "deploy",
                    config = ShellStepConfig(allowedCommands = setOf(UNSTARTABLE_EXECUTABLE)),
                    definition = ShellCommandDefinition(executable = UNSTARTABLE_EXECUTABLE),
                    command = { _, _ -> ShellCommand(command = listOf(UNSTARTABLE_EXECUTABLE)) },
                    merge = { state, _, _ -> state },
                )
            }
        }
    }.build { it }

private fun codexWorkflow(cliPath: String): Workflow<String, String> =
    workflow<String>("checkout") {
        codexStep(
            name = "codex",
            config = CodexStepConfig(cliPath = cliPath),
            prompt = { _, _ -> "dispatched" },
            merge = { _, output, _ -> output },
        )
    }.build { it }

private fun hermesWorkflow(cliPath: String): Workflow<String, String> =
    workflow<String>("checkout") {
        hermesStep(
            name = "hermes",
            config = HermesStepConfig(cliPath = cliPath),
            prompt = { _, _ -> "dispatched" },
            merge = { _, output, _ -> output },
        )
    }.build { it }

private fun persistence(store: WorkflowCheckpointStore): WorkflowPersistence<String> =
    WorkflowPersistence(checkpointStore = store, stateCodec = PlainCodec)

private fun governed(): GovernedRun = GovernedRun(WorkflowContext(workflowId = "run-1"), GOVERNED)

private suspend fun cancelRun(store: WorkflowCheckpointStore) {
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

/**
 * Decorates the real store to force an admission ordering and to count what the runtime asked for.
 *
 * [cancelAfterAdmit] commits a cancellation immediately after that granted admission, which is how "the step was
 * admitted but the effect was not" and "the effect was admitted and cancellation followed" become deterministic.
 * [refuseAdmissionsFromAdmit] makes the store itself refuse from that admission on, standing in for a profile that
 * cannot decide admission at all.
 */
private class RecordingStore(
    private val delegate: WorkflowCheckpointStore,
    private val cancelAfterAdmit: Int? = null,
    private val refuseAdmissionsFromAdmit: Int? = null,
) : WorkflowCheckpointStore by delegate {
    private val mutex = Mutex()
    private var attempts = 0
    private val granted = mutableListOf<Long>()

    val admitCount: Int
        get() = attempts

    /** How many admissions the authority actually granted, in order. */
    val grantedAdmissions: Int
        get() = granted.size

    /** Every sequence the authority issued, in order: the durable identity of each grant. */
    val grantedSequences: List<Long>
        get() = granted

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        mutex.withLock {
            attempts += 1
            if (refuseAdmissionsFromAdmit != null && attempts >= refuseAdmissionsFromAdmit) {
                throw WorkflowCancellationUnsupportedException(
                    "${delegate::class.simpleName} cannot admit in this test",
                )
            }
            val admission = delegate.admit(request)
            if (admission.admitted) {
                granted += admission.sequence
                if (cancelAfterAdmit == attempts) {
                    cancelRun(delegate)
                }
            }
            admission
        }
}

private object PlainCodec : WorkflowStateCodec<String> {
    override fun encode(state: String): String = state

    override fun decode(payload: String): String = payload
}

/** Commands every Linux CI image has, and whose own output proves the process really started. */
private const val ECHO_EXECUTABLE = "/bin/echo"
private const val TOUCH_EXECUTABLE = "/usr/bin/touch"

/** An executable that cannot be launched: dispatching it surfaces START_FAILED, so a refusal is distinguishable. */
private const val UNSTARTABLE_EXECUTABLE = "/nonexistent/tramai-process-boundary"

private val GOVERNED =
    GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId("checkout"),
                configuration = WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                environmentId = EnvironmentId("test"),
                deploymentId = DeploymentId("dep"),
            ),
        runId = RunId("run-1"),
    )
