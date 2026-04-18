# Testing Aurora Code

Aurora includes a dedicated testing module so you can test AI-dependent application code without network calls.

## What The Testing Module Provides

- `MockAiProvider`
- `SimulatedFailureProvider`
- `RecordingOperationObserver`
- `AuroraAssertions`

## Basic Test Pattern

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith """{"status":"ok"}"""
}

val observer = RecordingOperationObserver()

val aurora = Aurora {
    provider(provider, default = true)
    model("gpt-5.1-chat-latest", "mock")
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
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(invoiceId: String): Status
}

data class Status(
    val status: String,
)
```

Test:

```kotlin
val service = aurora.create<Analyzer>()
val result = runBlocking { service.analyze("invoice-1") }

assertEquals(Status("ok"), result)

AuroraAssertions.assertThat(provider, observer)
    .whenCalled("analyze")
    .wasCalledTimes(1)
    .andRetried(0)
    .andParsedSuccessfully()
    .emittedProvider("mock")
```

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

## What To Test

Recommended Aurora-facing application tests:

- prompt and argument wiring through service methods
- structured return behavior
- retry behavior for malformed output
- provider routing decisions
- business logic wrapped around Aurora service calls

## What Not To Over-Test

You usually do not need to unit test:

- every internal prompt word choice
- provider HTTP specifics in application tests
- Jackson itself

Those belong in Aurora module tests or provider integration tests.

## Spring Applications

For Spring applications, you can override real providers with a test `ModelProvider` bean or assemble Aurora manually in isolated tests, depending on how integrated the code under test is.

## Confidence Model

The goal is not to prove model intelligence. The goal is to prove:

- your application sends the expected inputs
- Aurora routes and retries as expected
- your code handles the typed result correctly
