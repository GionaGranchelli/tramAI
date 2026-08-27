# Module: `spring-sovereign-starter`

> **One-liner:** See the Architecture section below.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Reference example for the sovereign Spring Boot starter: invoice analysis service wired with JDBC persistence, sovereign ops REST, and the local OpenAI-compatible provider under the `sovereign-lab` profile.

### Public entry points

- `SpringSovereignStarterApplication` — Spring Boot entry point
- `InvoiceAiService`, `InvoiceAnalysisResult` — example service/domain
- `DemoModelProvider`, `InvoiceAnalysisRunner` — example support

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation` of `tramai-spring-boot-starter`, `tramai-spring`, `tramai-spring-boot-starter-sovereign-persistence-jdbc`, `tramai-spring-boot-starter-sovereign-ops`, `tramai-spring-boot-starter-sovereign-ops-rest`, `tramai-openai`, `tramai-spring-boot-starter-local-provider-openai` (all project deps); Spring Boot starter — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring Boot application lifecycle

### Thread-safety and concurrency

- Standard Spring Boot concurrency; JDBC stores borrow the container datasource

### Failure semantics

- Example-level; `sovereign-lab` profile demonstrates local-provider operation

### Contract tests / TCKs

- `SovereignLabProfileSmokeTest`, `spring-sovereign-starter:e2eTest`

### Do not

- Do not treat example code as library API

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
