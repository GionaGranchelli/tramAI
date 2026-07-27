You are working on the TramAI repository at ~/Development/aurora.

Create PR #209: "test(0.6.0): characterize engine cancellation boundaries"

Context:
- PR #207 (merged to master) added `rethrowIfCancellation()`, `onCallCancelled()`, and `completeCancellation()` to the engine and core.
- The streaming cancellation handler (`onCallCancelled`) was verified in the existing TramaiEngineTest, but the non-streaming retry loop, fallback routing, structured-output repair loop, tool execution, and OpenTelemetry integration remain uncharacterized.
- This PR adds dedicated contract tests WITHOUT changing any production code.

Files to read for context:
- tramai-engine/src/main/kotlin/dev/tramai/engine/TramaiEngine.kt (constructor signatures, completeCancellation())
- tramai-engine/src/main/kotlin/dev/tramai/engine/RetryPolicySettings.kt
- tramai-core/src/main/kotlin/dev/tramai/core/observation/OperationObservation.kt (onCallCancelled default impl)
- tramai-core/src/main/kotlin/dev/tramai/core/provider/ProviderRegistry.kt (builder API, fallback routes)
- tramai-core/src/main/kotlin/dev/tramai/core/annotations/Operation.kt (model, providerRetries, maxRetries fields)
- tramai-engine/src/test/kotlin/dev/tramai/engine/TramaiEngineTest.kt (RecordingObserver, RecordingProvider, StreamingProvider, ToolCallingRecordingProvider fixtures — lines 3464-3623)
- tramai-observability/src/main/kotlin/dev/tramai/observability/OpenTelemetryOperationObserver.kt (onCallCancelled impl)
- tramai-observability/src/test/kotlin/dev/tramai/observability/OpenTelemetryOperationObserverTest.kt (existing patterns, RecordingProvider, helper methods)
- docs/releases/0.6.0-characterization-matrix.md

Implementation requirements:

## A. Create EngineCancellationContractTest

Create file: tramai-engine/src/test/kotlin/dev/tramai/engine/EngineCancellationContractTest.kt

Package: dev.tramai.engine

Use a dedicated contract test class (NOT TramaiEngineTest.kt). Follow the existing patterns: private helper fixtures, `@Test` from `kotlin.test.Test`, use `runBlocking` for coroutine execution (consistent with existing engine tests), use `assertThat` from AssertJ.

### Fixtures to include (inline, private to the test class):

```kotlin
private class RecordingObserver : OperationObserver {
    val records = mutableListOf<Record>()

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val record = Record(context = context)
        records += record
        return object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) { record.response = response }
            override fun onProviderFailure(error: Throwable) { record.providerFailure = error }
            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { record.engineEvents += EngineEventRecord(name, attributes) }
            override fun onCallCompleted(parseSuccess: Boolean?) { record.parseSuccess = parseSuccess; record.completionCount++ }
            override fun onCallCancelled() { record.cancelled = true }
        }
    }

    data class Record(
        val context: OperationCallContext,
        var response: ModelResponse? = null,
        var providerFailure: Throwable? = null,
        var parseSuccess: Boolean? = null,
        var completionCount: Int = 0,
        var cancelled: Boolean = false,
        val engineEvents: MutableList<EngineEventRecord> = mutableListOf(),
    )
}

private data class EngineEventRecord(val name: String, val attributes: Map<String, Any?>)
```

Note: Do NOT reuse the RecordingObserver from TramaiEngineTest — keep it self-contained in the contract test.

Use the SAME RecordingProvider pattern as TramaiEngineTest:
```kotlin
private class RecordingProvider(
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }
}
```

### Test 1: Provider cancellation bypasses retry and fallback

Configure:
- Primary provider that throws `CancellationException` (a real one from `kotlinx.coroutines.CancellationException`)
- Use `@Operation(model = "test-model", providerRetries = 2)` on the service method
- Use `FailingIfCalledProvider` as a second provider with a fallback route
- Use ProviderRegistry.builder() with:
  - `.provider("primary", cancellingProvider)`
  - `.provider("fallback", failingProvider)`
  - `.model("test-model", "primary")`
  - `.fallbackProvider("test-model", "fallback")`
  - `.defaultProvider("primary")`
