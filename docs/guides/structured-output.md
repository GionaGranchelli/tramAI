# Structured Output

Structured output is Tramai's defining feature.

Instead of asking the model for a string and parsing it yourself, you return a Kotlin type and let Tramai do the contract work.

## Start With This

If you want the smallest useful structured-output example, start here.

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
        model = "gpt-4o",
    )
    suspend fun analyze(incident: String): TicketSummary
}
```

That is the only code change required to switch from raw text to typed output:

- return a Kotlin type instead of `String`
- keep the operation prompt focused on the business task

## From Raw String To Typed DTO

Raw string version:

```kotlin
@AiService
interface IncidentAnalyzer {
    @Operation(
        prompt = "Analyze the incident and summarize the outcome",
        model = "gpt-4o",
    )
    suspend fun analyze(incident: String): String
}
```

Structured version:

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
        model = "gpt-4o",
    )
    suspend fun analyze(incident: String): TicketSummary
}
```

That one change moves parsing, extraction, and retry handling into TramAI instead of your application code.

## What Tramai Does Automatically

When a method returns a non-`String` non-`Unit` type, Tramai:

1. generates a schema-like JSON contract
2. injects that contract into the prompt
3. asks the provider to return JSON only
4. extracts JSON from the response
5. deserializes it into the return type
6. validates the result
7. retries with structured feedback if parsing or validation fails

The retry loop lives in the engine. The validation result comes from the structured module. That separation is deliberate.

## Why This Matters

Without structured output, application code usually ends up with:

- raw strings that need manual parsing
- prompt-specific JSON cleanup logic
- repeated error handling for malformed model responses

With structured output, your application code gets:

- a typed return value
- a central retry path for malformed or invalid responses
- one explicit contract close to the service method

## Validation Annotations

Tramai currently supports these annotations:

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

Use these annotations when the values are operationally important. If a field drives decisions, constrain it.

## Retry Behavior

The current retry behavior is:

- `maxRetries` on `@Operation` controls structured retry attempts
- default is `2`, which means up to `3` total attempts
- retries happen only for structured parsing and validation failures

Example:

```kotlin
@Operation(
    prompt = "Return a structured classification",
    model = "gpt-4o",
    maxRetries = 4,
)
```

## First Good Production Pattern

For a first production-grade structured operation:

1. keep the return type small
2. use precise field names
3. constrain critical numeric and list fields
4. avoid nesting unless the nested structure has clear business meaning

## What Works Well

Tramai structured output works best when:

- the return type is a data class
- field names are clear
- constraints are simple and explicit
- the prompt describes the job, not the JSON syntax details

## What To Avoid

Avoid these patterns:

- giant nested object graphs without a real need
- ambiguous string fields like `result`, `value`, `statusText`, `finalAnswer` all at once
- weakly constrained list content when the list drives business decisions

## Testing Structured Output

The fastest deterministic test pattern is:

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith """
        {
          "severity": "high",
          "owner": "platform-team",
          "actions": ["page on-call", "rollback release"]
        }
    """.trimIndent()
}
```

Then assert the parsed Kotlin object directly.

To verify recovery behavior, queue an invalid response first and a valid response second.

## JSON Extraction Behavior

Tramai currently tolerates:

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

## Next Step

After your first structured operation works:

- read [Testing TramAI Code](./testing.md)
- read [Providers and Model Routing](./providers.md)
- read [Production Hardening](./production-hardening.md) if the operation is business-critical
