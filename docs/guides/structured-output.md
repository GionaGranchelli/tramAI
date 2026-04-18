# Structured Output

Structured output is Aurora's defining feature.

Instead of asking the model for a string and parsing it yourself, you return a Kotlin type and let Aurora do the contract work.

## Basic Example

```kotlin
data class TicketSummary(
    val severity: String,
    val owner: String,
    val actions: List<String>,
)

@AiService
interface IncidentAnalyzer {
    @Operation(
        prompt = "Analyze the incident and return a structured summary",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(incident: String): TicketSummary
}
```

## What Aurora Does Automatically

When a method returns a non-`String` non-`Unit` type, Aurora:

1. generates a schema-like JSON contract
2. injects that contract into the prompt
3. asks the provider to return JSON only
4. extracts JSON from the response
5. deserializes it into the return type
6. validates the result
7. retries with structured feedback if parsing or validation fails

The retry loop lives in the engine. The validation result comes from the structured module. That separation is deliberate.

## Validation Annotations

Aurora currently supports these annotations:

- `@AiDescription`
- `@AiRange`
- `@AiMinItems`

Example:

```kotlin
data class SpendAnalysis(
    @property:AiDescription("Total spend in USD")
    @property:AiRange(min = 0.0, max = 1_000_000.0)
    val totalSpend: Double,

    @property:AiDescription("Ordered cost reduction recommendations")
    @property:AiMinItems(1)
    val recommendations: List<String>,

    @property:AiDescription("Confidence between 0 and 1")
    @property:AiRange(min = 0.0, max = 1.0)
    val confidence: Double,
)
```

## Retry Behavior

The current retry behavior is:

- `maxRetries` on `@Operation` controls structured retry attempts
- default is `2`, which means up to `3` total attempts
- retries happen only for structured parsing and validation failures

Example:

```kotlin
@Operation(
    prompt = "Return a structured classification",
    model = "gpt-5.1-chat-latest",
    maxRetries = 4,
)
```

## What Works Well

Aurora structured output works best when:

- the return type is a data class
- field names are clear
- constraints are simple and explicit
- the prompt describes the job, not the JSON syntax details

## What To Avoid

Avoid these patterns:

- giant nested object graphs without a real need
- ambiguous string fields like `result`, `value`, `statusText`, `finalAnswer` all at once
- weakly constrained list content when the list drives business decisions

## JSON Extraction Behavior

Aurora currently tolerates:

- raw JSON
- fenced markdown code blocks containing JSON
- JSON objects or arrays embedded in surrounding text

This makes the parser resilient to common provider formatting drift.

## Current Boundaries

Structured output currently does not provide:

- provider-native structured output modes
- full schema expressiveness
- streaming structured partials
- schema version negotiation

The implemented baseline is schema-in-prompt plus parse-and-retry.
