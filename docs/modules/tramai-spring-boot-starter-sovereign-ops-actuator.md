# Module: `tramai-spring-boot-starter-sovereign-ops-actuator`

> **One-liner:** Actuator surface for sovereign ops — health indicators and worker-status/metrics snapshot endpoints.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Actuator surface for sovereign ops: health indicators (`SovereignOpsWorkerHealthIndicator`, `ApprovedContinuationResumeWorkerHealthIndicator`), worker-status endpoint properties, resume-queue metrics snapshots.

### Public entry points

- `SovereignOpsWorkerHealthIndicator`, `ApprovedContinuationResumeWorkerHealthIndicator` — health indicators
- `SovereignOpsWorkerStatusEndpointProperties`, `ApprovedContinuationResumeWorkerMetricsProperties` — properties
- `ApprovedResumeQueueMetricsSnapshotProvider` — metrics snapshot

Verify against `tramai-spring-boot-starter-sovereign-ops-actuator/api/tramai-spring-boot-starter-sovereign-ops-actuator.api`.

### Internal extension points

- Health/metrics providers for the ops surface

### Significant dependencies

- `api(tramai-spring-boot-starter-sovereign-ops)`; Spring Boot actuator (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Spring singletons; health/metrics calls must be safe for concurrent HTTP requests

### Failure semantics

- Health indicator degrades to DOWN rather than throwing on store unavailability

### Contract tests / TCKs

- `SovereignOpsWorkerHealthIndicatorTest`, `ApprovedResumeQueueMetricsSnapshotProviderTest`

### Do not

- Do not add micrometer/OTel bindings here — use the dedicated ops-observability starters

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
