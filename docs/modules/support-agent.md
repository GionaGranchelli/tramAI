# Module: `support-agent`

> **One-liner:** Example support agent — a standalone application using tramai-standalone with the Ollama provider and custom tools.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Example support agent: a standalone application using `tramai-standalone` with the Ollama provider and custom tools.

### Public entry points

- `SupportAgent`, `Tools` — example agent/tools
- `Response` — example domain
- `Main` — entry point

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation(tramai-standalone)`, `implementation(tramai-ollama)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Main-function lifecycle

### Thread-safety and concurrency

- Example-level

### Failure semantics

- Example-level error handling

### Contract tests / TCKs

- Exercised by example tests

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
