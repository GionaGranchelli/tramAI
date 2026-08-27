# Module: `tramai-spring-boot-starter-sovereign-ops-observability`

> **One-liner:** OpenTelemetry binding for sovereign ops — exports audit-outbox worker spans via a worker observer.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

OpenTelemetry binding for sovereign ops: exports audit-outbox worker spans (`OpenTelemetrySovereignOpsAuditOutboxWorkerObserver`) via `SovereignOpsOutboxObservabilityAutoConfiguration`.

### Public entry points

- `OpenTelemetrySovereignOpsAuditOutboxWorkerObserver` — observability observer
- `SovereignOpsOutboxObservabilityAutoConfiguration` — auto-configuration

Verify against `tramai-spring-boot-starter-sovereign-ops-observability/api/tramai-spring-boot-starter-sovereign-ops-observability.api`.

### Internal extension points

- Audit-outbox worker observer slot

### Significant dependencies

- `api(tramai-spring-boot-starter-sovereign-ops)`; OpenTelemetry SDK (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Observer must be safe for concurrent use

### Failure semantics

- Tracing failures must not break the worker path

### Contract tests / TCKs

- Covered via sovereign ops E2E

### Do not

- Do not add micrometer bindings here — use the micrometer starter

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
