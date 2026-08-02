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

**Goal:** Separate internal diagnostic detail from public, model-visible, audit-visible, and telemetry-visible errors.

### Tasks

1. Define `SafeFailureCode` or domain-specific typed reason-code families.
2. Define four explicit error surfaces:
   - internal cause;
   - public caller message;
   - model-visible message;
   - audit/telemetry metadata.
3. Remove arbitrary exception messages from model-visible tool results.
4. Review provider HTTP failure handling so response bodies are:
   - bounded;
   - sanitised;
   - disabled or redacted by default;
   - never copied wholesale into public exceptions.
5. Review debug logging of provider bodies and secret-related paths.
6. Centralize safe error sanitisation for shell, HTTP, MCP, tools, providers, persistence, and approvals.
7. Add negative tests with tokens, prompts, paths, SQL fragments, command arguments, and malformed payloads.

### Acceptance criteria

- Sensitive fixtures never appear in public exceptions, model messages, normal logs, metrics, or exported evidence.
- Internal causes remain available to explicitly configured diagnostic sinks.
- Safe reason codes are stable and documented.
- Tests verify both redaction and diagnostic usefulness.

---

## Epic 1.3: Runtime lifecycle ownership

**Goal:** Ensure no convenience API creates an uncloseable runtime.

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

---

## Epic 1.4: HTTP network-boundary correctness

**Goal:** Remove unsupported security assumptions and strengthen outbound HTTP controls.

### Tasks

1. Correct comments and documentation that imply one DNS lookup fully prevents rebinding.
2. Define application-level URL validation as defence-in-depth rather than the sole egress boundary.
3. Introduce a pluggable outbound-network policy or controlled transport abstraction.
4. Expose the actual connected-address validation capability where technically possible.
5. Require explicit allowlists for governed/sovereign HTTP steps.
6. Document deployment-level firewall, proxy, service-mesh, or network-policy requirements.
7. Add tests for:
   - loopback;
   - RFC1918/private networks;
   - link-local;
   - IPv4 alternative encodings;
   - IPv6 local ranges;
   - redirects;
   - user-info confusion;
   - DNS changes between validation and connection where test infrastructure permits.

### Acceptance criteria

- No documentation claims complete DNS-rebinding prevention from pre-resolution alone.
- Governed profiles can require an explicit outbound transport policy.
- Redirect behaviour remains deny-by-default unless explicitly configured.

---

# Phase 2 — Runtime Composition Model

## Epic 2.1: Introduce immutable runtime component groups

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

1. Introduce immutable component groups without changing public builder APIs initially.
2. Move all all-or-none composition validation into component constructors or factories.
3. Distinguish required components, optional capabilities, and no-op implementations.
4. Replace nullable dependency clusters with explicit capability types where possible.
5. Document thread-safety and lifecycle ownership for every component group.
6. Ensure component snapshots are immutable after runtime construction.

### Acceptance criteria

- `TramaiEngine` and its main execution coordinators receive cohesive component groups rather than dozens of unrelated dependencies.
- Invalid partial approval, policy, persistence, or evidence configurations fail during build.
- Runtime code does not discover configuration dynamically.

---

## Epic 2.2: Create one provider-routing plan

**Goal:** Eliminate shadow configuration across standalone, sovereign, Spring, and provider-registry builders.

### Proposed model

```kotlin
data class ProviderRoutingPlan(
    val providers: Map<ProviderId, ModelProvider>,
    val routes: Map<ModelId, List<ProviderRoute>>, 
    val defaultProvider: ProviderId?,
)
```

### Tasks

1. Add typed provider and model identifiers or validated value classes.
2. Make duplicate provider registration fail rather than silently replace.
3. Validate blank names, unknown providers, duplicate routes, invalid defaults, and fallback loops during construction.
4. Expose an immutable routing-plan snapshot for validation and evidence generation.
5. Apply additional sovereign constraints as validation policies over the same plan.
6. Make Spring construct the same routing plan rather than reimplementing route logic.
7. Remove sovereign builder shadow maps after migration.

### Acceptance criteria

