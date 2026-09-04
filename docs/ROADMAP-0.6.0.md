# TramAI 0.6.0 — Code Quality, Architecture, and Maintainability Roadmap

> **Status:** Draft release roadmap  
> **Target release:** TramAI 0.6.0  
> **Baseline:** TramAI 0.5.0, after publication and release verification  
> **Primary objective:** Make TramAI easier to understand, safer to change, harder to misuse, and credible under expert review.  
> **Theme:** **Clarity is a runtime property.**

---

## 1. Executive Decision

TramAI 0.6.0 is a **quality, architecture, and maintainability release**.

The release will not be driven by adding another broad capability area. The existing runtime already spans typed AI services, structured output, providers, policy, DLP, approvals, evidence, persistence, orchestration, workers, scheduling, MCP, RAG, server integration, Spring Boot integration, and sovereign deployment support.

The next responsibility is to make that capability set:

- understandable by a new maintainer;
- reviewable by security and architecture teams;
- predictable under cancellation, shutdown, retry, replay, and failure;
- modular enough that one change does not require understanding the entire runtime;
- consistent across providers, stores, workflow steps, and integration surfaces;
- protected by automated architectural and quality checks;
- pleasant for both humans and AI coding agents to navigate.

The release thesis is:

> **TramAI 0.6.0 turns a capable governed-AI runtime into a codebase whose structure visibly supports its claims.**

This roadmap treats maintainability as part of product credibility. TramAI should not merely work. Its source should make the correctness model legible.

---

## 2. Release Principles

All 0.6.0 work follows these principles.

### 2.1 Correctness before cosmetic refactoring

Cancellation, lifecycle, retry, replay, security, and audit-ordering defects are fixed before file-size or naming improvements.

### 2.2 Behaviour-preserving decomposition

Large components are split behind characterization tests. Public behaviour, error semantics, evidence ordering, and compatibility are preserved unless an intentional breaking change is documented.

### 2.3 Composition is separate from execution

Builders and Spring configuration assemble immutable runtime components. Execution classes consume those components but do not own configuration discovery.

### 2.4 Explicit ownership

Every coroutine scope, worker, process, client, store, registry, and cache has a documented owner and shutdown path.

### 2.5 One authoritative model per concept

Provider routing, structured contracts, workflow definitions, event names, failure codes, and store semantics must not be represented independently in several places.

### 2.6 Fail-open and fail-closed behaviour is declared

Policy, audit, evidence, metrics, observers, telemetry, DLP, and persistence failures must have explicit failure semantics. They must not depend on incidental exception handling.

### 2.7 Public APIs remain small and stable

0.6.0 may change preview or internal APIs where necessary, but stable APIs must be protected by binary-compatibility checks and documented migration paths.

### 2.8 Tests verify contracts, not only implementations

Provider, persistence, worker, workflow-step, and structured-output implementations should share reusable technology compatibility kits where behaviour must be uniform.

### 2.9 Quality gates are automated

A standard that exists only in this document is not a standard. Critical requirements become CI checks, architecture tests, static-analysis rules, or release verification tasks.

### 2.10 Readability is a feature

A reader should be able to determine from package names, class names, constructor dependencies, and data types:

- what a component owns;
- what it may call;
- what state it changes;
- what failures it emits;
- whether it is thread-safe;
- how it is shut down;
- what evidence or telemetry it produces.

---

## 3. Scope

### 3.1 In scope

- Coroutine cancellation and shutdown correctness
- Resource and runtime lifecycle ownership
- Engine decomposition
- Workflow runtime decomposition
- Removal of hidden process-global state
- Tool failure and retry semantics
- Provider consistency and transport contracts
- Structured-output architecture
- Provider-routing and configuration model consolidation
- Spring integration modularity
- Persistence and worker contract tests
- Exception, error-code, telemetry, and audit-event consistency
- Network boundary and SSRF hardening
- Build-logic decomposition
- Static analysis, formatting, coverage, mutation, API, and architecture checks
- Module graph review and dependency enforcement
- Public API documentation and maintainability guidance
- Performance and concurrency regression protection
- Release-readiness evidence for maintainability

### 3.2 Explicit non-goals

0.6.0 does **not** claim to deliver:

- EU AI Act compliance or legal certification;
- production certification for every provider or deployment;
- a new hosted control plane;
- a production-grade enterprise reviewer UI;
- a broad new agent framework;
- new provider breadth unless needed to validate the provider contract;
- a full rewrite of TramAI;
- arbitrary clean-architecture layering disconnected from actual runtime responsibilities;
- 100% line coverage as a vanity metric;
- breaking stable APIs merely to make code aesthetically uniform.

---

## 4. Current Architectural Risks

The 0.6.0 work is motivated by the following confirmed or strongly indicated risks.

### 4.1 Central invocation concentration

`TramaiInvocationHandler` owns provider resolution, retries, circuit breaking, structured output, memory, caching, policy, DLP, tools, approvals, replay, observation, audit, and evidence-sensitive ordering. Its dependency count and method surface make the main execution boundary difficult to review as a whole.

### 4.2 Workflow concentration

`Workflow.kt` combines workflow metadata, exceptions, observation, execution, persistence coordination, step dispatch, builders, compatibility, and multiple execution concerns. Adding a built-in step requires editing a central concrete-type dispatch.

### 4.3 Cancellation inconsistencies

Some execution paths preserve `CancellationException`; others wrap it as a provider, tool, HTTP, or workflow failure. This can cause retries, fallbacks, misleading telemetry, or delayed shutdown after cancellation.

### 4.4 Weak lifecycle ownership

Convenience APIs may construct engine instances without returning an explicit lifecycle handle. Runtime-owned coroutine scopes and jobs must always have a reachable owner and deterministic close path.

### 4.5 Hidden global workflow state

Worker workflow bindings use process-global mutable state keyed by workflow name, with unchecked generic casts and implicit registration side effects.

### 4.6 Conflated tool semantics

Tool idempotency is currently used as a proxy for failure retryability. These are independent concepts and must be modelled separately.

### 4.7 Duplicated configuration truth

Standalone, sovereign, Spring, and provider-registry builders maintain overlapping representations of providers, model routes, fallbacks, defaults, and validation rules.

### 4.8 Parallel structured-output logic

Kotlin and JavaBean schema generation, shape validation, post-deserialization validation, annotation lookup, and recursion detection are maintained through several mirrored code paths.

### 4.9 Provider behaviour drift

Provider adapters independently implement HTTP construction, streaming, timeout handling, cancellation, error mapping, body logging, usage extraction, tool-call parsing, and safe failure reporting.

### 4.10 Integration coupling

The general Spring module constructs concrete providers and secret backends directly, increasing dependency weight and making provider additions changes to central auto-configuration.

### 4.11 Build-logic concentration

The root `build.gradle.kts` owns publishing, release verification, artifact inspection, XML parsing, evidence generation, and many custom tasks. The build is becoming another application with limited modularity and testability.

### 4.12 Module sprawl without one machine-readable maturity model

The repository contains many runtime, adapter, integration, persistence, server, platform, starter, and example modules. Publishability, maturity, ownership, and dependency rules are represented in several places and can drift.

### 4.13 Stringly typed operational contracts

Event names, attribute keys, reason codes, audit metadata, and failure codes are often represented as repeated strings. Typographical drift can silently break dashboards, evidence exporters, or tests.

### 4.14 Safe-error boundary ambiguity

Raw provider bodies, exception messages, paths, command details, or tool failures can cross into logs, public exceptions, model-visible tool messages, or telemetry without one authoritative sanitisation policy.

### 4.15 Time and concurrency determinism

Runtime code uses a mixture of injected clocks, `System.currentTimeMillis()`, random jitter, global state, and process hooks. Duration measurement, wall-clock semantics, retry timing, and tests should be more explicit.

---

## 5. Roadmap Overview

| Phase | Epic | Purpose | Priority |
|---|---|---|---|
| 0 | Baseline and architecture contract | Establish facts, metrics, invariants, and release gates | P0 |
| 1 | Correctness hotfixes | Fix cancellation, safe errors, lifecycle leaks, and network-boundary overclaims | P0 |
| 2 | Runtime composition model | Create immutable component groups and explicit ownership | P0 |
| 3 | Engine decomposition | Split the main invocation path into cohesive coordinators | P0 |
| 4 | Workflow and worker decomposition | Remove global state and split workflow execution responsibilities | P0 |
| 5 | Failure, event, and telemetry contracts | Centralize safe failures, retry semantics, event types, and fail-open/closed behaviour | P1 |
| 6 | Provider and integration architecture | Add provider TCK, shared transport utilities, and modular Spring adapters | P1 |
| 7 | Structured-output architecture | Compile one descriptor model used by schema and validation | P1 |
| 8 | Persistence and concurrency assurance | Add store TCKs, worker state-machine tests, and deterministic timing | P1 |
| 9 | Module and build architecture | Enforce module boundaries and move build logic into tested convention plugins | P1 |
| 10 | Continuous code quality | Add formatting, static analysis, API, dependency, coverage, mutation, and architecture gates | P1 |
| 11 | Readability and maintainer experience | Make code navigation, invariants, and contributor expectations explicit | P2 |
| 12 | Stabilisation and release proof | Freeze, benchmark, audit, migrate examples, and verify the release | P0 |

---

# Phase 0 — Baseline and Architecture Contract

## Epic 0.1: Establish the 0.6.0 baseline

**Goal:** Record the current state before refactoring so improvement is measurable and regressions are visible.

### Tasks

1. Tag or record the exact 0.5.0 release commit used as the baseline.
2. Generate a module dependency graph.
3. Record production and test LOC by module.
4. Record the largest files, classes, functions, constructors, and dependency fan-in/fan-out.
5. Record test duration by module and slowest test classes.
6. Record current binary public API dumps.
7. Record current dependency versions and transitive dependency graph.
8. Record baseline code coverage for critical modules.
9. Run a targeted mutation-testing baseline for policy, approval, routing, evidence, and retry logic.
10. Generate a list of all broad catches in suspend-capable code.
11. Generate a list of process-global mutable registries and singleton stores.
12. Generate a list of direct wall-clock and random-number usage in runtime code.
13. Inventory public error messages, reason codes, audit event names, metric names, and attribute keys.

### Deliverables

- `docs/releases/0.6.0-maintainability-baseline.md`
- `docs/architecture/module-dependency-graph.md`
- Machine-readable baseline under `config/quality/0.6.0-baseline.json`
- `verifyMaintainabilityBaseline` Gradle task

### Acceptance criteria

- Baseline generation is deterministic.
- CI publishes the baseline report as an artifact.
- No refactoring PR begins without tests covering the behaviour being moved.
- Deviations from the baseline require an explicit explanation in the PR.

---

## Epic 0.2: Declare architectural invariants

**Goal:** Convert TramAI's runtime philosophy into enforceable rules.

### Required invariants

- Cancellation is never converted into a normal provider, tool, workflow, or persistence failure.
- Every runtime-owned scope, thread, process, and worker has an owner and close path.
- No production runtime behaviour depends on process-global mutable registration.
- Composition code may know implementations; core execution code depends on contracts.
- Stable APIs do not depend on internal implementation types.
- Audit/evidence ordering is preserved across refactoring.
- Security-sensitive failures use stable safe reason codes.
- Provider and store implementations satisfy shared contracts.
- No module dependency cycles are allowed.
- Example modules may depend on public modules; public modules must never depend on examples.
- Core modules must not depend on framework-specific integration modules.

### Deliverables

- `docs/architecture/0.6.0-architecture-principles.md`
- ADRs for runtime composition, cancellation, failure taxonomy, workflow registry, and module layering
- Architecture tests or Gradle verification tasks for each enforceable invariant

---

# Phase 1 — Correctness Hotfixes

This phase is intentionally completed before large decomposition work.

## Epic 1.1: Cancellation correctness

**Goal:** Make coroutine cancellation propagate consistently through every suspend boundary.

**Status: ✅ Complete — PRs #207, #209, #211–#214, and #216**

### Tasks

1. Introduce one shared cancellation rethrow helper in the lowest appropriate module.
2. Audit every `catch (Exception)` and `catch (Throwable)` in:
   - providers;
   - engine execution;
   - tool adapters;
   - workflow steps;
   - worker loops;
   - persistence implementations;
   - observers and exporters.
3. Preserve cancellation before wrapping, retrying, auditing as a normal failure, or falling back.
4. Ensure timeout exceptions intentionally mapped to domain timeouts remain distinguishable from parent-scope cancellation.
5. Add tests proving cancellation does not:
   - trigger provider fallback;
   - trigger provider retry;
   - convert to tool transient failure;
   - persist a normal failed result;
   - emit misleading failure evidence;
   - leave a child process running.
6. Add a static rule or architecture test that flags broad catches in suspend functions without an explicit cancellation branch.

### Acceptance criteria

- All provider adapters pass cancellation contract tests.
- All built-in workflow step families pass cancellation contract tests.
- Tool execution passes cancellation contract tests.
- Runtime shutdown does not wait for work that ignored cancellation.
- No cancellation is exposed as a retryable business failure.

---

## Epic 1.2: Safe error boundaries

