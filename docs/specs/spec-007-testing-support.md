# SPEC-007: Testing Support

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M7
- Related ADRs: [ADR-003](../adr/adr-003.md), [ADR-006](../adr/adr-006.md)
- Related docs: [Architecture Overview](../architecture/overview.md)

## Problem

Tramai will only be practical in production code if developers can test AI-dependent behavior without live network access, brittle HTTP stubs, or mocking internal library mechanics.

## Scope

- `tramai-testing` module
- `MockAiProvider` DSL
- capture and inspection of outbound requests
- simulated failure provider support for retry-path testing
- assertion helpers for structured parsing and observability behavior
- Spring test support such as `@MockAiResponse`

## Non-Goals

- Full contract testing for every real provider
- Snapshot testing framework integration
- End-to-end cloud testing in unit test scope

## Functional Requirements

- Tests must be able to register canned responses by operation or method name.
- Tests must be able to inspect what Tramai sent to the provider.
- Retry-path behavior must be testable by simulating malformed responses or transient failures.
- Assertion helpers should support checking retry count, parse success, and relevant observability output when instrumentation is enabled.
- Spring test support must allow replacing live provider execution with deterministic mock responses.

## Quality Requirements

- Test utilities must avoid requiring Mockito or HTTP-level stubbing for normal Tramai tests.
- Test helpers should make failure output easy to understand.
- The testing module must stay framework-optional except for explicitly Spring-scoped features.

## Design Notes

- Tramai's testing story should validate the same abstractions the user writes against, not internal implementation classes.
- The testing module is part of the core product credibility because the library explicitly rejects leaky AI integration patterns.

## Acceptance Criteria

- A JUnit test can execute an `@AiService` method with a deterministic mock provider and assert on the typed result.
- A retry-path test can simulate malformed JSON and verify retry behavior.
- Spring test support can override or short-circuit live provider execution for an injected service.

## Risks and Follow-Ups

- Operation identification by method name alone may be insufficient in overloaded or proxied scenarios.
- Assertion APIs should stay small enough to remain maintainable.
- Some planned Spring-scoped testing conveniences remain follow-up work rather than shipped functionality.
