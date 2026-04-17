# SPEC-005: Standalone Runtime and Java API

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M5
- Related ADRs: [ADR-005](../adr/adr-005.md), [ADR-008](../adr/adr-008.md)
- Related docs: [Module Overview](../architecture/modules.md)

## Problem

Aurora must work without any framework and must be usable by both Kotlin and Java consumers. The standalone runtime is the canonical product shape, not a secondary packaging artifact.

## Scope

- `aurora-standalone` composition module
- Kotlin DSL builder
- Java builder entry point
- Blocking wrappers for suspend-backed operations
- BOM support for consumers using multiple Aurora artifacts

## Non-Goals

- Framework autoconfiguration
- Framework-specific scanning or bean registration
- Additional language-specific wrappers beyond Kotlin and Java

## Functional Requirements

- Aurora must expose a straightforward standalone construction path for Kotlin and Java consumers.
- Consumers must be able to register providers, choose defaults, and create service proxies without a framework.
- Java consumers must have a non-DSL builder entry point.
- Blocking access to suspend-based operations must be available for Java and non-coroutine codebases.
- The standalone module must assemble the required runtime pieces without forcing framework dependencies.

## Quality Requirements

- Examples and quickstart docs must reflect the standalone path first.
- Blocking wrappers must document and preserve predictable threading behavior.
- The BOM must give consumers a coherent version alignment story.

## Design Notes

- Standalone is the reference runtime that all framework adapters should build on.
- Kotlin ergonomics should not be sacrificed, but Java must not feel like an afterthought.

## Acceptance Criteria

- A Kotlin application can build Aurora with the DSL and invoke an `@AiService`.
- A Java application can build Aurora with `builder()` and invoke a blocking operation.
- No framework dependencies are required for the standalone path.

## Risks and Follow-Ups

- Blocking wrapper naming and generation strategy may need refinement once the concrete API exists.
- Version alignment and artifact naming should be reviewed again before public release.
