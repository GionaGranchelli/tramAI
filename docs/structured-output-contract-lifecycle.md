# Structured Output Contract Lifecycle

> **Status:** Documentation for the current structured-output contract lifecycle.
> **Phase:** Phase 2 / Epic 2 of the [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md).
> **Depends on:** [Workflow API Stability Boundary](workflow-api-stability-boundary.md) for stability levels.
> **Also see:** [Workflow Lifecycle Model](workflow-lifecycle-model.md) (Stage 5 — Structured Output / Repair).

---

## Purpose

This document explains how TramAI moves from a typed Kotlin or Java return type declaration to a structured output contract, validates provider output, and handles repair and failure paths.

It is based on the current implementation at the time of writing (2026-07). Where the code does not clearly prove a behavior, this document says so explicitly.

---

## Lifecycle Overview

```
Kotlin/Java return type
       │
       ▼
Contract / Schema Generation
       │
       ▼
Validator Annotations
       │
       ▼
Provider Output Capture
       │
       ▼
Parsing / Deserialization
       │
       ├── Parse failure → Repair Feedback → Retry → (back to Provider or Exhaustion)
       │
       ▼
Validation
       │
       ├── Validation failure → Repair Feedback → Retry → (back to Provider or Exhaustion)
       │
       ▼
Typed Result or Failure
```

---

## Stage 1 — Return Type Discovery

### What happens

TramAI determines the expected return type when a service interface method is processed during `TramaiEngine.create()`.

The return type is resolved from the method's Kotlin reflection data:
- For Kotlin services, `kotlinFunction.returnType` provides the `KType` with full generic information.
- The resolved `KType` is stored in `OperationDefinition.returnType` as `KType?`.
- If Kotlin reflection metadata is unavailable (Java-only proxy or missing `kotlin-reflect`), `returnType` is `null` and structured output operations will fail at invocation time with a clear error.

The return type participates in:
1. **Contract generation** — `handler.createContract(returnType)` builds a JSON schema-like structure.
2. **Parsing** — The `KType` guides Jackson deserialization via `objectMapper.typeFactory.constructType(targetType.javaType)`.
3. **Validation** — The `KType.classifier` drives type-specific validation rules.

### Code-proven facts

| Fact | Source |
|------|--------|
| Return type is resolved via `kotlinFunction.returnType` | `OperationDefinition.create()` |
| Structured output requires `returnType != null` | `structuredContract()` requires non-null |
| `returnType` is stored on `OperationDefinition` at proxy creation time | `OperationDefinition.returnType: KType?` |
| Only non-String, non-Unit, non-streaming return types go through this path | `ReturnKind.STRUCTURED` dispatch |

### What is NOT proven by code

- Whether a manually-specified schema override (replacing the generated schema) is supported.
- Whether type erasure affects collection element validation in all JVM scenarios.

---

## Stage 2 — Contract / Schema Generation

### What happens

When the engine enters `executeStructured()`, it calls `operation.structuredContract(handler)`, which delegates to `handler.createContract(targetType)`.

The contract generation is **per invocation** — `createContract` is called each time `executeStructured` is entered, not cached at service creation time.

The Jackson-based handler (`JacksonStructuredOutputHandler`) generates a JSON schema-like structure by inspecting the `KType`:

| Input type | Schema output |
|------------|---------------|
| `String` | `{"type": "string"}` |
| `Int`, `Long`, `Short` | `{"type": "integer"}` |
| `Float`, `Double` | `{"type": "number"}` |
| `Boolean` | `{"type": "boolean"}` |
| `List<X>` | `{"type": "array", "items": <schema for X>}` |
| `Map<*, *>` | Unsupported — throws `IllegalStateException` |
| Data class / POJO | `{"type": "object", "properties": {...}, "required": [...], "additionalProperties": false}` |

Object properties are discovered via `KClass.memberProperties` filtered to `PUBLIC` visibility, sorted alphabetically by name. Non-nullable properties are added to the `required` array. Nullable types include `"nullable": true` in the schema.

The schema is serialized as a pretty-printed JSON string (`schemaJson`) and injected into the model prompt via `operation.initialMessages(arguments, contract.schemaJson)`.

### Internal details

