# Structured Output Contract Lifecycle

This document describes how TramAI generates, validates, and evolves structured output contracts. It is the authoritative reference for the schema and validation behaviour of `JacksonStructuredOutputHandler`.

## Type Discovery

Output types are discovered through language-specific reflection paths:

| Output type | Discovery mechanism | Validation |
|---|---|---|
| Kotlin `data class` or `class` | `KClass.memberProperties` (Kotlin reflection) | Kotlin `validateObject()` — nullability, `@AiRange`, `@AiMinItems`, recursive |
| Conventional JavaBean | Jackson `deserializationConfig.introspect()` | JavaBean `validateJavaValue()` — required-by-default, `@AiRange`, `@AiMinItems`, recursive, pre-deserialization shape check |
| Java record | Unsupported/unproven | — |
| Constructor-only immutable Java DTO | Unsupported/unproven | — |

### Kotlin path

- Properties are discovered via `KClass.memberProperties`, filtered to `PUBLIC` visibility.
- Nullable properties (`T?`) are **not** listed as required.
- Annotations: `@AiDescription`, `@AiRange`, `@AiMinItems` on properties.
- Recursive and nested Kotlin types are supported.

### JavaBean path

- Properties are discovered via Jackson's `deserializationConfig.introspect()`.
- Only properties that are **both writable (setter/writable field) and readable (getter/readable field)** are included. Getter-only calculated properties and setter-only write-only properties are excluded.
- **Every discovered property is required** (conservative fail-closed rule). Required presence is checked **before deserialization** by validating the raw JSON tree, because Java primitive fields (int, double, boolean) can never be null after Jackson deserialization and therefore cannot be detected post-hoc.
- Supported annotations and their lookup order:
  1. Backing field (`@Target(FIELD)`)
  2. Setter parameter (`@Target(VALUE_PARAMETER)`)
  3. Constructor parameter
  4. Getter method
  5. Setter method
- Collection support: `java.util.Collection` (including `ArrayList`, `Set`, etc.) — recognised via `isAssignableFrom`. `Iterable` (non-Collection) is not supported.
- Recursive JavaBean types (A → List<A>) fail with a controlled error at schema-generation time. Sibling properties of the same type both receive complete schemas.
- Maps (`java.util.Map` and subclasses) are unsupported.
- Java nullability annotations (`@Nullable`, `@NonNull`, etc.) are **not** interpreted.

## Schema Generation

Both paths produce the same JSON Schema shape:

```json
{
  "type": "object",
  "properties": {
    "propertyName": { "type": "string" }
  },
  "required": ["propertyName"],
  "additionalProperties": false
}
```

Property ordering is deterministic and alphabetical for both paths.

## Validation

### Pre-deserialization shape validation

Before Jackson deserializes a response, the raw JSON tree is validated against the target type using `validateJsonShape()` / `validateJavaJsonShape()`. This catches:

- Missing required keys (critical for Java primitives that cannot be null)
- Null required values
- Missing nested required keys inside collections and nested beans

The shape walker mirrors `schemaForType()` / `schemaForJavaType()`:

| Type | Shape check |
|---|---|
| Scalar (`String`, `int`, `boolean`, etc.) | No shape constraints |
| `List<T>` | Node must be an array; items recursively checked with `T` |
| Kotlin object | Public properties recursively checked |
| JavaBean | Every required key must exist and not be null; properties recursively checked |

### Post-deserialization validation

After deserialization, `validateValue()` dispatches to the language-specific validator:

- **Kotlin**: `validateObject()` checks nullability, `@AiRange`, `@AiMinItems`, and recurses using `KType`.
- **JavaBean**: `validateJavaValue()` is a unified recursive validator that mirrors `schemaForJavaType()`. It handles:
  - Null checks (required properties fail)
  - `@AiRange` bounds on numeric values
  - `@AiMinItems` minimum collection size
  - Collections: every item recursively validated, null items rejected
  - Nested JavaBeans: every property recursively validated
  - Maps: rejected as unsupported

## Contract Evolution

Adding, removing, or renaming fields in a DTO changes the generated schema. This is an intentional property of TramAI's structured output design.
