package dev.tramai.engine.tool

import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ToolAuthorizationCoordinatorTest {
    @Test fun `allow maps to allow`() { runBlocking { assertThat(ToolAuthorizationCoordinator(policyHelper()).authorize(toolRequest(testTool()), "{}")).isEqualTo(ToolAuthorizationDecision.Allow) } }
    @Test fun `deny retains original decision`() { runBlocking {
        val decision = PolicyDecision.Deny("no", "blocked"); val actual = ToolAuthorizationCoordinator(policyHelper { decision }).authorize(toolRequest(testTool()), "{}")
        assertThat(actual).isInstanceOf(ToolAuthorizationDecision.Deny::class.java); assertThat((actual as ToolAuthorizationDecision.Deny).decision).isSameAs(decision)
    } }
    @Test fun `approval retains original decision`() { runBlocking {
        val decision = PolicyDecision.RequireApproval(ApprovalRequirement("test-tool", "digest", "reason", 1)); val actual = ToolAuthorizationCoordinator(policyHelper { decision }).authorize(toolRequest(testTool()), "{}")
        assertThat(actual).isInstanceOf(ToolAuthorizationDecision.RequireApproval::class.java); assertThat((actual as ToolAuthorizationDecision.RequireApproval).decision).isSameAs(decision)
    } }
}
