# SPEC-005: Standalone Runtime and Java API

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M5
- Related ADRs: [ADR-005](../adr/adr-005.md), [ADR-008](../adr/adr-008.md), [ADR-011](../adr/adr-011.md), [ADR-012](../adr/adr-012.md)
- Related docs: [Module Overview](../architecture/modules.md)

## Problem

Tramai must work without any framework and must be usable by both Kotlin and Java consumers. The standalone runtime is the canonical product shape, not a secondary packaging artifact.

## Scope

- `tramai-standalone` composition module
- Kotlin DSL builder
- Java builder entry point
- Explicit blocking service interface support
- BOM support for consumers using multiple Tramai artifacts

## Non-Goals

- Framework autoconfiguration
- Framework-specific scanning or bean registration
- Auto-generated `*Blocking` companion methods for suspend service interfaces
- Mandatory observability dependencies inside `tramai-standalone`
- Additional language-specific wrappers beyond Kotlin and Java

## Functional Requirements

- Tramai must expose a straightforward standalone construction path for Kotlin and Java consumers.
- Consumers must be able to register providers, choose defaults, and create service proxies without a framework.
- Java consumers must have a non-DSL builder entry point.
- Tramai must support blocking service interfaces for Java and non-coroutine codebases.
- Tramai v1 must not depend on auto-generated `*Blocking` methods for suspend interfaces unless a concrete generation strategy is adopted.
- `tramai-standalone` must remain the minimal runtime composition and must not pull `tramai-observability` transitively.
- The standalone module must assemble the required runtime pieces without forcing framework dependencies.

## Quality Requirements

- Examples and quickstart docs must reflect the standalone path first.
- Blocking invocation behavior must document and preserve predictable threading behavior.
- The standalone packaging story must remain clear about which capabilities are included by default and which remain opt-in.
- The BOM must give consumers a coherent version alignment story.

## Design Notes

- Standalone is the reference runtime that all framework adapters should build on.
- Kotlin ergonomics should not be sacrificed, but Java must not feel like an afterthought.
- The Java story in v1 should rely on explicit blocking interfaces rather than implicit generated companion methods.
- The standalone artifact should remain minimal; observability becomes automatic only when its optional module is also present.

## Acceptance Criteria

- A Kotlin application can build Tramai with the DSL and invoke an `@AiService`.
- A Java application can build Tramai with `builder()` and invoke a blocking service interface.
- A standalone consumer can use Tramai without receiving observability dependencies transitively.
- No framework dependencies are required for the standalone path.

## Risks and Follow-Ups

- A future one-source-of-truth strategy for suspend and blocking service APIs may require generation and a follow-up ADR.
- Version alignment and artifact naming should be reviewed again before public release.