| Detail | Status |
|--------|--------|
| Contract generated per invocation (not cached) | Code-proven — `createContract` called each `executeStructured` |
| Schema uses Jackson `ObjectMapper` with Kotlin module | Code-proven |
| Object property discovery uses `KClass.memberProperties` | Code-proven |
| Schema injected into model prompt | Code-proven — `initialMessages(..., schemaJson)` |
| `Map` types return an error at schema generation time | Code-proven |

### What is NOT proven by code

- Whether contracts are deterministic across JVM versions (property ordering).
- Whether contract changes between invocations (e.g., after return type modification) are reliably detected by tests.

---

## Stage 3 — Validator Annotation Contribution

### Currently supported annotations

#### `@AIRange`

| Attribute | Type | Description |
|-----------|------|-------------|
| `min` | `Double` | Inclusive lower bound |
| `max` | `Double` | Inclusive upper bound |

Target: numeric properties (`PROPERTY`, `FIELD`, `VALUE_PARAMETER`).

Contributes to schema:
```json
"minimum": 0.0,
"maximum": 1.0
```

Contributes to validation after parsing:
- Checks `propertyValue.toDouble()` is within `[min, max]`.
- Returns an error string like `"Property 'confidence' must be between 0.0 and 1.0"`.

#### `@AIMinItems`

| Attribute | Type | Description |
|-----------|------|-------------|
| `value` | `Int` | Minimum number of elements |

Target: collection properties (`PROPERTY`, `FIELD`, `VALUE_PARAMETER`).

Contributes to schema:
```json
"minItems": 1
```

Contributes to validation after parsing:
- Checks `(collectionValue as Collection<*>).size >= minItems.value`.
- Returns an error string like `"Property 'recommendations' must contain at least 1 items"`.

### Schema generation flow

```
property.findAnnotation<AiRange>()?.let {
    schema["minimum"] = it.min
    schema["maximum"] = it.max
}
property.findAnnotation<AiMinItems>()?.let {
    schema["minItems"] = it.value
}
property.findAnnotation<AiDescription>()?.let {
    schema["description"] = it.value
}
```

### Code-proven facts

| Fact | Source |
|------|--------|
| `@AIRange` adds `minimum`/`maximum` to property schema | `JacksonStructuredOutputHandler.propertySchema()` |
| `@AIMinItems` adds `minItems` to property schema | Same |
| `@AiDescription` adds `description` to property schema | Same |
| Validation checks `@AIRange` after parsing | `validateObject()` — checks each property |
| Validation checks `@AIMinItems` after parsing | Same |
| Non-nullable properties without `@AIRange`/`@AIMinItems` are still validated for null | `validateObject()` |

### What is NOT proven by code

- Custom domain validators. There is **no** extension point for user-defined validators beyond the built-in annotations.
- Multi-field or cross-field validation rules.

---

## Stage 4 — Provider Output Capture

### What happens

After policy evaluation and routing, the engine dispatches the request to the configured provider via `executeWithTools()`, which calls `callProviderWithFallbacks()`.

The raw provider response (as a `String`) is returned to the structured output handler for analysis.

### Code-proven facts

| Fact | Source |
|------|--------|
| Provider response is captured as raw text | `handler.analyze(rawResponse = result.response.content, ...)` |
| The response includes tool calls if present | Tool loop precedes structured analysis |

### What is NOT proven by code

- Whether DLP redaction is always applied before structured output analysis. The current implementation comments suggest this happens in the provider call layer, but this document does not treat that as a structured-output lifecycle guarantee.
- Whether very large provider responses are handled with streaming or truncation before structured analysis.

---

## Stage 5 — Parsing and Deserialization

### What happens

`handler.analyze(rawResponse, targetType)` attempts to convert the raw provider output into the expected typed value.

#### Step 1: JSON extraction

`extractJsonCandidate(rawResponse)` attempts to locate a JSON payload:

1. If the response starts with `` ``` ``, it assumes a markdown fenced code block and extracts the content between `` ``` `` and the closing `` ``` ``.
2. Otherwise, it finds the first `{` / `}` pair (JSON object) or `[` / `]` pair (JSON array).
3. If no JSON structure is found, it throws `IllegalArgumentException`.

#### Step 2: Deserialization

The extracted JSON is deserialized via Jackson's `ObjectMapper` with the Kotlin module:
```kotlin
val javaType = objectMapper.typeFactory.constructType(targetType.javaType)
objectMapper.readerFor(javaType).readValue<Any>(jsonCandidate)
```

### Parse failure paths

| Failure | Error message pattern | Feedback sent to model |
|---------|----------------------|----------------------|
| No JSON found | `"Could not find a JSON object or array in the model response"` | `"Your previous response did not contain valid JSON. Return only valid JSON that matches the requested schema."` |
| JSON present but won't deserialize | Varies (Jackson exception message) | `"Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only."` |

### Code-proven facts

| Fact | Source |
|------|--------|
| JSON extraction handles fenced code blocks | `extractJsonCandidate()` |
| JSON extraction falls back to raw `{`/`}` or `[`/`]` finding | Same |
| Parse failures return `StructuredOutputResult.Failure` | `analyze()` |
| Failure includes `errorSummary` and `feedbackMessage` | `StructuredOutputResult.Failure` |

### What is NOT proven by code

- Whether partial JSON (truncated output) is handled gracefully vs. causing unpredictable Jackson errors.
- Whether the JSON extraction handles nested objects correctly in all edge cases.

---

## Stage 6 — Validation

### What happens

After successful deserialization, `validateValue(value, targetType)` performs structural and annotation-based validation.

#### Validation rules

| Rule | Implementation |
|------|----------------|
| Null check | If `value == null` and `targetType` is non-nullable, returns `"Value must not be null"` |
| Primitive scalars | `String`, `Int`, `Long`, `Short`, `Float`, `Double`, `Boolean` — no validation beyond type check |
| List items | Recursively validates each element against the item `KType` |
| Objects | Calls `validateObject()` on the deserialized instance |

#### Object validation (`validateObject`)

For each public property (sorted alphabetically):

1. **Null check** — If property is null but return type is non-nullable, fails with `"Property 'name' must not be null"`.
2. **`@AIRange` check** — If present, verifies numeric value is within `[min, max]`.
3. **`@AIMinItems` check** — If present, verifies collection size meets minimum.
4. **Recursive validation** — Validates nested property values.

### Validation failure path

When validation fails, the handler returns `StructuredOutputResult.Failure` with:
- `errorSummary`: The validation error string (e.g., `"Property 'confidence' must be between 0.0 and 1.0"`).
- `feedbackMessage`: `"Your previous response failed validation: <error>. Return corrected JSON only."`

### Code-proven facts

| Fact | Source |
|------|--------|
| `@AIRange` is checked during validation | `validateObject()` |
| `@AIMinItems` is checked during validation | Same |
| Null checks apply to non-nullable properties | Same |
| Validation is recursive for nested objects | `validateValue` recursive call |
| Validation failure returns `StructuredOutputResult.Failure` | `analyze()` |
| Validation checks structural/annotation constraints only | No semantic validation exists |

### What is NOT proven by code

- Whether non-public properties (e.g., `internal`, `protected`) that bypass validation cause silent data loss.
- Whether Java records or POJOs with getter-only properties are fully validated.

---

## Stage 7 — Repair Feedback

### What happens

When `handler.analyze()` returns `StructuredOutputResult.Failure`, the engine enters the repair loop in `executeStructuredAttempt()`:

```kotlin
messages += Message(MessageRole.ASSISTANT, analysis.rawResponse)
messages += Message(MessageRole.USER, analysis.feedbackMessage)
```

This appends two messages to the conversation history:
1. The **failed assistant response** (raw) — so the model sees its own previous output.
2. A **user feedback message** — the `feedbackMessage` from the `StructuredOutputResult.Failure`, which contains actionable error information.

The feedback message content varies by failure type:

| Failure type | Feedback message example |
|-------------|------------------------|
| No JSON found | `"Your previous response did not contain valid JSON. Return only valid JSON that matches the requested schema."` |
| Deserialization failure | `"Your previous response contained JSON that could not be parsed into the requested output type. Return corrected JSON only."` |
| Validation failure | `"Your previous response failed validation: Property 'confidence' must be between 0.0 and 1.0. Return corrected JSON only."` |

The engine then re-invokes the provider with the extended message history, allowing the model to correct its output.

### Code-proven facts

| Fact | Source |
|------|--------|
| Repair feedback includes the failed raw response | `messages += Message(ASSISTANT, analysis.rawResponse)` |
| Repair feedback includes the `feedbackMessage` as a user message | `messages += Message(USER, analysis.feedbackMessage)` |
| Repair feedback includes validation error details (when applicable) | `feedbackMessage` embeds `errorSummary` |
| The model is re-invoked with the extended history | `executeStructuredAttempt` re-enters `executeWithTools` |

### What is NOT proven by code

- Whether the exact repair prompt shape and retry policy should be treated as stable API. Stage 8 documents the current retry behavior (3 attempts by default), but PR #169 should verify it with regression tests before treating it as a compatibility guarantee.
- Whether repair feedback can be customized or overridden by application code.

---

## Stage 8 — Retry Exhaustion

### What happens

The retry loop is controlled by `Operation.maxRetries`:

```kotlin
val maxAttempts = operation.operation.maxRetries + 1
repeat(maxAttempts) { attemptIndex ->
    val value = executeStructuredAttempt(...)
    if (value != null) return value
}
```

Default: `maxRetries = 2` → **3 total attempts** (initial + 2 retries).

When the last attempt (`attemptIndex == maxAttempts - 1`) also fails, `handleStructuredFailure` throws:

```kotlin
throw StructuredOutputException(
    message = "Structured output parsing failed after $maxAttempts attempt(s)",
    originalPrompt = operation.operation.prompt,
    lastRawResponse = analysis.rawResponse,
    validationError = analysis.errorSummary,
    attemptCount = maxAttempts,
)
```

### Code-proven facts

| Fact | Source |
|------|--------|
| `maxAttempts = maxRetries + 1` | `executeStructuredRetryLoop()` |
| Default `maxRetries` is `2` | `@Operation(maxRetries = 2)` |
| Exhaustion throws `StructuredOutputException` | `handleStructuredFailure()` |
| Exception includes prompt, last response, validation error, and attempt count | `StructuredOutputException` constructor |
| Observation is notified of parse failure | `result.observation.onStructuredParseFailure()` before throw |

---

## Stage 9 — Typed Result or Failure

### Possible outcomes

| Outcome | Meaning | Exception/Result type |
|---------|---------|----------------------|
| **Typed success** | Provider output parsed and validated into the declared type | Returns the typed value directly |
| **Parse failure (exhausted)** | All attempts failed to extract valid JSON | `StructuredOutputException` |
| **Validation failure (exhausted)** | All attempts produced valid JSON but failed annotation constraints | `StructuredOutputException` |
| **Repair success** | A retry attempt produced valid typed output | Returns the typed value directly |
| **Repair exhausted** | All attempts ended without valid typed output | `StructuredOutputException` |
| **Provider failure** | Provider returned an error or was unreachable before structured parsing | `ProviderException` or timeout exception |

### Code-proven facts

| Fact | Source |
|------|--------|
| Success returns the typed value directly from `executeStructuredAttempt` | `analysis.value` returned on `StructuredOutputResult.Success` |
| Terminal failure always throws `StructuredOutputException` | `handleStructuredFailure()` |
| `StructuredOutputException` contains diagnostic fields | `originalPrompt`, `lastRawResponse`, `validationError`, `attemptCount` |

---

## Stability Mapping

| Surface | Stability | Notes |
|---------|-----------|-------|
| Typed return values (`ReturnKind.STRUCTURED`) | Stable | Core workflow contract |
| `@AiService` + `@Operation` structured dispatch | Stable | Route to `executeStructured` for non-String return types |
| `@AIRange` | Stable | Schema and validation |
| `@AIMinItems` | Stable | Schema and validation |
| `@AiDescription` | Stable | Schema documentation |
| `StructuredOutputContract` | Public type | `data class` in `tramai-core` |
| `StructuredOutputResult` | Public type | `sealed interface` in `tramai-core` |
| `StructuredOutputHandler` | Public SPI | Interface in `tramai-core` |
| Jackson implementation | Internal | `JacksonStructuredOutputHandler` in `tramai-structured` |
| Contract/schema generation internals | Internal | `schemaForType`, `objectSchema`, `listSchema` |
| JSON extraction (`extractJsonCandidate`) | Internal | Private method in Jackson handler |
| Repair feedback shape | Internal | `feedbackMessage` constructed inside handler |
| `StructuredOutputException` | Public | Thrown on exhaustion |
| Custom validator extension point | Deferred | No API exists for user-defined validators beyond built-in. See [Structured Output Validator Extension Model](structured-output-validator-extension-model.md). PR #170 defines the design boundary only — no runtime implementation exists yet. |
| Contract versioning/snapshot guarantees | Deferred | Not implemented |
| Full JSON Schema compatibility claim | Forbidden | Schema is JSON-like but not certified against the JSON Schema specification |

---

## Open Questions for PRs #169–#170

The following behaviors were verified with tests in PR #169. Custom validator design questions are now tracked in the [Structured Output Validator Extension Model](structured-output-validator-extension-model.md) introduced by PR #170 — the document defines the design boundary and records the remaining implementation decisions without resolving them.

| Question | Current status |
|----------|----------------|
| Are contracts rebuilt or cached across invocations? | Code shows per-invocation `createContract`, but no test proves this is the intentional contract lifecycle. |
| Does adding a new field to a return type change the generated contract? | Schema is built from `KClass.memberProperties` (dynamic), but no test proves contract evolution. |
| Does `@AIRange` affect both schema and validation? | Code shows both — test coverage exists but may not cover edge cases. |
| Does `@AIMinItems` affect both schema and validation? | Same as above. |
| Does repair feedback include actionable parse/validation errors? | `feedbackMessage` includes `errorSummary` — test coverage exists but repair integration (engine-level retry) is not fully tested. |
| Does Java-facing structured output behavior match Kotlin-facing behavior? | The handler uses `targetType.javaType` — Java interop coverage is limited. |
| What is the exact repair prompt shape? | Currently internal — should be documented or tested if it becomes stable. |
| Are there edge cases in JSON extraction (nested braces, multiple JSON objects)? | Extraction is greedy (first `{` to last `}`) — edge cases are not tested. |

---

## Allowed Claims

It is allowed to say:

- TramAI documents a structured-output contract lifecycle.
- TramAI derives structured-output expectations from typed Kotlin/Java declarations.
- `@AIRange` and `@AIMinItems` participate in structured-output schema generation and validation.
- Structured output can produce typed success, parse failure, validation failure, repair success, or repair exhaustion.
- Some lifecycle details are internal and will be verified in follow-up tests (PR #169).

## Forbidden Claims

It is not allowed to say:

- TramAI guarantees model correctness or factual truth.
- TramAI guarantees full JSON Schema compatibility unless proven by tests.
- TramAI guarantees all structured-output contracts are backward compatible.
- TramAI supports custom validators as a stable API (no extension point exists).
- TramAI proves legal or regulatory compliance.
- TramAI provides EU AI Act conformity certification.
- TramAI is production-certified.

---

## Acceptance Criteria

This document is complete when:

1. It explains the lifecycle from return type to typed result/failure.
2. It documents return type discovery.
3. It documents contract/schema generation based on current code.
4. It documents `@AIRange`.
5. It documents `@AIMinItems`.
6. It documents parsing/deserialization.
7. It documents validation.
8. It documents repair feedback behavior only as far as code proves it.
9. It documents retry exhaustion/final failure.
10. It lists stable/preview/internal/deferred structured-output surfaces.
11. It lists open questions for PR #169.
12. It includes allowed and forbidden claims.
13. It cross-links from the workflow lifecycle model.
14. It updates the post-sovereignty roadmap.
15. `./gradlew check` passes.

---

## Related Documents

| Document | Purpose |
|----------|---------|
| [Structured Output Validator Extension Model](structured-output-validator-extension-model.md) | Future design boundary for custom validators (PR #170) |
| [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md) | Phase 2 defines the structured-output contract epic |
| [Workflow Lifecycle Model](workflow-lifecycle-model.md) | Stage 5 (Structured Output / Repair) maps to this lifecycle |
| [Workflow API Stability Boundary](workflow-api-stability-boundary.md) | Defines the stability level of structured-output surfaces |

---

*Part of Phase 2 / Epic 2 of the [Post-Sovereignty TramAI Roadmap](POST-SOVEREIGNTY-ROADMAP.md).*
