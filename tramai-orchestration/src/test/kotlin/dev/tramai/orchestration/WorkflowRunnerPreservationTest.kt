package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Epic 5.3 — proves the failure-isolation wiring at the [WorkflowRunner]
 * boundary through the PUBLIC workflow API. A throwing observer (telemetry /
 * secondary side effect) must never change the business outcome: a successful
 * workflow stays successful, a failing workflow keeps its original exception
 * as primary.
 *
 * Each test passes a deliberately throwing [WorkflowObserver] to
 * [Workflow.run] and asserts the business outcome is preserved.
 */
class WorkflowRunnerPreservationTest {

    @Test
    fun `workflow success survives throwing onWorkflowCompleted observer`() {
        val stepRan = AtomicInteger(0)
        val observer = ThrowingObserver(throwOnWorkflowCompleted = true)
        val workflow = workflow<Unit>("preserve-success-completed") {
            localStep(name = "step-a", transform = { state, _ -> stepRan.incrementAndGet(); state })
        }.build { Unit }

        runBlocking { workflow.run(initialState = Unit, observer = observer) }

        // No exception escapes: the run completed normally and the step ran.
        assertThat(stepRan.get()).isEqualTo(1)
    }

    @Test
    fun `workflow failure keeps original exception primary when onWorkflowFailed throws`() {
        val observer = ThrowingObserver(throwOnWorkflowFailed = true)
        val workflow = workflow<Unit>("preserve-failure-failed") {
            localStep(name = "step-a", transform = { _, _ -> throw IllegalStateException("primary-failure") })
        }.build { Unit }

        assertThatThrownBy {
            runBlocking { workflow.run(initialState = Unit, observer = observer) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("primary-failure")
    }

    @Test
    fun `workflow success survives throwing onWorkflowStarted observer`() {
        val stepRan = AtomicInteger(0)
        val observer = ThrowingObserver(throwOnWorkflowStarted = true)
        val workflow = workflow<Unit>("preserve-success-started") {
            localStep(name = "step-a", transform = { state, _ -> stepRan.incrementAndGet(); state })
        }.build { Unit }

        runBlocking { workflow.run(initialState = Unit, observer = observer) }

        assertThat(stepRan.get()).isEqualTo(1)
    }

    @Test
    fun `workflow success survives throwing step observers`() {
        val stepRan = AtomicInteger(0)
        val observer = ThrowingObserver(throwOnStepStarted = true, throwOnStepCompleted = true)
        val workflow = workflow<Unit>("preserve-success-step-observers") {
            localStep(name = "step-a", transform = { state, _ -> stepRan.incrementAndGet(); state })
        }.build { Unit }

        runBlocking { workflow.run(initialState = Unit, observer = observer) }

        assertThat(stepRan.get()).isEqualTo(1)
    }

    @Test
    fun `workflow failure survives throwing onStepFailed observer`() {
        val observer = ThrowingObserver(throwOnStepFailed = true)
        val workflow = workflow<Unit>("preserve-failure-step-failed") {
            localStep(name = "step-a", transform = { _, _ -> throw IllegalStateException("step-primary") })
        }.build { Unit }

        assertThatThrownBy {
            runBlocking { workflow.run(initialState = Unit, observer = observer) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("step-primary")
    }

    @Test
    fun `workflow success survives throwing onWorkflowEvent observer`() {
        val stepRan = AtomicInteger(0)
        val observer = ThrowingObserver(throwOnWorkflowEvent = true)
        val workflow = workflow<Unit>("preserve-success-event") {
            localStep(name = "step-a", transform = { state, _ -> stepRan.incrementAndGet(); state })
        }.build { Unit }

        runBlocking { workflow.run(initialState = Unit, observer = observer) }

        assertThat(stepRan.get()).isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // Fixture: observer that throws IllegalStateException from any callback
    // flagged with `throwOn*`. Everything else uses the interface defaults.
    // -------------------------------------------------------------------------

    private class ThrowingObserver(
        val throwOnWorkflowStarted: Boolean = false,
        val throwOnWorkflowEvent: Boolean = false,
        val throwOnStepStarted: Boolean = false,
        val throwOnStepCompleted: Boolean = false,
        val throwOnStepFailed: Boolean = false,
        val throwOnWorkflowCompleted: Boolean = false,
        val throwOnWorkflowFailed: Boolean = false,
    ) : WorkflowObserver {
        override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
            if (throwOnWorkflowStarted) throw IllegalStateException("boom-workflow-started")
        }

        override fun onWorkflowEvent(
            workflowName: String,
            name: String,
            attributes: Map<String, Any?>,
            context: WorkflowContext,
        ) {
            if (throwOnWorkflowEvent) throw IllegalStateException("boom-workflow-event")
        }

        override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
            if (throwOnStepStarted) throw IllegalStateException("boom-step-started")
        }

        override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) {
            if (throwOnStepCompleted) throw IllegalStateException("boom-step-completed")
        }

        override fun onStepFailed(
            workflowName: String,
            stepName: String,
            error: Throwable,
            context: WorkflowContext,
        ) {
            if (throwOnStepFailed) throw IllegalStateException("boom-step-failed")
        }

        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
            if (throwOnWorkflowCompleted) throw IllegalStateException("boom-workflow-completed")
        }

        override fun onWorkflowFailed(name: String, error: Throwable, context: WorkflowContext) {
            if (throwOnWorkflowFailed) throw IllegalStateException("boom-workflow-failed")
        }
    }
}
