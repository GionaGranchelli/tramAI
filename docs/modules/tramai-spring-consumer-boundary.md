# Module: `tramai-spring-consumer-boundary`

> **One-liner:** Consumer-boundary fixture module — compilation/API evidence for the external-consumer boundary, no runtime code.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Consumer-boundary fixture module: represents the external-consumer boundary for Spring integration (compilation/API evidence), not a runtime library.

### Public entry points

- None — fixture module (no production source, empty api dump)

### Internal extension points

- N/A — fixture

### Significant dependencies

- None (java-library, no project deps) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- N/A — fixture module

### Thread-safety and concurrency

- N/A — fixture module

### Failure semantics

- N/A — fixture module; used by build-time consumer evidence

### Contract tests / TCKs

- Consumed by API-compatibility verification (Epic 10.2)

### Do not

- Do not add runtime code here — this is a boundary fixture

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — testing-support layer
