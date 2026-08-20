package dev.tramai.engine.tool

import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.ToolFailureCode
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolInvocationExecutorTest {
    private fun executor(decision: PolicyDecision = PolicyDecision.Allow, observer: RecordingToolObserver = RecordingToolObserver(), gate: ToolApprovalGate = ToolApprovalGate { _, _, _ -> }) =
        ToolInvocationExecutor(ToolAuthorizationCoordinator(policyHelper { decision }), ToolRetryPolicy(), observer, gate)
    @Test fun `success returns without diagnostics`() { runBlocking { val observer = RecordingToolObserver(); assertThat(executor(observer = observer).execute(toolRequest(testTool()))).isEqualTo(ToolResult.Success("ok")); assertThat(observer.events).isEmpty() } }
    @Test fun `thrown and returned transient non idempotent failures have same terminal semantics`() { runBlocking {
        val thrown = RecordingToolObserver(); val returned = RecordingToolObserver()
        val a = executor(observer = thrown).execute(toolRequest(testTool(execute = { _, _ -> throw RuntimeException("x") })));
        val b = executor(observer = returned).execute(toolRequest(testTool(execute = { _, _ -> ToolResult.TransientFailure(RuntimeException("x")) })));
        assertThat(a).isEqualTo(ToolResult.PermanentFailure(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage)); assertThat(b).isEqualTo(a)
        listOf(thrown, returned).forEach { assertThat(it.events).hasSize(1); assertThat(it.events.single().code).isEqualTo(ToolFailureCode.EXECUTION_FAILED); assertThat(it.events.single().retryClassified).isFalse() }
    } }
    @Test fun `non idempotent transient failure never executes twice despite attempt budget`() { runBlocking {
        val calls = AtomicInteger()
        val result = executor().execute(toolRequest(testTool { _, _ -> calls.incrementAndGet(); throw RuntimeException() }))
        assertThat(result).isEqualTo(ToolResult.PermanentFailure(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage))
        assertThat(calls.get()).isEqualTo(1)
    } }
    @Test fun `failure classification is independent of repetition safety only repeat safe tools execute again`() { runBlocking {
        val unsafeCalls = AtomicInteger(); val safeCalls = AtomicInteger()
        val unsafe = executor().execute(toolRequest(testTool(idempotent = false) { _, _ -> unsafeCalls.incrementAndGet(); throw RuntimeException() }))
        val safe = executor().execute(toolRequest(testTool(idempotent = true) { _, _ -> safeCalls.incrementAndGet(); throw RuntimeException() }))
        assertThat(unsafeCalls.get()).isEqualTo(1)
        assertThat(safeCalls.get()).isEqualTo(2)
        assertThat(unsafe).isEqualTo(ToolResult.PermanentFailure(ToolFailureCode.EXECUTION_FAILED.defaultModelMessage))
        assertThat(safe).isEqualTo(ToolResult.PermanentFailure(ToolFailureCode.RETRY_EXHAUSTED.defaultModelMessage))
    } }
    @Test fun `idempotent exception retries once then succeeds`() { runBlocking { val calls = AtomicInteger(); val observer = RecordingToolObserver(); val result = executor(observer = observer).execute(toolRequest(testTool(idempotent = true) { _, _ -> if (calls.getAndIncrement() == 0) throw RuntimeException() else ToolResult.Success("ok") })); assertThat(result).isEqualTo(ToolResult.Success("ok")); assertThat(calls.get()).isEqualTo(2); assertThat(observer.events).hasSize(1); assertThat(observer.events.single().retryClassified).isTrue() } }
    @Test fun `exhausted idempotent failure records retry exhausted`() { runBlocking { val calls = AtomicInteger(); val observer = RecordingToolObserver(); val result = executor(observer = observer).execute(toolRequest(testTool(idempotent = true) { _, _ -> calls.incrementAndGet(); throw RuntimeException() })); assertThat(result).isEqualTo(ToolResult.PermanentFailure(ToolFailureCode.RETRY_EXHAUSTED.defaultModelMessage)); assertThat(calls.get()).isEqualTo(2); assertThat(observer.events.map { it.code }).containsExactly(ToolFailureCode.EXECUTION_FAILED, ToolFailureCode.EXECUTION_FAILED, ToolFailureCode.RETRY_EXHAUSTED); assertThat(observer.events.filter { it.code == ToolFailureCode.EXECUTION_FAILED }).allMatch { it.retryClassified } } }
    @Test fun `invalid input keeps safe message and no retry`() { runBlocking { val observer = RecordingToolObserver(); val result = executor(observer = observer).execute(toolRequest(testTool { _, _ -> throw ToolInvalidInputException.withSafeModelMessage("diagnostic", "safe") })); assertThat(result).isEqualTo(ToolResult.InvalidInput("safe")); assertThat(observer.events).hasSize(1); assertThat(observer.events.single().code).isEqualTo(ToolFailureCode.INVALID_INPUT) } }
    @Test fun `observer failure cannot replace successful result`() { runBlocking { assertThat(executor(observer = RecordingToolObserver(RuntimeException())).execute(toolRequest(testTool()))).isEqualTo(ToolResult.Success("ok")) } }
    @Test fun `approval gate runs before approved execution`() { runBlocking { var gateCalls = 0; val req = ApprovalRequirement("test-tool", "d", "r", 1); val result = executor(PolicyDecision.RequireApproval(req), gate = ToolApprovalGate { _, _, _ -> gateCalls++ }).execute(toolRequest(testTool())); assertThat(result).isEqualTo(ToolResult.Success("ok")); assertThat(gateCalls).isEqualTo(1) } }
    @Test fun `deny propagates and tool and gate are untouched`() { var calls = 0; var gateCalls = 0; assertThatThrownBy { runBlocking { executor(PolicyDecision.Deny("no", "no"), gate = ToolApprovalGate { _, _, _ -> gateCalls++ }).execute(toolRequest(testTool { _, _ -> calls++; ToolResult.Success("bad") })) } }.isInstanceOf(PolicyViolationException::class.java); assertThat(calls).isZero(); assertThat(gateCalls).isZero() }
}
