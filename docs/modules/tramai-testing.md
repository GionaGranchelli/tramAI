# Module: `tramai-testing`

> **One-liner:** Mock providers, recording observers, and fluent assertions for deterministic Tramai integration tests.
> **Module type:** `tooling`
> **Group:** `dev.tramai`, **Version:** `0.3.1`
> **Source files:** 6, **LOC:** 448
> **Dependency:** `tramai-core` (api), AssertJ (implementation)

---

## L1: Quick Start (30-second read)

### What

`tramai-testing` provides six test-dedicated types that let you test Tramai `@AiService` interfaces without calling a real AI provider:

| Type | Purpose |
|------|---------|
| `MockAiProvider` | Deterministic in-memory provider. Returns pre-configured responses in sequence per method name. |
| `SimulatedFailureProvider` | Provider that interleaves responses and failures (retryable / non-retryable) in a defined sequence. |
| `MockTool` | A `TramaiTool` that records every invocation and returns a fixed or computed response. |
| `RecordingOperationObserver` | Captures every engine attempt, provider response, parse failure, and engine event. |
| `TramaiAssertions` | Fluent assertion DSL wiring a provider + observer into call-count, retry, parse, and failure assertions. |
| `RecordedRequestProvider` | Interface contract shared by `MockAiProvider` and `SimulatedFailureProvider` for request inspection. |

### Why

Real AI provider calls are **non-deterministic, slow, expensive, and dependent on network access**. Testing against real models means flaky tests, long feedback loops, API costs, and no ability to assert on retry/error behavior. `tramai-testing` solves this by letting you:

- **Replace the provider** with an in-memory double that returns whatever you configure
- **Simulate failures** (rate limits, timeouts, auth errors) to prove your retry policy, circuit breakers, and error handling work
- **Capture every attempt** including parse failures and engine events, so you can assert on the exact number of retries, the failure type, and the parse outcome
- **Assert fluently** against the recorded call history with a DSL that keeps tests readable

### When to use

Use `tramai-testing` **whenever you write integration tests for Tramai `@AiService` interfaces** — which should be essentially always.

| Scenario | What to use |
|----------|-------------|
| Happy-path test — provider returns valid output | `MockAiProvider` + `RecordingOperationObserver` + `TramaiAssertions` |
| Retry recovery — first call fails, second succeeds | `MockAiProvider` with sequenced responses, or `SimulatedFailureProvider` with failure → response |
| Non-retryable failure surfaces immediately | `SimulatedFailureProvider` + `.nonRetryableFailure(...)` |
| Structured parse retries exhausted | `MockAiProvider` with N consecutive invalid JSON responses |
| Tool interaction tests | `MockTool` + `MockAiProvider` |
| Exhaustive provider failure testing | `SimulatedFailureProvider` with arbitrary `Throwable` |

### How to add

**Gradle (Kotlin DSL) — `testImplementation` only:**

```kotlin
dependencies {
    testImplementation("dev.tramai:tramai-testing:0.3.1")
}
```

**Maven — `test` scope only:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-testing</artifactId>
    <version>0.3.1</version>
    <scope>test</scope>
</dependency>
```

> **Important:** This module must **never** be added as `implementation` or `api` — it is strictly a test-support library. Its AssertJ dependency (`implementation(libs.assertj.core)`) is bundled internally so it does not leak into your application's compile classpath.

### Where to go next

| If you want to... | Go here |
|-------------------|---------|
| Create a Tramai engine without framework adapters | `tramai-standalone` |
| Understand the provider SPI being mocked | `tramai-core` (`ModelProvider`) |
| See real tests using these utilities | `tramai-testing/src/test/kotlin/` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

Every test follows the same pattern:

1. **Create** a mock/simulated provider and recording observer
2. **Wire** them into a `Tramai` instance (via `tramai-standalone`)
3. **Invoke** the `@AiService` method
4. **Assert** with `TramaiAssertions`

#### Happy path with `MockAiProvider`

```kotlin
data class Weather(val temperature: Double, val condition: String)

@AiService
interface WeatherService {
    @Operation(prompt = "Weather in {city}", model = "claude-sonnet-4-20250514")
    suspend fun getWeather(city: String): Weather
}

