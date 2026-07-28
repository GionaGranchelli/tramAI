package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

/**
 * Contract tests proving that cancellation during workflow execution
 * escapes without normal failure classification, retry, or observer
 * contamination.
 */
class WorkflowCancellationContractTest {

    // -------------------------------------------------------------------------
    // Test 1: Direct workflow execution — cancellation from a local step
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation from a local step escapes without failure classification`() {
        val observer = RecordingCancellationObserver()
        val workflow = workflow<Unit>("cancel-direct") {
            localStep(
                name = "cancelling-step",
                transform = { _, _ -> throw CancellationException("cancelled by step") },
            )
            localStep(
                name = "must-not-execute",
                transform = { state, _ -> error("must not be reached") },
            )
        }.build { Unit }

        assertThatThrownBy {
            runBlocking { workflow.run(initialState = Unit, observer = observer) }
        }.isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled by step")

        // No step-failure or workflow-failure events
        assertThat(observer.failedSteps).isEmpty()
        assertThat(observer.workflowFailed).isFalse()
        assertThat(observer.workflowCompleted).isFalse()

        // Only the cancelling step was started
        assertThat(observer.startedSteps).containsExactly("cancelling-step")
        assertThat(observer.completedSteps).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Test 2: Workflow resume — cancellation preserves classification
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation during resume preserves cancellation classification`() {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val observer = RecordingCancellationObserver()
        val workflow = workflow<Unit>("cancel-resume") {
            localStep(
                name = "cancelling-step",
                transform = { _, _ -> throw CancellationException("cancelled during resume") },
            )
        }.build { Unit }

        val context = WorkflowContext(workflowId = "resume-cancel-test")

        // Seed a checkpoint at step index 0 (the cancelling step is next)
        runBlocking {
            checkpointStore.save(
                checkpoint = WorkflowCheckpoint(
                    workflowName = workflow.name,
                    workflowId = "resume-cancel-test",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = UnitCodec.encode(Unit),
                    metadata = workflow.checkpointMetadata(),
                ),
            )
        }

        // Resume — the step throws CancellationException
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = context,
                    observer = observer,
                    persistence = WorkflowPersistence(
                        checkpointStore = checkpointStore,
                        stateCodec = UnitCodec,
                        leaseStore = leaseStore,
                        leasePolicy = WorkflowLeasePolicy(
                            ownerId = "resume-cancellation-test",
                        ),
                    ),
                )
            }
        }.isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled during resume")

        // No failure classification (cancellation escapes cleanly)
        assertThat(observer.failedSteps).isEmpty()
        assertThat(observer.workflowFailed).isFalse()
        assertThat(observer.workflowCompleted).isFalse()

        // Lease was released
        runBlocking {
            assertThat(leaseStore.currentLease(workflow.name, context.workflowId)).isNull()
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Parallel branch cancellation
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation in one parallel branch cancels siblings without onStepFailed`() {
        val observer = RecordingCancellationObserver()
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingCancelled = CompletableDeferred<Unit>()

        val workflow = workflow<Unit>("cancel-parallel") {
            parallelStep(
                name = "parallel",
                items = { listOf("slow", "cancel") },
                invoke = { item ->
                    when (item as String) {
                        "slow" -> {
                            siblingStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                siblingCancelled.complete(Unit)
                            }
                        }
                        "cancel" -> {
                            siblingStarted.await()
                            throw CancellationException("branch cancelled")
                        }
                        else -> error("unexpected item: $item")
                    }
                },
                merge = { state, _ -> state },
            )
            localStep(
                name = "must-not-execute",
                transform = { state, _ -> error("must not be reached") },
            )
        }.build { Unit }

        assertThatThrownBy {
            runBlocking { workflow.run(initialState = Unit, observer = observer) }
        }.isInstanceOf(CancellationException::class.java)

        // The slow branch was actually cancelled by its sibling
        assertThat(siblingCancelled.isCompleted).isTrue()

        // No parallel branch received onStepFailed
        assertThat(observer.failedSteps).isEmpty()

        // No workflow failure or completion
        assertThat(observer.workflowFailed).isFalse()
        assertThat(observer.workflowCompleted).isFalse()

        // The subsequent step was never started
        assertThat(observer.startedSteps.any { it.contains("must-not-execute") }).isFalse()
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private object UnitCodec : WorkflowStateCodec<Unit> {
        override fun encode(state: Unit): String = "unit"
        override fun decode(payload: String): Unit = Unit
    }

    private class RecordingCancellationObserver : WorkflowObserver {
        val startedSteps = mutableListOf<String>()
        val completedSteps = mutableListOf<String>()
        val failedSteps = mutableListOf<String>()
        var workflowFailed = false
        var workflowCompleted = false

        override fun onWorkflowStarted(name: String, context: WorkflowContext) = Unit
        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
            workflowCompleted = true
        }
        override fun onWorkflowFailed(name: String, error: Throwable, context: WorkflowContext) {
            workflowFailed = true
        }
        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) = Unit
        override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
            startedSteps += stepName
        }
        override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
            completedSteps += stepName
        }
        override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) {
            failedSteps += stepName
        }
        override fun onScheduledTick(
            workflowName: String,
            scheduledFireAt: java.time.Instant,
            context: WorkflowContext,
        ) = Unit
        override fun onSkippedTick(
            workflowName: String,
            scheduledFireAt: java.time.Instant,
            reason: String,
            context: WorkflowContext,
        ) = Unit

        fun reset() {
            startedSteps.clear()
            completedSteps.clear()
            failedSteps.clear()
            workflowFailed = false
            workflowCompleted = false
        }
    }
}
