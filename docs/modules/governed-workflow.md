# Module: `governed-workflow`

> **One-liner:** Example application demonstrating governed workflows — claim classification and triage with security/governance constraints applied by the engine.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example application demonstrating governed workflows: claim classification and triage with security/governance constraints applied by the engine.

### Public entry points

- `ClaimTriageWorkflow`, `ClaimClassifier` — example workflow components
- `GovernedWorkflowMain` — entry point
- `ClaimTriageTypes` — example domain

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation(tramai-orchestration)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Main-function lifecycle; workflow execution owned by the engine

### Thread-safety and concurrency

- Example-level; coroutine-driven workflow execution

### Failure semantics

- Example-level error handling

### Contract tests / TCKs

- Exercised by tool-governance verification / example tests

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