@Test
fun `mock provider returns configured response`() {
    // 1. Create the mock provider — configure one response for method "getWeather"
    val provider = MockAiProvider {
        onMethod("getWeather") respondWith """{"temperature":22.5,"condition":"Sunny"}"""
    }
    val observer = RecordingOperationObserver()

    // 2. Wire into Tramai
    val tramai = Tramai {
        provider(provider, default = true)
        model("claude-sonnet-4-20250514", "mock")
        observer(observer)
    }
    val service = tramai.create<WeatherService>()

    // 3. Invoke
    val result = runBlocking { service.getWeather("London") }

    // 4. Assert — both value and call history
    assertEquals(Weather(22.5, "Sunny"), result)
    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("getWeather")
        .wasCalledTimes(1)
        .andRetried(0)
        .andParsedSuccessfully()
        .emittedProvider("mock")
}
```

#### Retry recovery with sequenced responses

Configure multiple responses for the same method — the provider returns them in order, cycling on the last:

```kotlin
@Test
fun `mock provider recovers from parse failure on retry`() {
    val provider = MockAiProvider {
        onMethod("getWeather") respondWith "not json"          // attempt 1 → parse fail
        onMethod("getWeather") respondWith """{"temperature":22.5,"condition":"Sunny"}"""  // attempt 2 → success
    }
    val observer = RecordingOperationObserver()
    val tramai = Tramai {
        provider(provider, default = true)
        model("claude-sonnet-4-20250514", "mock")
        observer(observer)
    }
    val service = tramai.create<WeatherService>()

    val result = runBlocking { service.getWeather("London") }

    assertEquals(Weather(22.5, "Sunny"), result)
    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("getWeather")
        .wasCalledTimes(2)
        .andRetried(1)
        .andParsedSuccessfully()
}
```

#### Retry exhaustion — all responses are broken JSON

```kotlin
@Test
fun `mock provider surfaces structured output exception after retries exhausted`() {
    val provider = MockAiProvider {
        onMethod("getWeather") respondWith "not json"
        onMethod("getWeather") respondWith "still not json"
        onMethod("getWeather") respondWith "still broken"
    }
    val observer = RecordingOperationObserver()
    val tramai = Tramai {
        provider(provider, default = true)
        model("claude-sonnet-4-20250514", "mock")
        observer(observer)
    }
    val service = tramai.create<WeatherService>()

    assertThatThrownBy { runBlocking { service.getWeather("London") } }
        .isInstanceOfSatisfying(StructuredOutputException::class.java) { error ->
            assertEquals(3, error.attemptCount)
            assertEquals("still broken", error.lastRawResponse)
        }

    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("getWeather")
        .wasCalledTimes(3)
        .andRetried(2)
        .andObservedParseFailure()
}
```

### Testing failure scenarios with `SimulatedFailureProvider`

`SimulatedFailureProvider` models real-world provider errors. You sequence outcomes per method: some calls succeed, others throw.

#### Retryable provider failure → recovery

```kotlin
@Test
fun `simulated provider recovers from rate limit`() {
    val provider = SimulatedFailureProvider {
        onMethod("summarize").retryableFailure("rate limited", statusCode = 429)
        onMethod("summarize") respondWith "recovered summary"
    }
    val observer = RecordingOperationObserver()
    val tramai = Tramai {
        provider(provider, default = true)
        model("claude-sonnet-4-20250514", "simulated-failure")
        observer(observer)
    }
    val service = tramai.create<RawSummaryService>()

    val result = runBlocking { service.summarize("invoice-1") }

    assertEquals("recovered summary", result)
    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("summarize")
        .wasCalledTimes(2)
        .andRetried(1)
        .andObservedFailure(ProviderException::class)
        .emittedProvider("simulated-failure")
}
```

#### Non-retryable failure surfaces immediately

```kotlin
@Test
fun `non-retryable failure throws immediately`() {
    val provider = SimulatedFailureProvider {
        onMethod("summarize").nonRetryableFailure("unauthorized", statusCode = 401)
    }
    val observer = RecordingOperationObserver()
    val tramai = Tramai {
        provider(provider, default = true)
        model("claude-sonnet-4-20250514", "simulated-failure")
        observer(observer)
    }
    val service = tramai.create<RawSummaryService>()

    assertThatThrownBy { runBlocking { service.summarize("invoice-1") } }
        .isInstanceOfSatisfying(ProviderException::class.java) { error ->
            assertEquals(401, error.statusCode)
            assertFalse(error.retryable)
        }

    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("summarize")
        .wasCalledTimes(1)
        .andRetried(0)
        .andFailedWith(ProviderException::class)
}
```

#### Arbitrary failure injection

```kotlin
val provider = SimulatedFailureProvider {
    onMethod("analyze").failWith(IOException("connection reset"))
}
```

### Testing tools with `MockTool`

`MockTool` captures every invocation so you can assert tool usage:

```kotlin
data class GetTimeInput(val timezone: String)

