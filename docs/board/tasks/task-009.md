# TASK-009: Build Spring Boot autoconfiguration and configuration binding

- Status: done
- Priority: medium
- Primary spec: [SPEC-006](../../specs/spec-006-spring-adapter.md)
- Related ADRs: [ADR-005](../../adr/adr-005.md)
- Last updated: 2026-04-18

## Rationale

Spring adoption matters, but the adapter must remain thin and preserve standalone semantics.

## Scope

- build autoconfiguration for Tramai runtime assembly
- scan and register `@AiService` interfaces as beans
- bind `tramai.*` configuration properties
- verify behavior in Spring integration tests

## Definition Of Done

- a Spring Boot app can inject an `@AiService` without manual bean registration
- `application.yml` configuration influences runtime behavior
- Spring integration tests cover registration and execution

## Notes

Avoid putting core execution behavior into the Spring module.
