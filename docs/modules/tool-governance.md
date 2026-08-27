# Module: `tool-governance`

> **One-liner:** Example application demonstrating tool governance — governed tools, a deterministic provider, and the tool-governance verification flow.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example application demonstrating tool governance: governed tools, a deterministic provider, and the tool-governance verification flow.

### Public entry points

- `GovernedTools`, `DeterministicToolProvider`, `ToolGovernanceMain` — example components

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation` of `tramai-engine`, `tramai-structured`, `tramai-security`; coroutines — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Main-function lifecycle

### Thread-safety and concurrency

- Example-level

### Failure semantics

- Example-level; governance violations enforced by the security layer

### Contract tests / TCKs

- Exercised by tool-governance verification / example tests

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
