# Specifications

This directory is the working base for specs-driven development in Tramai.

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

## Phase Mapping

- Phase 1: `SPEC-001` to `SPEC-003`
- Phase 2: `SPEC-004` to `SPEC-006`
- Phase 3: `SPEC-007` to `SPEC-008`
- Phase 4: `SPEC-009` to `SPEC-010`
- Phase 5: `SPEC-011`
- Future design: `SPEC-012`

## Current Status Snapshot

- `SPEC-001` to `SPEC-007`: implemented in the repository
- `SPEC-008`: in progress, with release and credibility work still open
- `SPEC-009` to `SPEC-010`: implemented in the repository
- `SPEC-011`: in progress, with resilience work underway
- `SPEC-012`: implemented in the repository, with promotion and follow-up documentation work tracked through `TASK-024`

## Status Meanings

- `proposed`: accepted as a requirements document, but not yet implemented
- `in_progress`: partially implemented or actively being closed
- `implemented`: behavior exists in the repository and the remaining work is mainly polish or follow-up
- `superseded`: no longer the active requirements source

## Initial Spec Set

### Phase 1: Foundation

- [SPEC-001: Core Engine and Proxy Execution](./spec-001-core-engine.md)
- [SPEC-002: Structured Output Pipeline](./spec-002-structured-output.md)
- [SPEC-003: Provider Integration and Routing](./spec-003-provider-integration.md)

### Phase 2: Production-Ready

- [SPEC-004: Observability Integration](./spec-004-observability.md)
- [SPEC-005: Standalone Runtime and Java API](./spec-005-standalone-java-api.md)
- [SPEC-006: Spring Boot Adapter](./spec-006-spring-adapter.md)

### Phase 3: Ecosystem

- [SPEC-007: Testing Support](./spec-007-testing-support.md)
- [SPEC-008: Documentation and Publishing Readiness](./spec-008-documentation-publishing.md)

### Phase 4: Growth

- [SPEC-009: Streaming Responses](./spec-009-streaming-responses.md)
- [SPEC-010: Tool Calling](./spec-010-tool-calling.md)

### Phase 5: Production Hardening

- [SPEC-011: Production Hardening](./spec-011-production-hardening.md)

### Future Design

- [SPEC-012: Orchestration and Coordination](./spec-012-orchestration-and-coordination.md)

Conversation memory, KSP proxy generation, and additional providers or framework adapters remain roadmap items only for now.

## Writing Rules

- Specs describe behavior and constraints, not implementation trivia.
- Each spec should have measurable acceptance criteria.
- Each spec should name explicit non-goals to protect scope.
- If a design decision changes, add or update an ADR rather than silently changing the spec.

## Relationship To The Board

- Specs define intended behavior, scope, and done conditions.
- The board tracks execution status and concrete tasks derived from specs.
- Tasks must link back to a primary spec.
- Tasks must not replace spec requirements.
