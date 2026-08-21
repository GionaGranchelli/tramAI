package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventFailurePolicy
import dev.tramai.core.observation.event.RuntimeEvents
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Epic 5.3 — workflow observer lifecycle matrix.
 *
 * Every [WorkflowObserver] callback is exercised with a throwing delegate. The
 * invariant under test: a telemetry failure is contained and can never change
 * the workflow's business outcome (success stays success, the primary failure
 * stays primary); cancellation always escapes; FAIL_CLOSED events propagate.
 */
class FailureIsolatingWorkflowObserverTest {

    private val workflowName = "test-workflow"
    private val context = WorkflowContext()
    private val now = Instant.now()

    private val throwingDelegate = object : WorkflowObserver {
        override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onWorkflowEvent(workflowName: String, name: String, attributes: Map<String, Any?>, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onWorkflowEvent(workflowName: String, event: RuntimeEvent, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onScheduledTick(workflowName: String, scheduledFireAt: Instant, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onSkippedTick(workflowName: String, scheduledFireAt: Instant, reason: String, context: WorkflowContext) = throw IllegalStateException("boom")
        override fun onMissedTick(workflowName: String, scheduledFireAt: Instant, reason: String, context: WorkflowContext) = throw IllegalStateException("boom")
    }

    private fun isolated(): WorkflowObserver = FailureIsolatingWorkflowObserver(throwingDelegate)

    @Test
    fun `workflow started failure is contained`() {
        isolated().onWorkflowStarted(workflowName, context)
    }

    @Test
    fun `workflow event legacy failure is contained`() {
        isolated().onWorkflowEvent(workflowName, "tramai.workflow.event", emptyMap(), context)
    }

    @Test
    fun `workflow event runtime failure is contained for fail-open events`() {
        val event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SUSPENDED) {
            set(RuntimeAttributes.WORKFLOW_ID_BARE, context.workflowId)
        }
        isolated().onWorkflowEvent(workflowName, event, context)
    }

    @Test
    fun `workflow event failure propagates for fail-closed events`() {
        val event = RuntimeEvent.of(
            RuntimeEvents.WORKFLOW_SUSPENDED.copy(
                name = "tramai.workflow.fail-closed.probe",
                failurePolicy = RuntimeEventFailurePolicy.FAIL_CLOSED,
            ),
        ) {
            set(RuntimeAttributes.WORKFLOW_ID_BARE, context.workflowId)
        }
        assertFailsWith<IllegalStateException> {
            isolated().onWorkflowEvent(workflowName, event, context)
        }
    }

    @Test
    fun `step started failure is contained`() {
        isolated().onStepStarted(workflowName, "step-a", context)
    }

    @Test
    fun `step completed failure is contained`() {
        isolated().onStepCompleted(workflowName, "step-a", context)
    }

    @Test
    fun `step failed failure is contained`() {
        isolated().onStepFailed(workflowName, "step-a", IllegalStateException("primary"), context)
    }

    @Test
    fun `workflow completed failure is contained`() {
        isolated().onWorkflowCompleted(workflowName, context)
    }

    @Test
    fun `workflow failed failure is contained and primary error preserved`() {
        isolated().onWorkflowFailed(workflowName, IllegalStateException("primary"), context)
    }

    @Test
    fun `scheduled tick failure is contained`() {
        isolated().onScheduledTick(workflowName, now, context)
    }

    @Test
    fun `skipped tick failure is contained`() {
        isolated().onSkippedTick(workflowName, now, "reason", context)
    }

    @Test
    fun `missed tick failure is contained`() {
        isolated().onMissedTick(workflowName, now, "reason", context)
    }

    @Test
    fun `cancellation from an observer always escapes unchanged`() {
        val cancellation = CancellationException("observer-cancelled")
        val cancellingDelegate = object : WorkflowObserver {
            override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) = throw cancellation
            override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) = throw cancellation
            override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) = throw cancellation
            override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) = throw cancellation
            override fun onStepCompleted(workflowName: String, stepName: String, context: WorkflowContext) = throw cancellation
            override fun onStepFailed(workflowName: String, stepName: String, error: Throwable, context: WorkflowContext) = throw cancellation
            override fun onScheduledTick(workflowName: String, scheduledFireAt: Instant, context: WorkflowContext) = throw cancellation
            override fun onSkippedTick(workflowName: String, scheduledFireAt: Instant, reason: String, context: WorkflowContext) = throw cancellation
            override fun onMissedTick(workflowName: String, scheduledFireAt: Instant, reason: String, context: WorkflowContext) = throw cancellation
            override fun onWorkflowEvent(workflowName: String, name: String, attributes: Map<String, Any?>, context: WorkflowContext) = throw cancellation
        }
        val obs = FailureIsolatingWorkflowObserver(cancellingDelegate)
        assertSame(cancellation, assertFailsWith<CancellationException> {
            obs.onWorkflowStarted(workflowName, context)
        })
        assertSame(cancellation, assertFailsWith<CancellationException> {
            obs.onWorkflowCompleted(workflowName, context)
        })
    }

    @Test
    fun `successful callbacks are forwarded to the delegate`() {
        val received = mutableListOf<String>()
        val delegate = object : WorkflowObserver {
            override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) {
                received += "started"
            }

            override fun onStepStarted(workflowName: String, stepName: String, context: WorkflowContext) {
                received += "step"
            }

            override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) {
                received += "completed"
            }
        }
        val obs = FailureIsolatingWorkflowObserver(delegate)
        obs.onWorkflowStarted(workflowName, context)
        obs.onStepStarted(workflowName, "step-a", context)
        obs.onWorkflowCompleted(workflowName, context)
        assertTrue(received == listOf("started", "step", "completed"))
    }
}
