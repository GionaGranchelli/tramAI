# SPEC-002: Structured Output Pipeline

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M2
- Related ADRs: [ADR-003](../adr/adr-003.md), [ADR-004](../adr/adr-004.md), [ADR-009](../adr/adr-009.md)
- Related docs: [Architecture Overview](../architecture/overview.md)

## Problem

Aurora's main differentiation depends on turning typed return values into reliable structured outputs rather than raw text blobs. The library needs a consistent schema, parsing, validation, and retry pipeline for Kotlin data classes and Java POJOs.

## Scope

- Structured output handling for non-`String` object return types
- Custom Jackson-based schema generation
- Schema caching per operation
- Response extraction from raw model text
- Deserialization and structured failure assessment
- `StructuredOutputException` carrying full failure context

## Non-Goals

- Native provider-specific structured output optimization beyond the provider contract hook
- Bean validation features that require a hard dependency when validation is absent from the classpath
- Retry policy ownership inside `aurora-structured`
- Streaming or partial structured responses

## Functional Requirements

- Aurora must generate JSON schema from supported return types using Kotlin nullability and Aurora annotations.
- Generated schema must include description and constraint metadata from `@AiDescription`, `@AiRange`, and `@AiMinItems`.
- Schema generation must occur once per operation and be cached for reuse.
- For structured results, prompts must include an explicit schema-based output contract.
- The parser must handle markdown fences and common non-JSON preambles before deserialization.
- `aurora-structured` must return a structured result object that represents success or failure and includes enough detail for the engine to decide whether to retry.
- Parse, extraction, deserialization, and validation failures must all flow through that result contract rather than leaking parsing logic into the engine.
- Retry orchestration remains owned by `aurora-engine`, which consumes the structured result and decides whether to append feedback, retry, or fail.
- When retries are exhausted, the thrown exception must contain the original prompt, last raw response, validation or parse failure detail, and attempt count.

## Quality Requirements

- The happy path should avoid regenerating schema on every call.
- Error messages sent back into the retry loop must be precise enough to help the model recover.
- The boundary with `aurora-engine` must remain narrow and avoid exposing parsing internals as orchestration APIs.
- Unit tests must cover nullable fields, nested types, malformed JSON, and retry exhaustion.

## Design Notes

- This pipeline should remain provider-agnostic by default and rely on schema-in-prompt as the baseline behavior.
- Provider-native structured output support can later become an optimization, not the foundational contract.
- Schema quality matters because it directly affects model output quality and parsing success rate.
- `aurora-structured` should report structured-output outcomes; `aurora-engine` should own what happens next.
- A boundary type broader than `ValidationResult` may be preferable if parse and extraction failures are represented alongside validation failures.

## Acceptance Criteria

- A method returning a Kotlin data class can succeed against a stub provider that emits valid JSON.
- A malformed first response triggers a validation feedback retry and can recover on a later attempt.
- Required versus optional fields follow Kotlin nullability semantics in generated schema.
- The structured module can signal retry-worthy failure details without implementing retry policy itself.
- Exhausted retries result in a typed structured output exception with failure context.

## Risks and Follow-Ups

- Schema expressiveness may need refinement once real providers are exercised.
- Complex generic types may need explicit support boundaries in later specs.
