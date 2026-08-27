# Module: `tramai-spring-consumer-selective`

> **One-liner:** Selective-consumer fixture module — compiles against a subset of Spring modules as API-compatibility consumer evidence.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Selective-consumer fixture module: compiles against a subset of Spring modules (`tramai-spring` facade + `tramai-spring-provider-openai`) as consumer evidence for API compatibility.

### Public entry points

- None — fixture module (no production source, empty api dump)

### Internal extension points

- N/A — fixture

### Significant dependencies

- `implementation(tramai-spring)`, `implementation(tramai-spring-provider-openai)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- N/A — fixture module

### Thread-safety and concurrency

- N/A — fixture module

### Failure semantics

- N/A — fixture module; used by build-time consumer evidence

### Contract tests / TCKs

- Consumed by API-compatibility verification (Epic 10.2)

### Do not

- Do not add runtime code here — this is a consumer fixture

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — testing-support layer
