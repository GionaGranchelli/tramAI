package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch

/**
 * Epic 8.2f P0-B discriminator: a workflow in [WorkflowRecoveryState.Required]
 * must not be executable by calling [WorkflowRunner.resume] directly.
 *
 * The documented delivery lifecycle blocks Required checkpoints pending
 * operator resolution through [WorkflowRecoveryController], and
 * [CheckpointPoller] refuses them. The runtime boundary must enforce the
 * same rule — a blocked workflow cannot be manually revived by bypassing the
 * worker poller. Pre-fix this test is RED: `resume()` loads the checkpoint,
 * validates step index and definition, then executes without inspecting
 * `recoveryState`.
 */
class WorkflowCheckpointResumeDiscriminatorTest {

    private data class State(val request: String, val draft: String? = null, val finalAnswer: String? = null)

    private object StateCodec : WorkflowStateCodec<State> {
        override fun encode(state: State): String = "${state.request}|${state.draft}|${state.finalAnswer}"
        override fun decode(payload: String): State {
            val parts = payload.split("|")
            return State(
                request = parts.getOrElse(0) { "" },
                draft = parts.getOrElse(1) { "" }.ifBlank { null },
                finalAnswer = parts.getOrElse(2) { "" }.ifBlank { null },
            )
        }
    }

    private class RecordingObserver : WorkflowObserver {
        val startedSteps = mutableListOf<String>()
        val completedSteps = mutableListOf<String>()
        var started = false
        var completed = false
        var failed = false

