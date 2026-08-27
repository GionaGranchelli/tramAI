# Module: `tramai-spring-boot-starter-sovereign-ops-rest`

> **One-liner:** REST surface for sovereign ops — approval control-plane and inbox controllers with reviewer UI support.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

REST surface for sovereign ops: approval control-plane + inbox controllers (`ApprovalControlPlaneController`, `ApprovalInboxController`), reviewer UI controller, DTOs.

### Public entry points

- `ApprovalControlPlaneController`, `ApprovalInboxController`, `ApprovalReviewerUiController` — controllers
- `ApprovalInboxDtos`, `ApprovalControlPlaneRestDtos` — DTOs
- `ApprovalControlPlaneRestAutoConfiguration` — auto-configuration

Verify against `tramai-spring-boot-starter-sovereign-ops-rest/api/tramai-spring-boot-starter-sovereign-ops-rest.api`.

### Internal extension points

- REST transport over the ops operations

### Significant dependencies

- `api(tramai-spring-boot-starter-sovereign-ops)`; Spring Web (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; controllers are container singletons

### Thread-safety and concurrency

- Controllers must be safe for concurrent HTTP requests

### Failure semantics

- Approval operations surface typed HTTP error responses

### Contract tests / TCKs

- `ApprovalControlPlaneControllerTest`, `ApprovalInboxControllerTest`

### Do not

- Do not add persistence here — controllers delegate to the ops operations

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
