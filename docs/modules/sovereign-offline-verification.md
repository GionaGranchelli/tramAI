# Module: `sovereign-offline-verification`

> **One-liner:** Example application proving zero-egress operation — loopback model server, loopback HTTP provider, and offline echo service generate a zero-egress verification report.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example application proving zero-egress operation: a loopback model server, loopback HTTP provider, and offline echo service generate a zero-egress verification report.

### Public entry points

- `LoopbackModelServer`, `LoopbackHttpModelProvider`, `OfflineEchoService` — example components
- `ZeroEgressVerificationReportV1`, `ZeroEgressReportWriter` — report types
- `OfflineVerificationMain` — entry point

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation` of `tramai-sovereign`, `tramai-security`, `tramai-core`, `tramai-engine` (all project deps) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Main-function lifecycle

### Thread-safety and concurrency

- Example-level; loopback server is single-process

### Failure semantics

- Example-level; report generation is the evidence output

### Contract tests / TCKs

- Exercised by sovereign zero-egress verification runs

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
