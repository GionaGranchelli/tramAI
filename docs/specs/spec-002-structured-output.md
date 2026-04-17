# SPEC-002: Structured Output Pipeline

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M2
- Related ADRs: [ADR-003](../adr/adr-003.md), [ADR-004](../adr/adr-004.md)
- Related docs: [Architecture Overview](../architecture/overview.md)

## Problem

Aurora's main differentiation depends on turning typed return values into reliable structured outputs rather than raw text blobs. The library needs a consistent schema, parsing, validation, and retry pipeline for Kotlin data classes and Java POJOs.

## Scope

- Structured output handling for non-`String` object return types
- Custom Jackson-based schema generation
- Schema caching per operation
- Response extraction from raw model text
- Deserialization, validation feedback, and structured retry loop
- `StructuredOutputException` carrying full failure context

## Non-Goals

- Native provider-specific structured output optimization beyond the provider contract hook
- Bean validation features that require a hard dependency when validation is absent from the classpath
- Streaming or partial structured responses

## Functional Requirements

- Aurora must generate JSON schema from supported return types using Kotlin nullability and Aurora annotations.
- Generated schema must include description and constraint metadata from `@AiDescription`, `@AiRange`, and `@AiMinItems`.
- Schema generation must occur once per operation and be cached for reuse.
- For structured results, prompts must include an explicit schema-based output contract.
- The parser must handle markdown fences and common non-JSON preambles before deserialization.
- On parse or validation failure, Aurora must append feedback to the conversation and retry up to the configured limit.
- When retries are exhausted, the thrown exception must contain the original prompt, last raw response, validation error, and attempt count.

## Quality Requirements

- The happy path should avoid regenerating schema on every call.
- Error messages sent back into the retry loop must be precise enough to help the model recover.
- Unit tests must cover nullable fields, nested types, malformed JSON, and retry exhaustion.

## Design Notes

- This pipeline should remain provider-agnostic by default and rely on schema-in-prompt as the baseline behavior.
- Provider-native structured output support can later become an optimization, not the foundational contract.
- Schema quality matters because it directly affects model output quality and parsing success rate.

## Acceptance Criteria

- A method returning a Kotlin data class can succeed against a stub provider that emits valid JSON.
- A malformed first response triggers a validation feedback retry and can recover on a later attempt.
- Required versus optional fields follow Kotlin nullability semantics in generated schema.
- Exhausted retries result in a typed structured output exception with failure context.

## Risks and Follow-Ups

- Schema expressiveness may need refinement once real providers are exercised.
- Complex generic types may need explicit support boundaries in later specs.
