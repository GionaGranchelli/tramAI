package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract tests proving that cancellation during tool execution
 * escapes without retry, reinjection, or normal failure classification.
 *
 * Closes GitHub issue #210.
 */
class ToolCancellationContractTest {

    // -------------------------------------------------------------------------
    // Test 1: thrown tool cancellation escapes without retry or reinjection
    // -------------------------------------------------------------------------

    @Test
    fun `idempotent tool cancellation escapes without retry or reinjection`() {
        val tool = CancellingTool()
        val provider = RecordingProvider()
        val observer = CancellationObserver()
        val audit = RecordingAuditEmitter()

        val registry = ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
            operationObserver = observer,
            policyDecisionAuditEmitter = audit,
        )
        val service = engine.create<TestService>()

        provider.responses.add(
            ModelResponse(
                content = "doing work",
                toolCalls = listOf(ToolCall("1", "cancelling-tool", """{"input":"x"}""")),
            ),
        )

        assertThatThrownBy { runBlocking { service.execute("input") } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled by tool")

        // Tool was called exactly once (no retry for idempotent after cancellation)
        assertThat(tool.calls.get()).isEqualTo(1)

        // Provider was called exactly once (no second provider request)
        assertThat(provider.calls.get()).isEqualTo(1)

        // Observation: exactly one cancelled record, no completion, no provider failure
        assertThat(observer.records).hasSize(1)
        val record = observer.records.single()
        assertThat(record.cancelled).isTrue()
        assertThat(record.completionCount).isZero()
        assertThat(record.providerFailure).isNull()

        // Audit: BEFORE_TOOL_EXECUTION emitted once, BEFORE_TOOL_RESULT_REINJECTION never
        val toolExecutionCalls = audit.calls.filter { it.first == EnforcementPoint.BEFORE_TOOL_EXECUTION }
        val reinjectionCalls = audit.calls.filter { it.first == EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION }
        assertThat(toolExecutionCalls).hasSize(1)
        assertThat(reinjectionCalls).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Test 2: custom ResolvedTool returning TransientFailure(CancellationException)
    // -------------------------------------------------------------------------

    @Test
    fun `custom resolved tool transient failure wrapping cancellation is rethrown`() {
        val provider = RecordingProvider()
        val observer = CancellationObserver()
        val cancellingTool = object : ResolvedTool {
            val toolCalls = java.util.concurrent.atomic.AtomicInteger(0)
            override val name: String = "wrapping-tool"
            override val description: String = "a tool that returns cancellation as transient failure"
            override val inputSchemaJson: String = """{"type":"object","properties":{"input":{"type":"string"}}}"""
            override val idempotent: Boolean = true
            override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
            override val security = null

            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
                toolCalls.incrementAndGet()
                return ToolResult.TransientFailure(
                    CancellationException("cancelled from resolved tool"),
                )
            }
        }

        val registry = ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(cancellingTool.name to cancellingTool)),
            operationObserver = observer,
        )
        val service = engine.create<TestService>()

        provider.responses.add(
            ModelResponse(
                content = "doing work",
                toolCalls = listOf(ToolCall("1", "wrapping-tool", """{"input":"x"}""")),
            ),
        )

        assertThatThrownBy { runBlocking { service.execute("input") } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled from resolved tool")

        // Tool was not retried
        assertThat(cancellingTool.toolCalls.get()).isEqualTo(1)
        // Provider was called exactly once
        assertThat(provider.calls.get()).isEqualTo(1)

        // Observation: cancelled, not completed, no provider failure
        assertThat(observer.records).hasSize(1)
        val record = observer.records.single()
        assertThat(record.cancelled).isTrue()
        assertThat(record.completionCount).isZero()
        assertThat(record.providerFailure).isNull()
    }

    // -------------------------------------------------------------------------
    // Test 3: non-cancellation idempotent tool failure still retries normally
    // -------------------------------------------------------------------------

    @Test
    fun `ordinary idempotent tool failure still retries normally`() {
        val tool = FailingIdempotentTool()
        val provider = RecordingProvider()
        val observer = CancellationObserver()

        val registry = ProviderRegistry.builder()
            .provider("primary", provider)
            .model("test-model", "primary")
            .defaultProvider("primary")
            .build()

        val engine = TramaiEngine(
            providerRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
            operationObserver = observer,
        )
        val service = engine.create<TestService>()

        provider.responses.add(
            ModelResponse(
                content = "try me",
                toolCalls = listOf(ToolCall("1", "failing-idempotent", """{"input":"x"}""")),
            ),
        )

        // Ordinary TransientFailure should be retried (idempotent tool)
        runBlocking { service.execute("input") }

        // Tool was retried (idempotent with IDEMPOTENT_TOOL_MAX_ATTEMPTS > 1)
        assertThat(tool.calls.get()).isGreaterThan(1)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** Tool that throws CancellationException on every call. */
    private class CancellingTool : ResolvedTool {
        val calls = AtomicInteger(0)
        override val name: String = "cancelling-tool"
        override val description: String = "a tool that cancels"
        override val inputSchemaJson: String = """{"type":"object","properties":{"input":{"type":"string"}}}"""
        override val idempotent: Boolean = true
        override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override val security = null

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            calls.incrementAndGet()
            throw CancellationException("cancelled by tool")
        }
    }

    /** Provider that returns pre-configured responses and records call count. */
    private class RecordingProvider : ModelProvider {
        val calls = AtomicInteger(0)
        val responses = mutableListOf<ModelResponse>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls.incrementAndGet()
            return if (responses.isNotEmpty()) responses.removeFirst()
            else ModelResponse(content = "done")
        }
    }

    /** Observer that tracks cancellation, completion, and provider failure. */
    private class CancellationObserver : OperationObserver {
        val records = mutableListOf<Record>()

        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            val record = Record()
            records += record
            return object : OperationObservation {
                override fun onProviderResponse(response: ModelResponse) = Unit
                override fun onProviderFailure(error: Throwable) { record.providerFailure = error }
                override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
                override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
                override fun onCallCompleted(parseSuccess: Boolean?) { record.completionCount++ }
                override fun onCallCancelled() { record.cancelled = true }
            }
        }

        data class Record(
            var providerFailure: Throwable? = null,
            var completionCount: Int = 0,
            var cancelled: Boolean = false,
        )
    }

    /** Records audit emissions for boundary verification. */
    private class RecordingAuditEmitter : PolicyDecisionAuditEmitter {
        val calls = mutableListOf<Triple<EnforcementPoint, PolicyContext, PolicyDecision>>()

        override suspend fun emit(
            enforcementPoint: EnforcementPoint,
            context: PolicyContext,
            decision: PolicyDecision,
        ) {
            calls.add(Triple(enforcementPoint, context, decision))
        }
    }

    /** Idempotent tool that always returns TransientFailure. */
    private class FailingIdempotentTool : ResolvedTool {
        val calls = AtomicInteger(0)
        override val name: String = "failing-idempotent"
        override val description: String = "always fails transiently"
        override val inputSchemaJson: String = """{"type":"object","properties":{"input":{"type":"string"}}}"""
        override val idempotent: Boolean = true
        override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override val security = null

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            calls.incrementAndGet()
            return ToolResult.TransientFailure(RuntimeException("always fails"))
        }
    }

    // -------------------------------------------------------------------------
    // Service interfaces
    // -------------------------------------------------------------------------

    @AiService
    private interface TestService {
        @Operation(
            prompt = "Execute the input",
            model = "test-model",
        )
        suspend fun execute(input: String): String
    }
}
