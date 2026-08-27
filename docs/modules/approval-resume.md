# Module: `approval-resume`

> **One-liner:** See the Architecture section below.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example application demonstrating the approved-resume lifecycle: an expense-approval workflow whose approvals suspend, resume through the approval control plane, and continue via the auto-resume worker.

### Public entry points

- `ExpenseApprovalWorkflow` — the example workflow
- `ExpenseModels`, `InMemoryExpenseLedger` — example domain

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation` of `tramai-spring-boot-starter`, `tramai-spring`, `tramai-spring-boot-starter-sovereign-persistence-jdbc`, `tramai-spring-boot-starter-sovereign-ops` (all project deps); Spring Boot starter, kotlin-reflect, coroutines — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring Boot application lifecycle

### Thread-safety and concurrency

- Standard Spring Boot concurrency; workflow state owned by the sovereign runtime

### Failure semantics

- Example-level error handling; approval suspension is the demonstrated happy path

### Contract tests / TCKs

- Exercised by sovereign runtime E2E / example tests

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
