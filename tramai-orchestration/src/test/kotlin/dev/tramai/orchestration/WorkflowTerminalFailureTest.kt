package dev.tramai.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every terminal failure of a run — initial or resumed — is handled through the same
 * choke point in the runner. These tests pin that contract on BOTH entry points: a
 * cancellation is rethrown to the caller and is never reported as a failure.
 *
 * The point of testing both paths is that an edit at the shared handler could otherwise
 * regress cancellation semantics everywhere at once, and a cancelled run silently reported
 * as FAILED is exactly the kind of misattribution this task exists to prevent.
 */
class WorkflowTerminalFailureTest {
    private val stateCodec =
        object : WorkflowStateCodec<String> {
            override fun encode(state: String): String = state

            override fun decode(payload: String): String = payload
        }

    @Test
    fun `a cancelled run rethrows the cancellation and reports no failure`() =
        runBlocking<Unit> {
            val observer = FailureRecordingObserver()
            val reachedStep = CompletableDeferred<Unit>()
            val workflow =
                workflow<String>(RUN_WORKFLOW) {
                    localStep("hold") { _, _ ->
                        reachedStep.complete(Unit)
                        awaitCancellation()
                    }
                }.build { it }

            val run =
                launch {
                    workflow.run(initialState = "seed", observer = observer)
                }
            reachedStep.await()
            run.cancelAndJoin()

            // The run really executed before being cancelled, the cancellation propagated
            // (a swallowed one would leave the job completed, not cancelled), and the
            // observer was never told the run failed.
            assertThat(observer.startedWorkflow).isTrue()
            assertThat(run.isCancelled).isTrue()
            assertThat(observer.failureCount).isZero()
        }

    @Test
    fun `a cancelled resume rethrows the cancellation and reports no failure`() =
        runBlocking<Unit> {
            val store = InMemoryWorkflowCheckpointStore()
            val persistence =
                WorkflowPersistence(
                    checkpointStore = store,
                    stateCodec = stateCodec,
                    deleteCheckpointOnCompletion = false,
                )
            val observer = FailureRecordingObserver()
            val reachedStep = CompletableDeferred<Unit>()
            var blockOnSecondExecution = false
            val workflow =
                workflow<String>(RESUME_WORKFLOW) {
                    localStep("one") { state, _ -> "$state-one" }
                    localStep("two") { state, _ ->
                        if (blockOnSecondExecution) {
                            reachedStep.complete(Unit)
                            awaitCancellation()
                        } else {
                            "$state-two"
                        }
                    }
                }.build { it }
            val context = WorkflowContext(workflowId = "cancel-resume-1")

            workflow.run(
                initialState = "seed",
                context = context,
                observer = observer,
                persistence = persistence,
            )
            // Rewind so the delayed step runs again — this time it blocks long enough to cancel.
            val current = store.load(RESUME_WORKFLOW, context.workflowId)!!
            store.save(
                current.copy(
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "one",
                    statePayload = "seed-one",
                ),
                expectedRevision = current.revision,
            )
            blockOnSecondExecution = true

            val resume =
                launch {
                    workflow.resume(context = context, observer = observer, persistence = persistence)
                }
            reachedStep.await()
            resume.cancelAndJoin()

            assertThat(resume.isCancelled).isTrue()
            assertThat(observer.failureCount).isZero()
        }

    private companion object {
        const val RUN_WORKFLOW = "cancel-terminal-run"
        const val RESUME_WORKFLOW = "cancel-terminal-resume"
    }
}

/** Records the two things the terminal-failure contract is about, and ignores the rest. */
private class FailureRecordingObserver : WorkflowObserver by NoOpWorkflowObserver {
    var failureCount = 0
        private set

    var startedWorkflow = false
        private set

    override fun onWorkflowStarted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        startedWorkflow = true
    }

    override fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        failureCount++
    }
}
