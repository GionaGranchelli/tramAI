# Architecture Decision Records

This directory records the key architectural decisions currently implied by the design and roadmap.

## ADR Index

- [ADR-001: Use Annotated Interface Methods as the Primary AI Abstraction](./adr-001.md)
- [ADR-002: Start with Runtime Proxy Generation Using JDK Dynamic Proxy](./adr-002.md)
- [ADR-003: Make Structured Output the Default Non-String Contract](./adr-003.md)
- [ADR-004: Use Custom Jackson-Based Schema Generation](./adr-004.md)
- [ADR-005: Keep the Core Framework-Agnostic and Ship Adapters Separately](./adr-005.md)
- [ADR-006: Enable OpenTelemetry Observability Automatically When Present](./adr-006.md)
- [ADR-007: Resolve Providers from Model Names with Explicit Override Support](./adr-007.md)
- [ADR-008: Build Kotlin-Native APIs with Java-Friendly Entry Points](./adr-008.md)
- [ADR-009: Keep Retry Orchestration in aurora-engine and Structured Analysis in aurora-structured](./adr-009.md)
- [ADR-010: Use an Explicit Provider Registry Instead of Model-Prefix Routing](./adr-010.md)
- [ADR-011: Support Explicit Blocking Service Interfaces in v1 Instead of Auto-Generated `*Blocking` Counterparts](./adr-011.md)
- [ADR-012: Keep `aurora-standalone` Minimal and Make Observability an Explicit Opt-In Module](./adr-012.md)

## ADR Conventions

- Status values: `proposed`, `accepted`, `superseded`, `deprecated`
- ADRs should describe one decision each
- Follow-up ADRs should supersede older ones instead of rewriting history
- Decisions that are still open in `DESIGN.md` can be captured later as separate proposed ADRs if they become active design work