@Test
fun `tool calls are captured and asserted`() {
    val getTimeTool = MockTool.fixed<GetTimeInput, String>(
        name = "get_time",
        description = "Get current time",
        response = "12:00 UTC",
    )
    val provider = MockAiProvider {
        onMethod("getTime") respondWith """{"time":"12:00 UTC"}"""
    }
    val observer = RecordingOperationObserver()
    // ... wire into Tramai with the tool registered ...

    TramaiAssertions.assertThat(provider, observer)
        .whenCalled("getTime")
        .andCalledTool("get_time")
        .andCalledToolTimes("get_time", 1)
}
```

### Assertion reference

`TramaiAssertions.assertThat(provider, observer)` returns a `TramaiAssertion` with these methods:

| Method | What it asserts |
|--------|-----------------|
| `.whenCalled(methodName)` | Narrows assertion scope to one method |
| `.wasCalledTimes(n)` | Exact number of provider requests for the method |
| `.andRetried(n)` | Number of retries after the initial call |
| `.andParsedSuccessfully()` | Final attempt's structured parse succeeded |
| `.andObservedParseFailure()` | At least one attempt had a parse failure |
| `.andObservedFailure(errorType)` | At least one attempt failed with the given exception type |
| `.andFailedWith(errorType)` | The *final* attempt failed with the given exception type |
| `.emittedProvider(providerId)` | Every attempt used the given provider ID |
| `.andCalledTool(toolName)` | At least one tool call used the given name |
| `.andCalledToolTimes(toolName, n)` | Exact count of tool calls for the given name |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-testing` follows three principles:

1. **Determinism over realism** — tests should never flake due to model non-determinism. Every mock returns exactly what you configure, in exactly the order you configure it.
2. **Observability as testability** — the `RecordingOperationObserver` exposes the full engine lifecycle (attempts, retries, parse outcomes, failures) so tests assert on *how* the engine behaved, not just *what* it returned.
3. **Readability through fluent DSL** — assertion chains read like natural language specs: `.whenCalled("analyze").wasCalledTimes(2).andRetried(1).andParsedSuccessfully()`.

### Module boundary

```
┌──────────────────────────────────────────────┐
│               tramai-testing                  │
│                                               │
│   ┌──────────────────────────────────────┐    │
│   │       RecordedRequestProvider         │    │
│   │       (interface, 1 file)            │    │
│   │  val requests: MutableList<ModelReq> │    │
│   └──────────┬───────────────────────────┘    │
│              │ implements                     │
│   ┌──────────▼──────────┐ ┌─────────────────┐ │
│   │   MockAiProvider     │ │SimulatedFailure │ │
│   │   (1 file)           │ │Provider (1 file)│ │
│   │                      │ │                 │ │
│   │ ModelProvider + DSL  │ │ModelProvider +  │ │
│   │ returns text only    │ │DSL returns text │ │
│   │                      │ │or throws errors │ │
│   └──────────────────────┘ └─────────────────┘ │
│                                               │
│   ┌──────────────────────────────────────┐    │
│   │      RecordingOperationObserver       │    │
│   │       (1 file)                       │    │
│   │  OperationObserver implementation    │    │
│   │  MutableList<CallRecord>             │    │
│   └──────────────────────────────────────┘    │
│                                               │
│   ┌──────────────────────────────────────┐    │
│   │         TramaiAssertions              │    │
│   │         (1 file)                     │    │
│   │  Fluent assertion DSL                │    │
│   │  Wraps RecordedRequestProvider +     │    │
│   │  RecordingOperationObserver          │    │
│   └──────────────────────────────────────┘    │
│                                               │
│   ┌──────────────────────────────────────┐    │
│   │            MockTool                   │    │
│   │         (1 file)                     │    │
│   │  TramaiTool<I,O> implementation      │    │
│   │  Records calls, returns fixed values │    │
│   └──────────────────────────────────────┘    │
│                                               │
│   Dependency: tramai-core (api)               │
│   Dependency: assertj-core (implementation)   │
└──────────────────────────────────────────────┘
```

**What `tramai-testing` does NOT include:**
- No test runner integration — use your framework of choice (JUnit 5, Kotlin Test)
- No Spring Boot test slices — pair with `tramai-spring`'s `@EnableTramai` if needed
- No HTTP mocking — Tramai's test model replaces the provider entirely, so no need for MockWebServer or WireMock

### Dependency graph