- One authoritative object represents configured provider routing.
- Standalone and sovereign modes differ through validation policy, not duplicated state.
- Evidence generation and runtime execution consume the same immutable plan.
- Invalid routes fail before service creation.

---

# Phase 3 — Engine Decomposition

## Epic 3.1: Characterize the execution pipeline

**Goal:** Protect exact behaviour before moving code.

### Required characterization coverage

- Request and prompt construction
- Memory injection and persistence
- Cache lookup and provenance checks
- Policy ordering
- Provider resolution
- Model registry authorization
- Retry and fallback ordering
- Circuit breaker transitions
- DLP inspection and sanitisation
- Structured-output repair retries
- Tool exposure policy
- Tool execution policy
- Tool approval suspension
- Tool reinjection filtering
- Approval resume and replay
- Observer callbacks
- Audit/evidence ordering
- Cancellation and timeout behaviour
- Streaming startup and terminal behaviour

### Deliverable

A deterministic execution-trace test fixture that records ordered semantic events and compares them with approved traces.

### Acceptance criteria

- Each extraction PR proves trace equivalence for affected scenarios.
- Security-sensitive ordering is tested directly, not inferred from final output.

---

## Epic 3.2: Extract operation planning

**Goal:** Separate reflection and operation-definition work from runtime execution.

### Components

- `ServiceDefinitionCompiler`
- `OperationDefinitionCompiler`
- `OperationExecutionPlan`
- `OperationFingerprintFactory`

### Tasks

1. Move service and operation metadata compilation out of the engine file.
2. Make operation plans immutable.
3. Cache safe reusable metadata where appropriate.
4. Keep reflection failures explicit and deterministic.
5. Preserve Java and Kotlin interop behaviour.

---

## Epic 3.3: Extract provider execution

**Goal:** Give routing, retries, fallbacks, circuit breaking, authorization, and provider observation one cohesive owner.

### Proposed components

- `ProviderExecutionCoordinator`
- `ProviderAttemptExecutor`
- `ProviderRetryPolicy`
- `ProviderFallbackPolicy`
- `ProviderAuthorizationService`

### Acceptance criteria

- Provider execution can be tested without tools, approvals, memory, or structured output.
- Retry and fallback decisions are typed results rather than scattered booleans.
- Cancellation never enters retry/fallback policy.
- Attempt observations are always completed exactly once.

---

## Epic 3.4: Extract tool execution

**Goal:** Give tool exposure, authorization, execution, retry, filtering, DLP, formatting, and reinjection explicit boundaries.

### Proposed components

- `ToolExposureCoordinator`
- `ToolAuthorizationCoordinator`
- `ToolInvocationExecutor`
- `ToolResultSanitizer`
- `ToolReinjectionCoordinator`

### Acceptance criteria

- Tool exposure and tool execution are independently testable.
- Idempotency and retryability are distinct.
- Model-visible tool messages use safe typed failures.
- DLP and size limits remain fail-closed where configured.
- Tool evidence remains ordered and complete.

---

## Epic 3.5: Extract approval suspension and resume

**Goal:** Isolate approval state transitions from general invocation dispatch.

### Proposed components

- `ApprovalSuspensionCoordinator`
- `ApprovalResumeCoordinator`
- `ContinuationClaimService`
- `ReplayAuthorizationService`
- `ResumeOperationRegistry`

### Acceptance criteria

- Suspension and resume each have a clear state-transition model.
- Store calls, token validation, version checks, claim, execution, completion, and cleanup order are explicit.
- Every terminal and retryable failure has a documented state effect.
- Replay tests remain deterministic across restart scenarios.

---

## Epic 3.6: Extract structured response, memory, cache, budget, and streaming coordinators

### Proposed components

- `StructuredResponseCoordinator`
- `ConversationMemoryCoordinator`
- `OperationCacheCoordinator`
- `TokenBudgetCoordinator`
- `StreamingExecutionCoordinator`

### Acceptance criteria

- Each component can be tested independently.
- The top-level invocation coordinator contains only execution sequencing.
- Component-specific state is not stored in unrelated coordinators.

---

## Epic 3.7: Reduce the invocation handler to an adapter

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

