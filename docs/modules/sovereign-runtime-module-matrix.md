# Sovereign Runtime Module Matrix

One-stop reference for the sovereign runtime modules on master. Updated 2026-06-18.

## Module Overview

| Module | Purpose | Runtime Role | Release Status |
|---|---|---|---|
| tramai-security | Policy enforcement, DLP, redaction, replay envelope safety | Governance core | Implemented / evolving |
| tramai-sovereign | Trust zones, sovereign routing, local/cloud enforcement primitives | Runtime composition | Implemented / evolving |
| tramai-persistence-file | Encrypted file-backed stores for approvals, continuations, audit, and outbox | Durable local persistence | Implemented / evolving |
| tramai-spring-boot-starter-sovereign | Spring Boot auto-configuration for sovereign runtime | Spring integration | Implemented / evolving |
| tramai-spring-boot-starter-sovereign-persistence-file | Spring auto-configuration for file-backed persistence | Spring persistence integration | Implemented / evolving |
| tramai-spring-boot-starter-sovereign-ops | Operational APIs: audit outbox, recovery, dispatch, background worker, observer SPI | Operational recovery | Implemented / evolving |
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
     -> audit outbox worker / recovery / dispatch

application
  -> tramai-spring-boot-starter-sovereign-ops-observability
     -> OpenTelemetry metrics only (no SDK, no exporter)

All modules are opt-in. The sovereign runtime does not activate unless explicitly configured.

## Finding More

- [Sovereign Runtime Release Readiness](../releases/sovereign-runtime-release-readiness.md)
- [TramAI Architecture](../ARCHITECTURE.md)
- [Project Status](../STATUS.md)
- [CHANGELOG](../../CHANGELOG.md)
