# Module: `tramai-spring-boot-starter-sovereign-ops-micrometer`

> **One-liner:** Micrometer binding for sovereign ops — exports audit-outbox worker metrics via a worker observer.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Micrometer binding for sovereign ops: exports audit-outbox worker metrics (`MicrometerSovereignOpsAuditOutboxWorkerObserver`) via `SovereignOpsOutboxMicrometerAutoConfiguration`.

### Public entry points

- `MicrometerSovereignOpsAuditOutboxWorkerObserver` — metrics observer
- `SovereignOpsOutboxMicrometerAutoConfiguration` — auto-configuration

Verify against `tramai-spring-boot-starter-sovereign-ops-micrometer/api/tramai-spring-boot-starter-sovereign-ops-micrometer.api`.

### Internal extension points

- Audit-outbox worker observer slot

### Significant dependencies

- `api(tramai-spring-boot-starter-sovereign-ops)`; Micrometer (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Metrics registration is idempotent; observer must be safe for concurrent use

### Failure semantics

- Metrics export failures must not break the worker path

### Contract tests / TCKs

- Covered via sovereign ops E2E (metrics assertions)

### Do not

- Do not add OTel bindings here — use the observability starter

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
