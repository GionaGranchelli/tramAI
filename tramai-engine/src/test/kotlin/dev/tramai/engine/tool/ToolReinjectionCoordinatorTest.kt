package dev.tramai.engine.tool

import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ToolCall
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test

class ToolReinjectionCoordinatorTest {
    private fun coordinator(registry: ToolRegistry, decision: suspend (dev.tramai.core.policy.PolicyContext) -> dev.tramai.core.policy.PolicyDecision = { dev.tramai.core.policy.PolicyDecision.Allow }) = ToolReinjectionCoordinator(registry, policyHelper(decision), ToolInvocationExecutor(ToolAuthorizationCoordinator(policyHelper()), ToolRetryPolicy(), RecordingToolObserver(), ToolApprovalGate { _, _, _ -> }), ToolResultSanitizer(registry, NoOpDlpInterceptor, NoOpDlpRedactionAuditEmitter, ToolResultFilteringSettings(), NoOpEngineEventObserver))
    private fun batch(vararg calls: ToolCall) = ToolCallBatchRequest(toolOperation(), mutableListOf(), calls.toList(), "cid", ExecutionSecurityContext(), dev.tramai.engine.EngineExecutionIdentity("run", "cid", dev.tramai.core.approval.Sha256Digest.of("sha256:${"a".repeat(64)}"), "v", "a"))
    @Test fun `multiple calls retain order`() { runBlocking { val c = coordinator(ToolRegistry(mapOf("a" to testTool("a") { _, _ -> dev.tramai.core.model.ToolResult.Success("A") }, "b" to testTool("b") { _, _ -> dev.tramai.core.model.ToolResult.Success("B") }))); val b = batch(ToolCall("1", "a", "{}"), ToolCall("2", "b", "{}")); c.process(b); assertThat(b.messages.map { it.content }).containsExactly("A", "B") } }
    @Test fun `denied reinjection leaves messages untouched`() { val calls = AtomicInteger(); val registry = ToolRegistry(mapOf("a" to testTool("a") { _, _ -> calls.incrementAndGet(); dev.tramai.core.model.ToolResult.Success("A") })); val b = batch(ToolCall("1", "a", "{}")); assertThatThrownBy { runBlocking { coordinator(registry) { if (it.enforcementPoint == dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION) dev.tramai.core.policy.PolicyDecision.Deny("no", "no") else dev.tramai.core.policy.PolicyDecision.Allow }.process(b) } }.isInstanceOf(PolicyViolationException::class.java); assertThat(b.messages).isEmpty(); assertThat(calls.get()).isEqualTo(1) }
    @Test fun `unknown call yields safe permanent message`() { runBlocking { val b = batch(ToolCall("1", "gone", "{}")); coordinator(ToolRegistry()).process(b); assertThat(b.messages.single().content).startsWith("Permanent error:") } }
    @Test fun `cancellation stops batch after prior result`() { val third = AtomicInteger(); val registry = ToolRegistry(mapOf("one" to testTool("one"), "two" to testTool("two") { _, _ -> throw CancellationException("stop") }, "three" to testTool("three") { _, _ -> third.incrementAndGet(); dev.tramai.core.model.ToolResult.Success("bad") })); val b = batch(ToolCall("1", "one", "{}"), ToolCall("2", "two", "{}"), ToolCall("3", "three", "{}")); assertThatThrownBy { runBlocking { coordinator(registry).process(b) } }.isInstanceOf(CancellationException::class.java); assertThat(b.messages).hasSize(1); assertThat(third.get()).isZero() }
    @Test fun `process one handles one call`() { runBlocking { val b = batch(ToolCall("1", "a", "{}")); coordinator(ToolRegistry(mapOf("a" to testTool("a")))).processOne(b, b.toolCalls.single(), 0); assertThat(b.messages.single().toolCallId).isEqualTo("1") } }
}
