# Copilot Instructions for Aurora

Aurora is a structured-first, observability-native AI workflow library for the JVM.

The project is Kotlin-first, Java-friendly, and designed around typed interface methods as the primary abstraction for AI operations. Code generation suggestions and edits must preserve the architecture and quality bar described below.

## What Aurora Is

Aurora is a library for backend engineers who want to integrate AI into JVM applications using:

- annotated interfaces
- typed inputs and outputs
- structured output as the default contract
- explicit provider registration
- optional observability via OpenTelemetry
- framework-free core runtime, with thin framework adapters

Aurora is not an agent framework, a prompt-management system, or a chain-composition library. Do not introduce those patterns casually.

## Architectural Expectations

Respect these module boundaries:

- `aurora-core` contains shared contracts, annotations, and common models
- `aurora-engine` owns orchestration, execution flow, and retry policy
- `aurora-structured` owns schema generation, response extraction, deserialization, and structured failure analysis
- `aurora-observability` is optional and should not become a mandatory dependency of the minimal runtime
- `aurora-standalone` is the minimal non-framework composition module
- provider resolution should use an explicit registry, not implicit model-prefix routing

Do not move parsing logic into `aurora-engine`.
Do not move retry policy into `aurora-structured`.
Do not make optional modules effectively mandatory through transitive coupling.

## API Design Expectations

Prefer APIs that are:

- explicit
- typed
- deterministic
- easy to test
- easy to reason about from the outside

Avoid:

- hidden fallback behavior
- magical API synthesis that the current runtime architecture cannot honestly support
- public API additions that blur the difference between core runtime and optional adapters

For Java support in v1:

- use explicit blocking service interfaces
- do not assume auto-generated `*Blocking` companion methods for suspend interfaces

## Testing Expectations

High-quality code in Aurora is code that is audited by tests.

When generating or editing code, also generate or update tests that verify:

- the happy path
- the failure path
- module-boundary behavior
- exception contents when failure context matters
- retry behavior where retries are part of the contract
- observability attributes and events where tracing behavior is part of the contract

Prefer strong assertions over shallow ones.

Good assertions:

- exact provider resolution outcome
- exact retry count
- exact exception type and important fields
- exact schema-required versus optional behavior
- exact span attribute or event presence

Weak assertions to avoid:

- "result is not null"
- "method did not throw"
- asserting implementation details instead of public behavior

## Error Handling Expectations

Aurora should fail clearly when correctness cannot be preserved.

Prefer:

- explicit configuration errors
- explicit provider resolution errors
- explicit structured output failure reporting
- exceptions that carry enough context for debugging and testing

Avoid silent fallback and ambiguous behavior.

## Documentation Alignment

When making changes, align with:

- `PLAN.md`
- `DESIGN.md`
- `docs/adr/`
- `docs/specs/`
- `docs/board/tasks/`

If the requested change contradicts an ADR or materially changes a module boundary, update the documentation instead of quietly coding around it.

## Style of Generated Code

Generate code that is:

- small and direct
- idiomatic for Kotlin or Java as appropriate
- dependency-conscious
- explicit about boundary ownership

Do not add comments that restate obvious code.
Do add small comments where a boundary or non-obvious constraint needs to be preserved.

## Decision Rule

If there is tension between convenience and correctness, prioritize correctness.

If there is tension between terse tests and trustworthy tests, prioritize trustworthy tests.

If there is tension between a clever abstraction and an auditable one, prioritize the auditable one.