> **Status:** ✅ Complete — tool failures (PR #219), provider HTTP/transport failures (PR #222), workflow-step/MCP/shell boundaries (PR #223), structured-output failures (PR #224), and persistence failures (PR #225) are implemented.

**Goal:** Separate internal diagnostic detail from public, model-visible, audit-visible, and telemetry-visible errors.

### Tasks

1. Define `SafeFailureCode` or domain-specific typed reason-code families. — **PR #219:** `ToolFailureCode`; **PR #222:** `ProviderFailureCode`; **PR #223:** `WorkflowStepFailureCode` for built-in external workflow steps; **PR #225:** `PersistenceFailureCode`. Approval and policy domains remain later slices.
2. Define four explicit error surfaces:
   - internal cause; — **PR #219:** `ToolFailureDiagnosticObserver`; **PR #222:** `ProviderFailureDiagnosticObserver`; **PR #223:** `WorkflowStepFailureDiagnosticObserver`; **PR #225:** `PersistenceFailureDiagnosticObserver` (all fail-open and diagnostic-only).
   - public caller message; — **PR #222:** fixed provider HTTP/transport messages; **PR #223:** fixed built-in workflow-step messages; **PR #225:** fixed persistence messages.
   - model-visible message; — **PR #219:** `ModelVisibleToolMessage.trusted(...)`.
   - audit/telemetry metadata. — **PR #222:** provider logs and telemetry expose fixed messages and trusted metadata only; **PR #225:** persistence raw detail remains diagnostic-observer-only while ordinary worker logs expose exception class names only. Approval surfaces remain.
3. Remove arbitrary exception messages from model-visible tool results. — **PR #219:** `ToolResult` retains its four 0.5.0 variants for exhaustive-`when` compatibility; `safeInvalidInput(...)`/`safePermanentFailure(...)` factories wrap validated or fixed text in the existing variants. Built-in engine and standalone paths never derive text from `Throwable.message`.
4. Review provider HTTP failure handling so response bodies are:
   - bounded;
   - sanitised;
   - disabled or redacted by default;
   - never copied wholesale into public exceptions. — **PR #222:** complete for provider HTTP failures; bounded previews are available only through `ProviderFailureDiagnosticObserver`.
5. Review debug logging of provider bodies and secret-related paths. — **PR #222:** complete for provider adapters; debug logs are metadata-only. **PR #225:** persistence worker logging emits exception class names only.
6. Centralize safe error sanitisation for shell, HTTP, MCP, tools, providers, persistence, and approvals. — **PR #222:** provider HTTP/transport helpers; **PR #223:** built-in HTTP, shell, MCP, Codex, and Hermes workflow-step helpers; **PR #225:** persistence boundaries. Approvals remain a later slice.
7. Add negative tests with tokens, prompts, paths, SQL fragments, command arguments, and malformed payloads. — **PR #219:** tool boundary; **PR #222:** provider HTTP, transport, adapter, and telemetry boundaries; **PR #224:** structured-output boundary; **PR #225:** persistence paths, SQL, payloads, observer behavior, cancellation, and worker failures. Approval surfaces remain.

### Acceptance criteria

- Sensitive fixtures never appear in public exceptions, model messages, normal logs, metrics, or exported evidence.
- Internal causes remain available to explicitly configured diagnostic sinks.
- Safe reason codes are stable and documented.
- Tests verify both redaction and diagnostic usefulness.

---

## Epic 1.3: Runtime lifecycle ownership

**Goal:** Ensure no convenience API creates an uncloseable runtime.

> **Status:** ✅ Complete — PR #226. `Tramai`/`SovereignTramai` are `AutoCloseable` and own one runtime/engine; `close()` cancels and joins engine-owned work (blocking calls, suspend invocations, streaming collections) via an internal lifecycle job; caller-supplied `job`/`scope` are never cancelled/joined; Spring closes via `destroyMethod`; resource ownership documented.

### Tasks

1. Make `Tramai` own one engine or one runtime session rather than constructing an unreachable engine per `create()` call.
2. Make lifecycle ownership visible through `AutoCloseable` or an equivalent explicit runtime contract.
3. Define whether service proxies remain valid after runtime closure.
4. Make repeated `close()` idempotent.
5. Document ownership of externally supplied providers, clients, stores, and executors.
6. Add leak tests for engine jobs, worker jobs, subprocesses, HTTP response streams, and shutdown hooks.
7. Ensure Spring manages lifecycle through bean destruction callbacks.

### Acceptance criteria

- Every engine can be closed through a public owner.
- Runtime jobs are cancelled and joined according to documented semantics.
- Repeated creation does not accidentally create independent hidden engines.
- Spring context shutdown leaves no TramAI-owned jobs or hooks active.

### Leak-test evidence matrix (roadmap task 6)

| Requirement | Existing proof |
|---|---|
| Engine jobs | PR #226 lifecycle tests: `close() cancels and joins` blocking/suspend/streaming engine-owned work (`blocking invocation in long suspension is cancelled and joined by close`, `self close from owned coroutine does not deadlock`, `self close from streaming owned coroutine does not deadlock`, `stream start racing close never hangs the collector`, `close does not deadlock when caller supplied its own job and scope`) |
| Worker jobs | Existing worker shutdown/cancellation tests in tramai-orchestration (`TramaiWorkerTest`, lease-drain and shutdown coverage from Epics 1.1/1.2) |
| Subprocesses | PR #216/#221 cancellation contract (`SubprocessCancellationContractTest` in tramai-orchestration) |
| HTTP response streams | Provider-level InputStream cleanup tests in tramai-openai `OpenAiProviderTest`: `stream closes response body after done marker`, `stream closes response body after malformed chunk`, `stream closes response body when collector stops after first token`, `mid stream io failure is retryable sanitized and observed`; plus #226 engine streaming lifecycle tests (`streaming collection suspended indefinitely is cancelled and cleaned up by close`, `mid-collection close terminates an in-flight stream`) and the springboot example E2E smoke test |
| Shutdown hooks | `TramaiWorkerTest`: `close deregisters the JVM shutdown hook and retains no reference` — proves the registered hook is absent from `Runtime` after close (`removeShutdownHook` returns false) and the worker retains no `Thread` reference; plus Spring `destroyMethod` close + context-shutdown tests and `repeated close is harmless` idempotency test in tramai-standalone |

---

## Epic 1.4: HTTP network-boundary correctness ✅

**Goal:** Remove unsupported security assumptions and strengthen outbound HTTP controls. **Complete (PR #227).**

### Tasks

1. ✅ Correct comments and documentation that imply one DNS lookup fully prevents rebinding.
2. ✅ Define application-level URL validation as defence-in-depth rather than the sole egress boundary.
3. ✅ Introduce a pluggable outbound-network policy or controlled transport abstraction.
4. ✅ Expose the actual connected-address validation capability where technically possible.
5. ✅ Require explicit allowlists for governed/sovereign HTTP steps.
6. ✅ Document deployment-level firewall, proxy, service-mesh, or network-policy requirements.
7. ✅ Add tests for:
   - loopback;
   - RFC1918/private networks;
   - link-local;
   - IPv4 alternative encodings;
   - IPv6 local ranges;
   - redirects;
   - user-info confusion;
   - DNS changes between validation and connection where test infrastructure permits.

### Acceptance criteria

- ✅ No documentation claims complete DNS-rebinding prevention from pre-resolution alone.
- ✅ Governed profiles can require an explicit outbound transport policy.
- ✅ Redirect behaviour remains deny-by-default unless explicitly configured.

---

# Phase 2 — Runtime Composition Model

## Epic 2.1: Introduce immutable runtime component groups

**Status: ✅ Complete — PR #228**

**Goal:** Replace constructor and builder explosion with cohesive, inspectable runtime configuration.

### Proposed component model

```kotlin
internal data class EngineComponents(
    val providers: ProviderComponents,
    val tools: ToolComponents,
    val security: SecurityComponents,
    val approvals: ApprovalComponents,
    val persistence: PersistenceComponents,
    val observation: ObservationComponents,
    val execution: ExecutionComponents,
)
```

The exact API may differ, but each group must have one responsibility and explicit invariants.

### Tasks

1. ✅ Introduce immutable component groups without changing public builder APIs initially.
2. ✅ Move all all-or-none composition validation into component constructors or factories.
3. ✅ Distinguish required components, optional capabilities, and no-op implementations.
4. ✅ Replace nullable dependency clusters with explicit capability types where possible.
5. ✅ Document thread-safety and lifecycle ownership for every component group.
6. ✅ Ensure component snapshots are immutable after runtime construction.

### Acceptance criteria

- ✅ `TramaiEngine` and its main execution coordinators receive cohesive component groups rather than dozens of unrelated dependencies.
- ✅ Invalid partial approval, policy, persistence, or evidence configurations fail during build.
- ✅ Runtime code does not discover configuration dynamically.

---

## Epic 2.2: Create one provider-routing plan

> **Status:** ✅ Complete — PR #229 (refactor(routing): introduce authoritative provider routing plan).

**Goal:** Eliminate shadow configuration across standalone, sovereign, Spring, and provider-registry builders.

### Implemented model

`ProviderRoutingPlan` is the single frozen source of configured provider routing:

```kotlin
@JvmInline value class ProviderId(val value: String)
@JvmInline value class ModelId(val value: String)
data class PlannedProviderRoute(val providerId: ProviderId, val effectiveModelId: ModelId)

class ProviderRoutingPlan private constructor(
    val providers: Map<ProviderId, ModelProvider>,
    val routes: Map<ModelId, List<PlannedProviderRoute>>,
    val defaultProvider: ProviderId?,
)
```

`ProviderRegistry` is a compatibility façade over the plan (existing public API preserved; additive routing-plan APIs introduced — `ProviderRoutingPlan`, `ProviderId`/`ModelId`, `PlannedProviderRoute`, `ProviderRegistry.from(...)`, `ProviderRegistry.routingPlan`, `Tramai.Builder.buildRoutingPlan()`). The engine freezes the plan into `ProviderComponents`; standalone composes through the plan builder; sovereign validates the same plan via `SovereignRoutingValidationPolicy` (no shadow maps); Spring resolves bean-over-property precedence before the plan builder so the canonical model never observes a duplicate.

### Tasks

1. ✅ Add typed provider and model identifiers or validated value classes (`ProviderId`, `ModelId`).
2. ✅ Make duplicate provider registration fail rather than silently replace (fail-fast at plan build).
3. ✅ Validate blank names, unknown providers, duplicate routes, invalid defaults, and degenerate route structures during construction. (Deliberately NOT recursive fallback routing/graph-cycle detection: `fallbackProvider()` keeps the same effective model on another provider and is not a self-loop.)
4. ✅ Expose an immutable routing-plan snapshot for validation and evidence generation.
5. ✅ Apply additional sovereign constraints as validation policies over the same plan (`SovereignRoutingValidationPolicy`).
6. ✅ Make Spring construct the same routing plan rather than reimplementing route logic (property providers + beans merged into one unique set; no Spring-side route validator).
7. ✅ Remove sovereign builder shadow maps after migration (`registeredProviders`, `primaryModelRoutes`, `fallbackRoutes`, `defaultProviderName`, `FallbackRoute` deleted).

### Acceptance criteria

- ✅ One authoritative object represents configured provider routing.
- ✅ Standalone and sovereign modes differ through validation policy, not duplicated state.
- ✅ Evidence generation and runtime execution consume the same immutable plan.
- ✅ Invalid routes fail before service creation.

---

# Phase 3 — Engine Decomposition

## Epic 3.1: Characterize the execution pipeline

> **Status: ✅ Complete — PR #230** (`test(engine): characterize execution pipeline semantics`)

**Goal:** Protect exact behaviour before moving code.

### Deliverable

A deterministic execution-trace test fixture that records ordered semantic events and compares them with approved traces, under `tramai-engine/src/test/kotlin/dev/tramai/engine/characterization/`:

- `ExecutionTrace.kt` / `ExecutionTraceSink.kt` — semantic event model (`TraceEvent(type, attributes)`) written by recording test doubles.
- `ExecutionTraceFixture.kt` — builds a `TramaiEngine` with recording doubles for observer, memory, cache, policy + audit, model registry, DLP, tool registry, approval collaborators, and streaming provider; fixed values (`conversation-1`, `logical-model`, `primary`, `call-1`) and `id=*` normalization for generated identifiers.
- Approved trace fixtures under `tramai-engine/src/test/resources/characterization/` compared via `containsExactlyElementsOf` — no snapshot-library dependency.
- Scenario suites: `ExecutionPipelineCharacterizationTest` (1-7), `ExecutionPipelineResilienceCharacterizationTest` (8-14), `ExecutionPipelineApprovalStreamingCharacterizationTest` (15-20).

### Required characterization coverage

- ✅ Request and prompt construction sequencing (scenarios 1, 7 — freezes ordering, not prompt content)
- ✅ Memory injection and persistence (scenario 2)
- ✅ Cache lookup and provenance checks (scenarios 3, 4)
- ✅ Policy ordering (scenarios 5, 6)
- ✅ Provider resolution (scenario 7)
- ✅ Model registry authorization (scenario 7)
- ✅ Retry and fallback ordering (scenarios 8, 9)
- ✅ Circuit breaker transitions (scenario 10)
- ✅ DLP inspection and sanitisation (scenarios 12, 17)
- ✅ Structured-output repair retries (scenario 11)
- ✅ Tool exposure policy (scenarios 12, 13)
- ✅ Tool execution policy (scenarios 12, 14)
- ✅ Tool approval suspension (scenario 15)
- ✅ Tool reinjection filtering (scenario 17)
- ✅ Approval resume and replay (scenario 16)
- ✅ Observer callbacks (all scenarios — provider.start/success/failure/complete)
- ✅ Audit/evidence ordering (scenarios 15, 16, 17)
- ✅ Cancellation during provider execution (scenario 20 — trace-level; deep contracts owned by the dedicated cancellation suites; timeout behaviour is covered by the dedicated timeout contract tests, not this suite)
- ✅ Streaming startup and terminal behaviour (scenarios 18, 19)

### Ongoing requirement for extraction PRs

- Each extraction PR proves trace equivalence for affected scenarios.
- Security-sensitive ordering is tested directly, not inferred from final output.

---

## Epic 3.2: Extract operation planning

> **Status: ✅ Complete — PR #232** (`refactor(engine): extract operation planning`)

**Goal:** Separate reflection and operation-definition work from runtime execution.

### Components

- `ServiceDefinitionCompiler` — service validation, `@SystemPrompt` extraction, method enumeration
- `OperationDefinitionCompiler` — authoritative operation reflection/metadata compiler; `OperationDefinition.create()` is a public compatibility façade delegating to it
- `OperationExecutionPlan` — internal immutable plan (definition + fingerprint + service/method identity)
- `OperationFingerprintFactory` — canonical cache fingerprint, byte-identical to pre-extraction

### Tasks

1. ✅ Move service and operation metadata compilation out of the engine file (`tramai-engine/.../planning/`).
2. ✅ Make operation plans immutable (`OperationExecutionPlan` internal data class).
3. ⏸️ Cache safe reusable metadata — deferred: no process-global reflection cache per 0.6.0 goals; engine-scoped compiler instance only. Revisit after benchmark evidence.
4. ✅ Keep reflection failures explicit and deterministic (compiler throws `ConfigurationException`/`IllegalArgumentException` identically to pre-extraction).
5. ✅ Preserve Java and Kotlin interop behaviour (Java fixture `JavaPlanningService` + Kotlin service tests through the compiler).

### Merge gate

- 20 characterization traces byte-identical (no `.trace` changes).
- `OperationDefinition` public API unchanged (`:tramai-engine:apiCheck` clean).

---

## Epic 3.3: Extract provider execution

> **Status: ✅ Complete — PR #233** (`refactor(engine): extract provider execution`)

**Goal:** Give routing, retries, fallbacks, circuit breaking, authorization, and provider observation one cohesive owner.

### Components

- `ProviderExecutionCoordinator` — route-level state machine: `BEFORE_PROVIDER_RESOLUTION` gate, candidate resolution, circuit-breaker `beforeCall`, circuit-open fallback transition, fallback gates, route sequencing. Owner of route ordering; delegates one-provider attempts to the executor.
- `ProviderAttemptExecutor` — one provider attempt and its observation lifecycle: request interceptor → start observation → route-selected event → model authorization → `BEFORE_PROVIDER_INVOCATION` gate → provider invocation → response interceptor → provider-output DLP gate → `onProviderResponse`. Cancellation → `completeCancellation` exactly once; DLP failure is not a provider failure; success transfers observation ownership to the caller.
- `ProviderRetryPolicy` — typed `ProviderRetryDecision.Retry/Stop`; owns retryable classification, attempt exhaustion, Retry-After, backoff/jitter, delay-source classification. No delay, observation, circuit-breaker, or event side effects.
- `ProviderFallbackPolicy` — typed `ProviderFallbackDecision.Continue/Stop` + `ProviderFallbackReason`; maps enum back to legacy event strings (`provider-failure`, `circuit-breaker-open`).
- `ProviderAuthorizationService` — model-registry authorization only; observation lifecycle stays in the executor.

### Tasks

1. ✅ Move non-streaming provider execution out of `TramaiInvocationHandler` into `tramai-engine/.../provider/`.
2. ✅ Replace retry/fallback boolean branches with typed decisions.
3. ✅ Keep cancellation entirely outside retry/fallback classification and circuit-breaker failure recording.
4. ✅ Preserve the observation-ownership semantic on successful calls (observation stays alive for tool/structured processing).
5. ✅ Keep tool exposure as an injected `ProviderRouteGate` (replaced by `ToolExposureCoordinator` in Epic 3.4).
6. ✅ Add dedicated component tests (6 files, 27 tests) constructing provider components without tools, approvals, memory, or structured output.
7. ⏸️ Streaming execution (`executeStreaming`, `executeStreamingRoute`, `collectStreamingRoute`, chunk state, backpressure, streaming terminal semantics) stays in place — reserved for `StreamingExecutionCoordinator` in Epic 3.6.

### Merge gate

- 20 characterization traces byte-for-byte identical (no `.trace` changes).
- `tramai-engine.api` unchanged (`:tramai-engine:apiCheck` clean).
- `verifyCancellationSafety` no new critical/high findings.
- `verifyPr -PchangeClass=runtime-behaviour` green.

---

## Epic 3.4: Extract tool execution — ✅ Complete

**Goal:** Give tool exposure, authorization, execution, retry, filtering, DLP, formatting, and reinjection explicit boundaries.

**Status:** Shipped in PR #234 (2026-08-16, merge commit `8a14027f`). All acceptance criteria met; 20/20 characterization traces byte-identical; `tramai-engine.api` unchanged.

### Shipped components (all internal, `dev.tramai.engine.tool`)

- `ToolExposureCoordinator` — ordered tool-definition exposure, `BEFORE_TOOL_EXPOSURE`, unknown-tool semantics preserved
- `ToolAuthorizationCoordinator` — pure typed `ToolAuthorizationDecision` (Allow/Deny/RequireApproval) from `BEFORE_TOOL_EXECUTION`
- `ToolRetryPolicy` — retryability ≠ idempotency, structurally explicit (adds a sixth component to the roadmap's five)
- `ToolInvocationExecutor` — authorize → gate → attempt → classify → retry → terminal result; owns the `ToolApprovalGate` seam
- `ToolResultSanitizer` — format + DLP/size filtering + cross-boundary detection + audit consistency
- `ToolReinjectionCoordinator` — batch/one-shot/resume reinjection with preserved ordering

### Acceptance criteria

- ✅ Tool exposure and tool execution are independently testable (40 component tests incl. 7-point mutation-sensitive cancellation contract)
- ✅ Idempotency and retryability are distinct (`ToolRetryPolicy` decision matrix)
- ✅ Model-visible tool messages use safe typed failures
- ✅ DLP and size limits remain fail-closed where configured
- ✅ Tool evidence remains ordered and complete

### Non-goals preserved

Streaming extraction (Epic 3.6) and tool-loop orchestration (Epic 3.7) untouched. `ProviderRouteGate` kept; `ToolExposureCoordinator` wired into it at composition time.

---

## Epic 3.5: Extract approval suspension and resume

**Status:** ✅ COMPLETE — PR #235 (merged `b495bd22`, 2026-08-17)

**Goal:** Isolate approval state transitions from general invocation dispatch.

### Delivered components

- `ApprovalSuspensionCoordinator` (implements `ToolApprovalGate`)
- `ApprovalResumeCoordinator`
- `ContinuationClaimService`
- `ReplayAuthorizationService`
- `ResumeOperationRegistry`
- `ClaimedResumeExecutor` (temporary bridge seam into remaining invocation machinery)

### Acceptance criteria — met

- ✅ Suspension and resume each have a clear state-transition model.
- ✅ Store calls, token validation, version checks, claim, execution, completion, and cleanup order are explicit (recorded observable sequence asserted in `ApprovalResumeCoordinatorTest`).
- ✅ Every terminal and retryable failure has a documented state effect (uncertain-outcome marker; CE never converted to uncertain outcome).
- ✅ Replay tests remain deterministic across restart scenarios; 20/20 characterization traces byte-identical.

### Verification

- `verifyCancellationSafety`: 294/294, zero new critical/high; two genuine CE-swallowing `runCatching` defects found and fixed during the audit.
- `:tramai-engine:apiCheck`: zero public API diff (`tramai-engine.api` unchanged; `ResumeOperationRegistry` internal).
- 80/80 approval component tests; full engine suite green.
- `approval/` has zero `TramaiInvocationHandler` references.

---

## Epic 3.6: Extract structured response, memory, cache, budget, and streaming coordinators

Status: ✅ COMPLETE — 3.6a via PR #236 (merged `37a5ef55`, 2026-08-17); 3.6b via PR #237 (merged `6fa4243e`, 2026-08-17); 3.6c streaming via PR #239 (merged `ac398604`, 2026-08-18).

### Components

- ✅ `StructuredResponseCoordinator` — 3.6b / #237
- ✅ `ConversationMemoryCoordinator` — 3.6a / #236
- ✅ `OperationCacheCoordinator` — 3.6a / #236
- ✅ `TokenBudgetCoordinator` + `TokenBudgetTracker` — 3.6a / #236
- ✅ `StreamingExecutionCoordinator` — 3.6c / #239 (merged `ac398604`, 2026-08-18)

### Acceptance criteria

- Each component can be tested independently.
- The top-level invocation coordinator contains only execution sequencing.
- Component-specific state is not stored in unrelated coordinators.

---

## Epic 3.7: Reduce the invocation handler to an adapter

**Status:** ✅ COMPLETE — PR #245 (2026-08-18, open at writing; expected merge
squash). The numbers 240–244 were consumed by the close-race saga: #240 (test
hardening, `5578a1a5`), #241 (CI containment, `c190d0fc`), #239 (streaming
extraction, `ac398604`), #243 (close() failure surfacing, `7156d296`), #244
(invocation-registry lock fix, `670055ae` — root cause of the recurring CI
hang), #242 (lifecycle-dispatcher contracts, `317af2da`).

