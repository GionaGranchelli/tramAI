# Annotation Reference

This page describes the current TramAI annotation surface in `0.3.x`.

If you are learning TramAI, start with the guides first. Use this page when you need exact field meanings and current behavior.

## `@AiService`

Marks an interface as a TramAI service contract.

### Use it on

- interfaces that TramAI should proxy

### Example

```kotlin
@AiService
interface Summarizer {
    @Operation(
        prompt = "Summarize the incident in one short sentence.",
        model = "gpt-4o",
    )
    suspend fun summarize(input: String): String
}
```

### Notes

- runtime-retained
- targeted at class declarations
- intended for interfaces, not concrete implementation classes

## `@Operation`

Declares one AI-backed method on an `@AiService`.

### Example

```kotlin
@Operation(
    prompt = "Summarize the incident",
    model = "gpt-4o",
    provider = "openai",
    tools = ["lookup"],
    maxRetries = 3,
    providerRetries = 2,
    timeoutMillis = 15_000,
    cacheable = true,
    cacheTtlMillis = 60_000,
)
```

### Fields

#### `prompt`

Base user prompt for the operation.

Use it to describe the job, not transport details.

#### `model`

Logical model name requested by the operation.

This is resolved through TramAI's provider registry unless `provider` is set explicitly.

#### `provider`

Optional explicit provider name.

Use it when you want the method to bypass normal model-to-provider resolution and always go to a specific provider.

#### `tools`

Optional list of tool names that the operation may call.

Use names that match the registered tool definitions. Tool access is explicit per operation.

#### `maxRetries`

Maximum number of structured-output retries after parse or validation failure.

Default: `2`

This is relevant mainly for methods returning structured types rather than `String`.

#### `providerRetries`

Maximum number of provider retries after transient provider/API failures.

Default: `3`

This is different from structured-output retry. Provider retry handles transient transport or API failure paths.

#### `timeoutMillis`

Maximum duration for a single provider attempt, in milliseconds.

Default: `30000`

#### `cacheable`

Whether successful non-streaming responses may be cached by the engine.

Default: `false`

#### `cacheTtlMillis`

Cache TTL, in milliseconds, when `cacheable = true`.

Default: `60000`

### Current Behavior

- operation metadata affects prompt rendering, routing, retries, timeout handling, and optional caching
- streaming is not enabled by an annotation flag; it is inferred from the return type
- tool calling is enabled by `tools = [...]`, not by a separate operation annotation

## `@SystemPrompt`

Applies a service-wide system prompt to all operations on the interface.

### Example

```kotlin
@AiService
@SystemPrompt("You are a precise billing assistant.")
interface BillingAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a short status.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoice: String): String
}
```

### Use it when

- every method on the service should share the same role or behavioral framing

### Avoid it when

- methods on the same service need very different system behavior

In that case, split the service into narrower interfaces.

## `@AiTool`

Marks a method for discovery as a portable TramAI tool.

This is mainly used by framework adapters such as Spring's tool scanner.

### Example

```kotlin
@Component
class VendorTools {
    @AiTool(
        name = "vendor_lookup",
        description = "Looks up vendor reliability and payment terms.",
        idempotent = true,
    )
    fun lookupVendor(input: VendorLookupInput): VendorDetails = TODO()
}
```

### Fields

#### `name`

Explicit tool name.

Default: empty string, which means adapter-specific fallback behavior may use the method name.

#### `description`

Human-readable description exposed to the model.

This should explain what the tool does, not how it is implemented.

#### `idempotent`

Whether repeating execution with the same logical input is safe. This describes repetition safety, not whether TramAI should retry the tool after a failure.

Default: `false`

#### `sideEffectLevel`

Side-effect classification for the tool.

Default: `UNKNOWN`

Use it to describe whether the tool reads data, writes data, or has stronger external effects.

#### `permission`

Permission required to expose and execute the tool under secure policy profiles.

Default: empty string.

Empty preserves the legacy annotation contract: no `ToolSecurityMetadata` is produced and the standard runtime exposes the tool as before. Secure profiles such as sovereign TramAI reject tools without security metadata (`tool-metadata-missing`). Setting a non-empty permission makes the annotation produce strict `ToolSecurityMetadata` using `risk`, `approval`, `managedNetworkEgress`, and `audit`.

#### `risk`

Risk classification used by secure policy profiles when `permission` is set.

Default: `RiskLevel.HIGH`

#### `approval`

Approval mode used by secure policy profiles when `permission` is set.

Default: `ApprovalMode.HUMAN_REQUIRED`

#### `managedNetworkEgress`

Managed network-egress metadata for TramAI-managed destinations when `permission` is set.

Default: `ManagedNetworkEgress.DENY`

This does not sandbox arbitrary networking performed inside the annotated method. Infrastructure controls remain required for code paths that bypass TramAI-managed transports.

#### `audit`

Audit detail metadata requested for governed execution when `permission` is set.

Default: `AuditDetail.FULL`

The governance defaults are deliberately conservative (high risk, human approval, denied managed egress, full audit metadata) so that opting into governance never silently weakens the declared policy posture.

## Structured Output Property Annotations

These annotations affect structured schema generation and validation for non-`String` return types.

## `@AiDescription`

Adds a human-readable description to a structured output property.

### Example

```kotlin
data class SpendAnalysis(
    @property:AiDescription("Total spend in USD")
    val totalSpend: Double,
)
```

Use it when the field meaning is not obvious from the property name alone.

## `@AiRange`

Constrains a numeric structured output property to a closed range.

### Example

```kotlin
data class Score(
    @property:AiRange(min = 0.0, max = 1.0)
    val confidence: Double,
)
```

Use it when the value drives decisions and must stay in a known numeric range.

## `@AiMinItems`

Declares the minimum allowed size for a collection property.

### Example

```kotlin
data class RecommendationSet(
    @property:AiMinItems(1)
    val actions: List<String>,
)
```

Use it when an empty collection would be semantically invalid.

## Current Boundaries

TramAI does not currently use dedicated annotations for:

- streaming selection
- provider-native structured-output toggles
- memory or conversation state
- framework-specific wiring choices

Streaming is selected through the return type, for example `Flow<StreamChunk>`.

## Related Pages

- [Getting Started](../guides/getting-started.md)
- [Structured Output](../guides/structured-output.md)
- [Tool Calling](../guides/tool-calling.md)
- [Configuration Reference](./configuration.md)
