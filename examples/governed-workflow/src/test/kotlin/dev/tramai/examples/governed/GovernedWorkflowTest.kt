package dev.tramai.examples.governed

import dev.tramai.orchestration.WorkflowGateRejectedException
import dev.tramai.orchestration.WorkflowHttpException
import dev.tramai.orchestration.WorkflowStepFailureCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GovernedWorkflowTest {

    private val lowRiskClaim = ClaimInput(
        claimId = "CL-TEST-001",
        amount = 500.0,
        type = "general",
        description = "Damaged goods",
    )

    private val restrictedClaim = ClaimInput(
        claimId = "CL-TEST-002",
        amount = 0.0,
        type = "restricted",
        description = "Restricted material",
    )

    private val highRiskClaim = ClaimInput(
        claimId = "CL-TEST-003",
        amount = 50_000.0,
        type = "liability",
        description = "Large liability claim",
    )

    private val workflow = buildClaimTriageWorkflow(DeterministicClaimClassifier())

    @Test
    fun `low-risk claim passes governed workflow`() { runBlocking {
        val result = workflow.run(initialState = ClaimTriageState(claim = lowRiskClaim))
        assertThat(result.status).isEqualTo("ready-for-review")
    }
    }

    @Test
    fun `restricted claim is rejected by policy gate`() {
        assertThatThrownBy {
            runBlocking {
                workflow.run(initialState = ClaimTriageState(claim = restrictedClaim))
            }
        }
            .isInstanceOf(WorkflowGateRejectedException::class.java)
            .hasMessageContaining("Restricted claim")
    }

    @Test
    fun `high-risk claim without approval is rejected`() {
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = ClaimTriageState(claim = highRiskClaim, approved = false),
                )
            }
        }
            .isInstanceOf(WorkflowGateRejectedException::class.java)
            .hasMessageContaining("human approval")
    }

    @Test
    fun `high-risk claim with approval passes`() { runBlocking {
        val result = workflow.run(
            initialState = ClaimTriageState(claim = highRiskClaim, approved = true),
        )
        assertThat(result.status).isEqualTo("ready-for-review")
    }
    }

    @Test
    fun `governed network policy rejects a non-allowlisted http step`() {
        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            runBlocking { buildGovernedNetworkPolicyWorkflow().run(Unit) }
        }!!

        assertThat(thrown).isInstanceOf(WorkflowHttpException::class.java)
            .hasMessage("Workflow http step was rejected by policy")
        assertThat((thrown as WorkflowHttpException).failureCode)
            .isEqualTo(WorkflowStepFailureCode.POLICY_REJECTED)
    }
}
