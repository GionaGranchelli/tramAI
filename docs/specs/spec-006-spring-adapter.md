# SPEC-006: Spring Boot Adapter

- Status: proposed
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M6
- Related ADRs: [ADR-005](../adr/adr-005.md)
- Related docs: [Module Overview](../architecture/modules.md)

## Problem

Spring Boot is a major adoption path for JVM backend libraries. Aurora needs a thin integration layer that makes `@AiService` proxies injectable without turning the core runtime into a Spring-specific system.

## Scope

- `aurora-spring` module
- autoconfiguration for the standalone runtime
- scanning and registration of `@AiService` interfaces as beans
- `aurora.*` configuration properties model
- explicit `@EnableAurora` opt-in annotation if needed alongside default autoconfiguration

## Non-Goals

- Spring-specific AI abstractions
- divergence from standalone execution semantics
- Quarkus and Micronaut adapters in this milestone

## Functional Requirements

- Adding `aurora-spring` to a Spring Boot application must be sufficient to register `@AiService` proxies as beans.
- Provider and default operation settings must be configurable from `application.yml` under the `aurora.*` namespace.
- Configuration properties should support IDE metadata generation.
- The Spring adapter must reuse the same core execution path as standalone usage.
- Integration tests must verify bean registration and successful operation execution inside a Spring test context.

## Quality Requirements

- The adapter must remain thin and avoid embedding core business logic.
- Misconfiguration should fail with actionable startup errors.
- Spring integration should not require manual `@Bean` declarations for normal usage.

## Design Notes

- This module exists to wire Aurora into Spring facilities, not to redefine how Aurora works.
- Any feature that only exists in the Spring adapter should be treated as a design smell unless strongly justified.

## Acceptance Criteria

- A Spring Boot app with Aurora on the classpath can inject an `@AiService` with no explicit bean factory method.
- `application.yml` values are bound and influence runtime behavior.
- Spring integration tests pass with a real or stubbed provider path.

## Risks and Follow-Ups

- Package scanning boundaries and bean naming rules may need dedicated documentation.
- Future framework adapters should follow the same thin-adapter model.
