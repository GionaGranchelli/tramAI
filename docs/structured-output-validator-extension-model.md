# Structured Output Validator Extension Model

> **Status:** Design boundary — no runtime implementation yet.
> **Phase:** Phase 2 / Epic 2 of the [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md).
> **Depends on:** [Structured Output Contract Lifecycle](structured-output-contract-lifecycle.md) (Stage 3 — Validator Annotation Contribution).
> **PR:** [#170](https://github.com/GionaGranchelli/tramAI/pull/170).

---

## Purpose

Define how TramAI should eventually support custom structured-output validators while preserving typed contracts, repair feedback, and API stability.

This document defines the **design boundary** for a future extension point. No runtime implementation exists today. The goal is to align on _what shape a future custom validator API should have_ before committing to implementation.

---

## Current State

Today TramAI supports these built-in validator annotations:

| Annotation | Target | Schema contribution | Runtime validation |
|------------|--------|---------------------|--------------------|
| `@AIRange` | Numeric properties | `minimum` / `maximum` | Checks value is within `[min, max]` |
| `@AIMinItems` | Collection properties | `minItems` | Checks collection size meets minimum |

PR [#169](https://github.com/GionaGranchelli/tramAI/pull/169) added tests proving that both annotations contribute to schema generation _and_ runtime validation.

**There is no stable custom validator extension point today.** Application code cannot add new annotations or SPI-based validators that participate in the contract lifecycle. The only way to extend validation is to modify TramAI's internal code or bypass structured output entirely.

---

## Design Goals

A future custom validator extension point should support:

| Goal | Meaning |
|------|---------|
| **Field-level validators** | Validate a single property (e.g., regex, length, range). |
| **Type-level validators** | Validate a whole DTO/object after all properties are populated. |
| **Cross-field validators** | Validate relationships between fields (e.g., `startDate < endDate`). |
| **Schema contribution** | Add JSON Schema-like hints where possible. |
| **Runtime validation** | Validate parsed typed values against custom rules. |
| **Repair feedback** | Produce actionable model-facing error messages. |
| **Java compatibility** | Usable from Java, not Kotlin-only. |
| **Deterministic tests** | Validator behavior testable without model calls. |
| **Clear stability** | Preview before Stable, with a defined promotion path. |

---

## Non-Goals

The following are explicitly **not part of this document** (and not part of PR #170):

- Custom validator implementation (code).
- New annotations or new annotation types.
- New SPI or public API surface.
- Runtime behavior changes.
- Breakage of the existing `@AIRange` / `@AIMinItems` behavior.
- Schema backward-compatibility guarantees.
- Model calls or provider integration.
- Benchmark execution or performance guarantees.
- Compliance, production-readiness, or certification claims.

PR #170 is a design-boundary document only.

---

## Validator Types

A future extension model should cover these validator categories:

### 1. Annotation-based validators

Example shape (illustrative — not implemented):

```kotlin
@Target(AnnotationTarget.PROPERTY)
annotation class AIRegex(
    val pattern: String,
    val message: String = "Value does not match required pattern",
)
```

Usage:

```kotlin
data class ContactInfo(
    @AIRegex(pattern = "^[^@]+@[^@]+$")
    val email: String,
)
```

**Strengths:**

- Simple, declarative, readable in DTO definitions.
- Easy to map to schema hints (`pattern` → `"pattern": "..."`).
- Works well for field-level constraints.
- Familiar pattern for JVM developers (c.f. Jakarta Validation, `@Pattern`).

**Weaknesses:**

- Limited for cross-field rules (e.g., `startDate < endDate`).
- Annotation explosion risk — every new constraint needs a new annotation type.
- Harder to express complex domain logic.

### 2. SPI-based validators

Example shape (illustrative — not implemented):

```kotlin
interface StructuredOutputValidator<T> {
    fun validate(value: T): StructuredValidationResult
}
```

Possible result shape:

```kotlin
sealed interface StructuredValidationResult {
    data object Valid : StructuredValidationResult
    data class Invalid(
        val path: String,
        val message: String,
        val repairHint: String,
    ) : StructuredValidationResult
}
```

Usage:

```kotlin
class EmailDomainValidator : StructuredOutputValidator<ContactInfo> {
    override fun validate(value: ContactInfo): StructuredValidationResult =
        if (value.email.endsWith("@example.com")) {
            StructuredValidationResult.Valid
        } else {
            StructuredValidationResult.Invalid(
                path = "email",
                message = "Email must be a company address",
                repairHint = "Use an email ending in @example.com",
            )
        }
}
```

**Strengths:**

- Supports domain validation with arbitrary logic.
- Supports cross-field validation.
- Testable without model calls.
- Can produce structured repair feedback.
- Single interface covers many patterns.

**Weaknesses:**

- Harder to map to schema (domain rules may not have a schema representation).
- Needs a registration/lifecycle design (global? per-service? per-operation?).
- More API stability burden — the interface shape must be right before stabilization.

### 3. Hybrid model (recommended direction)

Use **annotation-based validators** for simple field/schema constraints.
Use **SPI-based validators** for domain/cross-field rules.

This lets TramAI keep easy validators simple while supporting complex enterprise or domain validation when needed.

---

## Schema Contribution Model

Not every validator can or should contribute to schema:

| Validator type | Schema contribution | Example |
|----------------|-------------------|---------|
| `@AIRange` | Yes — `minimum` / `maximum` | Strong signal for model output |
| `@AIMinItems` | Yes — `minItems` | Strong signal for model output |
| `@AIRegex` (future) | Yes — `pattern` | Strong signal for model output |
| `@AISize` (future) | Yes — `minLength` / `maxLength` | Strong signal for model output |
| Domain SPI validator | Often no | Domain rules may not have JSON Schema representations |
| Cross-field validator | Usually no | Relationships between fields cannot be expressed by the current property-level schema contribution model |

Rules:

- If a validator can express its constraint in JSON Schema, it **should** contribute to schema. Schema hints improve model output quality.
- If a validator cannot contribute to schema, it **must still** produce repair-friendly feedback at runtime.
- A validator that contributes to schema must be **reconcilable** with runtime validation: the schema hint and the runtime check must agree.

---

## Runtime Validation Model

Runtime validation runs after JSON deserialization, before the typed result is returned to the caller.

### Execution order (proposed)

1. **Built-in nullability checks** (always run, no extension needed).
2. **Built-in annotation validators** (`@AIRange`, `@AIMinItems`).
3. **Custom annotation validators** (if registered, in registration order).
4. **Custom SPI validators** (registered, in registration order).
5. **Cross-field validators** (after all field-level checks pass).

### Structure

Each validator returns one of:

```kotlin
sealed interface StructuredValidationResult {
    data object Valid : StructuredValidationResult
    data class Invalid(
        val path: String,
        val message: String,
        val repairHint: String,
    ) : StructuredValidationResult
}
```

Multiple simultaneous failures should be collected rather than failing fast on the first error. This gives the repair loop a complete picture of what was wrong.

---

## Repair Feedback Model

Every future custom validator must produce repair-friendly output:

| Field | Purpose | Example |
|-------|---------|---------|
| `path` | Where the error occurred | `items[0].price` |
| `message` | Human-readable error | "Price must be a positive value" |
| `repairHint` | Model-facing correction instruction | "Ensure price is a positive number" |

Do **not** make validators throw random exceptions as the normal failure path. They should return structured failures that the repair loop can aggregate and present to the model.

**Safety rule:** Validators should not leak sensitive raw model output into repair messages. Repair hints should be minimal and safe.

---

## Stability Boundary

| Surface | Proposed stability | Notes |
|---------|-------------------|-------|
| `@AIRange` | Stable | Existing — tests prove schema + validation |
| `@AIMinItems` | Stable | Existing — tests prove schema + validation |
| `@AiDescription` | Stable | Existing — schema description only |
| Custom annotation validators | Preview | Once implemented — Preview until tested |
| `StructuredOutputValidator` SPI | Preview | Once implemented — Preview until API boundary tests exist |
| Schema contribution SPI | Deferred | Annotations handle schema — SPI-level schema contribution is deferred |
| Cross-field / domain validators | Deferred or Preview design only | Design documented here, implementation deferred |
| External / network validators | Deferred | Validators making network calls need tool/policy governance |
| Validator-to-policy integration | Deferred | Policy enforcement is a separate concern |
| Validator failure evidence export | Deferred | Deferred to Phase 5 (Runtime Evidence Export) |

---

## Security and Safety Considerations

1. **Validators must not mutate workflow state.** Validation is read-only — a validator should not trigger side effects.

2. **Validators must not execute tools or make provider calls.** If a validator needs external data, that is a separate (deferred) category requiring policy governance.

3. **Validators must not leak sensitive data.** Repair hints and error messages should not contain raw PII, secrets, or internal model semantics.

4. **Validators should be deterministic by default.** The default validator contract should not require network access, current time, random state, or mutable global state. Non-deterministic validators are a separate future category.

5. **Validator ordering should be explicit and testable.** If ordering matters, it must be configurable and documented.

---

## Java Compatibility

The future validator extension must not be Kotlin-only. Key requirements:

- `StructuredOutputValidator` interface must use Java-friendly types (no Kotlin-specific generics, no inline classes, no default parameter values that cannot be called from Java).
- Registration must be possible through explicit constructors or factory methods, not only Kotlin object expressions.
- Annotation-based validators must respect Java retention and target policies.

---

## Open Questions

The following questions are intentionally left unresolved. They should be answered before or during implementation:

| Question | Context |
|----------|---------|
| Should custom validators be registered globally, per service, per operation, or per type? | Affects API surface and lifecycle complexity. |
| Should validators be discovered from annotations, explicit registration, or both? | Annotation discovery is convenient but less explicit. |
| Should schema contribution and runtime validation be separate interfaces? | Separation gives flexibility but more surface. |
| Should validator failures be included in audit/evidence export? | Relevant to Phase 5 — design may need to reserve an extension point. |
| How should validators avoid leaking sensitive data in repair feedback? | Requires sanitization policy — not implemented yet. |
| How should Java users register validators without Kotlin `KType` dependency? | May need a `Class<*>`-based registration path. |
| Should validators run before or after built-in annotations? | Order matters for error messages and UX. |
| Should validator ordering be deterministic and configurable? | Required for deterministic tests. |

---

## Acceptance Criteria

This document is complete when:

1. It states the current supported validators are `@AIRange` and `@AIMinItems`.
2. It states there is no custom validator extension point today.
3. It defines future annotation-based validator direction.
4. It defines future SPI-based validator direction.
5. It recommends or discusses a hybrid model.
6. It separates schema contribution from runtime validation.
7. It explains repair-friendly validation failures.
8. It includes Java compatibility considerations.
9. It includes deterministic testing requirements.
10. It includes security/safety considerations.
11. It includes stability mapping.
12. It includes open questions.

---

## Related Documents

| Document | Purpose |
|----------|---------|
| [Structured Output Contract Lifecycle](structured-output-contract-lifecycle.md) | Full contract lifecycle — this model builds on Stage 3 |
| [Workflow API Stability Boundary](workflow-api-stability-boundary.md) | Stability levels and promotion rules |
| [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md) | Phase 2 — task 5 is this document |
