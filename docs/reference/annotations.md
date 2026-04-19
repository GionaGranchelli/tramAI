# Annotation Reference

Tramai currently uses a small annotation set.

## `@AiService`

Marks an interface as an Tramai service contract.

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
- `maxRetries`: structured-output retry count
- `providerRetries`: provider retry count for transient failures
- `timeoutMillis`: per-attempt provider timeout in milliseconds

Example:

```kotlin
@Operation(
    prompt = "Summarize the incident",
    model = "gpt-5.1-chat-latest",
    provider = "openai",
    maxRetries = 3,
    providerRetries = 2,
    timeoutMillis = 15_000,
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

## Current Gaps

Tramai does not yet include annotations for:

- tool calling
- streaming
- provider-native structured output toggles
- memory or conversation state