---

# Phase 4 — Workflow and Worker Decomposition

## Epic 4.1: Replace concrete-type central dispatch

**Goal:** Make workflow steps polymorphic through a shared execution request and result contract.

### Proposed contract

```kotlin
internal interface InternalWorkflowStep<S> {
    val name: String
    suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S>
}
```

### Tasks

1. Move each built-in step's execution behind the step contract or a registered executor.
2. Remove the growing central `when` over concrete step types.
3. Keep common observation, step counting, persistence, and error handling in one wrapper.
4. Define which step types can suspend and checkpoint.
5. Define nested-step suspension semantics explicitly.

### Acceptance criteria

- Adding a new built-in step does not require editing a central type-dispatch list.
- Shared execution rules remain consistent across all steps.
- Unsupported nested suspension fails at validation time where possible.

---

## Epic 4.2: Split `Workflow.kt`

### Target files/components

- `Workflow.kt` — immutable public definition
- `WorkflowRunner.kt` — run and resume coordination
- `WorkflowStepExecutor.kt` — common step wrapper
- `WorkflowBuilder.kt` — DSL
- `WorkflowObservation.kt` — observer contracts and event model
- `WorkflowErrors.kt` — exception taxonomy
- `WorkflowDefinitionCompatibility.kt`
- `WorkflowPersistenceSession.kt`
- `WorkflowBranchExecutor.kt`
- `WorkflowParallelExecutor.kt`
- `WorkflowDelayCoordinator.kt`

### Acceptance criteria

- Files align with one primary reason to change.
- The public DSL remains source-compatible where feasible.
- Definition compatibility remains deterministic.
- Persistence checkpoints retain backward compatibility or provide an explicit migration.

---

## Epic 4.3: Remove global worker workflow bindings

**Goal:** Make workflow registration explicit, instance-scoped, and type-safe.

### Tasks

1. Introduce `WorkflowBindingRegistry` as an injected runtime component.
2. Key bindings by a typed identity including name, definition version, and state/result metadata.
3. Remove unchecked retrieval based only on workflow name.
4. Remove implicit registration caused by executing a workflow.
5. Make worker startup validate all required bindings.
6. Define duplicate and conflicting registration behaviour.
7. Add isolation tests for multiple runtimes, application contexts, tenants, and parallel tests.

### Acceptance criteria

- No process-global workflow registry remains.
- Two runtimes can register workflows with the same name without interference.
- Type mismatches fail during registration rather than through unchecked cast behaviour.

---

## Epic 4.4: Worker state-machine decomposition

**Goal:** Split polling, leasing, heartbeat, execution, renewal, shutdown, and recovery responsibilities.

### Proposed components

- `WorkerLifecycleController`
- `CheckpointPoller`
- `LeaseCoordinator`
- `LeaseRenewalLoop`
- `WorkflowExecutionSupervisor`
- `WorkerHeartbeatPublisher`
- `WorkerShutdownCoordinator`

### Acceptance criteria

- Worker lifecycle states are explicit.
- Start, repeated start, graceful shutdown, crash, takeover, and timeout are model-tested.
- Every launched job has one owner.
- Shutdown-hook registration/removal is deterministic and tested.
- Wall-clock duration measurement uses an injected or monotonic time source.

---

# Phase 5 — Failure, Event, and Telemetry Contracts

## Epic 5.1: Separate idempotency, retryability, and replayability

**Goal:** Model three independent dimensions correctly.

### Required concepts

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

## Epic 6.1: Provider Technology Compatibility Kit

**Goal:** Make every provider adapter satisfy the same observable contract.

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

## Epic 6.2: Shared provider transport utilities

**Goal:** Remove repeated low-level HTTP and streaming concerns without hiding provider protocol differences.

### Candidate utilities

- Safe request builder
- Timeout application
- Retry-after parser using an injected clock
- Bounded response reader
- Safe provider error decoder
- SSE line parser primitives
- JSON response guards
- Resource-closing helpers
- Common usage-metrics model adapters

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

**Goal:** Make schema generation and validation consume one authoritative type model.

### Proposed model

