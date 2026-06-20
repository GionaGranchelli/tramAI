# Sovereign Runtime Module Matrix

One-stop reference for the sovereign runtime modules on master. Updated 2026-06-20.

### Sovereign Runtime

See the [worker observability runbook](../operations/sovereign-ops-worker-observability-runbook.md) for operator-facing
documentation covering the Actuator status endpoint, Actuator health component,
Micrometer metrics, OpenTelemetry metrics, PromQL examples, and
troubleshooting flows.

| Module | Purpose | Runtime Role | Release Status |
|---|---|---|---|
| tramai-security | Policy enforcement, DLP, redaction, replay envelope safety | Governance core | Implemented / evolving |
| tramai-sovereign | Trust zones, sovereign routing, local/cloud enforcement primitives | Runtime composition | Implemented / evolving |
| tramai-persistence-file | Encrypted file-backed stores for approvals, continuations, audit, and outbox | Durable local persistence | Implemented / evolving |
| tramai-spring-boot-starter-sovereign | Spring Boot auto-configuration for sovereign runtime | Spring integration | Implemented / evolving |
| tramai-spring-boot-starter-sovereign-persistence-file | Spring auto-configuration for file-backed persistence | Spring persistence integration | Implemented / evolving |
| tramai-spring-boot-starter-sovereign-ops | Operational APIs: audit outbox, recovery, dispatch, background worker, observer SPI | Operational recovery | Implemented / evolving |
| tramai-spring-boot-starter-sovereign-ops-actuator | Optional read-only Actuator endpoint and health indicator for worker status | Operational visibility | Implemented / opt-in |
| tramai-spring-boot-starter-sovereign-ops-micrometer | Micrometer metrics for ops audit outbox worker | Operational observability | Implemented / opt-in |
| tramai-spring-boot-starter-sovereign-ops-observability | OpenTelemetry metrics for ops audit outbox worker | Operational observability | Implemented |
| examples:sovereign-document-intelligence | End-to-end reference sovereign workflow | Demo / evidence pack | Implemented |

## Dependency Direction

application
  -> tramai-spring-boot-starter-sovereign
     -> tramai-sovereign
     -> tramai-security

application
  -> tramai-spring-boot-starter-sovereign-persistence-file
     -> tramai-persistence-file

application
  -> tramai-spring-boot-starter-sovereign-ops
     -> Operational auto-configuration (audit outbox, recovery, dispatch)

application
  -> tramai-spring-boot-starter-sovereign-ops-actuator
     -> Read-only Actuator endpoint and health indicator (requires spring-boot-actuator)

application
  -> tramai-spring-boot-starter-sovereign-ops-micrometer
     -> Micrometer metrics only (requires MeterRegistry bean)

application
  -> tramai-spring-boot-starter-sovereign-ops-observability
     -> OpenTelemetry metrics only (no SDK, no exporter)

All modules are opt-in. The sovereign runtime does not activate unless explicitly configured.

## Finding More

- [Worker observability runbook](../operations/sovereign-ops-worker-observability-runbook.md)
- [Sovereign Runtime Release Readiness](../releases/sovereign-runtime-release-readiness.md)
- [TramAI Architecture](../ARCHITECTURE.md)
- [Project Status](../STATUS.md)
- [CHANGELOG](../../CHANGELOG.md)
