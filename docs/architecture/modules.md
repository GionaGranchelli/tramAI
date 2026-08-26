# Module Layers

> **Navigation:** the authoritative module list, classification (layer, maturity, publishability, API stability, owner, release inclusion) and dependency direction live in the machine-readable manifest. This page explains the layer philosophy only; it does not maintain a second module list.

## Sources

- Module classification / ownership / maturity / publishability: [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml)
- Forbidden / allowed dependency edges: [`config/quality/module-boundaries.yml`](../../config/quality/module-boundaries.yml)
- Generated module matrix (all 58 modules with layer/maturity/api/published/owner/release): [`docs/reference/module-matrix.md`](../reference/module-matrix.md)
- Dependency topology graph (v0.5.0 baseline snapshot): [`docs/architecture/module-dependency-graph.md`](./module-dependency-graph.md). Current dependency policy is defined by `module-catalog.yml` and `module-boundaries.yml`; current resolved dependency edges are verified by `./gradlew verify060Architecture`

## Layer Philosophy

The manifest defines 10 layers with a conceptual dependency direction (**orientation, not a strict enforced hierarchy** — exact policy lives in each module's `dependencyPolicy` and in `module-boundaries.yml`, verified by the maintainability baseline):

```
core-contracts
      ↓
runtime-execution
      ↓
governance-security / persistence / provider-adapters
      ↓
framework-integrations
      ↓
operations-observability / higher-capabilities
      ↓
applications-examples

testing-support → supports contracts without entering the runtime dependency flow
```

- **core-contracts** — annotations, shared contracts, request/response models, exceptions. Near zero-dependency.
- **runtime-execution** — engine, structured output, orchestration, standalone.
- **governance-security** — policy, approval, audit, evidence, sovereign runtime.
- **persistence** — storage implementations (file, JDBC).
- **provider-adapters** — vendor providers via the provider SPI.
- **framework-integrations** — Spring core/starter/sovereign, provider starters, secrets.
- **operations-observability** — OTel, platform wiring, server/MCP/dashboard surfaces.
- **higher-capabilities** — memory, RAG, embeddings, scheduler, vector stores.
- **applications-examples** — executable examples (excluded from release).
- **testing-support** — TCKs, fakes, consumer boundary tests.

## Key Boundaries

The following rules are enforced by `module-boundaries.yml` and verified by the maintainability baseline (new cycles and forbidden edges fail CI):

- `tramai-core` must remain as close to zero-dependency as practical.
- `tramai-engine` depends on `tramai-core`; it owns orchestration and retry policy.
- `tramai-structured` owns schema generation, extraction, deserialization, and structured failure analysis.
- `tramai-observability` is optional and must remain decoupled from the core happy path.
- Provider modules plug into the engine through the provider interface; they must not implement retry/fallback/circuit logic.
- `tramai-standalone` composes the minimal runtime for non-framework users.
- Framework adapters such as `tramai-spring-core` are thin integration layers, not alternate runtimes.
- `tramai-orchestration` owns workflow execution semantics and persistence boundaries.
- `tramai-scheduler` sits above orchestration and must not backflow scheduling concerns into the engine.
- `tramai-server` sits above orchestration/scheduler, exposing operational APIs rather than redefining workflow semantics.
- `tramai-mcp` is an adapter, not a second orchestration engine.
- `tramai-platform` owns tenancy, governance, and plugin/runtime policy concerns above the server layer.
- `tramai-dashboard` is a UI packaging module and must remain optional at runtime.

## Design Constraint

The same core operation semantics apply everywhere:

- standalone applications
- Spring Boot applications
- future framework adapters
- tests using mock providers

That same rule extends upward: the operational modules compose the core runtime, they do not fork it.

For existing per-module documentation, see `docs/modules/`. Epic 11.2b normalizes and completes these cards against the authoritative 58-module manifest.
