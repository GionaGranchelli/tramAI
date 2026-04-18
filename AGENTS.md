# AGENTS.md

This repository contains Aurora, a structured-first, observability-native AI workflow library for the JVM.

Aurora exists to let backend engineers add AI capabilities to existing JVM applications through typed interface methods rather than chain-oriented or agent-oriented abstractions. The project is Kotlin-first, Java-friendly, framework-agnostic in its core, and designed for strong observability and strong testability.

## Project Purpose

Aurora should be the library a JVM backend engineer reaches for when they want:

- one annotated interface as the primary AI abstraction
- typed inputs and typed outputs
- structured output as the default contract for non-`String` results
- explicit module boundaries
- OpenTelemetry-friendly observability
- framework-free usage, with optional framework adapters

The codebase should reflect that purpose. Avoid adding abstractions that push Aurora toward agent frameworks, prompt-template-heavy APIs, or hidden orchestration models.

## Non-Negotiable Design Principles

- Typed contracts over raw prompt plumbing in application code
- Framework-agnostic core, thin adapters
- Structured output is a first-class capability, not an add-on
- Observability is important, but optional at the dependency level
- Fail loudly and with context when correctness cannot be guaranteed
- Prefer explicitness over magical behavior when the API surface or module boundaries are at stake

## Architectural Guardrails

Contributors and coding agents must preserve the current architectural decisions documented under `docs/adr/`.

Especially important:

- `aurora-engine` owns orchestration and retry policy
- `aurora-structured` owns schema generation, extraction, deserialization, and structured failure analysis
- `aurora-standalone` remains minimal
- `aurora-observability` remains optional and opt-in at the dependency level
- provider resolution is registry-based, not driven by fragile model-prefix heuristics
- blocking Java support in v1 uses explicit blocking service interfaces, not invented `*Blocking` methods

If a change pressures one of these boundaries, update or add an ADR before implementation drifts.

## Quality Bar

Aurora is a library. Library bugs become downstream application bugs. The standard is therefore higher than "works in one example."

Every change should aim for:

- coherent API design
- explicit failure modes
- deterministic behavior
- narrow, well-defended module boundaries
- test coverage that proves the intended behavior

Do not accept vague correctness. If behavior matters, assert it.

## Testing Standard

Tests are not ornamental in this repository. They are part of the design audit trail.

Every meaningful behavior change should come with tests that:

- prove the happy path
- prove the failure path
- prove boundary behavior where modules interact
- prove assertions about retries, validation, and exceptions where relevant

When adding or changing code, prefer tests that verify externally visible behavior over tests that mirror implementation internals.

Examples of the expected rigor:

- proxy dispatch tests should assert exact routing behavior
- structured output tests should assert parse success, parse failure, retry triggering, and terminal exception payloads
- provider tests should assert deterministic routing, timeout handling, retry behavior, and error mapping
- observability tests should assert span attributes and parse-failure events, not just that tracing code executed

## Assertions and Invariants

Write code that defends its invariants early and clearly.

Prefer:

- explicit validation of unsupported service definitions
- explicit resolution errors for unknown providers or models
- explicit exception payloads with enough context for debugging
- explicit assertions in tests for contract-level behavior

Avoid:

- silent fallback behavior
- hidden cross-module coupling
- tests that only check that "nothing crashed"
- shallow tests that assert non-null when the important question is semantic correctness

## Auditability

Code should be easy to audit by reading tests and public contracts together.

That means:

- names should be precise
- exceptions should communicate cause and context
- module responsibilities should be visible from APIs
- test names should describe the audited behavior

If a reviewer cannot tell from the tests what a feature guarantees, the test suite is not strong enough yet.

## Preferred Workflow

Before significant implementation work:

- check the relevant spec in `docs/specs/`
- check any related ADRs in `docs/adr/`
- check the current execution task in `docs/board/tasks/` if one exists

During implementation:

- keep changes aligned with the spec
- do not smuggle architectural changes in through implementation details
- add or update tests alongside the code

After implementation:

- verify the acceptance criteria are actually covered
- update docs if the public contract changed

## Repository Guidance

As the repository grows:

- keep `aurora-core` small and dependency-light
- keep `aurora-engine` focused on orchestration
- keep `aurora-structured` focused on structured-output mechanics
- keep optional modules truly optional
- keep framework adapters thin

## In Case of Ambiguity

When choosing between convenience and clarity, choose clarity.

When choosing between implicit behavior and explicit behavior, choose explicit behavior.

When choosing between lighter code and better-tested code, choose the code that is easier to trust.
