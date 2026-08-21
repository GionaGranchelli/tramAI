package dev.tramai.examples.governed

import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowGateRejectedException
import dev.tramai.orchestration.WorkflowObserver
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Failure diagnostics smoke tests for the governed workflow example.
 *
 * Proves that when a governed workflow fails, a developer can understand:
 * - what failed (exception type)
 * - where it failed (gate name)
 * - why it failed (rejection reason)
 * - which steps completed before failure
 * - which step failed
 */
class GovernedWorkflowFailureDiagnosticsTest {

    private val restrictedClaim = ClaimInput(
        claimId = "CL-DIAG-001",
        amount = 0.0,
        type = "restricted",
        description = "Restricted material",
    )

    private val highRiskClaim = ClaimInput(
        claimId = "CL-DIAG-002",
        amount = 50_000.0,
        type = "liability",
        description = "Large liability claim",
    )

    private val workflow = buildClaimTriageWorkflow(DeterministicClaimClassifier())

    // ── Failure paths ────────────────────────────────────────────

    @Test
    fun `restricted claim failure explains policy gate and step trail`() {
        val observer = RecordingWorkflowObserver()

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ClaimTriageState(claim = restrictedClaim),
                    observer = observer,
                )
            }
        }
            .isInstanceOf(WorkflowGateRejectedException::class.java)
            .hasMessageContaining("policy-check")
            .hasMessageContaining("Restricted claim requires manual handling")

        assertThat(observer.startedSteps)
            .containsExactly("classify", "policy-check")

        assertThat(observer.completedSteps)
            .containsExactly("classify")

        assertThat(observer.failedSteps)
            .containsExactly("policy-check")
    }

    @Test
    fun `high-risk unapproved claim failure explains approval gate and step trail`() {
        val observer = RecordingWorkflowObserver()

        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ClaimTriageState(
                        claim = highRiskClaim,
                        approved = false,
                    ),
                    observer = observer,
                )
            }
        }
            .isInstanceOf(WorkflowGateRejectedException::class.java)
            .hasMessageContaining("approval-required")
            .hasMessageContaining("High-risk claim requires human approval")

        assertThat(observer.startedSteps)
            .containsExactly("classify", "policy-check", "approval-required")

        assertThat(observer.completedSteps)
            .containsExactly("classify", "policy-check")

        assertThat(observer.failedSteps)
            .containsExactly("approval-required")
    }

    // ── Success path (clean diagnostic trail) ─────────────────────

    @Test
    fun `approved high-risk claim has clean diagnostic trail`() { runBlocking {
        val observer = RecordingWorkflowObserver()

        val result = workflow.run(
            initialState = ClaimTriageState(
                claim = highRiskClaim,
                approved = true,
            ),
            observer = observer,
        )

        assertThat(result.status).isEqualTo("ready-for-review")

        assertThat(observer.startedSteps)
            .containsExactly("classify", "policy-check", "approval-required", "finalize")

        assertThat(observer.completedSteps)
            .containsExactly("classify", "policy-check", "approval-required", "finalize")

        assertThat(observer.failedSteps).isEmpty()
    }
    }

    // ── Local observer ───────────────────────────────────────────

    private class RecordingWorkflowObserver : WorkflowObserver {
        val startedSteps = mutableListOf<String>()
        val completedSteps = mutableListOf<String>()
        val failedSteps = mutableListOf<String>()

        override fun onStepStarted(
            workflowName: String,
            stepName: String,
            context: WorkflowContext,
        ) {
            startedSteps += stepName
        }

        override fun onStepCompleted(
            workflowName: String,
            stepName: String,
            context: WorkflowContext,
        ) {
            completedSteps += stepName
        }

        override fun onStepFailed(
            workflowName: String,
            stepName: String,
            error: Throwable,
            context: WorkflowContext,
        ) {
            failedSteps += stepName
        }
    }
}
