# TramAI Architecture Map

This is the authoritative navigation map for the TramAI repository. It tells you **where a responsibility lives, what is authoritative, and where to start reading** — it does not reproduce the authoritative models themselves.

> **Rule:** this document points to authoritative sources; it does not duplicate them. If a fact is owned by a manifest, catalog, dump, or TCK, link to it. Do not maintain parallel truth here.

## 1. Sources of truth

| Concept | Authoritative source |
|---------|----------------------|
| Module classification / ownership / maturity / publishability / API stability / release inclusion | [`config/quality/module-catalog.yml`](./config/quality/module-catalog.yml) |
| Module dependency exceptions / forbidden layer edges | [`config/quality/module-boundaries.yml`](./config/quality/module-boundaries.yml) |
| Generated module overview (matrix) | [`docs/reference/module-matrix.md`](./docs/reference/module-matrix.md) — generated from the catalog, do not edit |
| Dependency topology (graph) | [`docs/architecture/module-dependency-graph.md`](./docs/architecture/module-dependency-graph.md) — **v0.5.0 baseline snapshot** (48 modules). Current dependency policy is defined by `module-catalog.yml` and `module-boundaries.yml`; current resolved dependency edges are verified by `./gradlew verify060Architecture` |
| Public API compatibility | `api/*.api` dumps per module (binary-compatibility-validator) |
| Runtime events / reason codes | [`RuntimeEventCatalogue.kt`](./tramai-core/src/main/kotlin/dev/tramai/core/observation/event/RuntimeEventCatalogue.kt) (authoritative); [`docs/reference/runtime-event-catalogue.md`](./docs/reference/runtime-event-catalogue.md) is its generated view |
| Provider routing | [`ProviderRegistry`](./tramai-core/src/main/kotlin/dev/tramai/core/provider/ProviderRegistry.kt) + provider routing contracts in `tramai-engine` |
| Structured output contract | compiled structured descriptor (`StructuredTypeDescriptor`, `StructuredContractFingerprint` in `tramai-structured`) |
| Persistence behavior | Store TCKs under [`tramai-testing`](./tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/) (e.g. `ApprovalStoreTck`) |
| Provider behavior | [`ProviderTck`](./tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/provider/ProviderTck.kt) |
| Architectural decisions | [`docs/adr/`](./docs/adr/) |

## 2. Module layers

The 10-layer model is defined by the manifest (`config/quality/module-catalog.yml`); this section is **conceptual orientation, not a strict enforced hierarchy** — exact dependency policy lives in the manifest's per-module `dependencyPolicy` fields and `module-boundaries.yml`. **Module membership is derived, not duplicated here** — see the [module matrix](./docs/reference/module-matrix.md).

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

Dependency direction is enforced by `module-boundaries.yml` + the maintainability baseline (`verifyForbiddenEdges` / `verifyDependencyCycles`), and aggregated by the unified architecture gate `./gradlew verify060Architecture`.

## 3. Where does X live?

| Responsibility | Start here | Contract / tests |
|----------------|-----------|------------------|
| Typed AI service API | `tramai-core` (`@AiService`, annotations) | API dumps + core tests |
| Engine invocation | `tramai-engine` (`TramaiEngine`, `InvocationExecutionCoordinator`, `TramaiInvocationHandler`) | engine behavioral tests |
| Provider routing | `tramai-engine` (`ProviderExecutionCoordinator`, `ProviderRegistry` in core) | provider TCK |
| Provider implementation | the provider module (`tramai-openai`, `tramai-ollama`, …) | [`ProviderTck`](./tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/provider/ProviderTck.kt) |
| Structured output | `tramai-structured` (`StructuredTypeCompiler`, `JacksonStructuredOutputHandler`) | structured contract TCK / descriptor tests |
| Tools | `tramai-core` (`Tool`, `AiTool`) + `tramai-engine/tool` | tool contract tests |
| Policy / DLP | `tramai-security` (`DefaultPolicyEngine`, `RuleBasedDlpInterceptor`) + `tramai-engine` (`PolicyEnforcementHelper`) | policy tests |
| Approval lifecycle | `tramai-security/approval` + `tramai-core/approval` contracts | [`ApprovalStoreTck`](./tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/persistence/approval/ApprovalStoreTck.kt) |
| Workflow execution | `tramai-orchestration` (`WorkflowExecutionSupervisor`, `TramaiWorker`) | workflow tests |
| Worker lifecycle | `tramai-orchestration` (`WorkerLifecycleController`, `WorkerShutdownCoordinator`) | lifecycle state-machine tests |
| Persistence | relevant persistence module (`tramai-persistence-file`, `tramai-persistence-jdbc`) | store TCK |
| Audit / evidence | `tramai-security` (`audit/`, `evidence/`) | audit/evidence contracts |
| Spring integration | `tramai-spring-core` (`AiServiceProxyAutoConfiguration`, `EnableTramai`) + starters | compatibility TCK |
| Observability | `tramai-observability` (`OpenTelemetryOperationObserver`) | observability contract tests |
| RAG / vector stores | `tramai-rag`, `tramai-vectorstore-*` (SPI in `tramai-vectorstore-spi`) | SPI tests |

## 4. Do not start here

| Task | Do NOT |
|------|--------|
| Adding a provider | Do NOT add provider-specific branches to the central engine coordinator (`TramaiEngine`, `InvocationExecutionCoordinator`). Providers implement the SPI; the engine owns routing/retry/fallback. |
| Adding a Spring provider integration | Do NOT add Spring dependencies to `tramai-core` / `tramai-engine` / `tramai-structured`. Spring integration lives in `tramai-spring-core` / starter modules. |
| Adding a persistence backend | Do NOT copy an existing store's behavior as the specification. Implement the SPI and enroll in the shared TCK. |
| Changing structured validation | Do NOT independently modify schema generation and post-parse validation. Start from the compiled structured contract (`StructuredTypeDescriptor`). |
| Adding an event / reason code | Do NOT introduce another string literal beside the authoritative `RuntimeEventCatalogue.kt` (`docs/reference/runtime-event-catalogue.md` is its generated view). |
| Adding a workflow step | Do NOT add new concrete-type dispatch to a central orchestrator god object. Extend the step abstraction in `tramai-orchestration`. |

## 5. Execution ownership

The runtime execution seams are described in detail in [`docs/architecture/execution-sequence.md`](./docs/architecture/execution-sequence.md).

Stable ownership statements (current):

- Provider admission and completion are owned by the engine's provider-execution boundary (`ProviderExecutionCoordinator` / `ProviderAttemptExecutor`); retries/fallbacks must not be implemented inside provider adapters.
- Structured output handling is owned by `tramai-structured`; the engine consumes its contracts.
- Workflow execution, worker lifecycle, and persistence fencing are owned by `tramai-orchestration` (`WorkflowExecutionSupervisor`, `WorkerLifecycleController`, `WorkerShutdownCoordinator`).
- Spring integration is a thin adapter over the engine via `tramai-spring-core`.

> Circuit-breaker OPEN/HALF_OPEN generation semantics are intentionally not frozen here while the 8.2g circuit-breaker lifecycle work is in flight. The ownership seam above is stable.

## 6. Navigation protocol for contributors and agents

Before editing a subsystem:

1. Read this map.
2. Follow its ownership pointer to the responsible module.
3. Read the relevant change guide under `docs/architecture/change-guides/` (added in Epic 11.2c).
4. Run the contract/TCK named by that guide.

Automated coding agents must also follow [`AGENTS.md`](./AGENTS.md) for the execution protocol.
