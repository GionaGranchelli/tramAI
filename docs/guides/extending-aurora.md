# Extending Aurora

Aurora is intentionally structured so new providers and observers can be added without changing the public service model.

## Add A Custom Provider

Implement `ModelProvider`:

```kotlin
class MyProvider : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse {
        return ModelResponse(
            content = "hello",
            modelUsed = request.model,
        )
    }

    override fun providerId(): String = "my-provider"
}
```

Then register it:

```kotlin
val aurora = Aurora {
    provider(MyProvider(), default = true)
    model("my-model", "my-provider")
}
```

## Provider Implementation Rules

Keep provider modules responsible for:

- translating `ModelRequest` into provider-native HTTP payloads
- mapping provider responses into `ModelResponse`
- surfacing transport or protocol failures as provider errors

Do not move these concerns into providers:

- retry policy for structured output
- typed parsing logic
- tracing policy
- service proxy rules

## Add A Custom Observer

Implement `OperationObserver` from `aurora-core` and attach it through the standalone builder.

This is the right place for:

- custom tracing adapters
- audit hooks
- internal logging bridges
- request-attempt counters

## Extend Documentation Safely

If you add a meaningful new behavior:

1. update or add a spec
2. add or update an ADR if the design changed
3. add tests
4. update the relevant guide and reference file

That keeps the project aligned with specs-driven development rather than allowing the docs to drift.

## Before Adding Large Features

Check whether the feature belongs in the current shape of Aurora.

Good fit:

- new provider module
- better testing helper
- richer observer implementation
- additional configuration docs

Higher-risk fit:

- agent-style tool orchestration
- memory and session state
- generated code paths
- provider-specific special cases leaking into the engine

For those, update specs and architecture docs first.
