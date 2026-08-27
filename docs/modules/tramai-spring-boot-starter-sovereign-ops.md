# Module: `tramai-spring-boot-starter-sovereign-ops`

> **One-liner:** Sovereign operations core — approval/resume control plane, audit operations, suspended-invocation operations, and the approved-continuation resume worker.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Sovereign operations core: approval/resume control plane, audit operations, suspended-invocation operations, approved-continuation resume worker — the ops surface shared by all sovereign ops starters.

### Public entry points

- `SovereignAuditOperations`, `SovereignSuspendedInvocationOperations`, `DefaultSovereignSuspendedInvocationOperations` — operations
- `ApprovalResumeControlPlaneAutoConfiguration`, `ApprovedContinuationResumeWorkerAutoConfiguration` — auto-configurations
- `ApprovedContinuationResumeWorkerStatusSnapshot` — status model

Verify against `tramai-spring-boot-starter-sovereign-ops/api/tramai-spring-boot-starter-sovereign-ops.api` (largest sovereign-ops api dump).

### Internal extension points

- Store/observer seams for persistence and observability (implemented by persistence/ops starters)

### Significant dependencies

- `api(tramai-core)`, `api(tramai-security)`, `api(tramai-sovereign)`, `api(tramai-spring-sovereign)`; `implementation(tramai-engine)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; worker lifecycle via auto-configuration

### Thread-safety and concurrency

- Spring singletons; worker must handle concurrent tick dispatch

### Failure semantics

- Approval/resume failures surface as typed store/operation errors; no silent partial writes

### Contract tests / TCKs

- Sovereign ops E2E tests (JDBC persistence, regulated-claim triage)

### Do not

- Do not add persistence or observability bindings here — those are separate starters

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