- Create TramaiEngine(providerRegistry = registry, operationObserver = observer)
- Service method with `@Operation(model = "test-model", providerRetries = 2)` on a suspend function returning String

```kotlin
private class CancellingProvider(
    val cancellation: CancellationException = CancellationException("cancelled by test"),
) : ModelProvider {
    val calls = AtomicInteger()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        calls.incrementAndGet()
        throw cancellation
    }
}

private class FailingIfCalledProvider : ModelProvider {
    val calls = AtomicInteger()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        calls.incrementAndGet()
        error("Fallback provider must not be invoked after cancellation")
    }
}
```

Assertions:
- The thrown exception is the SAME cancellation instance (`assertSame`)
- `cancellingProvider.calls.get() == 1`
- `failingProvider.calls.get() == 0`
- In the observer:
  - `record.providerFailure == null`
  - `record.cancelled == true`
  - `record.completionCount == 0` (onCallCompleted is NOT called for cancellation)
  - `record.response == null`

Test name: `fun provider cancellation bypasses retry and fallback and preserves identity()`

Service interface:
```kotlin
@AiService
interface CancellationTestService {
    @Operation(model = "test-model", providerRetries = 2)
    suspend fun execute(input: String): String
}
```

### Test 2: Cancellation during structured-output repair stops all further attempts

Set up:
- A `@Operation` with `model = "test-model"`, `maxRetries = 3` (structured output retries, default is 2), `providerRetries = 1`
- A provider that returns structured output (e.g. a non-JSON string for first call, then CancellationException on second)
- `structuredOutputHandler = JacksonStructuredOutputHandler`
- Service interface returns a non-String type

```kotlin
interface StructuredOutputService {
    @Operation(model = "test-model", maxRetries = 3, providerRetries = 1)
    suspend fun parse(input: String): StatusResult
}

private data class StatusResult(val status: String)
```

Use a `SequencedProvider`-like approach: first response is "not json" (triggers structured output retry), second response throws CancellationException.

Assertions:
- `provider.calls == 2` (one for the malformed response, one for the cancellation)
- Original CancellationException reaches the caller
- No `StructuredOutputException` wraps the cancellation
- Observer record shows `cancelled == true`, no `providerFailure`
- No third repair attempt occurs

Test name: `fun cancellation during structured output repair stops all further attempts()`

### Test 3: Idempotent tool cancellation is not converted to transient failure

Set up:
- A provider that emits one tool call and then does nothing more
- A tool whose `execute` throws `CancellationException`
- The tool is registered with `toolRegistry` and configured as idempotent
- Service method has `@Operation(model = "test-model", tools = ["my-tool"])`

```kotlin
private class CancellingTool : AiTool {
    val calls = AtomicInteger()
    override val name: String get() = "my-tool"
    override val description: String get() = "A tool that cancels"
    override val parameters: JsonObject? get() = null

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
        calls.incrementAndGet()
        throw CancellationException("tool cancelled by test")
    }
}
```

Assertions:
- `tool.calls == 1`
- Provider calls count as observed by the observer — the engine should NOT call the provider again for tool-result reinjection when the tool result is CancellationException
- Original CancellationException escapes to the caller
- No transient `ToolResult.TransientFailure` is produced
- No `onProviderFailure` or normal tool-failure observation

Test name: `fun idempotent tool cancellation is not converted to transient failure()`

NOTE: If implementing this test is too complex (tool execution boundary in the engine may catch CancellationException before it reaches the route handler), skip it and explain why in the test comments. The streaming and non-streaming tests are the priority.

### Test 4: Streaming consumer cancellation bypasses fallback and emits one cancelled outcome

- Same pattern as the existing `streaming cancellation propagates to the provider and is observed` test but WITH a fallback route configured
- Streaming provider emits one token, then `awaitCancellation()`
- Fallback provider would `error("must not be called")` if invoked
- Call `service.stream(input).take(1).toList()` to trigger consumer cancellation

Assertions:
- `received.tokens == ["first"]`
- primary provider stream cancellation observed (provider.cancelled == true)
- fallback provider calls == 0
- observer shows `cancelled == true`, `providerFailure == null`, `completionCount == 0`

