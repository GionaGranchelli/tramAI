package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.planning.OperationDefinitionCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProviderAttemptExecutorTest {
    @Test fun `success transfers observation ownership in result`() {
        runBlocking {
        val observation = RecordingObservation(); val executor = executor(observation = observation)
        val result = executor.execute(request(provider = FakeProvider { ModelResponse("ok") }))
        assertThat(result.response.content).isEqualTo("ok"); assertThat(result.providerId).isEqualTo("primary")
        assertThat(observation.responses).isEqualTo(1); assertThat(observation.completed).isZero(); assertThat(observation.cancelled).isZero()
    }
    }

    @Test fun `request and response interceptors surround provider and dlp`() {
        runBlocking {
        val order = mutableListOf<String>()
        val interceptor = object : OperationInterceptor {
            override fun interceptRequest(context: OperationCallContext, messages: List<Message>): List<Message> { order += "request"; return messages }
            override fun interceptResponse(context: OperationCallContext, response: ModelResponse): ModelResponse { order += "response"; return response }
        }
        val executor = executor(interceptor = interceptor, sanitizer = ProviderResponseSanitizer { response, _, _, _, _, _, _ -> order += "dlp"; response })
        executor.execute(request(provider = FakeProvider { order += "provider"; ModelResponse("ok") }))
        assertThat(order).containsExactly("request", "provider", "response", "dlp")
    }
    }

    @Test fun `authorizes before invoking provider`() {
        runBlocking {
        val order = mutableListOf<String>(); val auth = authorization { order += "authorization"; approvedModel() }
        executor(authorization = auth).execute(request(provider = FakeProvider { order += "provider"; ModelResponse("ok") }))
        assertThat(order).containsExactly("authorization", "provider")
    }
    }

    @Test fun `dlp failure is terminal but not provider failure`() {
        val observation = RecordingObservation(); val executor = executor(observation = observation, sanitizer = ProviderResponseSanitizer { _, _, _, _, _, _, _ -> throw DlpInspectionException("blocked") })
        assertThatThrownBy { runBlocking { executor.execute(request(provider = FakeProvider { ModelResponse("secret") })) } }.isInstanceOf(DlpInspectionException::class.java)
        assertThat(observation.failures).isZero(); assertThat(observation.completed).isEqualTo(1)
    }

    @Test fun `provider error records failure consults retry and completes`() {
        val observation = RecordingObservation(); val executor = executor(observation = observation)
        assertThatThrownBy { runBlocking { executor.execute(request(provider = FakeProvider { throw ProviderException("down", retryable = false) })) } }.isInstanceOf(ProviderException::class.java)
        assertThat(observation.failures).isEqualTo(1); assertThat(observation.completed).isEqualTo(1)
    }

    @Test fun `cancellation cancels observation without completion or retry`() {
        val observation = RecordingObservation(); val calls = intArrayOf(0); val executor = executor(observation = observation)
        assertThatThrownBy { runBlocking { executor.execute(request(provider = FakeProvider { calls[0]++; throw CancellationException("stop") })) } }.isInstanceOf(CancellationException::class.java)
        assertThat(calls[0]).isEqualTo(1); assertThat(observation.cancelled).isEqualTo(1); assertThat(observation.completed).isZero()
    }

    @Test fun `authorization failure completes observation exactly once`() {
        val observation = RecordingObservation(); val executor = executor(observation = observation, authorization = authorization { null })
        assertThatThrownBy { runBlocking { executor.execute(request(provider = FakeProvider { ModelResponse("never") })) } }.isInstanceOf(ModelRegistryException::class.java)
        assertThat(observation.completed).isEqualTo(1)
    }
}

@AiService internal interface ProviderComponentService { @Operation(prompt = "test", model = "model", providerRetries = 0) suspend fun answer(): String }
@AiService internal interface RetryingProviderComponentService { @Operation(prompt = "test", model = "model", providerRetries = 1) suspend fun answer(): String }
internal fun componentOperation(retries: Int = 0): OperationDefinition {
    val type = if (retries == 0) ProviderComponentService::class.java else RetryingProviderComponentService::class.java
    val method = type.methods.single { it.name == "answer" }
    val annotation = method.getAnnotation(Operation::class.java)
    return OperationDefinitionCompiler.compileDefinition(method, annotation, null)
}
internal fun approvedModel() = RegisteredModel("id", "primary", "model", "r1", ModelArtifactDigest.of("sha256:${"a".repeat(64)}"), true)
internal fun authorization(answer: suspend () -> RegisteredModel?) = ProviderAuthorizationService(ModelRegistryEnforcer(object : ModelRegistry { override suspend fun findApprovedModel(providerId: String, modelName: String) = answer() }, ModelRegistrySettings(enabled = true)))
internal fun request(provider: ModelProvider, retries: Int = 0, counter: AttemptCounter = AttemptCounter()) = ProviderRetryRequest("primary", provider, ModelRequest("model", emptyList(), timeoutMillis = 1_000), componentOperation(retries), counter, 0, "cid", ExecutionSecurityContext(), CircuitBreakerPermit("primary", 0))
internal fun executor(observation: RecordingObservation = RecordingObservation(), interceptor: OperationInterceptor = object : OperationInterceptor {}, authorization: ProviderAuthorizationService = authorization { approvedModel() }, sanitizer: ProviderResponseSanitizer = ProviderResponseSanitizer { response, _, _, _, _, _, _ -> response }, retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }), circuitBreaker: ProviderCircuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings())): ProviderAttemptExecutor = ProviderAttemptExecutor("service", OperationObserver { observation }, interceptor, circuitBreaker, retryPolicy, authorization, ProviderInvocationGate { _, _, _, _ -> }, sanitizer)
internal class FakeProvider(private val block: suspend (ModelRequest) -> ModelResponse) : ModelProvider { override suspend fun complete(request: ModelRequest) = block(request); override fun providerId() = "fake" }
internal class RecordingObservation : OperationObservation { var responses = 0; var failures = 0; var completed = 0; var cancelled = 0; val events = mutableListOf<Pair<String, Map<String, Any?>>>() ; override fun onProviderResponse(response: ModelResponse) { responses++ }; override fun onProviderFailure(error: Throwable) { failures++ }; override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit; override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { events += name to attributes }; override fun onCallCompleted(parseSuccess: Boolean?) { completed++ }; override fun onCallCancelled() { cancelled++ }; fun routeSelected(): Map<String, Any?> = events.first { it.first == "tramai.route.selected" }.second }
