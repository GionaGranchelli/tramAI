# Module: `kotlin-consumer-smoke`

> **One-liner:** See the Architecture section below.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Kotlin consumer compilation proof (Epic 10.2): proves the stable TramAI API compiles from a Kotlin consumer perspective.

### Public entry points

- `KotlinConsumerSmoke` — consumer compilation evidence

Repository-facing only (fixture module).

### Internal extension points

- N/A — fixture

### Significant dependencies

- `implementation(tramai-core)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- N/A — fixture module

### Thread-safety and concurrency

- N/A — fixture module

### Failure semantics

- A Kotlin compilation failure fails the API-compatibility gate (fail-soft evidence)

### Contract tests / TCKs

- Consumed by API-compatibility verification (`verifyApiCompatibility` family)

### Do not

- Do not add runtime code here — this is a compilation-proof fixture

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
