# Module: `tramai-structured`

> **One-liner:** Generates JSON schemas from Kotlin types, extracts and validates structured objects from LLM responses.
> **Module type:** `core`
> **Build coordinates:** `dev.tramai:tramai-structured:0.5.0`

---

## L1: Quick Start (30-second read)

### What

`tramai-structured` is the module that makes structured output a first-class capability in Tramai. It provides a single public class — `JacksonStructuredOutputHandler` — that:

- **Generates JSON Schema** from Kotlin data classes, enums, and nested types (leveraging Kotlin nullability, `@AiDescription`, `@AiRange`, and `@AiMinItems` annotations)
- **Extracts JSON** from model responses, handling markdown fences and arbitrary text preambles
- **Deserializes** the extracted JSON into the target Kotlin type via Jackson
- **Validates** the result against annotation-driven constraints (range, min items, nullability)
- **Returns** a typed `StructuredOutputResult.Success` or `StructuredOutputResult.Failure` with a feedback message the engine can feed into a retry

### Why

LLMs return unstructured text. When your application needs typed, validated data — a `SpendAnalysis` object, a list of `Recommendation` enums, a nested configuration tree — you need a reliable pipeline that:

1. Tells the model what shape to return (schema)
2. Extracts JSON from whatever the model actually emits (markdown fences, preamble text)
3. Parses it into your target type
4. Validates constraints (ranges, required fields, collection sizes)
5. Reports failures in a way the engine can use for automatic retries

Without this module, every consumer would reimplement JSON extraction, schema generation, and validation — inconsistently.

### When to use

- Your `@Operation` methods return types other than `String` — data classes, value classes, enums, lists, maps of scalars
- You need reliable typed output from models that occasionally emit malformed JSON
- You want annotation-driven validation (`@AiRange`, `@AiMinItems`) on structured output properties
- You want automatic retry feedback when the model's response fails to parse or validate

**You do not need** `tramai-structured` separately if you only return `String` from your `@Operation` methods — the engine handles that directly.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
implementation("dev.tramai:tramai-structured:0.5.0")
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-structured</artifactId>
    <version>0.4.0</version>
</dependency>
```

The module pulls in:
- `tramai-core` (the `StructuredOutputHandler` SPI)
- `jackson-databind` + `jackson-module-kotlin` (JSON processing)
- `kotlin-reflect` (runtime type introspection)

### Where to go next

- [Structured Output Spec (SPEC-002)](../specs/spec-002-structured-output.md)
- [JacksonStructuredOutputHandler API](#L4-API-Reference)
- [Module Guide: Which modules do I need?](../module-guide.md)

---

## L2: Usage Guide (5-minute read)

### Quick usage

The `JacksonStructuredOutputHandler` is the single implementation of `StructuredOutputHandler`. It is automatically wired by `TramaiEngine` when `tramai-structured` is on the classpath. You typically do not instantiate it directly — but here is how it works end to end.

**1. Define a data class with annotations:**

```kotlin
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.annotations.AiMinItems

data class SpendAnalysis(
    @property:AiDescription("Total spend in USD, always positive")
    @property:AiRange(min = 0.0, max = 1000000.0)
    val totalSpend: Double,
    @property:AiDescription("Cost reduction recommendations, ordered by impact")
    @property:AiMinItems(1)
    val recommendations: List<String>,
    @property:AiDescription("Confidence score between 0.0 and 1.0")
    @property:AiRange(min = 0.0, max = 1.0)
    val confidence: Double,
    val nickname: String?,
)
```

**2. The handler generates a JSON Schema from this type:**

```json
{
  "type" : "object",
  "properties" : {
    "confidence" : {
      "type" : "number",
      "description" : "Confidence score between 0.0 and 1.0",
      "minimum" : 0.0,
      "maximum" : 1.0
    },
    "nickname" : {
      "type" : "string",
      "nullable" : true
    },
    "recommendations" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      },
      "minItems" : 1,
      "description" : "Cost reduction recommendations, ordered by impact"
    },
    "totalSpend" : {
      "type" : "number",
      "description" : "Total spend in USD, always positive",
      "minimum" : 0.0,
      "maximum" : 1000000.0
    }
  },
  "required" : ["totalSpend", "recommendations", "confidence"],
  "additionalProperties" : false
}
```

**3. The handler parses a model response (including markdown fences):**

```kotlin
val handler = JacksonStructuredOutputHandler()
val result = handler.analyze(
    rawResponse = """
        ```json
        {
          "totalSpend": 1200.5,
          "recommendations": ["right-size"],
          "confidence": 0.95,
          "nickname": null
        }
        ```
    """.trimIndent(),
    targetType = typeOf<SpendAnalysis>(),
)

