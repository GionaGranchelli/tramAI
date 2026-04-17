# Specifications

This directory is the working base for specs-driven development in Aurora.

The intent is to make each meaningful implementation slice explicit before code starts:

- what problem is being solved
- what is in and out of scope
- what requirements must be met
- what acceptance criteria define done
- which ADRs and architecture constraints the work must honor

## Spec Lifecycle

1. Start from the roadmap milestone or a justified feature request.
2. Write or update a spec before implementation begins.
3. Link the spec to any relevant ADRs.
4. Keep the spec stable during execution unless scope changes are intentional.
5. When implementation lands, update status and note any follow-up ADR or reference docs needed.

## Spec Template

Use [templates/spec-template.md](./templates/spec-template.md) for new work.

## Initial Spec Set

- [SPEC-001: Core Engine and Proxy Execution](./spec-001-core-engine.md)
- [SPEC-002: Structured Output Pipeline](./spec-002-structured-output.md)
- [SPEC-003: Provider Integration and Routing](./spec-003-provider-integration.md)
- [SPEC-004: Observability Integration](./spec-004-observability.md)
- [SPEC-005: Standalone Runtime and Java API](./spec-005-standalone-java-api.md)
- [SPEC-006: Spring Boot Adapter](./spec-006-spring-adapter.md)
- [SPEC-007: Testing Support](./spec-007-testing-support.md)
- [SPEC-008: Documentation and Publishing Readiness](./spec-008-documentation-publishing.md)

## Writing Rules

- Specs describe behavior and constraints, not implementation trivia.
- Each spec should have measurable acceptance criteria.
- Each spec should name explicit non-goals to protect scope.
- If a design decision changes, add or update an ADR rather than silently changing the spec.