        override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
            started = true
        }

        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
            completed = true
        }

        override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) {
            failed = true
        }

        override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
            startedSteps += stepName
        }

        override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
            completedSteps += stepName
        }

        override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) {
        }
    }

    private fun buildWorkflow(name: String) =
        workflow<State>(name) {
            localStep("step-one") { state, _ -> state.copy(draft = "after-one") }
            localStep("step-two") { state, _ -> state.copy(finalAnswer = "after-two") }
        }.build(clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"))) { it.finalAnswer }

    @Test
    fun `required checkpoint cannot be resumed directly`() {
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = StateCodec,
        )
        val context = WorkflowContext(workflowId = "wf-required")
        val observer = RecordingObserver()
        val workflow = buildWorkflow("required-resume")

        // Seed a compatible checkpoint that points at a real step, then force
        // recovery-required. (The workflow run itself would delete its
        // checkpoint on completion; a suspended mid-run checkpoint is the
        // realistic shape here, so construct it directly.)
        val seed = WorkflowCheckpoint(
            workflowName = "required-resume",
            workflowId = "wf-required",
            nextStepIndex = 1,
            stepExecutions = 1,
            lastCompletedStepName = "step-one",
            statePayload = StateCodec.encode(State(request = "r", draft = "after-one")),
            revision = 0,
            metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
            savedAtEpochMillis = 1_000L,
            recoveryState = WorkflowRecoveryState.Normal,
        )
        runBlocking { store.save(seed) }
        val before = runBlocking { store.load("required-resume", "wf-required") }!!
        val record = WorkflowRecoveryRecord(
            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
            stepName = "step-one",
            attemptId = "attempt-1",
            priorWorkerId = "worker-1",
            detectedAtEpochMillis = before.savedAtEpochMillis,
            idempotencyKey = "idem-1",
            instructions = "manual confirmation required",
        )
        runBlocking {
            store.requireRecovery(
                "required-resume",
                "wf-required",
                expectedRevision = before.revision,
                expectedGeneration = before.checkpointGeneration,
                record = record,
            )
        }
        val required = runBlocking { store.load("required-resume", "wf-required") }!!
        assertThat(required.recoveryState).isEqualTo(WorkflowRecoveryState.Required(record))

        // Direct resume must be rejected before any step executes.
        observer.startedSteps.clear()
        observer.completedSteps.clear()

        val error = runBlocking {
            runCatching {
                workflow.resume(context = context, observer = observer, persistence = persistence)
            }.exceptionOrNull()
        }

        assertThat(error)
            .withFailMessage("direct resume of a Required checkpoint must be rejected")
            .isInstanceOf(WorkflowRecoveryStateException::class.java)

        // No workflow step executed, no completion, checkpoint value-identical.
        assertThat(observer.startedSteps).isEmpty()
        assertThat(observer.completedSteps).isEmpty()
        assertThat(observer.started).isFalse
        assertThat(observer.completed).isFalse
        assertThat(runBlocking { store.load("required-resume", "wf-required") }).isEqualTo(required)
    }

    @Test
    fun `normal resume honors persisted frontier state and step count`() {
        val store = InMemoryWorkflowCheckpointStore()
        val codec = RecordingStateCodec()
        val persistence = WorkflowPersistence(store, codec, deleteCheckpointOnCompletion = false)
        val context = WorkflowContext(workflowId = "wf-frontier")
        val observer = RecordingObserver()
        val workflow = workflow<State>("frontier-resume") {
            localStep("step-zero") { state, _ -> state.copy(draft = "must-not-run") }
            localStep("step-one") { state, _ -> state.copy(finalAnswer = "${state.draft}-one") }
            localStep("step-two") { state, _ -> state.copy(finalAnswer = "${state.finalAnswer}-two") }
        }.build { it.finalAnswer }
        val persistedState = State(request = "request", draft = "persisted")
        runBlocking {
            store.save(
                checkpointFor(
                    workflow = workflow,
                    context = context,
                    state = persistedState,
                    nextStepIndex = 1,
                    stepExecutions = 4,
                    lastCompletedStepName = "step-zero",
                    codec = codec,
                ),
            )
        }

        val result = runBlocking { workflow.resume(context, observer, persistence) }
        val retained = runBlocking { store.load(workflow.name, context.workflowId) }!!

        assertThat(result).isEqualTo("persisted-one-two")
        assertThat(codec.decodedPayloads).containsExactly(codec.encode(persistedState))
        assertThat(observer.startedSteps).containsExactly("step-one", "step-two")
        assertThat(retained.nextStepIndex).isEqualTo(3)
        assertThat(retained.stepExecutions).isEqualTo(6)
        assertThat(retained.revision).isEqualTo(3)
    }

    @Test
    fun `successful resume advances once per step and completion obeys delete policy`() {
        for (deleteOnCompletion in listOf(false, true)) {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence = WorkflowPersistence(store, StateCodec, deleteCheckpointOnCompletion = deleteOnCompletion)
            val context = WorkflowContext(workflowId = "wf-complete-$deleteOnCompletion")
            val workflow = workflow<State>("completion-$deleteOnCompletion") {
                localStep("only") { state, _ -> state.copy(finalAnswer = "done") }
            }.build { it.finalAnswer }
            val seed = runBlocking {
                store.save(
                    checkpointFor(
                        workflow,
                        context,
                        State("request"),
                        nextStepIndex = 0,
                        stepExecutions = 7,
                        lastCompletedStepName = null,
                        codec = StateCodec,
                    ),
                )
            }

            assertThat(runBlocking { workflow.resume(context, persistence = persistence) }).isEqualTo("done")
            val final = runBlocking { store.load(workflow.name, context.workflowId) }
            if (deleteOnCompletion) {
                assertThat(final).isNull()
            } else {
                assertThat(final).isNotNull
                assertThat(final!!.revision).isEqualTo(seed.revision + 1)
                assertThat(final.nextStepIndex).isEqualTo(1)
                assertThat(final.stepExecutions).isEqualTo(8)
                assertThat(final.checkpointGeneration).isEqualTo(seed.checkpointGeneration)
            }
        }
    }

    @Test
    fun `failure suspension and cancellation retain the last durable checkpoint`() {
        val scenarios = listOf<Pair<String, suspend () -> Nothing>>(
            "failure" to { throw IllegalStateException("failed") },
            "suspension" to { throw WorkflowSuspendedException("suspended") },
            "cancellation" to { throw CancellationException("cancelled") },
        )
        scenarios.forEach { (kind, failure) ->
            val store = InMemoryWorkflowCheckpointStore()
            val persistence = WorkflowPersistence(store, StateCodec)
            val context = WorkflowContext(workflowId = "wf-$kind")
            val workflow = workflow<State>("retain-$kind") {
                localStep("stop") { _, _ -> failure() }
            }.build { it.finalAnswer }
            val seed = runBlocking {
                store.save(
                    checkpointFor(
                        workflow,
                        context,
                        State("request"),
                        nextStepIndex = 0,
                        stepExecutions = 2,
                        lastCompletedStepName = null,
                        codec = StateCodec,
                    ),
                )
            }

            val error = runBlocking { runCatching { workflow.resume(context, persistence = persistence) }.exceptionOrNull() }

            when (kind) {
                "failure" -> assertThat(error).isInstanceOf(IllegalStateException::class.java)
                "suspension" -> assertThat(error).isInstanceOf(WorkflowSuspendedException::class.java)
                else -> assertThat(error).isInstanceOf(CancellationException::class.java).hasMessage("cancelled")
            }
            assertThat(runBlocking { store.load(workflow.name, context.workflowId) }).isEqualTo(seed)
        }
    }

    @Test
    fun `resume loaded generation cannot save or delete a recreated successor`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = InMemoryWorkflowCheckpointStore()
        val persistence = WorkflowPersistence(store, StateCodec)
        val context = WorkflowContext(workflowId = "wf-race")
        val workflow = workflow<State>("generation-race") {
            localStep("park") { state, _ ->
                entered.complete(Unit)
                release.await()
                state.copy(finalAnswer = "old-completed")
            }
        }.build { it.finalAnswer }
        val first = store.save(
            checkpointFor(
                workflow,
                context,
                State("old"),
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                codec = StateCodec,
            ),
        )

        val oldResume = async { runCatching { workflow.resume(context, persistence = persistence) } }
        entered.await()
        store.delete(
            workflow.name,
            context.workflowId,
            expectedRevision = first.revision,
            expectedGeneration = first.checkpointGeneration,
        )
        val successor = store.save(
            checkpointFor(
                workflow,
                context,
                State("successor"),
                nextStepIndex = 0,
                stepExecutions = 41,
                lastCompletedStepName = null,
                codec = StateCodec,
            ),
        )
        release.complete(Unit)

        assertThat(oldResume.await().exceptionOrNull())
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(store.load(workflow.name, context.workflowId)).isEqualTo(successor)
    }

    @Test
    fun `stale resumed execution cannot completion-delete a recreated successor`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = InMemoryWorkflowCheckpointStore()
        // Gate inside the codec: the last suspend-free seam between the resume
        // loading G1 and session.complete()'s completion-delete. decode() runs
        // on the async dispatcher, so the blocking latch cannot stall the
        // test thread's event loop.
        val gatedCodec = object : WorkflowStateCodec<State> {
            override fun encode(state: State): String = StateCodec.encode(state)
            override fun decode(payload: String): State {
                entered.countDown()
                release.await()
                return StateCodec.decode(payload)
            }
        }
        val persistence = WorkflowPersistence(store, gatedCodec)
        val context = WorkflowContext(workflowId = "wf-completion-race")
        val workflow = workflow<State>("completion-generation-race") {
            localStep("only") { state, _ -> state.copy(finalAnswer = "done") }
        }.build { it.finalAnswer }
        // G1 seeded past its only step (nextStepIndex == steps.size): the stale
        // resume executes zero steps, so no post-step saveCheckpoint fires and
        // the sole store write is the completion-delete in session.complete().
        val first = store.save(
            checkpointFor(
                workflow,
                context,
                State("old"),
                nextStepIndex = 1,
                stepExecutions = 1,
                lastCompletedStepName = "only",
                codec = StateCodec,
            ),
        )

        val observer = RecordingObserver()
        val oldResume = async(Dispatchers.Default) {
            runCatching { workflow.resume(context, observer = observer, persistence = persistence) }
        }
        entered.await()
        store.delete(
            workflow.name,
            context.workflowId,
            expectedRevision = first.revision,
            expectedGeneration = first.checkpointGeneration,
        )
        val successor = store.save(
            checkpointFor(
                workflow,
                context,
                State("successor"),
                nextStepIndex = 0,
                stepExecutions = 41,
                lastCompletedStepName = null,
                codec = StateCodec,
            ),
        )
        release.countDown()

        // The completion-delete of G1 races the recreated G2 and loses the CAS:
        // the conflict surfaces as a workflow failure, and G2 survives untouched.
        assertThat(oldResume.await().exceptionOrNull())
            .isInstanceOf(WorkflowCheckpointConflictException::class.java)
        assertThat(observer.failed).isTrue
        assertThat(observer.completed).isFalse
        assertThat(store.load(workflow.name, context.workflowId)).isEqualTo(successor)
    }

    private class RecordingStateCodec : WorkflowStateCodec<State> {
        val decodedPayloads = mutableListOf<String>()
        override fun encode(state: State): String = StateCodec.encode(state)
        override fun decode(payload: String): State {
            decodedPayloads += payload
            return StateCodec.decode(payload)
        }
    }

    private fun checkpointFor(
        workflow: Workflow<State, String?>,
        context: WorkflowContext,
        state: State,
        nextStepIndex: Int,
        stepExecutions: Int,
        lastCompletedStepName: String?,
        codec: WorkflowStateCodec<State>,
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = workflow.name,
        workflowId = context.workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = stepExecutions,
        lastCompletedStepName = lastCompletedStepName,
        statePayload = codec.encode(state),
        metadata = workflow.checkpointMetadata(),
        savedAtEpochMillis = 1_000L,
    )
}
