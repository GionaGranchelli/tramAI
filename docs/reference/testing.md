# Testing Reference

Tramai's testing support currently lives in `tramai-testing`.

## `MockAiProvider`

Purpose:

- deterministic provider for tests

Key behavior:

- captures `ModelRequest` objects
- returns configured responses by method name
- can queue multiple responses to simulate retries

Example:

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith """{"status":"ok"}"""
}
```

## `SimulatedFailureProvider`

Purpose:

- deterministic failure and recovery sequences for retry-path tests

Key behavior:

- captures `ModelRequest` objects
- can throw retryable or non-retryable failures in sequence
- can recover with a later configured response

Example:

```kotlin
val provider = SimulatedFailureProvider {
    onMethod("analyze").retryableFailure("rate limited", statusCode = 429)
    onMethod("analyze") respondWith """{"status":"ok"}"""
}
```

## `RecordingOperationObserver`

Purpose:

- capture per-attempt observation data

Useful for asserting:

- retry count
- chosen provider id
- parse success or failure
- observed provider failures

## `TramaiAssertions`

Fluent assertion helper around:

- recorded-request test providers
- `RecordingOperationObserver`

Example:

```kotlin
TramaiAssertions.assertThat(provider, observer)
    .whenCalled("analyze")
    .wasCalledTimes(2)
    .andRetried(1)
    .andParsedSuccessfully()
    .emittedProvider("mock")
```

## Best Use Cases

The testing module is best for:

- application service tests
- prompt wiring verification
- structured retry tests
- routing tests

It is not a replacement for:

- provider HTTP integration tests
- system tests against real services

Use both where appropriate.
