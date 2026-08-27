# Module: `tramai-spring-boot-starter`

> **One-liner:** Unified Spring Boot starter — aggregates tramai-spring-core (generic runtime) and tramai-spring-sovereign (sovereign profile) into one dependency, profile selected via tramai.profile.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Unified Spring Boot starter: aggregation module composing `tramai-spring-core` (generic runtime) + `tramai-spring-sovereign` (sovereign profile) into one dependency. New applications select the profile via `tramai.profile`.

### Public entry points

- Starter artifact only — aggregates `tramai-spring-core` + `tramai-spring-sovereign` (no source of its own)

### Internal extension points

- None — composition/publishing module

### Significant dependencies

- `api(tramai-spring-core)`, `api(tramai-spring-sovereign)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- No runtime resources; delegates to the composed modules

### Thread-safety and concurrency

- N/A — no runtime code

### Failure semantics

- N/A — no runtime code

### Contract tests / TCKs

- Covered via consumer tests (spring-sovereign-starter example, E2E)

### Do not

- Do not add provider/secret adapters here — add them via the dedicated starter modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