**Target responsibility:**

```text
resolve method
resolve immutable operation plan
adapt JVM proxy/suspend invocation
create execution context
delegate to invocation coordinator
return result
```

### Maintainability target

- The handler should no longer own provider, tool, approval, DLP, cache, memory, or streaming algorithms.
- Direct dependencies should be reduced to a small number of cohesive collaborators.
- No function in the handler should represent a complete business/runtime subsystem.

### Acceptance criteria

- `TramaiInvocationHandler` is understandable in one review session.
- Execution behaviour is located by responsibility, not by searching a multi-thousand-line file.
- No public API regression is introduced unintentionally.
- ✅ `TramaiInvocationHandler` is a thin JVM adapter (no execution algorithms, no policy/DLP enforcement, not a `ClaimedResumeExecutor`).
- ✅ `InvocationExecutionCoordinator` sequences return-kind dispatch and builds the execution graph; it delegates DLP to `ProviderResponseDlpSanitizer` and the post-claim approval path to `ClaimedResumeExecutionCoordinator`.
- ✅ 20/20 characterization traces byte-identical; 589+ engine tests green; zero public API diff.

---

# Phase 3 — ✅ COMPLETE (Epic 3.1–3.7, PRs #230–#245)

Phase 3 (invocation-layer decomposition) is complete: the invocation handler,
structured response, memory, cache, budget, streaming, tool-loop, DLP, and
claimed-resume approval execution all live in dedicated coordinators under
`tramai-engine/src/main/kotlin/dev/tramai/engine/{invocation,streaming,structured,tool,provider}`.

# Phase 4 — Workflow and Worker Decomposition

## Epic 4.1: Replace concrete-type central dispatch

**Status:** ✅ COMPLETE — PR #246 (2026-08-18, open at writing; expected merge squash).

**Goal:** Make workflow steps polymorphic through a shared execution request and result contract.

### Delivered contract

```kotlin
internal sealed interface InternalWorkflowStep<S> {
    val name: String
    val suspensionMode: WorkflowStepSuspensionMode   // NONE | TOP_LEVEL_CHECKPOINT
    suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S>
}
```

- `Workflow.executeStep()` is now the common wrapper only: step budget → onStepStarted → `step.execute(request)` → cancellation rethrow → sanitisation → onStepFailed/onStepCompleted. The 12-arm concrete `when` is gone.
- Runtime collaborators are grouped in `WorkflowStepExecutionServices`; nested steps re-enter the wrapper via the `executeNestedSteps` callback (same context/observer/counter, `persistenceSession = null`, `topLevelStepIndex = null`, no inherited resume metadata).
- Suspension is explicit via `suspensionMode`; only `DelayWorkflowStep` is `TOP_LEVEL_CHECKPOINT`.
- Nested checkpoint-suspending steps are rejected at `build()` (previously a runtime error).
- Hardened HTTP/Shell/Hermes/Codex/MCP execution algorithms untouched — thin contract adapters only.

### Tasks

1. ✅ Each built-in step executes through the step contract.
2. ✅ Central `when` over concrete step types removed from runtime execution.
3. ✅ Observation, step counting, persistence, and error handling remain in one wrapper.
4. ✅ `WorkflowStepSuspensionMode` models which steps can suspend/checkpoint.
5. ✅ Nested suspension semantics explicit + build-time validation.

### Acceptance criteria

- ✅ Adding a new built-in step does not require editing a central type-dispatch list.
- ✅ Shared execution rules remain consistent across all steps.
- ✅ Unsupported nested suspension fails at validation time.

### Non-goals (deferred to #247+)

- Splitting `Workflow.kt` into runner/builder/step-executor files (Epic 4.2) — ✅ done in #249.
- Concrete dispatch in replay descriptors / canonical rendering / static validation (definition-level, deliberately untouched).
- Worker bindings, replay/retry semantics, public plugin API.

---

## Epic 4.2: Split `Workflow.kt`

✅ **COMPLETE — PR #249**

### Target files/components

- `Workflow.kt` — immutable public definition (1923 → ~200 lines; `workflow()` + `DEFAULT_WORKFLOW_DEFINITION_VERSION` retained for WorkflowKt JVM ABI)
- `WorkflowRunner.kt` — run and resume coordination (lifecycle, top-level/nested iteration, checkpoint-after-step, completion/suspension/failure/cancellation)
- `WorkflowStepExecutor.kt` — common step wrapper (single shared wrapper; frozen ordering; no concrete dispatch)
- `WorkflowBuilder.kt` — DSL + build validation (duplicate names, static command policies, nested-suspension rejection)
- `WorkflowObservation.kt` — observer contracts and event model
- `WorkflowErrors.kt` — exception taxonomy
- `WorkflowDefinitionCompatibility.kt` — canonical rendering, digest, metadata (verbatim move; digest frozen by golden test)
- `WorkflowPersistenceSession.kt` — checkpoint/lease/abort session
- `WorkflowBranchExecutor.kt` — branch selection + nested routing through shared wrapper
- `WorkflowParallelExecutor.kt` — bounded async execution
- `WorkflowDelayCoordinator.kt` — delay suspension/checkpoint mechanics

### Acceptance criteria

- ✅ Files align with one primary reason to change.
- ✅ Public DSL remains source-compatible — `apiCheck` reports ZERO public API diff.
- ✅ Definition compatibility remains deterministic — golden digest `45936b12…` unchanged across the split.
- ✅ Persistence checkpoints retain backward compatibility — metadata keys and checkpoint format untouched.

### Verification

- `:tramai-orchestration:test` + `apiCheck` + `verifyCancellationSafety` + `verifyChangePolicy` + `verifyMaintainabilityBaseline` all green.
- `WorkflowDecompositionArchitectureTest` (6 tests, mutation-verified): facade declares no orchestration methods; runner owns run/resume; step executor invokes only the polymorphic contract; no concrete-step refs in runner or executor bytecode; branch nested execution crosses the shared wrapper.
- `WorkflowStepExecutionArchitectureTest` updated to scan `WorkflowStepExecutor` (wrapper moved out of `Workflow`).
- `WorkflowDefinitionDigestGoldenTest` freezes digest + delay metadata keys.

---

## Epic 4.3: Remove global worker workflow bindings

