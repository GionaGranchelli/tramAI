# Testing Tramai Code

Tramai includes a dedicated testing module so you can test AI-dependent application code without network calls.

## Start With This

If you only need one working pattern, use:

- `MockAiProvider` to control model responses
- `RecordingOperationObserver` if you want to assert retries and execution details
- `TramaiAssertions` to assert Tramai behavior fluently

## What The Testing Module Provides

- `MockAiProvider`
- `SimulatedFailureProvider`
- `RecordingOperationObserver`
- `TramaiAssertions`

## Copy-Paste Test Pattern

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith """{"status":"ok"}"""
}

val observer = RecordingOperationObserver()

val tramai = Tramai {
    provider(provider, default = true)
    model("gpt-4o", "mock")
    observer(observer)
}
```

Then call the service and assert both result and behavior.

## Example

```kotlin
@AiService
interface Analyzer {
    @Operation(
        prompt = "Analyze the invoice",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceId: String): Status
}

data class Status(
    val status: String,
)
```

Test:

```kotlin
val service = tramai.create<Analyzer>()
val result = runBlocking { service.analyze("invoice-1") }

assertEquals(Status("ok"), result)

TramaiAssertions.assertThat(provider, observer)
    .whenCalled("analyze")
    .wasCalledTimes(1)
    .andRetried(0)
    .andParsedSuccessfully()
    .emittedProvider("mock")
```

## The Fastest Useful Tests

Most application teams should start with these three tests:

1. happy path returns the typed result you expect
2. malformed first response retries and then succeeds
3. retryable provider failure recovers or fails the way you expect

## Simulating Retries

You can queue multiple responses for the same method:

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith "not json"
    onMethod("analyze") respondWith """{"status":"ok"}"""
}
```

This is useful for verifying:

- structured retry behavior
- final parse success
- call counts

For provider-side retry behavior, use `SimulatedFailureProvider`:

```kotlin
val provider = SimulatedFailureProvider {
    onMethod("analyze").retryableFailure("rate limited", statusCode = 429)
    onMethod("analyze") respondWith """{"status":"ok"}"""
}
```

This lets application tests verify:

- retryable provider failures
- non-retryable failure handling
- recovery after transient provider errors

## Structured Output Test From Zero

This is the most common first testing need:

```kotlin
data class Status(
    val status: String,
)

val provider = MockAiProvider {
    onMethod("analyze") respondWith """{"status":"ok"}"""
}
```

Then assert the typed value returned from your `@AiService` method. That proves your application code is consuming structured output, not a fragile raw string.

## Spring Test Shape

For Spring applications, the practical pattern is:

1. wire your normal Spring context
2. replace the real provider with a deterministic `ModelProvider` bean in tests
3. exercise the service or controller normally

That keeps the test close to real application wiring without making network calls.

## What To Test

Recommended Tramai-facing application tests:

- prompt and argument wiring through service methods
- structured return behavior
- retry behavior for malformed output
- provider routing decisions
- business logic wrapped around Tramai service calls

## What Not To Over-Test

You usually do not need to unit test:

- every internal prompt word choice
- provider HTTP specifics in application tests
- Jackson itself

Those belong in Tramai module tests or provider integration tests.

## Confidence Model

The goal is not to prove model intelligence. The goal is to prove:

- your application sends the expected inputs
- Tramai routes and retries as expected
- your code handles the typed result correctly

## Next Step

After the first deterministic tests are in place:

- read [Structured Output](./structured-output.md) if your methods still return raw strings
- read [Providers and Model Routing](./providers.md) if you need to verify routing behavior
- read [Observability](./observability.md) if you want to assert emitted spans or metrics