when (result) {
    is StructuredOutputResult.Success -> {
        val analysis = result.value as SpendAnalysis
        println("Spend: ${analysis.totalSpend}")
    }
    is StructuredOutputResult.Failure -> {
        println("Failed: ${result.errorSummary}")
        println("Feedback for retry: ${result.feedbackMessage}")
    }
}
```

**4. Validation failures produce retry-friendly feedback:**

```kotlin
// If the model returns recommendations: [] (violates @AiMinItems(1))
// or confidence: 1.4 (violates @AiRange(0.0, 1.0))
val result = handler.analyze(rawResponse = /* ... */, targetType = typeOf<SpendAnalysis>())
// result is Failure with:
//   errorSummary: "Property 'recommendations' must contain at least 1 items"
//   feedbackMessage: "Your previous response failed validation: ... Return corrected JSON only."
```

The engine automatically feeds this feedback back to the model for a retry attempt.

### Advanced usage

#### Enums

Enums are serialized as string schemas. Define your enum:

```kotlin
enum class Grade {
    STANDARD, PREMIUM, ENTERPRISE
}

data class Subscription(
    val grade: Grade,
    val monthlyCost: Double,
)
```

The generated schema will include `grade` as a `"type": "string"` property, and Jackson will deserialize the enum value from the string in the JSON response.

#### Nested objects

Nested data classes produce nested JSON Schema objects:

```kotlin
data class Address(
    val city: String,
    val country: String,
)

data class Customer(
    val name: String,
    val address: Address,
    val tags: List<String>,
)
```

Generated schema:

```json
{
  "type" : "object",
  "properties" : {
    "address" : {
      "type" : "object",
      "properties" : {
        "city" : { "type" : "string" },
        "country" : { "type" : "string" }
      },
      "required" : ["city", "country"],
      "additionalProperties" : false
    },
    "name" : { "type" : "string" },
    "tags" : {
      "type" : "array",
      "items" : { "type" : "string" }
    }
  },
  "required" : ["name", "address", "tags"],
  "additionalProperties" : false
}
```

#### Nullable properties

Properties declared with `?` (e.g., `val nickname: String?`) are marked `"nullable": true` in the schema and are excluded from the `"required"` array. The parser accepts `null` values for these properties without error.

#### Lists of objects

```kotlin
data class LineItem(
    val sku: String,
    val quantity: Int,
)

data class Order(
    val orderId: String,
    val items: List<LineItem>,
)
```

The `items` property generates an `"array"` schema with `"items"` referencing the nested `LineItem` object schema.

### Expert usage

#### Custom Jackson ObjectMapper

The handler accepts a custom `ObjectMapper` in its constructor:

```kotlin
val mapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build()

val handler = JacksonStructuredOutputHandler(objectMapper = mapper)
```

This is useful when you need custom serialization configuration, mix-ins, or custom deserializers across all structured output handling.

#### Direct schema generation

```kotlin
val schemaJson: String = handler.generateSchema(typeOf<SpendAnalysis>())
// Returns the JSON Schema string without creating a full contract
```

#### Direct deserialization (without analysis pipeline)

```kotlin
// From a JsonNode
val node: JsonNode = mapper.readTree("""{"totalSpend": 100.0}""")
val analysis = handler.deserialize(node, typeOf<SpendAnalysis>()) as SpendAnalysis

// From a string
val analysis2 = handler.deserialize("""{"totalSpend": 100.0}""", typeOf<SpendAnalysis>()) as SpendAnalysis

// From any compatible value
val analysis3 = handler.deserialize(mapOf("totalSpend" to 100.0), typeOf<SpendAnalysis>()) as SpendAnalysis
```

#### Serialization for tool results

```kotlin
val jsonNode = handler.serialize(SpendAnalysis(100.0, listOf("fix"), 0.5, null))
// Returns a JsonNode tree
```

### Configuration reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `objectMapper` | `ObjectMapper` | `JsonMapper` with `kotlinModule()` | Jackson mapper used for schema generation, deserialization, and serialization |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

The module follows a **parse-validate-retry** architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    tramai-structured                          │
│                                                              │
│  Schema Generation ──► Response Extraction ──► Deserialize  │
│       │                       │                     │        │
│       │                       │                     ▼        │
│       │                       │              Validate         │
│       │                       │              /      \        │
│       │                       │           OK        FAIL     │
│       │                       │            │          │      │
│       ▼                       ▼            ▼          ▼      │
│  StructuredOutputContract  StructuredOutputResult            │
│                                                              │
│  ↑ SPI (tramai-core)                     ↓ Consumed by       │
│  JacksonStructuredOutputHandler          tramai-engine       │
└─────────────────────────────────────────────────────────────┘
```

Three core principles:

