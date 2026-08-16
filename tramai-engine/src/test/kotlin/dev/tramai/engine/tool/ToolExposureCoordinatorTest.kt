package dev.tramai.engine.tool

import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ToolExposureCoordinatorTest {
    @Test fun `exposure follows definition registration order`() { runBlocking {
        val seen = mutableListOf<String>()
        ToolExposureCoordinator(ToolRegistry(mapOf("one" to testTool("one"), "two" to testTool("two"))), policyHelper { seen += it.toolName!!; PolicyDecision.Allow })
            .enforce(toolOperation("one", "two"), "cid", ExecutionSecurityContext())
        assertThat(seen).containsExactly("one", "two")
    } }
    @Test fun `unknown exposure is evaluated with null security`() { runBlocking {
        var security: Any? = Any(); var point: EnforcementPoint? = null
        ToolExposureCoordinator(ToolRegistry(), policyHelper { point = it.enforcementPoint; security = it.toolSecurity; PolicyDecision.Allow })
            .enforce(toolOperation("missing"), "cid", ExecutionSecurityContext())
        assertThat(point).isEqualTo(EnforcementPoint.BEFORE_TOOL_EXPOSURE); assertThat(security).isNull()
    } }
    @Test fun `denial is propagated`() { 
        assertThatThrownBy { runBlocking { ToolExposureCoordinator(ToolRegistry(mapOf("x" to testTool("x"))), policyHelper { PolicyDecision.Deny("no", "no") }).enforce(toolOperation("x"), "cid", ExecutionSecurityContext()) } }.isInstanceOf(PolicyViolationException::class.java)
    }
    @Test fun `cancellation passes through unchanged`() {
        val cancellation = CancellationException("stop")
        assertThatThrownBy { runBlocking { ToolExposureCoordinator(ToolRegistry(), policyHelper { throw cancellation }).enforce(toolOperation("x"), "cid", ExecutionSecurityContext()) } }.isSameAs(cancellation)
    }
}
