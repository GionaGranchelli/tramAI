# Annotation Reference

TramAI currently uses a small annotation set.

## `@AiService`

Marks an interface as a TramAI service contract.

Use it on interfaces only.

```kotlin
@AiService
interface Summarizer
```

## `@Operation`

Declares an AI-backed method.

Fields:

- `prompt`: base user prompt
- `model`: logical model name
- `provider`: optional explicit provider id override
- `tools`: optional list of tool names available to the operation
- `maxRetries`: structured-output retry count
- `providerRetries`: provider retry count for transient failures
- `timeoutMillis`: per-attempt provider timeout in milliseconds
- `cacheable`: enables engine-owned caching for successful non-streaming responses
- `cacheTtlMillis`: cache lifetime in milliseconds when caching is enabled

Example:

```kotlin
@Operation(
    prompt = "Summarize the incident",
    model = "gpt-5.1-chat-latest",
    provider = "openai",
    tools = ["lookup"],
    maxRetries = 3,
    providerRetries = 2,
    timeoutMillis = 15_000,
    cacheable = true,
    cacheTtlMillis = 60_000,
)
```

## `@SystemPrompt`

Applies a service-wide system prompt to all operations on the interface.

```kotlin
@AiService
@SystemPrompt("You are a precise billing assistant.")
interface BillingAnalyzer
```

## `@AiDescription`

Adds a human-readable description to a structured output property.

Useful for:

- clarifying field intent
- making schema prompts less ambiguous

## `@AiRange`

Constrains a numeric field.

```kotlin
@property:AiRange(min = 0.0, max = 1.0)
val confidence: Double
```

## `@AiMinItems`

Constrains collection length.

```kotlin
@property:AiMinItems(1)
val recommendations: List<String>
```

## Current Annotation Behavior

- annotations are runtime-retained
- structured field annotations influence schema generation and validation
- operation annotations influence prompt rendering and routing

## Current Behavior Boundaries

TramAI does not currently use dedicated annotations for:

- streaming
- provider-native structured output toggles
- memory or conversation state

Streaming is selected through the return type `Flow<StreamChunk>`.

Tool calling is enabled through `@Operation(tools = [...])` rather than through a separate annotation.