```kotlin
sealed interface StructuredTypeDescriptor {
    data class Scalar(/* type, nullable, constraints */) : StructuredTypeDescriptor
    data class Collection(/* item, nullable, constraints */) : StructuredTypeDescriptor
    data class Object(/* name, properties, nullable */) : StructuredTypeDescriptor
}
```

### Tasks

1. Build descriptors from Kotlin reflection.
2. Build descriptors from Jackson JavaBean introspection.
3. Represent requiredness, nullability, descriptions, ranges, item constraints, and recursion explicitly.
4. Generate schema from the descriptor.
5. Validate raw JSON shape from the descriptor.
6. Validate deserialized values from the descriptor.
7. Generate a stable contract fingerprint from the descriptor.
8. Cache descriptors safely by type and configuration identity.
9. Define recursion and unsupported-type behaviour consistently.

### Acceptance criteria

- Schema generation and validation no longer implement separate type-dispatch trees.
- Kotlin and Java differences are explicit in descriptor compilation, not duplicated throughout validation.
- Every emitted schema rule has a matching validation rule or a documented reason why validation is delegated to deserialization.

---

## Epic 7.2: Structured-output contract TCK

### Required cases

- Kotlin data classes
- JavaBeans
- Nullable and non-null fields
- Primitive missing fields
- Nested objects
- Generic collections
- Root arrays
- Annotation constraints
- Unknown properties
- Recursive types
- Unsupported maps
- Malformed JSON
- Extra prose around JSON
- Repair feedback determinism
- Contract fingerprint evolution

### Acceptance criteria

- The same fixtures validate schema, shape, deserialization, value constraints, and repair messages.
- Contract drift tests explain exactly which descriptor element changed.

---

# Phase 8 — Persistence and Concurrency Assurance

## Epic 8.1: Persistence Store TCKs

**Goal:** Ensure in-memory, file, and JDBC implementations share the same behavioural contract.

### Store families

- Approval store
- Approval continuation store
- Suspended invocation store
- Audit store
- Audit outbox store
- Workflow checkpoint store
- Workflow lease store
- Step-attempt store
- Memory store

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

- Every published store implementation passes the relevant TCK.
- Implementation-specific tests cover only storage technology and performance differences.
- Contract failures use common typed exceptions or reason codes.

---

## Epic 8.2: State-machine and property-based tests

**Goal:** Test lifecycle logic through transitions rather than isolated examples.

### Targets

- Approval lifecycle
- Continuation lifecycle
- Worker lifecycle
- Lease lifecycle
- Outbox lifecycle
- Workflow checkpoint/resume lifecycle
- Circuit breaker states
- Provider retry/fallback decisions

### Tasks

1. Define state-transition models.
2. Generate valid and invalid action sequences.
3. Assert invariants after every transition.
4. Add concurrency tests for claims, versions, leases, duplicate decisions, and takeover.
5. Add deterministic schedulers/clocks for timing-sensitive tests.

---

## Epic 8.3: Time, randomness, and scheduling abstractions

**Goal:** Eliminate incidental nondeterminism from domain decisions.

### Tasks

1. Use `Clock` for wall-clock timestamps.
2. Use a monotonic time source for duration and timeout accounting where appropriate.
3. Inject jitter/random sources into retry policies.
4. Avoid direct `System.currentTimeMillis()` in domain logic.
5. Centralize scheduler ownership.
6. Make tests independent of real sleeps whenever possible.

### Acceptance criteria

- Critical timing tests use virtual or injected time.
- Retry and lease tests are deterministic.
- Duration logic does not break under wall-clock changes.

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

---

## Epic 9.2: Move build logic into `build-logic`

**Goal:** Make Gradle configuration modular, typed, testable, and mostly declarative.

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

### Required gates

- Kotlin formatting enforced in CI
- Detekt or equivalent static analysis
- Compiler warnings reviewed and treated as errors for TramAI code where feasible
- No unused dependencies
- No forbidden API usage
- No broad catch in suspend code without cancellation handling
- No raw thread or global scope creation outside approved lifecycle factories
- No unbounded response-body reads
- No direct sensitive payload logging

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
