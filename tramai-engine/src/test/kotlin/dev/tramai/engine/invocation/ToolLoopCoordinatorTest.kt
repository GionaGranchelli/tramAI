package dev.tramai.engine.invocation

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.ToolFailureDiagnosticEvent
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.NoOpEngineEventObserver
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.ToolResultFilteringSettings
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.ProviderAttemptExecutor
import dev.tramai.engine.provider.ProviderAuthorizationService
import dev.tramai.engine.provider.ProviderCallResult
import dev.tramai.engine.provider.ProviderExecutionCoordinator
import dev.tramai.engine.provider.ProviderExecutionRequest
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderFallbackPolicy
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.provider.ProviderResponseSanitizer
import dev.tramai.engine.provider.ProviderRetryDelayPolicy
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.provider.componentOperation
import dev.tramai.engine.tool.ToolApprovalGate
import dev.tramai.engine.tool.policyHelper
import dev.tramai.engine.tool.testTool
import dev.tramai.engine.tool.ToolAuthorizationCoordinator
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.engine.tool.ToolInvocationExecutor
import dev.tramai.engine.tool.ToolReinjectionCoordinator
import dev.tramai.engine.tool.ToolResultSanitizer
import dev.tramai.engine.tool.ToolRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolLoopCoordinatorTest {
    @Test
    fun `cancellation during tool reinjection calls onCallCancelled not onCallCompleted`() {
        runBlocking {
            val observation = RecordingObservation()
            val provider = FakeProvider { ModelResponse("needs tool", toolCalls = listOf(ToolCall("1", "boom", "{}"))) }
            val registry = ToolRegistry(mapOf("boom" to testTool("boom") { _, _ -> throw CancellationException("stop") }))
            val coordinator = coordinator(provider, registry, observation)

            assertThatThrownBy { runBlocking { coordinator.execute(context()) } }.isInstanceOf(CancellationException::class.java)
            assertThat(observation.events.map { it.first }).contains("tramai.call.cancelled")
            assertThat(observation.events.map { it.first }).doesNotContain("tramai.call.completed")
        }
    }

    @Test
    fun `unknown tool call is normalized to unregistered placeholder`() {
        runBlocking {
            var calls = 0
            val provider = FakeProvider {
                if (calls++ == 0) ModelResponse("calls", toolCalls = listOf(ToolCall("1", "ghost-tool", "{\"x\":1}")))
                else ModelResponse("done")
            }
            val coordinator = coordinator(provider, ToolRegistry(), RecordingObservation())

            coordinator.execute(context())
            val assistant = provider.requestedMessages().last { it.role == MessageRole.ASSISTANT }
            assertThat(assistant.toolCalls!!.single().name).isEqualTo("unregistered_tool")
            assertThat(assistant.toolCalls!!.single().argumentsJson).isEqualTo("{}")
        }
    }

    @Test
    fun `token budget failure completes observation and throws`() {
        runBlocking {
            val observation = RecordingObservation()
            val provider = FakeProvider { ModelResponse("exceeds budget", inputTokens = 2, outputTokens = 2) }
            val budget = TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 1))
            val coordinator = coordinator(provider, ToolRegistry(), observation, budget)

            assertThatThrownBy { runBlocking { coordinator.execute(context(budgetTracker = budget.createTracker())) } }
                .isInstanceOf(TokenBudgetExceededException::class.java)
            assertThat(observation.events.map { it.first }).contains("tramai.call.completed")
        }
    }

    @Test
    fun `max five iterations then fails with exceeded message`() {
        runBlocking {
            val calls = AtomicInteger()
            val provider = FakeProvider {
                calls.incrementAndGet()
                ModelResponse("loop", toolCalls = listOf(ToolCall("c$calls", "echo", "{}")))
            }
            val registry = ToolRegistry(mapOf("echo" to testTool("echo") { _, _ -> ToolResult.Success("ok") }))
            val coordinator = coordinator(provider, registry, RecordingObservation())

            assertThatThrownBy { runBlocking { coordinator.execute(context()) } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Exceeded maximum tool call loops")
            assertThat(calls.get()).isEqualTo(5)
        }
    }

    @Test
    fun `ordinary reinjection error keeps primary error and completes observation`() {
        runBlocking {
            val observation = RecordingObservation()
            val provider = FakeProvider { ModelResponse("calls", toolCalls = listOf(ToolCall("1", "ok", "{}"))) }
            val registry = ToolRegistry(mapOf("ok" to testTool("ok")))
            val denying = policyHelper {
                if (it.enforcementPoint == dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION) {
                    dev.tramai.core.policy.PolicyDecision.Deny("no", "no")
                } else {
                    dev.tramai.core.policy.PolicyDecision.Allow
                }
            }
            val coordinator = coordinator(provider, registry, observation, reinjectionPolicy = denying)

            assertThatThrownBy { runBlocking { coordinator.execute(context()) } }
                .isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)
            assertThat(observation.events.map { it.first }).contains("tramai.call.completed")
        }
    }

    @Test
    fun `no tool calls returns provider result directly`() {
        runBlocking {
            val provider = FakeProvider { ModelResponse("plain answer") }
            val coordinator = coordinator(provider, ToolRegistry(), RecordingObservation())
            val result = coordinator.execute(context())
            assertThat(result.response.content).isEqualTo("plain answer")
        }
    }

    // --- fixtures ---

    private fun coordinator(
        provider: FakeProvider,
        registry: ToolRegistry,
        observation: RecordingObservation,
        budget: TokenBudgetCoordinator = TokenBudgetCoordinator(TokenBudgetSettings()),
        reinjectionPolicy: dev.tramai.engine.PolicyEnforcementHelper = policyHelper(),
    ): ToolLoopCoordinator {
        val observer = dev.tramai.core.observation.OperationObserver { observation }
        val attempt = ProviderAttemptExecutor(
            "service",
            observer,
            object : dev.tramai.core.observation.OperationInterceptor {},
            ProviderCircuitBreaker(CircuitBreakerSettings()),
            ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }),
            permissiveAuthorization(),
            ProviderInvocationGate { _, _, _, _ -> },
            ProviderResponseSanitizer { response, _, _, _, _, _, _ -> response },
        )
        val providerCoordinator = ProviderExecutionCoordinator(
            ProviderRoutingPlan.builder().provider("primary", provider).model("model", "primary").build(),
            ProviderCircuitBreaker(CircuitBreakerSettings()),
            attempt,
            ProviderFallbackPolicy(),
            ProviderResolutionGate { _, _, _ -> },
            ProviderFallbackGate { _, _, _, _, _, _ -> },
        )
        val exposure = ToolExposureCoordinator(registry, policyHelper())
        val invocation = ToolInvocationExecutor(
            ToolAuthorizationCoordinator(policyHelper()),
            ToolRetryPolicy(),
            RecordingToolObserver(),
            ToolApprovalGate { _, _, _ -> },
        )
        val reinjection = ToolReinjectionCoordinator(
            registry,
            reinjectionPolicy,
            invocation,
            ToolResultSanitizer(registry, NoOpDlpInterceptor, NoOpDlpRedactionAuditEmitter, ToolResultFilteringSettings(), NoOpEngineEventObserver),
        )
        return ToolLoopCoordinator(providerCoordinator, exposure, budget, registry, reinjection)
    }

    private fun context(
        budgetTracker: TokenBudgetTracker = TokenBudgetTracker(TokenBudgetSettings()),
        messages: MutableList<Message> = mutableListOf(),
    ) = ToolLoopContext(
        operation = componentOperation().copy(toolDefinitions = listOf(dev.tramai.core.model.ToolDefinition("echo", "echo", "{}"))),
        messages = messages,
        tokenBudgetTracker = budgetTracker,
        correlationId = "cid",
        securityContext = ExecutionSecurityContext(),
        identity = EngineExecutionIdentity("run", "cid", Sha256Digest.of("sha256:${"a".repeat(64)}"), "v1", "actor"),
    )

    private fun permissiveAuthorization() = ProviderAuthorizationService(
        ModelRegistryEnforcer(
            object : ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String) =
                    RegisteredModel("id", providerId, modelName, "r1", ModelArtifactDigest.of("sha256:${"a".repeat(64)}"), true)
            },
            ModelRegistrySettings(enabled = true),
        ),
    )
}

private class FakeProvider(
    private val block: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse = block(request).also { requests += request }
    override fun providerId() = "primary"
    val requests = mutableListOf<ModelRequest>()
    fun requestedMessages(): List<Message> = requests.flatMap { it.messages }
}

private class RecordingObservation : OperationObservation {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    override fun onProviderResponse(response: ModelResponse) { events += "tramai.provider.response" to mapOf() }
    override fun onProviderFailure(error: Throwable) { events += "tramai.provider.failure" to mapOf() }
    override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) { events += "tramai.structured.parse_failure" to mapOf() }
    override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { events += name to attributes }
    override fun onCallCompleted(parseSuccess: Boolean?) { events += "tramai.call.completed" to mapOf() }
    override fun onCallCancelled() { events += "tramai.call.cancelled" to mapOf() }
}

private class RecordingToolObserver : dev.tramai.core.observation.ToolFailureDiagnosticObserver {
    override fun record(event: ToolFailureDiagnosticEvent) {}
}