```
tramai-core ─── tramai-testing ─── assertj-core (implementation)
     │
     └── (testImplementation: tramai-standalone, coroutines, JUnit)
```

At compile time (`api`), `tramai-testing` exposes `tramai-core` types. At test runtime, you also need `tramai-standalone` (or `tramai-engine`) to wire the mock provider into a working engine — but that's a `testImplementation` dependency, not a module dependency.

### Inner mechanics

#### 1. `RecordedRequestProvider` — the shared contract

Both `MockAiProvider` and `SimulatedFailureProvider` implement `RecordedRequestProvider`:

```kotlin
interface RecordedRequestProvider {
    val requests: MutableList<ModelRequest>
}
```

This gives `TramaiAssertions` a uniform way to inspect what requests were emitted — the method name, messages, tool calls, and other `ModelRequest` fields — regardless of which provider variant is in use.

#### 2. `MockAiProvider` — sequential text response engine

```
MockAiProvider.Builder
    │
    ├── onMethod("analyze") respondWith "response A"    ← appends to responsesByMethod["analyze"]
    ├── onMethod("analyze") respondWith "response B"    ← appends to responsesByMethod["analyze"]
    └── build()
            │
            ▼
    MockAiProvider
        │  complete(request):
        │    requests += request                    ← record every request
        │    method = request.operationMethod       ← route by method name
        │    index = responseIndexByMethod[method]++
        │    response = responses[method][index]    ← sequential, wraps on last
        │    return ModelResponse(content = response)
        │
        └── providerId() = "mock"
```

Key design decisions:

- **Method-based routing** — each `@Operation` method gets its own response sequence, so a single provider can serve a service with multiple operations
- **Sequential consumption** — responses are consumed in order; once exhausted, the last response repeats (so retries beyond the configured sequence still get *something*)
- **Text-only** — `MockAiProvider` only returns string content. For structured output, the `tramai-structured` handler parses the string into the target type. If the string isn't valid JSON for the expected schema, the engine triggers a parse failure and retry — which is exactly the behavior you want to test.

#### 3. `SimulatedFailureProvider` — outcome sequencing

```
SimulatedFailureProvider.Builder
    │
    ├── onMethod("summarize").retryableFailure("rate limited", statusCode=429)   ← Outcome.Failure
    ├── onMethod("summarize") respondWith "recovered"                            ← Outcome.Response
    └── build()
            │
            ▼
    SimulatedFailureProvider
        │  complete(request):
        │    requests += request
        │    method = request.operationMethod
        │    outcome = outcomes[method][index]++
        │    when (outcome):
        │      is Outcome.Response -> return outcome.response
        │      is Outcome.Failure  -> throw outcome.error
        │
        └── providerId() = "simulated-failure"
```

Outcome types:

| Outcome | Builder method | What happens |
|---------|---------------|--------------|
| `Outcome.Response` | `respondWith(string)` | Returns `ModelResponse(content = string)` |
| `Outcome.Response` | `respondWith(ModelResponse)` | Returns the exact response, useful for setting token counts |
| `Outcome.Failure` | `retryableFailure(message, statusCode?)` | Throws `ProviderException(retryable=true, statusCode, message)` |
| `Outcome.Failure` | `nonRetryableFailure(message, statusCode?)` | Throws `ProviderException(retryable=false, statusCode, message)` |
| `Outcome.Failure` | `failWith(throwable)` | Throws the exact exception — any `Throwable` |

#### 4. `RecordingOperationObserver` — the engine's flight recorder

Implements `OperationObserver` from `tramai-core`:

```
RecordingOperationObserver
    │
    ├── callRecords: MutableList<CallRecord>    ← all attempts, in order
    │
    └── onCallStarted(context)
            │
            ▼
    CallRecord(
        context = OperationCallContext(methodName, providerId, attempt, ...),
        response: ModelResponse? = null,            ← set by onProviderResponse()
        providerFailure: Throwable? = null,          ← set by onProviderFailure()
        parseFailureSummary: String? = null,         ← set by onStructuredParseFailure()
        parseSuccess: Boolean? = null,               ← set by onCallCompleted()
        engineEvents: MutableList<EngineEvent>,      ← set by onEngineEvent()
    )
```

Each call to `onCallStarted` creates a new `CallRecord` appended to `callRecords`. The returned `RecordingObservation` wires each subsequent observer callback back to that record. This gives `TramaiAssertions` access to:

- `response` — what the provider returned (for asserting on content)
- `providerFailure` — what exception was thrown (for asserting on error type)
- `parseFailureSummary` — the structured output failure description (for asserting parse failures occurred)
- `parseSuccess` — whether the final structured parse succeeded (for asserting on parse outcome)
- `engineEvents` — arbitrary named events with attributes (for asserting on engine behavior like retry decisions, circuit breaker state)

#### 5. `TramaiAssertions` — fluent assertion DSL

```
TramaiAssertions.assertThat(provider, observer)
    │
    ▼
TramaiAssertion
    │
    └── .whenCalled(methodName)
            │
            ▼
    MethodAssertion
        │
        ├── .wasCalledTimes(n)         → assert provider.requests.count { opMethod == methodName } == n
        ├── .andRetried(n)             → assert (callRecords.count { methodName } - 1) == n
        ├── .andParsedSuccessfully()   → assert last callRecord.parseSuccess == true
        ├── .andObservedParseFailure() → assert any callRecord.parseFailureSummary is not blank
        ├── .andObservedFailure(cls)   → assert any callRecord.providerFailure isInstanceOf cls
        ├── .andFailedWith(cls)        → assert last callRecord.providerFailure isInstanceOf cls
        ├── .emittedProvider(id)       → assert every callRecord.context.providerId == id
        ├── .andCalledTool(name)       → assert any request's toolCalls includes name
        └── .andCalledToolTimes(n, m)  → assert exact count of tool calls with name
```

The assertion methods are chainable (each returns `MethodAssertion`), and all delegate to AssertJ's `assertThat(...)` under the hood.

#### 6. `MockTool` — recording tool double

```kotlin
class MockTool<I, O>(
    name: String,
    description: String,
    inputType: KClass<I>,
    private val responder: suspend (I) -> O,   ← function to compute or return response
) : TramaiTool<I, O> {
    val calls = mutableListOf<I>()               ← every invocation recorded here

    override suspend fun execute(input: I, context: ToolExecutionContext): O {
        calls.add(input)
        return responder(input)
    }
}
```

Companion factory `MockTool.fixed(name, description, response)` creates a tool that returns the same value on every invocation — the most common pattern. For variable responses, construct `MockTool` directly with a custom `responder` lambda.

### Error model

`tramai-testing` does not define its own exceptions. Errors are surfaced through Tramai's standard exception hierarchy from `tramai-core`:

| Scenario | Exception | Where thrown |
|----------|-----------|-------------|
| Missing `operationMethod` on request | `ProviderException` | `MockAiProvider.complete()`, `SimulatedFailureProvider.complete()` |
| No responses configured for a method | `ProviderException` | `MockAiProvider.complete()` |
| No outcomes configured for a method | `ProviderException` | `SimulatedFailureProvider.complete()` |
| Retryable provider failure | `ProviderException(retryable=true)` | `SimulatedFailureProvider` via `.retryableFailure()` |
| Non-retryable provider failure | `ProviderException(retryable=false)` | `SimulatedFailureProvider` via `.nonRetryableFailure()` |
| Exhausted structured parse retries | `StructuredOutputException` | Engine (if mock returns all invalid JSON) |

### Testing strategy

The module itself has one test file (`TestingModuleTest.kt`) with 5 tests that serve as both regression tests and living documentation:

| Test | What it proves |
|------|---------------|
| `mock provider returns configured method responses and records requests` | Happy-path: mock returns valid JSON, TramaiAssertions proves 1 call, 0 retries, successful parse |
| `mock provider can drive retry scenarios with sequenced responses` | Parse-failure recovery: 2nd response is valid JSON, assertions prove 2 calls, 1 retry |
| `simulated failure provider can drive retryable provider recovery` | Provider-failure recovery: 429 then success, assertions prove failure observed |
| `simulated failure provider surfaces non retryable failures immediately` | Non-retryable: 401 thrown immediately, 1 call, 0 retries, final failure is ProviderException |
| `mock provider captures exhausted structured parse failures` | Exhaustion: 3 invalid JSON responses, `StructuredOutputException` with `attemptCount=3` |

For consumers writing their own tests:

- **Every `@AiService` method** should have at least one happy-path test with `MockAiProvider` and one failure test
- **Always wire a `RecordingOperationObserver`** — even on happy paths, it documents the exact call count and retry behavior, which catches regression when retry policies change
- **Use `SimulatedFailureProvider` for provider-adjacent behavior** (rate limiting, auth failures, timeouts) — the engine's retry policy is only testable when you control exactly which failures occur
- **Test structured output retries** with `MockAiProvider` by configuring invalid JSON for the first N responses and valid JSON for the last — this proves your schema and handler are wired correctly
