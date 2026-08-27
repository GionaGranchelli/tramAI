# Module: `sovereign-document-intelligence`

> **One-liner:** Example application for sovereign document intelligence — invoice analysis with a deterministic provider, payment scheduling tool, and sovereign security controls.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example application for sovereign document intelligence: invoice analysis with a deterministic provider, payment scheduling tool, and sovereign security controls.

### Public entry points

- `InvoiceAnalysisService`, `DocumentIntelligenceMain` — example components
- `SchedulePaymentTool` — example tool
- `InvoiceModels`, `DeterministicInvoiceProvider`, `DocumentIntelligenceExampleSupport` — example domain/support

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation` of `tramai-sovereign`, `tramai-security`, `tramai-core`, `tramai-engine` (all project deps) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Main-function lifecycle; sovereign runtime owned by the application

### Thread-safety and concurrency

- Example-level; coroutine-driven

### Failure semantics

- Example-level error handling; sovereignty rules enforced by the security layer

### Contract tests / TCKs

- Exercised by sovereign evidence runs / example tests

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