**Goal:** Make workflow registration explicit, instance-scoped, and type-safe. **Complete (PR #250).**

### Tasks

1. ✅ Introduce `WorkflowBindingRegistry` as an injected runtime component.
2. ✅ Key bindings by a typed identity including name, definition version, and state/result metadata.
3. ✅ Remove unchecked retrieval based only on workflow name.
4. ✅ Remove implicit registration caused by executing a workflow.
5. ✅ Make worker startup validate all required bindings.
6. ✅ Define duplicate and conflicting registration behaviour.
7. ✅ Add isolation tests for multiple runtimes, application contexts, tenants, and parallel tests.

### Acceptance criteria

- ✅ No process-global workflow registry remains.
- ✅ Two runtimes can register workflows with the same name without interference.
- ✅ Type mismatches fail during registration rather than through unchecked cast behaviour.

---

## Epic 4.4: Worker state-machine decomposition

**Goal:** Split polling, leasing, heartbeat, execution, renewal, shutdown, and recovery responsibilities. **Complete (PR #251).**

### Components (all internal, one mutable-state owner each)

- `WorkerLifecycleController` — root `SupervisorJob`/scope, startup sequence, crash/shutdown delegation
- `CheckpointPoller` — enumeration, ordering, candidate filtering, poll failure boundary
- `LeaseCoordinator` — claim/contention/release + observer events
- `LeaseRenewalLoop` — per-execution renewal cadence
- `WorkflowExecutionSupervisor` — active-execution registry, binding resolution, execution machinery
- `WorkerHeartbeatPublisher` — registration + heartbeat
- `WorkerShutdownCoordinator` — frozen graceful-shutdown sequence + shutdown state
- `WorkflowRecoveryCoordinator` — recovery state machine (unknown attempts, retry approvals)

### Acceptance criteria

- ✅ Worker lifecycle states are explicit (one owner per mutable state).
- ✅ Start, repeated start, graceful shutdown, crash, takeover, and timeout remain covered by the existing worker/recovery suites (unchanged, green).
- ✅ Every launched job has one owner; exactly one worker root coroutine lifecycle exists (architecture-guarded).
- ✅ Shutdown-hook registration/removal is deterministic (frozen sequence preserved verbatim, coordinator-owned).
- ⏳ Wall-clock duration measurement uses an injected or monotonic time source — **moved to section 4.15 (Time and concurrency determinism)**: an explicit non-goal of #251, so this criterion is not part of Epic 4.4's completion. Epic 4.4 is complete without it.
- ✅ Zero public API diff; checkpoint/persistence schema unchanged; cancellation safety 295=295.

---

# Phase 5 — Failure, Event, and Telemetry Contracts

## Epic 5.1: Separate idempotency, retryability, and replayability

**Goal:** Model three independent dimensions correctly.

- **Idempotency:** Is repeating the side effect safe?
- **Retryability:** Is the failure likely to succeed if attempted again?
- **Replayability:** Can the workflow step be reconstructed and replayed after interruption?

### Tasks

1. Add typed tool failure categories.
2. Add typed provider retry decisions.
3. Add typed workflow replay descriptors.
4. Make automatic retry require both retryable failure and safe repetition.
5. Document non-idempotent transient failures as manual-recovery cases where applicable.
6. Add tests covering every combination.

### Acceptance criteria

- No code infers retryability solely from idempotency.
- No code infers replayability solely from idempotency.
- Failure types expose stable safe reason codes.

### Completion (#253)

- ✅ **Tool retryability ≠ idempotency (structural):** `ToolInvocationExecutor` classifies every generic tool failure as `TransientFailure` regardless of `tool.idempotent`; the attempt budget is uniform; `ToolRetryPolicy` alone decides Retry vs Stop from `retryable × repeat-safe × attempts-remaining`. `TramaiTool.idempotent` KDoc now declares repetition safety only.
- ✅ **Provider retryability remains failure-derived:** `ProviderRetryDecision`/`ProviderRetryPolicy` retained; retryability comes from `TimeoutException`/`ProviderException.retryable` (no redesign).
- ✅ **Workflow replay model is two-dimensional:** `WorkflowStepReplayDescriptor` = `WorkflowStepReplayability` × `WorkflowStepRepetitionSafety` × idempotency key. Steps reclassified (shell/hermes/codex/mcp/parallel = REPLAYABLE+UNSAFE; plugin = NON_REPLAYABLE+UNSAFE; HTTP POST/PATCH without key = REPLAYABLE+UNSAFE).
- ✅ **One decision owner:** `WorkflowReplayDecisionPolicy` (pure, typed `Replay`/`RequireRecovery(reason)`); `WorkflowRecoveryCoordinator` owns only state transitions/persistence; recovery controller and execution supervisor consume the descriptor.
- ✅ **Persistence compatibility preserved:** legacy `ReplayPolicy` remains schema-v1 wire format, confined to the codec/JDBC boundary + retained public aiStep DSL overloads (architecture-guarded); `REPLAYABLE+UNSAFE → NON_REPLAYABLE`, legacy `NON_REPLAYABLE → NON_REPLAYABLE+UNSAFE`. Zero checkpoint/attempt-schema change, existing records readable.
- ✅ **Tests:** full 11-case decision matrix (incl. the two killer rows: replayable+unsafe → manual recovery; non-replayable+idempotent → manual recovery), tool retry matrix, non-idempotent-never-executes-twice executor test, ReplayPolicy boundary arch test — all mutation-verified both directions.
- ✅ **Regression surface:** recovery contract, durable file/JDBC recovery, worker takeover (incl. the two HTTP replay tests), supervisor, codec, and store-contract suites all green; existing #215–#218 semantics unchanged.

---

## Epic 5.2: Create a typed runtime event catalogue

**Goal:** Prevent drift in event names, metric names, evidence families, and attribute keys.

### Tasks

1. Define typed event identifiers for engine, workflow, worker, approval, policy, tool, routing, and evidence events.
2. Define typed or centrally registered attribute keys.
3. Associate each event with:
   - allowed attributes;
   - sensitivity classification;
   - audit/evidence eligibility;
   - metric/span mapping;
   - fail-open/fail-closed behaviour.
4. Update exporters and observers to consume the catalogue.
5. Add a verifier that rejects unknown or incompatible attributes.
6. Generate reference documentation from the catalogue.

### Acceptance criteria

- No duplicated literal event names in production code outside the catalogue.
- Attribute allowlists are generated from the same definitions used at runtime.
- Evidence exporters and observability adapters agree on event semantics.

### Completion (#254)

- ✅ **Typed catalogue in `tramai-core`** (`dev.tramai.core.observation.event`): `RuntimeEventCatalogue` (every event identifier with domain, sensitivity, audit/evidence eligibility, allowed + required attributes, metric mapping), typed `RuntimeAttributeKey<T>` (one canonical value type per key), `RuntimeMetrics` (20 descriptors), `RuntimeEvents` (compile-time refs), `RuntimeEvent.of(...)` builder that rejects out-of-schema keys, missing required attributes, and wrong value types at construction. Init fails fast on duplicates/type conflicts.
- ✅ **Repository-wide migration:** engine (provider route, circuit, streaming retry, DLP, approval replay, token budget), workflow (security, delay, http, mcp, shell, codex, hermes, checkpoint/lease/suspended events), worker observer (18 events), workflow observer (step events, dynamic user context under the declared `DynamicAttributeNamespaces.WORKFLOW_CONTEXT`), operation observer (engine events, `gen_ai.usage.*` keys); plus scheduler (`delay_wakeup.unregistered`, schedule context keys), server/platform run-store protocol (`tramai.workflow.*`/`tramai.step.*` SSE events), and sovereign ops outbox metrics/tags. Event names and metric names preserved byte-for-byte; `OperationObservation.onEngineEvent(RuntimeEvent)` is an additive overload delegating to the legacy form — no public API break.
- ✅ **Fail-closed architecture verifier (two layers):** ASM LDC bytecode scan of the four core modules' built jars (catches `const` inlining) plus a repository-wide source scan of every `tramai-*` module. Only exact declared Spring configuration-property literals are allowed; a catalogue identifier literal and any config-namespace lookalike both fail closed (production must reference `RuntimeEvents.X`/`RuntimeMetrics.X`/`RuntimeAttributes.X`). Mutation-verified on both layers (injected literal → red); the test task declares every module's production Kotlin files as inputs (no UP-TO-DATE skip) without consuming task outputs. The guard found and drove the migration of 40+ real un-catalogued literals across all modules.
- ✅ **Reference documentation generated from the catalogue** (`docs/reference/runtime-event-catalogue.md`) with a drift-check test (`RuntimeEventCatalogueDocumentationTest`) asserting the committed doc equals renderer output.
- ✅ **Review round resolved (multi-agent review REQUEST CHANGES → all findings fixed):** P1-1 `AgentCliSupport` now emits exclusively through `RuntimeEvent.of` with typed catalogue definitions (composition-by-prefix removed; `tramai.workflow.{codex,hermes}.completed` extended to declare `response_length`/`duration_ms`/`exit_code`); P1-2 coverage made repository-wide (above); P2-1 same-name type-conflict check compares `valueType` (unit-tested); P2-2 builder enforces canonical value types at runtime against generic erasure (unsafe-cast regression test); `RuntimeEvent.of(definition)` documented as intentional third-party extension.
- ✅ **Value-type correction flagged for review:** size/attempt/route-index/prompt/response/duration/exit-code attribute values are now `Long` (catalogue-mandated canonical types) instead of the legacy mixed `Int`; observer tests updated accordingly.
- ✅ **Gate:** full 4-module test suite, 4 apiChecks, `verifyCancellationSafety`, `verifyWorkflowApiStabilityBoundary`, `verifyChangePolicy`, `verifyMaintainabilityBaseline` (MQ-0004 deviation updated for the catalogue singletons), and `verifyPr` all green.

---

## Epic 5.3: Define observer, audit, and evidence failure policy

**Goal:** Make secondary-system failures predictable.

### Required matrix

For each extension point, document whether failure is:

- fail-closed;
- fail-open with diagnostic event;
- retried;
- buffered through an outbox;
- terminal for the operation;
- ignored only for explicitly non-authoritative telemetry.

### Acceptance criteria

- Authoritative audit failure cannot be accidentally swallowed.
- Non-authoritative metrics cannot accidentally fail a business operation unless explicitly configured.
- Tests cover observer exceptions at every lifecycle point.
- Exactly-once or at-least-once claims are stated precisely.

---

# Phase 6 — Provider and Integration Architecture

## Epic 6.1: Provider Technology Compatibility Kit ✅

**Goal:** Make every provider adapter satisfy the same observable contract.

**Status: complete (PR #257).** Contract implementation lives in
`tramai-testing` test fixtures (`ProviderTck` + `StubHttpClient` +
`ProviderHttpFixtures`); all eight published providers have green runners
pinned in `ProviderTckEnrollmentArchitectureTest`; intentional deviations are
documented in `docs/reference/provider-compatibility-contract.md`. The TCK
forced three production fixes: Anthropic tool translation, Ollama `VISION` +
`STREAMING` pinned via a protocol-aware `VisionSpec` (base64 image payload
without a MIME marker, per the Ollama wire protocol), and Bedrock
client-ownership + real incremental streaming through an internal client
factory seam. No shared transport
abstraction was introduced — Epic 6.2 owns transport consolidation.

### Contract areas

- Provider identity
- Timeout propagation
- Cancellation propagation
- Retryable HTTP status mapping
- `Retry-After` handling
- Transport failure mapping
- Safe error redaction
- Tool-call parsing
- Structured-output capability declaration
- Vision/multimodal mapping
- Streaming token ordering
- Streaming completion semantics
- Usage and reasoning-token extraction
- Empty/malformed response behaviour
- Resource closure

### Tasks

1. Create reusable fake HTTP transports and fixtures.
2. Create an abstract provider contract test suite.
3. Run it against OpenAI-compatible, OpenAI, Azure OpenAI, Anthropic, Ollama, Gemini, Bedrock, and DeepSeek adapters as applicable.
4. Document intentional provider-specific deviations.
5. Require the TCK for every future provider.

### Acceptance criteria

- Every published provider passes the common contract.
- Provider-specific tests focus on protocol differences rather than repeating generic assertions.
- Cancellation, redaction, timeout, and streaming behaviour cannot silently drift.

---

## Epic 6.2: Shared provider transport utilities ✅

**Goal:** Remove repeated low-level HTTP and streaming concerns without hiding provider protocol differences.

**Status: complete (PR #258).** `dev.tramai.core.provider.transport` in
`tramai-core` centralises the transport invariants that are genuinely
identical between adapters:

- `parseRetryAfterMillis(value, clock)` — deterministic `Retry-After`
  parsing with an injected `Clock` (default `systemUTC`), removing the hidden
  `System.currentTimeMillis()` wall-clock dependency.
- `rejectedProviderHttpResponse(...)` — one primitive for the rejected-
  response lifecycle: bounded 8 KiB body read, deterministic closure,
  debug-metadata logging, fail-open diagnostic observer delivery, and
  `Retry-After` propagation. The caller decides throw vs `StreamChunk.Error`.
- `providerJsonRequest(uri, request, body)` — URI + JSON `Content-Type` +
  normalized timeout + POST framing; authentication and provider-protocol
  headers remain adapter-owned so the wire contract stays visible in each
  provider's source.
- `readSseDataPayload(reader)` / `sseDataPayload(line)` / `sseEventName(line)`
  — SSE framing only (prefix stripping, field skipping, EOF); payload
  interpretation (`[DONE]`, deltas, Anthropic event semantics, Gemini
  candidates) stays in the adapters.

Migrated in order: OpenAI-compatible (DeepSeek benefits via delegation),
Azure OpenAI, Anthropic, Gemini, Ollama. Bedrock intentionally keeps its
AWS SDK transport. The #257 TCK is the regression oracle and was not
weakened; all eight provider runners stay green. Deliberately NOT extracted
(in line with the guardrail): a Jackson-typed successful-body helper
(`tramai-core` has no Jackson dependency; stream closure is stdlib `use{}`),
usage-metrics adapters, and any universal provider transport abstraction.

### Candidate utilities

- Safe request builder ✅ (as `providerJsonRequest`)
- Timeout application ✅ (pre-existing, reused)
- Retry-after parser using an injected clock ✅
- Bounded response reader ✅ (pre-existing, reused)
- Safe provider error decoder ✅ (pre-existing, reused)
- SSE line parser primitives ✅
- JSON response guards — skipped (see above)
- Resource-closing helpers ✅ (stdlib `use` at call sites)
- Common usage-metrics model adapters — skipped (not mechanically equivalent)

### Guardrail

Do not create a universal provider abstraction that forces unlike protocols into one opaque implementation. Share transport invariants, not provider business semantics.

---

## Epic 6.3: Modularize Spring integration

### Target modules

- `tramai-spring-core`
- `tramai-spring-provider-openai`
- `tramai-spring-provider-anthropic`
- `tramai-spring-provider-ollama`
- provider-specific modules as needed
- `tramai-spring-secrets-file`
- `tramai-spring-secrets-vault`
- `tramai-spring-secrets-aws`

The final module names may differ, but central Spring integration must not require every concrete provider and secret backend.

### Tasks

1. Move generic bean discovery and runtime assembly into Spring core.
2. Move concrete provider property classes and factories into provider-specific auto-configurations.
3. Move AWS and Vault secret backends into optional modules.
4. Add auto-configuration ordering and condition tests.
5. Add a minimal dependency consumer test proving that Spring core does not pull unused provider SDKs.
6. Maintain migration aliases or clear upgrade instructions where property namespaces change.

### Acceptance criteria

- Adding a provider does not require modifying Spring core.
- Consumers only receive dependencies for selected adapters.
- Auto-configuration failures identify the exact missing or conflicting setting.

---

# Phase 7 — Structured-Output Architecture

## Epic 7.1: Compile a language-neutral structured type descriptor

**Status: ✅ Implemented in PR #265 (Epic 7.1)**

**Goal:** Make schema generation and validation consume one authoritative type model.

### Proposed model

```kotlin
sealed interface StructuredTypeDescriptor {
    data class Scalar(/* type, nullable, constraints */) : StructuredTypeDescriptor
    data class Enum(/* values, nullable */) : StructuredTypeDescriptor
    data class Collection(/* item, nullable, constraints */) : StructuredTypeDescriptor
    data class Object(/* name, properties, nullable */) : StructuredTypeDescriptor
}
```

*Deviation from the original sketch: `Enum` is first-class. PR #262 showed
that collapsing enums into another category makes schema/parser drift easy to
reintroduce — an enum is not merely a scalar detail.*

### Tasks

1. ✅ Build descriptors from Kotlin reflection. (`KotlinStructuredTypeCompiler`)
2. ✅ Build descriptors from Jackson JavaBean introspection. (`JacksonJavaBeanStructuredTypeCompiler`)
3. ✅ Represent requiredness, nullability, descriptions, ranges, item constraints, and recursion explicitly. (immutable `CompileContext` active-path recursion)
4. ✅ Generate schema from the descriptor. (`StructuredSchemaRenderer`)
5. ✅ Validate raw JSON shape from the descriptor. (`StructuredJsonShapeValidator`)
6. ✅ Validate deserialized values from the descriptor. (`StructuredValueValidator`)
7. ✅ Generate a stable contract fingerprint from the descriptor. (`StructuredContractFingerprint`, internal — not yet exposed on `StructuredOutputContract`; Epic 7.2 will pressure-test before any stable API decision)
8. ✅ Cache descriptors safely by type and configuration identity. (instance-scoped `StructuredDescriptorCache` bound to the handler's `ObjectMapper`)
9. ✅ Define recursion and unsupported-type behaviour consistently. (language-neutral errors, shared active-path semantics)

### Acceptance criteria

- ✅ Schema generation and validation no longer implement separate type-dispatch trees.
- ✅ Kotlin and Java differences are explicit in descriptor compilation, not duplicated throughout validation. (ASM architecture guard enforces this)
- ✅ Every emitted schema rule has a matching validation rule or a documented reason why validation is delegated to deserialization. (enum value membership is delegated to Jackson deserialization, preserving pre-descriptor error behaviour)

---

## Epic 7.2: Structured-output contract TCK

**Status: ✅ Implemented in PR #266 (Epic 7.2)**

One reusable test kit drives the entire structured-output lifecycle per
fixture: descriptor compilation → generated schema → raw JSON shape
validation → deserialization → runtime value validation → deterministic
repair feedback. No layer maintains its own independent fixture lists.

### Required cases

- ✅ Kotlin data classes
- ✅ JavaBeans
- ✅ Nullable and non-null fields
- ✅ Primitive missing fields
- ✅ Nested objects
- ✅ Generic collections
- ✅ Root arrays
- ✅ Annotation constraints
- ✅ Unknown properties
- ✅ Recursive types
- ✅ Unsupported maps
- ✅ Malformed JSON
- ✅ Extra prose around JSON
- ✅ Repair feedback determinism
- ✅ Contract fingerprint evolution
- ✅ Enum regression cases (root/nested/nullable enum, every declared value
  succeeds, unknown value fails via deserialization, legacy
  `{name, ordinal}` object form rejected) — #262 was the incident that made
  Phase 7 necessary

### Acceptance criteria

- ✅ The same fixtures validate schema, shape, deserialization, value constraints, and repair messages. (`StructuredOutputContractCase` → `StructuredOutputContractTck`)
- ✅ Contract drift tests explain exactly which descriptor element changed. (one-mutation-at-a-time fingerprint evolution; fingerprint excludes `Object.typeName` — semantic parity across Kotlin/JavaBean DTOs)
- ✅ Mutation evidence: temporarily ignoring `@AiRange`, disabling required-property shape enforcement, dropping fingerprint components, reverting the complete-JSON extractor path, and removing unknown-property shape rejection each turn the TCK RED.

### Production fixes surfaced by the TCK (PR #266)

- **Root scalar extraction.** The extractor now accepts a complete trimmed
  JSON value before the object/array bracket search, so structured scalar
  roots (enum `"LOW"`, integer `42`, double `0.85`, boolean `true`) round-trip
  instead of failing with "Could not extract JSON content". Prose-wrapped
  scalars remain un-extractable (only complete JSON values or object/array
  inside prose are accepted).
- **Unknown properties owned by the shape validator.** `additionalProperties:
  false` is enforced by `StructuredJsonShapeValidator` (`Property 'x' is not
  allowed`), independent of the consumer's Jackson configuration — previously
  it was delegated to Jackson deserialization and silently weakened by a
  custom `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`.

---

# Phase 8 — Persistence and Concurrency Assurance

## Epic 8.1: Persistence Store TCKs

**Status: ✅ DONE** — Approval store slice done (PR #267), Approval continuation slice done (PR #269), suspended invocation slice done (PR #270), audit store slice done (PR #271), audit outbox slice done (PR #272), workflow checkpoint slice done (PR #273), workflow lease slice done (PR #274), memory slice done (PR #275).

**Goal:** Ensure in-memory, file, and JDBC implementations share the same behavioural contract.

### Store families

- Approval store — ✅ PR #267 (shared `ApprovalStoreTck`, 37 cases × 3 implementations + enrollment guard)
- Approval continuation store — ✅ PR #269 (shared `ApprovalContinuationStoreTck`, 50 cases × 3 implementations + enrollment guard)
- Suspended invocation store — ✅ PR #270 (shared `SuspendedInvocationStoreTck`, 39 cases × 3 implementations + enrollment guard)
- Audit store — ✅ PR #271 (shared `AuditStoreTck`, 43 cases × 3 implementations + enrollment guard)
- Audit outbox store — ✅ PR #272 (shared `SovereignOpsAuditOutboxStoreTck`, 55 cases × 3 implementations + enrollment guard)
- Workflow checkpoint store — ✅ PR #273 (shared `WorkflowCheckpointStoreTck`, 42 cases × 4 implementations + enrollment guard)
- Workflow lease store — ✅ PR #274 (shared `WorkflowLeaseStoreTck` 51 cases + `WorkflowLeaseCheckpointFenceTck` 14 cases, × 3 implementations + enrollment guards)
- Step-attempt store — ✅ PR #218 (shared TCK + restart recovery tests for in-memory, file, and JDBC)
- Memory store — ✅ PR #275 (shared `ChatMemoryStoreTck`, 50 cases × 2 implementations (JDBC + Redis) + enrollment guard; Redis activity-index + atomic batch appends + JDBC ordinal-retry production changes)

### Contract areas

- Create/read/update semantics
- Optimistic concurrency
- Expected-version conflicts
- Idempotency
- Claim and lease semantics
- Expiry
- Ordering
- Atomicity
- Corruption handling
- Encryption boundary where applicable
- Cancellation
- Resource closure
- Safe error reporting

### Acceptance criteria

- ✅ Every published store implementation passes the relevant TCK (Approval: 3/3 via #267; Approval continuation: 3/3 via #269; suspended invocation: 3/3 via #270; audit: 3/3 via #271; audit outbox: 3/3 via #272; workflow checkpoint: 4/4 via #273; workflow lease: 3/3 store + 3/3 fence via #274; step-attempt: 3/3 via PR #218).
- ✅ Implementation-specific tests cover only storage technology and performance differences (encryption, permissions, corruption, record format for file; SQL schema, JSON mapping, connection cleanup for JDBC).
- ✅ Contract failures use common typed exceptions or reason codes (`ApprovalStoreConflictException`, `ApprovalStoreNotFoundException`, `ApprovalStoreTokenRejectedException`, `ApprovalStoreNotConsumableException`, `IllegalApprovalTransitionException`; `ApprovalContinuationConflictException`, `ApprovalContinuationNotFoundException`, `ApprovalContinuationNotClaimableException`, `ApprovalContinuationNotCompletableException`; AuditStore: `audit-store-invalid-stream-id`, `audit-store-invalid-event-id`, `audit-stream-id-mismatch`, `audit-sequence-gap`, `audit-hash-chain-broken`, `audit-event-hash-mismatch`, `audit-schema-version-unsupported`, `audit-duplicate-event-id`).

### Deliverables (PR #267)

- `tramai-testing/src/testFixtures/.../persistence/approval/` — `ApprovalStoreTck` (37 cases), `ApprovalStoreTckHarness`, `ApprovalStoreFixtures`, `MutableClock`
- Runners: `InMemoryApprovalStoreTckTest`, `FileApprovalStoreTckTest`, `JdbcApprovalStoreTckTest`
- `ApprovalStoreTckEnrollmentArchitectureTest` — every future `ApprovalStore` implementation must ship a `<Store>TckTest` runner extending the TCK
- `JdbcApprovalStore` — consume uses `SELECT ... FOR UPDATE` inside an explicit transaction (concurrent identical deliveries serialize into one fresh + one replay)
- Zero public API change; no persisted format or schema changes; no existing tests deleted
- Reference: `docs/reference/persistence-store-compatibility-contract.md`

### Deliverables (PR #269)

- `tramai-testing/src/testFixtures/.../persistence/approval/continuation/` — `ApprovalContinuationStoreTck` (50 cases), `ApprovalContinuationStoreTckHarness`, `ApprovalContinuationFixtures`
- Runners: `InMemoryApprovalContinuationStoreTckTest`, `FileApprovalContinuationStoreTckTest`, `JdbcApprovalContinuationStoreTckTest`
- `StoreEnrollmentScanner` (shared with the #267 guard) + `ApprovalContinuationStoreTckEnrollmentArchitectureTest`
- `JdbcApprovalContinuationStore` — three contract fixes the TCK exposed: late-cancel lazy-expiry normalization, `argumentsDigest` validation on create, version-before-status precedence in claim
- Exactly-once raw-argument release proven shared: a second claim can never retrieve arguments, concurrent claims release to exactly one winner
- Zero public API change; no persisted format or schema changes; no existing tests deleted
- Reference: `docs/reference/persistence-store-compatibility-contract.md`

### Deliverables (PR #270)

- `tramai-testing/src/testFixtures/.../persistence/engine/` — `SuspendedInvocationStoreTck` (39 cases), `SuspendedInvocationFixtures`; `tramai-testing` testFixtures gained a dependency on `tramai-engine` (the SPI's home module)
- Runners: `InMemorySuspendedInvocationStoreTckTest` (tramai-engine — the engine's default store, enrolled like any other), `FileSuspendedInvocationStoreTckTest`, `JdbcSuspendedInvocationStoreTckTest`
- `SuspendedInvocationStoreTckEnrollmentArchitectureTest` — reuses the shared `StoreEnrollmentScanner`
- `InMemorySuspendedInvocationStore` gained the shared validations it lacked (ID fields, envelope binding, canonical digest, redaction invariants via shared `ReplayEnvelopeValidator`); `JdbcSuspendedInvocationStore` now enforces the same redaction invariants (rejects correctly-digested unredacted envelopes); SPI KDoc durability claim corrected to implementation-specific
- Deliberate decisions: JDBC's unique `replay_envelope_digest` index stays JDBC-specific (documented, not copied); historySize consistency and the redaction sentinel ARE shared contract (File already enforced them)
- Mutation evidence (11 mutations, each restored): duplicate overwrite, remove-without-delete, reveal-null, envelope-leak-after-remove, digest/toolCallId/toolName/toolCallIndex checks dropped, non-atomic remove, redaction-sentinel not required, historySize not validated
- Zero public API change; no persisted format or schema changes; no existing tests deleted
- Reference: `docs/reference/persistence-store-compatibility-contract.md`

### Deliverables (PR #271)

- `tramai-testing/src/testFixtures/.../persistence/audit/` — `AuditStoreTck` (43 cases), `AuditStoreFixtures`; `tramai-testing` testFixtures gained a dependency on `tramai-security` (the SPI's home module)
- Runners: `InMemoryAuditStoreTckTest` (tramai-security — the default store, enrolled like any other), `FileAuditStoreTckTest`, `JdbcAuditStoreTckTest`; `AuditStoreTckEnrollmentArchitectureTest` reuses the shared `StoreEnrollmentScanner`
- Production alignment: `InMemoryAuditStore` gained blank stream/event-ID validation + fixed safe reason codes (was interpolated); `FileAuditStore` gained blank stream/event-ID validation; `JdbcAuditStore` gained blank event-ID validation and `appendNext()`'s transaction cleanup follows the #267 precedence (rollback/restore failures suppressed on the primary), with deterministic cancellation regressions
- Deliberate decisions: appendNext is a chain-authority API (factory-callback semantics pinned, not just rows); event-ID uniqueness is per-stream shared contract — JDBC's global `uq_audit_events_event_id` stays implementation-specific hardening (a direct SPI caller can reuse an ID across streams; retained because AuditEngine uses UUID IDs)
- Mutation evidence (14 mutations, each restored): stream/sequence/previous-hash/self-hash/schema/duplicate-event-ID/blank-ID checks dropped, inclusive cursor, ignored limit, latest-returns-first, removed append lock, shared metadata reference, store-before-validate
- Zero public API change; no persisted format or schema changes; no existing tests deleted
- Reference: `docs/reference/persistence-store-compatibility-contract.md`

### Deliverables (PR #272)

- `tramai-testing/src/testFixtures/.../persistence/outbox/` — `SovereignOpsAuditOutboxStoreTck` (55 cases), `SovereignOpsAuditOutboxFixtures` (deterministic T0/IDs/lease — never `UUID.randomUUID()`/`Instant.now()`); `tramai-testing` testFixtures gained a test-fixture-only dependency on `tramai-spring-boot-starter-sovereign-ops` (the SPI's home module; no Spring enters any production runtime module)
- Runners: `InMemorySovereignOpsAuditOutboxStoreTckTest`, `FileSovereignOpsAuditOutboxStoreTckTest`, `JdbcSovereignOpsAuditOutboxStoreTckTest`; `SovereignOpsAuditOutboxStoreTckEnrollmentArchitectureTest` reuses the shared `StoreEnrollmentScanner`
- One legal delivery state machine pinned across all three stores (JDBC's PR #85 guards are authoritative): PREPARED→PENDING→EMITTING→EMITTED; EMITTING→FAILED_RETRYABLE→(re-claim)→EMITTING; PREPARED|EMITTING→FAILED_PERMANENT; expired-claim recovery with strict `claimExpiresAt < now` lease boundary; `lastErrorCode = null` on every fresh claim
- Real cross-store divergences fixed: InMemory gained PREPARED-only readiness, EMITTING-only emission, the legal markFailed matrix, error-code clearing on claim, `[]` for non-positive limits, fixed reason codes; File gained blank-ID validation, the emission/failure guards, error-code clearing, and a write-staging fix (temp files now outside the scanned outbox dir — the pool-claim race exposed it); Jdbc normalized duplicate errors and collapsed all five mutating transaction paths onto one #267-precedence helper with deterministic cancellation regressions
- Mutation evidence (16 mutations, each restored): duplicate-ID/event-key overwrites, loser-record rollback, blank-ID, readiness/emission/failure-matrix guards, claim eligibility, lease boundary, attempt increment, error-code clearing, listing filter, non-atomic claim, ignored limit
- Zero public API change; no persisted format or schema changes; no existing tests deleted
- Remaining families: workflow checkpoint, workflow lease, memory
- Reference: `docs/reference/persistence-store-compatibility-contract.md`

### Deliverables (PR #273)

- `tramai-testing/src/testFixtures/.../persistence/checkpoint/` — `WorkflowCheckpointStoreTck` (42 cases), `WorkflowCheckpointFixtures` (deterministic — never the `System.currentTimeMillis()` savedAt default); `tramai-testing` testFixtures gained a test-fixture-only dependency on `tramai-orchestration` (the SPI's home module)
- Runners: `InMemoryWorkflowCheckpointStoreTckTest`, `FileWorkflowCheckpointStoreTckTest`, `MarkdownWorkflowCheckpointStoreTckTest`, `JdbcWorkflowCheckpointStoreTckTest` (real H2, not a proxy backend); `WorkflowCheckpointStoreTckEnrollmentArchitectureTest` reuses the shared `StoreEnrollmentScanner` (now skipping private declarations — the supervisor's private lease-fencing decorator is not a store family member)
- One identity/revision/delete/recovery contract across all four stores: checkpoint = versioned logical record keyed by (workflowName, workflowId); store-owned revision progression (1 → 2 → 3); optimistic concurrency via expectedRevision; delete/idempotency (unconditional delete is a no-op on missing, recreate starts at rev 1); recovery-state persistence through the SPI's default load-then-save (proven sufficiently atomic by the competing-requireRecovery race — no override rewritten); `WorkflowCheckpointCatalog` deliberately outside the TCK (distinct optional SPI, Markdown intentionally does not implement it)
- Principal production fix: **File/Markdown logical-key collision repair** — the lossy `sanitizePathSegment` collapsed `"order/a"` and `"order?a"` onto one file; the checkpoint stores now default to the new collision-free `CollisionFreeWorkflowCheckpointPathStrategy` (URL-safe Base64, injective) with legacy-path fallback, identity verification (never overwrite a colliding key's record), and migrate-on-first-update (never two authoritative copies). `DefaultWorkflowCheckpointPathStrategy` unchanged (lease store depends on it). Additive public API; `api/` dump regenerated
- Mutation evidence (13 mutations, each restored): duplicate-create overwrite, input-revision trust, revision 0, ignored expected-revision (save + delete), missing+expected create/delete, missing-delete throws, non-atomic save/delete, recovery-state normalization, lossy path regression
- Zero public API change to existing types; no persisted format or schema changes to existing records (legacy records readable + migratable); no existing tests deleted
- Remaining families: workflow lease, memory
- Reference: `docs/reference/persistence-store-compatibility-contract.md`; legacy path behavior documented in `docs/guides/orchestration-persistence.md`

### Deliverables (PR #274)

- `tramai-testing/src/testFixtures/.../persistence/lease/` — `WorkflowLeaseStoreTck` (51 cases), `WorkflowLeaseCheckpointFenceTck` (14 cases), `WorkflowLeaseFixtures`, `MutableMillisClock` (thread-safe AtomicLong clock; no system clock, no sleeps)
- Runners: `InMemoryWorkflowLeaseStoreTckTest`, `FileWorkflowLeaseStoreTckTest`, `JdbcWorkflowLeaseStoreTckTest` (real H2) + the same three stores enrolled in the fence contract (`*WorkflowLeaseStoreCheckpointFenceTckTest`, JDBC lease + checkpoint stores sharing ONE DataSource for the atomic fence transaction); two enrollment guards (`WorkflowLeaseStoreTckEnrollmentArchitectureTest`, `WorkflowLeaseCheckpointFenceTckEnrollmentArchitectureTest`) reusing the #273 scanner
- Contract: for one (workflowName, workflowId), at any instant at most one active lease token authorizes mutations, and storage technology cannot change that answer. Claim identity, exact expiry boundary (`expiresAt > now` active), renewal from durable state, release semantics (leaseId is the fencing capability — ownerId alone insufficient), stale predecessor can never renew/release/fence, input-domain hardening (IllegalArgumentException outside the boundary), 4 real races × 20 (initial claim, expired takeover, renew-vs-claim at exact expiry, colliding keys in parallel)
- Companion fence contract: `StaleWorkflowLeaseException` (`"Workflow lease is no longer active"`) distinct from `WorkflowLeaseConflictException`; checkpoint unchanged on stale rejection; **new shared invariant: lease identity must equal checkpoint identity (IllegalArgumentException, fail before touching storage)**; the fence is NOT a renewal API and must not mutate lease metadata
- Principal production fixes: **File lease identity** — `CollisionFreeWorkflowLeasePathStrategy` (safe segments keep their legacy path; unsafe → `~`+base64url, injective, never collides with legacy paths; legacy leases honored with identity verification, NOT migrated while live — lock namespace unchanged under an active lease); **JDBC renew returns the durable row** (caller's tampered snapshot no longer leaks); **JDBC fence uses SELECT ... FOR UPDATE** instead of the state-mutating UPDATE (checkpointRevision never rewritten as a side effect); shared caller-input validation in all three stores
- Mutation evidence (17 mutations, each restored): active-claim guard, expiry boundary, fixed leaseId, renew leaseId/owner/expired/revision/expiry, release token, fence key-binding, fence forged-token, input validation, File lossy path, File foreign legacy identity, JDBC caller snapshot, JDBC mutating lock, File lock removal
- Zero public API change to existing types; `api/` dump regenerated for the additive strategy class; no existing tests deleted
- Remaining family: memory
- Reference: `docs/reference/persistence-store-compatibility-contract.md`; lease layout documented in `docs/guides/orchestration-persistence.md`

---

## Epic 8.2: State-machine and property-based tests

**Status: 🚧 IN PROGRESS** — Approval lifecycle done (PR #278); continuation lifecycle done (PR #279); worker lifecycle done (PR #280); lease lifecycle done (PR #290); outbox lifecycle done (PR #291); workflow checkpoint/resume lifecycle done (PR #295); circuit breaker lifecycle done (PR #302); provider retry/fallback lifecycle done (Epic 8.2h); remaining targets pending.

**Goal:** Test lifecycle logic through transitions rather than isolated examples.

### Targets

- Approval lifecycle — ✅ PR #278 (5 model-based properties × 3 implementations added to `ApprovalStoreTck`: 42 shared cases total; pure `ApprovalLifecycleModel` oracle, 32-seed × 32-action deterministic corpus with guaranteed wrong-version-while-pending coverage, per-step invariants, wrong-version decision matrix + 3 concurrency properties ×20, coverage guard, 18-mutation evidence; zero production changes)
- Continuation lifecycle — ✅ PR #279 (10 model-based properties × 3 implementations added to `ApprovalContinuationStoreTck`: 60 shared cases total; pure `ApprovalContinuationLifecycleModel` oracle with post-failure state — failed ops can legitimately normalize to EXPIRED, with rewind-clock properties proving the failed claim/cancel itself persisted the EXPIRED transition before reporting; 32-seed × 32-action corpus with forced archetypes (claim→complete/uncertain, boundary, late claim/cancel, lazy get) + 25-category semantic coverage guard incl. exactly-once argument release; 10 properties: generated sequences, late claim/cancel persistence, wrong-version matrix, 8-way claim race, mixed claim/cancel race, claimed resolution race, concurrent lazy expiry, generated sweep model, generated stale-claim query model; 22-mutation evidence; 1 deliberate production fix — File `findStaleClaimed` truncated to limit before sorting)
- Worker lifecycle — ✅ PR #280 (20 model-based properties in `tramai-orchestration` test sources — single implementation, no backend matrix; pure `WorkerLifecycleModel` oracle with phase/generation/exactly-once event counters and `Failure(kind, next)` rollback on failed startup; 32-seed × 32-action corpus with forced archetypes (clean cycles, shutdown-before-start, crash→shutdown→restart, duplicates, close-equivalence) + 23-category semantic coverage guard; 20 properties: generated sequences, registration-failure rollback, shutdown-during-registration cannot resurrect, 8 concurrent starts → one generation, 8 concurrent shutdowns → one owner, start-during-drain no-op, crash ≠ graceful shutdown, shutdown stops heartbeat/poll ownership before the stopped event, stale start contender has zero authority, aborted start keeps ownership while shutdown still drains, cancelled registration rolls back, shutdown in the claim→prepare gap is accepted never lost, startup cannot commit RUNNING after a completed shutdown, failed-startup cleanup cannot delete a newer generation's row, shutdown cannot bisect the activation epilogue, old-generation STOPPED cleanup cannot delete a newer generation's row, stale startup reset cannot clobber an in-flight shutdown, observer reentrant shutdown during activation leaves a clean stopped worker, old-generation cleanup under SHUTTING_DOWN cannot delete a newer row, cleanup finishing before shutdown cannot release lifecycle ownership; 32-mutation evidence, 0 weak; 3 deliberate production changes — `WorkerLifecycleController` ownership primitive (`AtomicReference<Job?>`) closing six defects: registration failure retained root ownership, suspended registration resurrected a dead generation after shutdown, a non-atomic start guard let racing starts create two generations, a stale start contender could reset the winner's shutdown state, an aborted start could release ownership while the shutdown owner still drained, and caller cancellation could leave a stuck half-owned lifecycle — then the split ownership+booleans replaced by ONE generation-aware atomic state machine (`Stopped/Starting/Running/ShuttingDown/Crashed`) closing four further races: shutdown lost in the claim→prepare gap, RUNNING commit after a completed shutdown (TOCTOU resurrection), old-generation cleanup deleting a newer generation's registry row, and the post-commit epilogue firing started/hook/accept after a completed shutdown — then the activation epilogue linearized against shutdown/crash by a non-suspending critical section plus a `Reconciling` state reserving STOPPED cleanup, closing the two review-round-3 residuals: shutdown bisecting the post-commit epilogue (started/hook/accept after workerStopped) and STOPPED cleanup deleting a newer generation's row — then the round-4 ownership-escape closes: the verify+`prepareLifecycleStart` reset made atomic under the same lock (stale starter can no longer clobber an in-flight shutdown's graceful state), the activation epilogue made reentrancy-safe (every shutdown-owned resource created before the final `onWorkerStarted` callback; lazy heartbeat/poll started only if still RUNNING), and the RECONCILING reservation extended to span the suspendable unregister from ANY still-ours state (cleanup begun under SHUTTING_DOWN can no longer delete a newer row after the release) — and finally the round-5 completion-order close: `Reconciling` carries `returnTo` + a `completion` signal, SHUTTING_DOWN-origin cleanup restores the captured state instead of releasing STOPPED, and the shutdown release loop waits for same-generation reconciliation before exposing STOPPED (STOPPED visible only after BOTH the graceful drain and the late-registration cleanup finish))
- Follow-up (anomaly, not in #280): worker lease acquire → no step start → release → reacquire loop observed in a lifecycle-heavy harness; not isolated; minimal reproducer first, dedicated PR if it reproduces
- Lease lifecycle — ✅ #290 (Epic 8.2d: 6 model-based properties added to `WorkflowLeaseStoreTck` (P1-P6, 51 → 57 shared cases) + 2 fence-lineage properties added to `WorkflowLeaseCheckpointFenceTck` (P7-P8, 14 → 16 shared cases) = 8 Epic 8.2d properties total, ×3 implementations; pure `WorkflowLeaseLifecycleModel` oracle with symbolic token generations (T1/T2/T3), snapshot-vs-generation capability distinction, monotonic clock; 32-seed × 32-action corpus with a forced 22-step discriminator spine + 23-category semantic coverage guard; 21-mutation evidence, 0 weak; 1 deliberate production fix — `JdbcWorkflowLeaseStore.claim` lost a legitimate exact-expiry takeover when a concurrent no-op release deleted the expired predecessor row between read and replace: the 0-row path now inserts the new lease instead of reporting conflict (recovered key is free))
- Outbox lifecycle — ✅ PR #291 (Epic 8.2e: 9 model-based properties added to `SovereignOpsAuditOutboxStoreTck` (P0-P8, 55 → 64 shared cases × 3 implementations): pure `SovereignOpsAuditOutboxLifecycleModel` oracle with attempt-generation authority, `expectedAttemptCount` dispatch-generation fencing, exact-expiry boundary opposite to lease semantics; 32-seed × 32-action corpus with 8 forced discriminator lanes + 25-category semantic coverage guard; 19-mutation evidence, 0 weak; 3 deliberate production changes — `markEmitted`/`markFailed` gained the `expectedAttemptCount` optimistic generation fence (public SPI change, api dump regenerated), JDBC `claimPending` keeps SKIP LOCKED as the fast path but when it selects zero rows probes non-lockingly and blocks on the candidate by primary key so a claim racing a concurrent terminal mutation serializes instead of reporting a false empty (deterministic gated-codec regression), and the dispatcher passes the generation it owns so a stale completion can never demote the newer attempt)
- Workflow checkpoint/resume lifecycle — ✅ PR #295 (Epic 8.2f: 51 shared cases × 4 implementations (42 → 51: +7 lifecycle properties P0–P6 + 2 generation/migration contract cases) in `WorkflowCheckpointStoreTck`; pure `WorkflowCheckpointLifecycleModel` oracle with incarnation+revision authority, 32-seed × 32-action corpus ×4 stores = 4,096 model-checked actions; resume lifecycle R1–R6 in `WorkflowCheckpointResumeDiscriminatorTest` (Required fail-closed, frontier honors, once-per-step advance, failure/cancellation retention, generation-fenced save/delete, stale resumed execution cannot completion-delete a recreated successor); legacy migration mini-contract ×3 persistent stores (legacy load → null generation, first fenced write installs token, stale legacy writer conflicts, concurrent migration race → exactly one token, legacy resume learns generation from first migrated save); 25-mutation evidence, 0 WEAK (discriminator set includes lease-fence TCK for composition mutations — M21 lesson); 3 deliberate production changes — store-owned incarnation token `WorkflowCheckpoint.checkpointGeneration` fencing save/delete/requireRecovery/clearRecovery against delete/recreate ABA (legacy null-generation records stay readable; first legitimate fenced mutation migrates), `WorkflowRunner.resume()` fails closed on `WorkflowRecoveryState.Required` (was: direct resume executed a Required checkpoint), and `WorkflowRecoveryController.retryStep`/`failWorkflow`/`loadRequiredCheckpoint` now take `expectedGeneration` so a stale operator command authorized against G1 can never act on a recreated same-revision G2 — the controller must not adopt the current generation on a caller's behalf)
- Circuit breaker states — ✅ PR #302 (Epic 8.2g: 13 model-based properties in `ProviderCircuitBreakerLifecyclePropertyTest` (P1–P13) driven by a pure `ProviderCircuitBreakerModel` oracle + 32-seed × 32-action corpus; 5 P0 lifecycle discriminators (`ProviderCircuitBreakerLifecycleDiscriminatorTest`, RED commit `cc1fc065` → GREEN after the production redesign); 22 secondary/concurrency discriminators H1–H17 (incl. H1b) + C1–C4 (`ProviderCircuitBreakerSecondaryRegressionTest`, `StreamingExecutionCoordinatorTest`); 31-candidate mutation evidence — 26 reachable/non-redundant/compile-valid candidates, 26/26 killed, 1 unreachable-by-contract, 1 invalid, 3 redundant (M15, M28, M29), **0 reachable weak**, reachable set re-run in full on the structural-guard head, with seven mutation-discovered oracle gaps closed (M01→H1b, M17→H5+events, M23→H6, M24→H7+events, M25→H8, M28→H12, M29→H13) and the guard mutations M30→H14/H15/H17, M31→H16; 2 production redesigns — the breaker now owns admission permits `(providerId, generation)`: `beforeCall` returns Allowed(permit)/Rejected(blockedUntil), completions consume the permit (stale generation rejected before state handling), exact expiry atomically grants ONE HALF_OPEN probe, probe success closes / probe failure reopens with fresh deadline, generation advances on every OPEN entry, OPEN can never own a completion permit, qualifying failures stay narrow (Timeout + retryable ProviderException), disabled breaker transparent; plus P1 round — EVERY terminal probe outcome is a breaker transition: neutral/abandoned probes (cancellation, DLP, policy, non-retryable error, budget exhaustion) release probe ownership via `onAbandoned`/neutral `onFailure` into OPEN with an ADVANCED generation (recovery-state transition, no CIRCUIT_OPENED event, never counted as failure), so no probe can strand HALF_OPEN forever; plus round-2 P1 — permit relinquishment is STRUCTURAL: both coordinators wrap the admitted route in `finally { onAbandoned(permit) }` (pre-route policy/cancellation, pre-try observer/interceptor failure, retry-delay cancellation can no longer strand the probe); sync/streaming parity — the admission permit is threaded through both execution paths (`ProviderAttemptExecutor` records `onSuccess` before returning; streaming never performs a second admission at completion))
- Provider retry/fallback lifecycle — ✅ Epic 8.2h (streaming: `StreamingExecutionCoordinator` retry loop honors `@Operation.providerRetries` — retryable startup failures retry the same provider before fallback, `N` retries = `N + 1` attempts, fallback only after exhaustion; P0-A RED commit `6edfc9cc` → GREEN `349f12b6`; 15 discriminators P0-A…P0-O incl. P0-K neutral breaker composition, P0-M recovery-eligible `STREAMING_STARTUP_RETRY`, and P0-O early-`Stop` route-exit (a `Stop` decision permanently leaves the route even before budget exhaustion — circuit-open from the stream is never re-entered, the fallback gate runs exactly once); P1–P14 independent model-vs-reality property oracle in `ProviderRetryFallbackLifecyclePropertyTest` — script-authoritative routing (model output never configures production inputs), ordered attempt/fallback-edge traces, per-route retry budgets, semantic breaker dispositions observed in reality, output-visibility disposition gates, forced circuit-open + gate-denial archetype (gate transition counted on denial); reality corpus 96 coordinator executions (32 seeds × 3 budgets), semantic coverage guard 288 model scripts; 23-candidate mutation evidence — 20 STRONG, 3 REDUNDANT, 0 UNREACHABLE, 0 reachable WEAK (P0-N delay discriminator converts M14/M22 from WEAK to STRONG; M23 `Stop`-fails-to-exit-route killed by P0-O); `docs/reference/state-machine-property-testing-contract.md` § Epic 8.2h)

### Tasks

1. Define state-transition models.
2. Generate valid and invalid action sequences.
3. Assert invariants after every transition.
4. Add concurrency tests for claims, versions, leases, duplicate decisions, and takeover.
5. Add deterministic schedulers/clocks for timing-sensitive tests.

### Deliverables (PR #278)

- `tramai-testing/src/testFixtures/.../persistence/approval/` — `ApprovalLifecycleModel` (pure oracle + action alphabet + outcome kinds + invariants), `ApprovalLifecycleActionGenerator` (deterministic, state-aware)
- `ApprovalStoreTck` augmented with 5 properties: generated lifecycle sequences (32 seeds × 32 actions, whole-record equality after every action, seed/step/prefix failure diagnostics), wrong-version decision matrix (Approve/Deny/Timeout × before/exact/after-expiry → CONFLICT + zero mutation), duplicate concurrent decisions (8 → 1 win/7 conflict), identical consumption (8 → 1 fresh + 7 replays, same durable record), competing consumers (8 → exactly one durable consumer identity)
- `tramai-testing/src/test/.../persistence/approval/` — `ApprovalLifecycleActionGeneratorTest` coverage guard (16 semantic categories incl. reachable wrong-version-while-pending + determinism + full status lattice)
- Model aligned to cross-store token-first precedence on the wrong-token path (SPI KDoc leaves check order unpinned)
- Zero production changes; no public API/schema/persisted-format changes; no existing tests deleted
- Reference: `docs/reference/state-machine-property-testing-contract.md`

---

## Epic 8.3: Time, randomness, and scheduling abstractions

**Status: ✅ COMPLETE** — closed by 8.3d PR 2 (machine-enforced nondeterminism closure). Contracts: `docs/reference/time-semantics-contract.md` (frozen 8.3a) + `docs/reference/nondeterminism-authority-contract.md` (final closure).

**Goal:** Eliminate incidental nondeterminism from domain decisions.

### Final decomposition

- **8.3a** — wall vs monotonic time semantics (frozen contract, injected Clock per worker boundary, `MonotonicTimeSource`/`NanoTimeSource` seam).
- **8.3b1** — retry randomness authority (`RetryJitterSource`).
- **#318** — durable step-attempt chronology (`StepAttemptIdentitySource`).
- **8.3b2a** — engine execution identity (`EngineIdentitySource`).
- **8.3b2b** — step-attempt identity authority.
- **8.3c** — scheduler lifecycle ownership (`SchedulerLoopOwner`, #332).
- **8.3d PR 1** — residual runtime authority centralization (lease/claim/jitter, #335).
- **8.3d PR 2** — machine-enforced closure: hardened canonical scanner, semantic allowlist (`config/quality/runtime-nondeterminism.yml`), fail-closed `verifyRuntimeNondeterminism` wired into `verifyMaintainabilityBaseline`, final authority contract.

### Tasks

1. Use `Clock` for wall-clock timestamps. — **8.3a: done** — one injected Clock per worker boundary; recovery controller ABI unchanged (internal `forTest` seam).
2. Use a monotonic time source for duration and timeout accounting where appropriate. — **8.3a: done** — `MonotonicTimeSource`/`NanoTimeSource` seam; `MonotonicDrainBudget` exact residual; heartbeat uptime monotonic.
3. Inject jitter/random sources into retry policies. — **8.3b1: done** — `RetryJitterSource` authority.
4. Avoid direct `System.currentTimeMillis()` in domain logic. — **8.3a: done** in orchestration elapsed/persisted paths; the public `WorkflowCheckpoint(savedAtEpochMillis = System.currentTimeMillis())` default remains deliberately (PUBLIC_COMPATIBILITY_BOUNDARY, allowlisted).
5. Centralize scheduler ownership. — **8.3c: done** — `SchedulerLoopOwner`.
6. Make tests independent of real sleeps whenever possible. — **8.3a: done** for the affected paths — 14 discriminators, exact arithmetic, zero timing thresholds (M04-hardened).

### Closure enforcement (8.3d PR 2)

- Canonical scanner detects callable references (`System::nanoTime`), Kotlin `Random` singleton forms, and all historical patterns; identity is `(module, file, source)` — line-independent.
- Every production finding has exactly one semantic classification (AUTHORITY / CAPABILITY_AUTHORITY / COMPOSITION_BOUNDARY / PUBLIC_COMPATIBILITY_BOUNDARY) in `config/quality/runtime-nondeterminism.yml`.
- Zero unclassified findings, zero stale entries; new direct nondeterminism fails CI (`verifyRuntimeNondeterminism` via `verifyMaintainabilityBaseline`).
- M01–M08 mutation campaign: 8/8 STRONG.

### Acceptance criteria

- Critical timing tests use virtual or injected time.
- Retry and lease tests are deterministic.
- Duration logic does not break under wall-clock changes.
- New direct nondeterminism fails CI; removed sources require allowlist cleanup (stale-entry rejection).

---

# Phase 9 — Module and Build Architecture

## Epic 9.1: Define module layers and maturity

**Goal:** Make the repository's module structure understandable and enforceable.

### Proposed layers

1. **API/SPI:** core contracts and annotations
2. **Runtime:** engine, structured output, orchestration
3. **Governance:** security, sovereign, evidence-related runtime
4. **Adapters:** providers, vector stores, persistence implementations
5. **Integration:** Spring, server, MCP, observability
6. **Composition:** standalone and starters
7. **Applications/examples:** examples and dashboard

### Tasks

1. Create a machine-readable module manifest containing:
   - layer;
   - maturity;
   - publishability;
   - public/internal status;
   - owner;
   - allowed dependencies;
   - release inclusion.
2. Generate settings, BOM inclusion, publishing lists, module matrix, and documentation indexes from that manifest where practical.
3. Add dependency-direction verification.
4. Detect cycles and forbidden edges.
5. Review modules with overlapping responsibilities or too little independent value.
6. Consolidate only where it improves ownership and dependency clarity.

### Acceptance criteria

- Publishability is not maintained through several hand-written lists.
- Every module has a documented reason to exist.
- CI rejects dependency cycles and forbidden layer edges.

### Status: ✅ Complete

- **AC1** (publishability not via hand-written lists) — ✅ PR #298 (manifest-derived publishing/BOM, `verifyModuleManifest` in `verifyPr`).
- **AC2** (documented reason to exist) — ✅ PRs #298, #304 (specific rationale for all 58 modules).
- **AC3** (cycles + forbidden edges rejected in CI) — ✅ PR #298 (M1–M8 mutation suite).
- **Tasks 1–4** — ✅ PR #298.
- **Task 5** (overlap review) — ✅ PR #300: 58/58 modules reviewed, 49 KEEP / 9 CLARIFY / 0 CONSOLIDATE.
- **Task 6** (consolidation) — ✅ PR #300: reviewed; no consolidation improves ownership/dependency clarity sufficiently to justify compatibility and churn costs. The 9 CLARIFY findings were resolved without consolidation: JDBC release-surface correction (PR #303) + rationale clarity (PR #304).

---

## Epic 9.2: Move build logic into `build-logic`

**Goal:** Make Gradle configuration modular, typed, testable, and mostly declarative.

> **Slicing:** see [docs/EPIC-9.2-build-logic.md](./EPIC-9.2-build-logic.md).
> 9.2a–9.2d complete.

### Status: ✅ Complete (9.2a, 9.2b, 9.2c, 9.2d)

- **9.2a — `tramai.publishing` convention plugin** — ✅ PR #308: extracted
  publication/signing/repository/POM configuration from the root
  `build.gradle.kts` into a tested TestKit convention plugin
  (`dev.tramai.build.publishing`), behavior-preserving: same publication
  surface, POM metadata, repository precedence, credential rules, signing
  semantics, and sovereignBundleLocal membership. Discriminator suite
  P1–P10 + S1 in `TramaiPublishingPluginTest`; `verifyChangePolicy`
  auto-classifies as `build-logic`.
- **9.2b — typed release/evidence tasks** (`tramai.release-verification`,
  `tramai.sovereign-verification`) — ✅ PR #313: typed/cache-aware release
  and evidence tasks extracted from root `doLast` closures.
- **9.2c — quality/test conventions + manifest-derived metadata** —
  ✅ PR #319 (`tramai.kotlin-library`/`tramai.java-platform`/
  `tramai.test-fixtures`), PR #322 (`tramai.testing`), PR #325
  (module-catalog.yml schema v3 descriptions).
- **9.2d — configuration-cache closure; root reduced to composition** —
  ✅ COMPLETE:
  - **a-series** — typed/config-cache conversions of verification tasks
    (C1 `help`/C2 `test`/C6 `verifyPublicationMetadata` CC-reusable).
  - **b1 — PR #353** — module-catalog.yml as single publishability
    authority (4 consumers fail-closed).
  - **b2 — PR #357** — root responsibility extraction: SBOM →
    `tramai.supply-chain`, sovereign-lab → `tramai.sovereign-lab-
    verification`, `verify050ReleaseReadiness` → `tramai.release-
    verification`; root is composition-only.
  - **b3 — PR #359** — developer lifecycle CC closure: release-only
    `verify050ReleaseReadiness` detached from `check` (C3 = 1 deliberate,
    invoked explicitly by publish workflow with `--no-configuration-cache`);
    final offender matrix C4 = 0, C5 = 0; `test` CC cold → stored →
    reused; `check` CC cold → stored → reused.

### Target convention plugins

- `tramai.kotlin-library`
- `tramai.java-platform`
- `tramai.publishing`
- `tramai.quality`
- `tramai.test-fixtures`
- `tramai.integration-test`
- `tramai.sovereign-verification`
- `tramai.release-verification`

### Tasks

1. Move publishing setup out of root `build.gradle.kts`.
2. Move custom release/evidence tasks into typed Gradle task classes.
3. Replace large `doLast` blocks with cacheable task inputs and outputs where possible.
4. Unit-test build logic with Gradle TestKit.
5. Generate project descriptions and publication metadata from the module manifest.
6. Reduce root build files to high-level composition.
7. Enable Gradle configuration cache for normal developer tasks.
8. Isolate unavoidable non-cacheable release tasks and document why.

### Acceptance criteria

- Root build logic is reviewable without scrolling through release implementation code.
- Normal `test` and `check` tasks support configuration cache.
- Release tasks have typed inputs, deterministic outputs, and TestKit coverage.

---

# Phase 10 — Continuous Code Quality

## Epic 10.1: Formatting and static analysis

**Status: ✅ COMPLETE — 10.1a ✅ merged (`731126bf`, PR #339) · 10.1b ✅ merged (`3aa4ef72`, PR #342) · 10.1c ✅ merged (`f7fd192e`, PR #344) · 10.1d ✅ merged (`868071aa`, PR #351)** (sliced: see `docs/EPIC-10.1-code-quality.md`)

### Slices (frozen decomposition)

| Slice | Scope | Status |
|---|---|---|
| 10.1a | Incremental Kotlin formatting gate (Spotless + pinned KtLint, git-ratcheted against the exact PR/push base) | ✅ merged (`731126bf`, PR #339) |
| 10.1b | Static analysis (Detekt or equivalent): baseline, prohibit growth, central suppression rationale | ✅ merged (`3aa4ef72`, PR #342) |
| 10.1c | Compiler + dependency hygiene: warning review / `-Werror` where feasible, unused-dependency enforcement | ✅ merged (`f7fd192e`, PR #344) |
| 10.1d | Forbidden/lifecycle/security static guards + final `check`/CI closure | ✅ merged (`868071aa`, PR #351) |

### Required gates

- ✅ Kotlin formatting enforced in CI (`spotlessCheck`, ratcheted against the exact base)
- ✅ Detekt static analysis (`verifyStaticAnalysis`, baseline-backed)
- ✅ Compiler warnings reviewed and treated as errors for TramAI code where feasible (`verifyCompilerWarnings`)
- ✅ No unused dependencies (`verifyDependencyHygiene`)
- ✅ No forbidden API usage (`verifyStaticSafetyGuards` R4)
- ✅ No broad catch in suspend code without cancellation handling (`verifyCancellationSafety`, exact-base aware)
- ✅ No raw thread or global scope creation outside approved lifecycle factories (`verifyStaticSafetyGuards` R1, 15 ownership exemptions)
- ✅ No unbounded response-body reads (`verifyStaticSafetyGuards` R2 + bounded helpers in `tramai-core` transport)
- ✅ No direct sensitive payload logging (`verifyStaticSafetyGuards` R3)

### Initial readability budgets

These are guardrails, not absolute design laws. Existing hotspots receive explicit migration exceptions until removed.

- No new production file above 800 lines without an architecture waiver.
- No new class above 500 lines without a waiver.
- No new function above 80 lines without a waiver.
- No new constructor with more than 12 direct dependencies without using a cohesive component object.
- Cyclomatic and cognitive complexity thresholds apply to new or materially modified code.
- Existing violations may not grow.

### Acceptance criteria

- Quality checks run through `./gradlew check`.
- CI comments or reports actionable violations.
- Suppressions require a reason and are centrally reviewable.

---

## Epic 10.2: Binary and source compatibility

**Status: ✅ COMPLETE — machinery landed via PR #307 (`d5486a35`, Track B3) + typed gate #346 (`564b4d05`); closure audit certified on current master (see `docs/EPIC-10.2-api-compatibility.md`).**

### Tasks

1. Add Kotlin binary-compatibility validation or equivalent API dumps.
2. Classify stable, preview, experimental, and internal packages.
3. Fail CI on accidental stable API changes.
4. Require migration notes for intentional preview API changes.
5. Add Java compilation smoke tests for public APIs.
6. Add Kotlin source-compatibility examples.

### Acceptance criteria

- Stable API changes cannot merge silently.
- Internal implementation refactoring does not accidentally expand public API.

---

## Epic 10.3: Coverage and mutation policy

**Goal:** Use coverage as a risk signal, not a vanity score.

### Policy

1. Establish baseline coverage first.
2. Require no regression in critical modules.
3. Set stronger branch-coverage expectations for:
   - engine coordination;
   - policy;
   - approval;
   - routing;
   - evidence;
   - persistence concurrency;
   - worker state machines.
4. Run targeted mutation testing for critical deterministic logic.
5. Track surviving mutants and either kill, justify, or remove unreachable code.

### Acceptance criteria

- New critical behaviour includes branch and negative-path tests.
- Mutation reports are available for release review.
- Coverage exclusions are explicit and justified.

---

## Epic 10.4: Architecture verification

### Automated rules

- No dependency cycles
- Core does not depend on adapters or Spring
- Examples are never production dependencies
- Runtime execution does not depend on process-global mutable registries
- Stable APIs do not expose internal implementation types
- Provider adapters pass the provider TCK
- Store adapters pass store TCKs
- Event names and reason codes come from approved catalogues
- Cancellation rules are satisfied
- Module manifest matches settings, publishing, and BOM configuration

### Deliverable

`./gradlew verify060Architecture`

---

## Epic 10.5: CI redesign

**Status: ✅ COMPLETE — parallelized CI track #360–#375; P3-E measured closure (`docs/EPIC-10.5-ci-redesign.md`): ordinary/leaf ≈ 8–10 min, high-fanout core/compiler-global ≈ 16–17 min (target band 15–18 min).**

### Target CI lanes

1. **Fast PR lane**
   - formatting
   - compilation
   - unit tests
   - static analysis
   - architecture checks
   - API compatibility

2. **Integration lane**
   - JDBC/Testcontainers
   - provider protocol fixtures
   - Spring context tests
   - workflow worker tests

3. **Security and sovereign lane**
   - zero-egress
   - evidence bundles
   - release artifacts
   - redaction and safe-error tests

4. **Compatibility lane**
   - minimum supported JDK 21
   - current CI JDK
   - Java consumer
   - Kotlin consumer
   - minimal Spring consumer

5. **Nightly/deep lane**
   - mutation testing
   - stress and concurrency tests
   - dependency vulnerability scan
   - performance benchmarks
   - repeated/flaky-test detection

### Acceptance criteria

- `check`, not only `test`, is a mandatory PR gate.
- Fast feedback remains reasonably quick through task separation and caching.
- Expensive assurance runs on an appropriate scheduled or release cadence.

---

# Phase 11 — Readability and Maintainer Experience

## Epic 11.1: Code organization standards

**Status: ✅ COMPLETE** (closure audit 2026-09-04; docs-only — the standards
were met and enforced incrementally under other epics; no dedicated delivery
PR existed, which is why this epic had no status marker).

**Closure record** — every standard maps to durable enforcement/evidence:

- *One primary responsibility per file; file names describe the owned
  concept; packages reflect architectural boundaries* — enforced by the
  architecture-guard test family (`WorkflowDecompositionArchitectureTest`,
  `TramaiWorkerDecompositionArchitectureTest`,
  `WorkflowStepExecutionArchitectureTest`, `ReplayPolicyBoundaryArchitectureTest`,
  TCK/store `*EnrollmentArchitectureTest` guards) plus ADR guardrails
  (`docs/adr/`) and `ARCHITECTURE.md` ownership pointers.
- *Public classes document lifecycle/thread-safety/failure semantics/
  ownership* — module docs (`docs/modules/`, per-module guides) +
  `CONTRIBUTING.md`/AGENTS.md quality bar; invariant cross-references are
  cited next to enforcing code (e.g. `FileApprovalContinuationStore` cites
  contract property P1-3 "CLAIMED must never lazily expire").
- *No review-residue comments* — no `P1-2`-style temporary references remain
  in production sources.
- *Boolean security/lifecycle parameters → named policies/value types* and
  *typed outcomes over exception-message inspection* — repository-wide
  conventions delivered with the state-machine/time/authority epics
  (sealed decision types, typed outcomes, injected clocks/identity sources);
  enforced by review checklists (PR template + AGENTS.md required PR
  questions, Epic 11.3 artifacts).
- *String maps at important boundaries → typed metadata* — typed metadata key
  constants at orchestration/persistence boundaries.
- *No vague generic `Manager`/`Helper`/`Utils`* — the remaining `Helper`
  classes are domain-precise (`WorkflowDigestHelper`,
  `ResumeDefinitionDigestHelper`, `ReplayEnvelopeDigestHelper`,
  `PolicyEnforcementHelper`); `PluginManager` owns a real plugin lifecycle.
- *Acceptance criteria* — behaviour locatable by package/type name via
  `ARCHITECTURE.md` + change guides (`docs/architecture/change-guides/`:
  provider, store, workflow step, approval state, event, structured-output);
  critical invariants visible next to enforcing code (property citations,
  KDoc contracts). No code change required; no MISSING standard.

### Standards

- One primary responsibility per production file.
- File names describe the owned concept, not a vague utility category.
- Package names reflect architectural boundaries.
- Public classes document lifecycle, thread safety, failure semantics, and ownership.
- Comments explain invariants and reasons, not review history.
- Comments such as `P1-2`, temporary review references, and stale migration notes are removed or moved to ADRs/issues.
- Boolean parameters that alter security or lifecycle behaviour are replaced with named policies or value types.
- String maps at important boundaries are replaced with typed metadata where feasible.
- Functions return typed outcomes instead of relying on exception-message inspection.
- No generic `Manager`, `Helper`, or `Utils` class without a precise domain responsibility.

### Acceptance criteria

- A maintainer can locate provider, tool, approval, workflow, persistence, and evidence behaviour by package and type name.
- Critical invariants are visible next to the code that enforces them.

---

## Epic 11.2: AI-readable architecture map

**Goal:** Make the repository easy for an AI coding agent to navigate without loading the entire codebase.

### Deliverables

- `ARCHITECTURE.md` with the current module and runtime flow
- Per-module `README.md` or package documentation containing:
  - responsibility;
  - public entry points;
  - internal extension points;
  - dependencies;
  - lifecycle;
  - thread-safety expectations;
  - failure semantics;
  - relevant tests.
- `docs/architecture/execution-sequence.md`
- `docs/architecture/change-guides/` for common modifications:
  - adding a provider;
  - adding a workflow step;
  - adding a store;
  - adding an event;
  - adding an approval state;
  - changing structured-output constraints.

### Acceptance criteria

- An AI or new contributor can identify the correct extension point without editing a central god-object.
- Each change guide names the mandatory contract tests and quality checks.

---

## Epic 11.3: Contributor and review standards

### Deliverables

- `CONTRIBUTING.md` update
- PR template with architecture and compatibility checklist
- Security-sensitive review checklist
- Refactoring checklist
- Test naming and fixture guidance
- Suppression/waiver process

### Required PR questions

- What responsibility changed?
- Which architectural boundary owns it?
- What cancellation behaviour applies?
- Who owns created resources?
- What is the safe public error?
- What evidence/audit/telemetry is emitted?
- Is the change replay-safe?
- Which TCK or characterization test proves compatibility?
- Does this change stable API or persisted data?

---

# Phase 12 — Stabilisation and 0.6.0 Release Proof

## Epic 12.1: Performance and resource baseline

### Benchmarks

- Service proxy creation
- Operation-plan compilation
- Cached invocation dispatch overhead
- Structured contract compilation
- Structured validation
- Provider routing
- Tool-call governance overhead
- Approval suspend/resume coordination
- Evidence export
- Workflow checkpoint/resume
- Worker polling under empty and loaded queues

### Resource checks

- Coroutine/job leak tests
- File descriptor leak tests
- HTTP stream closure
- Subprocess termination
- Shutdown-hook cleanup
- Cache and registry boundedness

### Acceptance criteria

- Material regressions require explicit release notes and rationale.
- No known resource leak remains in the supported lifecycle.

---

## Epic 12.2: Example and documentation migration

### Tasks

1. Migrate all examples to the canonical lifecycle API.
2. Migrate examples away from deprecated preview APIs.
3. Ensure examples use explicit safe error handling.
4. Ensure governed examples use explicit outbound network and tool policies.
5. Update module guides after Spring modularization.
6. Update architecture diagrams to match actual package boundaries.
7. Remove duplicated or obsolete design documents.
8. Mark historical documents clearly and keep them out of primary navigation.

### Acceptance criteria

- Every public example compiles against published 0.6.0 artifacts.
- Documentation contains no stale type or module names.
- One canonical page exists for each major concept.

---

## Epic 12.3: Independent code review

**Goal:** Test whether the codebase communicates quality to someone who did not build it.

### Review profiles

- Senior Kotlin/JVM engineer
- Distributed systems engineer
- Application security engineer
- Spring Boot maintainer
- Open-source contributor unfamiliar with TramAI
- Independent AI coding/review agent with no prior conversation context

### Review questions

- Can the execution path be followed without reading a multi-thousand-line class?
- Are cancellation and lifecycle rules obvious?
- Are provider and persistence contracts consistent?
- Are security boundaries accurately described?
- Are failures safe and diagnosable?
- Can a new provider, store, or workflow step be added through a documented extension path?
- Do tests describe behaviour rather than internal implementation?
- Does module structure communicate product boundaries?

### Acceptance criteria

- P0/P1 review findings are resolved before release.
- Deferred P2/P3 findings are recorded with owners and rationale.

---

## Epic 12.4: Release verification command

### Deliverable

```bash
./gradlew verify060MaintainabilityRelease
```

### The command must verify

- full `check` lifecycle;
- formatting and static analysis;
- cancellation architecture rules;
- module dependency rules;
- binary API compatibility;
- provider TCK;
- store TCK;
- workflow and worker state-machine suites;
- Spring minimal-consumer tests;
- Java and Kotlin consumer tests;
- evidence and zero-egress verification;
- publication metadata;
- local publication and consumer resolution;
- documentation link/reference integrity;
- no unexpected maintainability-budget regression;
- release notes and migration guide presence.

---

# 6. Proposed PR Sequence

PR numbers should be assigned only when work begins. The sequence below is intentionally ordered to reduce risk.

| Order | Suggested title | Main outcome |
|---:|---|---|
| 1 | `docs(0.6): define maintainability baseline and architecture invariants` | Canonical roadmap, baseline, ADR plan |
| 2 | `fix(coroutines): preserve cancellation across providers` | Provider cancellation correctness |
| 3 | `fix(coroutines): preserve cancellation across tools and workflow steps` | Tool/workflow cancellation correctness |
| 4 | `fix(errors): introduce safe runtime failure boundaries` | Stable safe failures and redaction |
| 5 | `fix(lifecycle): make standalone runtime ownership explicit` | Closeable runtime and leak tests |
| 6 | `fix(http): correct and harden outbound network policy` | SSRF boundary accuracy |
| 7 | `refactor(config): introduce immutable engine component groups` | Constructor/composition foundation |
| 8 | `refactor(routing): introduce authoritative provider routing plan` | Remove duplicated routing state |
| 9 | `test(engine): add semantic execution trace characterization` | Refactor safety net |
| 10 | `refactor(engine): extract operation definition compiler` | Reflection/planning separation |
| 11 | `refactor(engine): extract provider execution coordinator` | Routing/retry/fallback isolation |
| 12 | `refactor(engine): extract tool execution coordinators` | Tool boundary isolation |
| 13 | `refactor(engine): extract approval suspension and resume` | Approval boundary isolation |
| 14 | `refactor(engine): extract memory cache budget and streaming` | Remaining subsystem isolation |
| 15 | `refactor(engine): reduce invocation handler to proxy adapter` | Complete engine decomposition |
| 16 | `refactor(workflow): introduce polymorphic step execution` | Remove central concrete dispatch |
| 17 | `refactor(workflow): split workflow definition runner and builder` | Workflow file decomposition |
| 18 | `refactor(worker): replace global workflow binding registry` | Instance-scoped registration |
| 19 | `refactor(worker): split lifecycle leasing polling and execution` | Worker decomposition |
| 20 | `refactor(failures): separate idempotency retryability and replayability` | Correct failure semantics |
| 21 | `refactor(events): introduce typed event and attribute catalogue` | Operational contract consistency |
| 22 | `test(provider): add provider compatibility kit` | Uniform provider contract |
| 23 | `refactor(provider): centralize safe transport utilities` | Reduce adapter duplication |
| 24 | `refactor(structured): introduce structured type descriptor` | One schema/validation model |
| 25 | `test(structured): add contract compatibility kit` | Descriptor and repair assurance |
| 26 | `test(persistence): add shared store compatibility kits` | Uniform store semantics |
| 27 | `test(runtime): add lifecycle state-machine and concurrency suites` | Approval/worker/lease assurance |
| 28 | `refactor(spring): split core provider and secret auto-configurations` | Optional integration dependencies |
| 29 | `build: add machine-readable module manifest and boundary checks` | Module governance |
| 30 | `build: extract convention plugins and typed release tasks` | Build maintainability |
| 31 | `quality: add formatting static analysis and API checks` | Mandatory PR gates |
| 32 | `quality: add coverage mutation and architecture verification` | Deep quality assurance |
| 33 | `docs: add architecture maps and change guides` | Human/AI navigation |
| 34 | `test(0.6): add compatibility consumers benchmarks and leak tests` | Release stabilization |
| 35 | `release: prepare TramAI 0.6.0 maintainability evidence` | Final release candidate |

The sequence may be split further. No PR should combine unrelated engine, workflow, build, and documentation rewrites merely to reduce PR count.

---

# 7. Definition of Done for Every 0.6.0 Refactoring PR

A refactoring PR is complete only when:

- [ ] The responsibility being moved is named explicitly.
- [ ] Characterization tests existed before the move or are added first.
- [ ] Cancellation behaviour is tested.
- [ ] Resource ownership is documented.
- [ ] Safe public and model-visible errors are tested.
- [ ] Audit/evidence/observer ordering is preserved or intentionally changed with an ADR.
- [ ] Thread-safety assumptions are documented.
- [ ] Stable APIs are unchanged or compatibility approval is recorded.
- [ ] Persisted formats are unchanged or migration is provided.
- [ ] No new circular dependency is introduced.
- [ ] The original hotspot becomes smaller in responsibility, not merely split by file length.
- [ ] New abstractions have direct tests.
- [ ] `./gradlew check` passes.
- [ ] Relevant TCKs pass.
- [ ] Documentation references the new authoritative location.
- [ ] Old duplicate code and stale comments are removed.

---

# 8. Maintainability Scorecard

The baseline phase records exact starting values. The release should satisfy the following directional goals.

| Area | 0.6.0 target |
|---|---|
| Invocation architecture | Proxy handler is a thin adapter; subsystems have cohesive coordinators |
| Workflow architecture | No central growing concrete-step dispatch; no process-global binding registry |
| Cancellation | Contract-tested across providers, tools, steps, workers, and stores |
| Lifecycle | Every runtime-owned resource has an explicit owner and deterministic close path |
| Configuration | One immutable provider-routing plan; component groups validate invariants once |
| Structured output | One descriptor drives schema, shape validation, value validation, and fingerprinting |
| Providers | Every published provider passes a shared TCK |
| Persistence | Every published store passes a shared TCK |
| Errors | Public/model/audit/telemetry surfaces use safe typed failures |
| Events | Event names and attributes come from a typed catalogue |
| Modules | Machine-readable maturity and dependency model; no forbidden edges or cycles |
| Build | Convention plugins and typed tasks; root build scripts primarily declarative |
| API | Stable API protected by compatibility validation |
| Tests | Critical state machines, concurrency, negative paths, and mutation results covered |
| CI | `check`, architecture, API, and compatibility gates mandatory |
| Documentation | Current architecture and extension paths match the code |
| Readability | Existing hotspots reduced; new complexity budgets enforced |

---

# 9. Release Acceptance Criteria

TramAI 0.6.0 may be tagged only when all of the following are true.

## Correctness

- [ ] Cancellation propagation is consistent across all supported runtime boundaries.
- [ ] Runtime lifecycle and shutdown tests pass without leaked jobs, hooks, streams, or processes.
- [ ] Tool retry semantics distinguish idempotency and retryability.
- [ ] Workflow registration is instance-scoped and type-safe.
- [ ] Safe-error tests prove sensitive fixtures do not cross public boundaries.
- [ ] HTTP network-security claims match actual guarantees.

## Architecture

- [ ] The invocation handler is a thin adapter.
- [ ] Provider, tool, approval, structured response, memory, cache, budget, and streaming responsibilities have cohesive owners.
- [ ] Workflow definition, execution, builder, persistence, and worker lifecycle are separated.
- [ ] One provider-routing plan is authoritative.
- [ ] No process-global mutable runtime registry remains.
- [ ] Module dependency rules pass.

## Quality

- [ ] Formatting and static analysis pass.
- [ ] Binary compatibility passes.
- [ ] Provider and store TCKs pass.
- [ ] Critical state-machine and concurrency tests pass repeatedly.
- [ ] Coverage does not regress from the approved baseline.
- [ ] Critical mutation-testing targets meet the approved threshold or have reviewed exceptions.
- [ ] No unexplained maintainability-budget regression exists.

## Build and release

- [ ] Standard developer tasks support configuration cache where intended.
- [ ] Build-logic TestKit tests pass.
- [ ] Publication metadata and BOM verification pass.
- [ ] Local publication and external consumer smoke tests pass.
- [ ] JDK 21 and the current supported CI JDK pass.
- [ ] Sovereign and zero-egress verification remain green.
- [ ] `verify060MaintainabilityRelease` passes from a clean checkout.

## Documentation

- [ ] Architecture diagrams reflect real package/module boundaries.
- [ ] Public lifecycle and failure semantics are documented.
- [ ] Provider, store, workflow-step, and event extension guides exist.
- [ ] Migration guide from 0.5.x exists.
- [ ] Historical plans are clearly separated from active guidance.
- [ ] README and status documents accurately describe 0.6.0 without production or compliance overclaims.

---

# 10. Risks and Controls

## Risk: Refactoring changes security-sensitive ordering

**Control:** Semantic execution-trace tests and small responsibility-focused PRs.

## Risk: Excess abstraction hides the runtime

**Control:** Prefer domain-specific coordinators and immutable data over generic pipelines, service locators, or reflection-heavy dependency injection.

## Risk: Public API churn delays adoption

**Control:** Protect stable APIs; change preview/internal APIs with migration guides; introduce internal component models behind current builders first.

## Risk: Quality tooling creates noise

**Control:** Baseline existing violations, prohibit growth, then burn down hotspots. Suppressions require rationale.

## Risk: The release becomes endless cleanup

**Control:** Use the release acceptance criteria and explicit non-goals. Defer optional aesthetic work that does not improve correctness, ownership, contract consistency, or navigability.

## Risk: Test count grows while confidence does not

**Control:** Prioritize contract tests, state-machine tests, concurrency tests, mutation testing, and negative security fixtures over duplicate implementation-level tests.

## Risk: Module splitting increases complexity

**Control:** Split only when it removes unwanted dependencies or clarifies ownership. Module count is not a quality metric by itself.

---

# 11. Recommended Release Governance

- 0.5.0 is published and verified before 0.6.0 architectural work begins.
- `master` advances to `0.6.0-SNAPSHOT` immediately after the 0.5.0 release.
- New broad feature epics are paused until Phase 3 and Phase 4 architecture foundations are complete.
- Every 0.6.0 PR maps to one roadmap epic.
- Every architecture decision is recorded through an ADR.
- P0 correctness work may be backported to 0.5.x when user-impact and compatibility justify it.
- A release branch is created only after all structural phases are complete.
- The release candidate receives independent human and AI review from a clean-context perspective.

---

# 12. Final 0.6.0 Outcome

The release is successful when a reader can open TramAI and quickly understand:

1. how an annotated service call becomes an execution plan;
2. where provider routing, retry, and fallback are decided;
3. where policy and DLP are enforced;
4. how tools are exposed, authorized, executed, approved, filtered, and reinjected;
5. how approval suspension and resume remain replay-safe;
6. how workflow steps, checkpoints, workers, and leases interact;
7. which failures are safe to retry;
8. which resources must be closed;
9. which events and evidence records are authoritative;
10. how to add a provider, store, workflow step, or integration without editing a god-object.

The quality bar is not perfection or aesthetic uniformity. It is this:

> **A competent engineer should trust TramAI more after reading its code than before reading it.**

And:

> **An AI coding agent should be able to make a local change without needing the whole repository in context or accidentally crossing an invisible boundary.**

That is the architectural promise of TramAI 0.6.0.