1. **Schema-first.** The model should know exactly what shape to return before it generates a response. The schema is injected into the system prompt by the engine.
2. **Resilient extraction.** Models often wrap JSON in markdown fences (` ```json `) or add explanatory text. The handler strips all of that before parsing.
3. **Rich failure context.** Every failure carries both a human-readable error summary (for logging/observability) and a model-targeted feedback message (for retry). The engine never needs to understand JSON to decide whether to retry.

### Module boundary

`tramai-structured` owns **one file and one public class** — `JacksonStructuredOutputHandler`. It implements the `StructuredOutputHandler` SPI defined in `tramai-core`:

| SPI method | Purpose |
|-----------|---------|
| `createContract(targetType)` | Returns a `StructuredOutputContract` containing the target type and its JSON Schema |
| `analyze(rawResponse, targetType)` | Full parse-validate pipeline → `StructuredOutputResult.Success` or `.Failure` |
| `generateSchema(type)` | Pure schema generation (no extraction or validation) |
| `deserialize(input, targetType)` | Low-level typed deserialization from `JsonNode`, `String`, or any compatible value |
| `serialize(value)` | Converts an object to a `JsonNode` tree |

What `tramai-structured` explicitly **does not** own:
- **Retry policy** — owned by `tramai-engine`. The engine inspects the `StructuredOutputResult` and decides how many retries, with what backoff.
- **Tool execution** — owned by `tramai-engine` and provider modules.
- **Schema caching** — the engine caches contracts per operation type to avoid regenerating schema on every call.

### Dependency graph

```
tramai-structured
  ├── tramai-core (api)          ← StructuredOutputHandler SPI, annotations
  ├── jackson-databind           ← JSON parsing, schema generation
  ├── jackson-module-kotlin      ← Kotlin data class support in Jackson
  └── kotlin-reflect             ← Runtime type introspection (KType, KClass)
```

`tramai-structured` is consumed by:
- `tramai-engine` — invokes `createContract()` and `analyze()` during operation execution
- `tramai-standalone` — transitively via engine
- `tramai-spring` — transitively via engine
- `tramai-mcp` — for structured output in MCP tool responses

### Inner mechanics

#### Schema generation flow

```
createContract(targetType: KType)
  │
  └─► schemaForType(targetType)
        │
        ├── String ──────────────────────────────────► {"type": "string"}
        ├── Int / Long / Short ──────────────────────► {"type": "integer"}
        ├── Float / Double ──────────────────────────► {"type": "number"}
        ├── Boolean ─────────────────────────────────► {"type": "boolean"}
        ├── List<E> ─────────────────────────────────► {"type": "array", "items": schemaForType(E)}
        ├── Map ─────────────────────────────────────► throws IllegalStateException (unsupported)
        └── data class / enum ───────────────────────► objectSchema()
              │
              ├── For each public property:
              │     ├── schemaForType(property.returnType)
              │     ├── @AiDescription → "description" field
              │     ├── @AiRange       → "minimum" / "maximum" fields
              │     ├── @AiMinItems    → "minItems" field
              │     └── nullable?      → "nullable": true, excluded from "required"
              │
              └── Returns:
                    {"type":"object","properties":{...},"required":[...],
                     "additionalProperties":false}
```

Key behaviors:
- **`Map` types are rejected** at schema generation time — they have no well-defined property shape for the model to target.
- **Nullable properties** (`Type?`) are excluded from `"required"` and emit `"nullable": true`.
- **Annotations** enrich the property schema with descriptions and constraints.
- **Properties are sorted alphabetically** for deterministic schema output.
- **`additionalProperties` is always `false`** to prevent the model from inventing fields.

#### Response extraction flow

```
analyze(rawResponse: String, targetType: KType)
  │
  ├── 1. extractJsonCandidate(rawResponse)
  │      ├── Strip markdown fences (```json ... ```)
  │      ├── Find first '{' ... '}'  (object)
  │      └── Fallback to first '[' ... ']' (array)
  │      └── Fail → StructuredOutputResult.Failure("Could not find JSON")
  │
  ├── 2. Deserialize with Jackson
  │      └── Fail → StructuredOutputResult.Failure("Could not deserialize")
  │
  └── 3. validateValue(parsed, targetType)
         ├── Null check against nullable
         ├── Recurse into lists (validate each item)
         ├── For objects: validate each property
         │     ├── Nullability enforcement
         │     ├── @AiRange     → numeric bounds check
         │     └── @AiMinItems  → collection size check
         └── Fail → StructuredOutputResult.Failure with constraint detail
```

The extraction step (`extractJsonCandidate`) is deliberately lenient:
1. If the response starts with `` ``` ``, it assumes a fenced code block and extracts the content between fences.
2. Otherwise, it finds the outermost `{` ... `}` pair (JSON object).
3. Failing that, it finds the outermost `[` ... `]` pair (JSON array).
4. If none found, it throws `IllegalArgumentException` which is caught and converted to a `Failure`.

#### Validation engine

The `validateValue` function recursively walks the deserialized object tree and checks every property against its annotations:

```kotlin
// Pseudocode for the validation logic
fun validateValue(value, targetType):
    if null → fail unless targetType is nullable
    if scalar (String, Int, etc.) → pass
    if List → validate each item
    if object → for each property:
        check not null if non-nullable
        check @AiRange bounds
        check @AiMinItems size
        recurse into nested objects
```

Validation happens **after** deserialization and is the final gate before returning `Success`. This means a response can be valid JSON but still fail validation (e.g., `confidence: 1.4` is valid JSON but violates `@AiRange(max = 1.0)`).

### Error model

The module defines **no custom exceptions**. All structured output errors flow through `StructuredOutputResult`:

| Failure mode | Trigger | `errorSummary` example |
|---|---|---|
| **Extraction failure** | No JSON object/array found | "Could not find a JSON object or array in the model response" |
| **Deserialization failure** | Malformed JSON or type mismatch | "Cannot deserialize value of type `double` from String" |
| **Null constraint violation** | Required field is null | "Property 'totalSpend' must not be null" |
| **Range violation** | Value outside `@AiRange` bounds | "Property 'confidence' must be between 0.0 and 1.0" |
| **Min items violation** | Collection smaller than `@AiMinItems` | "Property 'recommendations' must contain at least 1 items" |

Each `Failure` also carries:
- `rawResponse` — the original model output (for logging)
- `feedbackMessage` — a model-targeted message like `"Your previous response failed validation: ... Return corrected JSON only."`

**At the engine level**, when retries are exhausted, the engine throws a `StructuredOutputException` (defined in `tramai-core`) that bundles the original prompt, last raw response, failure detail, and attempt count.

### Testing strategy

The test file (`JacksonStructuredOutputHandlerTest.kt`) covers:

| Test | What it proves |
|------|---------------|
| `generates schema with nullability and custom annotations` | Schema includes `@AiDescription`, `@AiRange`, `@AiMinItems`, nullable properties |
| `parses fenced json successfully` | Markdown fence extraction works end to end |
| `returns validation failure when annotated constraints are violated` | `@AiMinItems` and `@AiRange` violations produce `Failure` |
| `returns parse failure when no json payload can be extracted` | Non-JSON responses are cleanly caught |
| `rejects unsupported root types during schema generation` | `Map<String, String>` is rejected at schema time |

The test uses `typeOf<SpendAnalysis>()` (Kotlin's reified type inference) to pass generic types to the handler, which is the same mechanism the engine uses.

---

## L4: API Reference

### `dev.tramai.structured.JacksonStructuredOutputHandler`

```kotlin
class JacksonStructuredOutputHandler(
    objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()
) : StructuredOutputHandler
```

The sole implementation of the `StructuredOutputHandler` SPI. Thread-safe once constructed (Jackson's `ObjectMapper` is thread-safe).

#### Constructor

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `objectMapper` | `ObjectMapper` | `JsonMapper` + `kotlinModule()` | Jackson mapper for all schema/parse/serialize operations |

#### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `createContract(targetType: KType)` | `StructuredOutputContract` | Generates schema + wraps in contract for prompt injection |
| `analyze(rawResponse: String, targetType: KType)` | `StructuredOutputResult` | Full pipeline: extract → parse → validate |
| `generateSchema(type: KType)` | `String` | Pure JSON Schema generation |
| `deserialize(input: Any, targetType: KType)` | `Any` | Low-level deserialization (from `JsonNode`, `String`, or any value) |
| `serialize(value: Any)` | `Any` (actually `JsonNode`) | Object → JsonNode tree |

### `dev.tramai.core.structured.StructuredOutputHandler` (SPI)

```kotlin
interface StructuredOutputHandler {
    fun createContract(targetType: KType): StructuredOutputContract
    fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult
    fun generateSchema(type: KType): String
    fun deserialize(input: Any, targetType: KType): Any
    fun serialize(value: Any): Any
}
```

### `dev.tramai.core.structured.StructuredOutputResult`

```kotlin
sealed interface StructuredOutputResult {
    data class Success(
        val value: Any,
        val rawResponse: String,
    ) : StructuredOutputResult

    data class Failure(
        val rawResponse: String,
        val errorSummary: String,
        val feedbackMessage: String,
    ) : StructuredOutputResult
}
```

### `dev.tramai.core.structured.StructuredOutputContract`

```kotlin
data class StructuredOutputContract(
    val targetType: KType,
    val schemaJson: String,
)
```

### Related annotations (in `tramai-core`)

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@AiDescription(value: String)` | Property, field, parameter | Describes the property meaning to the model |
| `@AiRange(min: Double, max: Double)` | Property, field, parameter | Constrains a numeric property to a closed range |
| `@AiMinItems(value: Int)` | Property, field, parameter | Enforces minimum collection size |