Test name: `fun stream consumer cancellation bypasses fallback()`

### Test 5: Cancellation observer failure is suppressed on the original cancellation

Set up an observer where `onCallCancelled()` throws a distinct exception (e.g., `IllegalStateException("observer error")`).

Assertions:
- The original `CancellationException` escapes to the caller (not replaced by the observer exception)
- The observer exception is present in `cancellation.suppressed`
- The cancellation cause is not replaced
- No normal failure handling follows

Test name: `fun cancellation observer failure is suppressed without replacing cancellation()`

## B. Create OpenTelemetryCancellationIntegrationTest

Create file: tramai-observability/src/test/kotlin/dev/tramai/observability/OpenTelemetryCancellationIntegrationTest.kt

Package: dev.tramai.observability

This test verifies that a REAL engine cancellation (not a direct observer call) produces the correct OpenTelemetry span and metric attributes.

### Fixtures:
- `InMemorySpanExporter` and `InMemoryMetricReader` (same pattern as `OpenTelemetryOperationObserverTest`)
- A CancellingProvider that throws CancellationException
- A real `TramaiEngine` with `OpenTelemetryOperationObserver`

### Test: "engine cancellation is attributed as cancelled in spans and metrics"

1. Create SDK tracer and meter providers with in-memory exporters
2. Create `OpenTelemetryOperationObserver(openTelemetry)`
3. Create `TramaiEngine(provider = cancellingProvider, operationObserver = oTelObserver)`
4. Catch the `CancellationException` from `service.respond("hello")`
5. Collect the finished span and metrics

Assertions:
- Exactly one operation span is finished
- The span's `tramai.outcome` attribute == "cancelled"
- The `tramai.operation.attempts` metric has `tramai.outcome == "cancelled"`
- No `tramai.error.type` attribute on the metrics
- The span is not classified as generic provider error (no `StatusCode.ERROR`)
- No duplicate completion metric is emitted
- The original `CancellationException` reaches the caller

```kotlin
@AiService
interface SimpleService {
    @Operation(model = "test-model")
    suspend fun respond(input: String): String
}
```

Use `runBlocking` to invoke the service, same as existing OTel tests.

## C. Update the characterization matrix

Update `docs/releases/0.6.0-characterization-matrix.md`:

Replace the "Cancellation propagation" row (line 25) and "Cancellation propagation (helper semantics)" row (line 26) with:

```
| Workflow | Cancellation propagation | `EngineCancellationContractTest` covers retry bypass, fallback bypass, structured-output repair stop, streaming fallback bypass, and observer failure suppression. `OpenTelemetryCancellationIntegrationTest` covers OTel cancellation attribution. | Orchestration, persistence, process, and subprocess cancellation remain uncharacterized. | 0.6.1 |
```

Also update the "Provider fallback on failure" row:
```
| Provider Routing | Provider fallback on failure | `EngineCancellationContractTest.provider cancellation bypasses retry and fallback and preserves identity` characterizes cancellation-fallback interaction. Ordinary failure fallback remains covered separately. | TBD — characterization pending | 0.6.1 |
```

And the "Provider retry" related rows where applicable.

Do NOT update the ROADMAP, STATUS, or any other file. Only the characterization matrix.

Non-goals:
- Do NOT modify any production source files (*/src/main/**)
- Do NOT change build-logic, config/quality/*, .github/workflows/*, or any CI/CD files
- Do NOT modify TramaiEngineTest.kt — the new tests go in SEPARATE files
- Do NOT modify the OpenTelemetryOperationObserverTest.kt — the OTel integration test is a SEPARATE file
- Do NOT add any API dumps, baseline updates, or deviation changes
- Do NOT add @Tag("integration") annotations — these tests should run in the default test suite
- Do NOT claim that orchestration, persistence, or subprocess cancellation is characterized by this PR

Validation:
```bash
./gradlew :tramai-engine:test --tests '*EngineCancellationContractTest' --rerun-tasks
./gradlew :tramai-observability:test --tests '*OpenTelemetryCancellationIntegrationTest' --rerun-tasks
./gradlew verifyPr -PchangeClass=runtime-behaviour
```

Create the files now.
