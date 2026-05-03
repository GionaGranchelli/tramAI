# Orchestrator Design Review

- Review date: 2026-05-03
- Reviewed docs:
  - `docs/architecture/orchestrator-vision.md`
  - `docs/specs/spec-013-scheduler.md`
  - `docs/specs/spec-014-server.md`
  - `docs/specs/spec-015-agent-steps.md`
  - `docs/specs/spec-016-distributed-execution.md`
  - `docs/specs/spec-017-platform.md`
  - `docs/board/orchestrator-board.md`
  - `docs/roadmap.md`

## Executive Assessment

The orchestrator direction is coherent at the product level: it extends the
existing typed workflow DSL into scheduling, external protocol exposure,
external step execution, distributed workers, and a human-facing platform.
That layering is mostly compatible with the current TramAI architecture.

The design is not yet implementation-ready as written. The biggest issue is
scope compression: several tasks combine enough work for a full milestone,
especially MCP server support, worker failover/idempotency, plugin loading, and
multi-tenancy. The specs also need sharper acceptance criteria around
dependency boundaries, persistence semantics, auth/security seams, schema
compatibility, and failure behavior.

The highest architectural correction is to preserve the existing dependency
principle that observability is optional at the dependency level. The new docs
currently say "observability is non-optional" and "every new module emits
OpenTelemetry traces"; that is valid as a runtime capability expectation, but
it conflicts with the existing ADR/spec language unless rephrased as
"instrumentation hooks are mandatory; the OTel dependency remains opt-in."

## 1. Architecture Coherence

### What Layers Correctly

The proposed five modules layer reasonably if their dependencies are kept
strict:

- `tramai-scheduler` should sit above `tramai-orchestration` and depend only on
  workflow definitions, checkpoint/lease SPIs, clock abstractions, and observer
  events.
- `tramai-server` should sit above orchestration and scheduler, exposing a
  registry of workflow definitions without changing the workflow runtime.
- `tramai-agent` should add external step implementations above orchestration,
  not move external IO concepts into `tramai-engine`.
- `tramai-distributed` should consume orchestration persistence and lease SPIs,
  and should not become a second workflow engine.
- `tramai-platform` should sit above server and distributed runtime, owning UI,
  auth, tenant metadata, API keys, and plugin lifecycle.

This preserves the current boundary where `tramai-engine` owns single-call
provider execution and `tramai-orchestration` owns graph execution,
checkpointing, and workflow-level stop policy.

### Coherence Issues

1. **Observability wording conflicts with existing optionality.**

   `orchestrator-vision.md` says "Observability is non-optional." Existing
   module docs, SPEC-004, ADR-006, and ADR-012 say observability is a core
   product claim but optional at the dependency level. The orchestrator specs
   should say every module must emit observer events and attach OTel spans when
   `tramai-observability` is present. New modules should not require OTel
   transitively.

2. **Scheduler durability is assigned to the checkpoint store too early.**

   SPEC-013 stores `next_fire_time` in the workflow checkpoint table. That
   conflates workflow-run state with workflow-definition schedule state. A
   schedule can exist before any run exists, and a recurring workflow needs tick
   records independent of a single run checkpoint. A cleaner boundary is a
   scheduler store SPI, with a JDBC adapter that may share the same database but
   not necessarily the same checkpoint table.

3. **Server and platform framework boundaries need an ADR.**

   `tramai-server` looks framework-agnostic in the vision, while
   `tramai-platform` is explicitly a Spring Boot application. The docs should
   decide whether `tramai-server` is:

   - framework-free embedded server,
   - Spring Boot starter/application,
   - Ktor/Netty-based runtime,
   - or an SPI plus adapters.

   This is a module-shape decision that deserves an ADR before implementation.

4. **MCP direction is split between REST adapter and first-class protocol.**

   The vision says MCP is first-class and every workflow is automatically
   exposable as an MCP server. SPEC-014 says MCP is a thin adapter translating
   MCP calls into REST API calls. That is acceptable operationally, but the
   spec should define whether REST is an internal implementation detail or a
   public dependency. A local stdio MCP server should not have to start an HTTP
   server just to call a workflow in-process.

5. **`tramai-agent` depends on server config in one place.**

   SPEC-015 says MCP server config should be part of `TramaiServer` config.
   That couples agent steps to server usage. Agent steps must remain usable in
   library/standalone workflows without `tramai-server`. Put MCP client config
   in `tramai-agent`, with server optionally reusing the same config model.

6. **Distributed execution changes the semantics of SPEC-012 checkpointing.**

   SPEC-012 explicitly checkpoints at top-level step boundaries and does not
   resume mid-step. SPEC-016 says another worker resumes after a crash mid-step,
   which means the previous step may be re-executed unless the checkpoint was
   saved before the external side effect. The distributed spec needs a formal
   execution model for "started but not checkpointed" steps.

7. **Plugin system can violate typed DSL guarantees.**

   `TASK-039` says plugins register new DSL functions usable without code
   changes. Kotlin DSL functions are compile-time API, so a runtime-loaded JAR
   cannot add a statically typed builder function to already-compiled workflow
   code. Runtime plugin step types can be configured by name, but typed Kotlin
   extension functions require a compile-time dependency. This must be split
   into compile-time plugin libraries and runtime plugin adapters.

## 2. Spec Completeness

### Cross-Cutting Gaps

- **Dependency acceptance criteria are missing.** Each new optional module
  should have an acceptance criterion proving it does not pull unrelated
  modules transitively. For example, `tramai-scheduler` should not require
  `tramai-server`, and `tramai-agent` should not require `tramai-platform`.

- **Observer vs OTel contract is underspecified.** The specs list events but do
  not define stable event names, attributes, payload shape, cardinality limits,
  redaction behavior, or whether state/input/output is captured by default.

- **Security posture is too thin for shell/webhook/server/platform.** Shell
  steps, webhooks, API keys, dashboard I/O, and MCP exposure all have real
  security implications. At minimum, specs need acceptance criteria for secret
  redaction, maximum body/output sizes, command argument handling, webhook
  replay prevention, CORS defaults, and request size limits.

- **Schema compatibility is underdefined.** Server, MCP, webhooks, scheduler
  digesting, and platform version management all depend on typed state schema.
  The specs need a shared definition of workflow name, definition version,
  structural digest, input schema, output schema, and state migration policy.

- **Cancellation semantics are incomplete.** REST cancellation, delayed
  workflow cancellation, SSE closure, distributed leases, shell subprocess
  termination, HTTP cancellation, and MCP calls all need one common workflow
  cancellation model.

- **Storage model is not specified enough.** Run listing, event replay,
  scheduler ticks, worker heartbeat, audit log, API keys, tenant metadata, and
  plugin state each require persistence. The specs should identify which store
  owns each record, even if table names are deferred.

### SPEC-013: Scheduler

Good:

- Includes deterministic clock testing.
- Recognizes schedule digest compatibility.
- Treats `delayStep` as resumable, not just a sleep.

Missing or unclear:

- Define whether cron uses 5 fields, 6 fields, or both, and how seconds are
  handled.
- Define daylight saving behavior: skipped local times, repeated local times,
  and timezone database source.
- Define misfire policy explicitly: skip, fire once, catch up bounded number,
  or fail.
- Define schedule identity. Is a scheduled run identified by workflow name,
  workflow definition version, schedule ID, or run ID?
- Add acceptance criteria for concurrent scheduler instances being rejected or
  safely coordinated once SPEC-016 exists.
- Split calendar support into a follow-up unless there is a concrete calendar
  API and test matrix.

### SPEC-014: Server

Good:

- Keeps workflow definitions compile-time.
- Uses RFC 7807 for REST errors.
- Separates REST, MCP, webhook, and SSE surfaces.

Missing or unclear:

- "Workflow CRUD" is inaccurate because workflow definitions are not edited.
  Use "workflow run management."
- `POST /resume` lacks an ID in the route or body contract. The request shape
  should be explicit.
- Run status model is missing: pending, running, delayed, waiting for gate,
  cancelled, failed, completed, skipped, etc.
- Pagination/sorting/filtering are required but not specified.
- SSE says `GET .../events?since=N` and also `Last-Event-Id`; define precedence
  and retention.
- MCP transport support should be a formal compatibility matrix, especially
  stdio vs HTTP/SSE.
- Webhook signature support should include replay protection and timestamp
  tolerance, not only HMAC validation.
- Auth is deferred, but server still needs unauthenticated-safe defaults for
  local development vs production.

### SPEC-015: Agent Steps

Good:

- Correctly avoids replacing `aiStep`.
- Treats external IO as first-class step types with checkpointing,
  observability, timeout, and retry.
- Calls out large output handling.

Missing or unclear:

- The DSL examples use `state` inside builder assignments (`body =
  state.invoiceData`, string interpolation from state). That is not obviously
  viable unless those properties are lambdas evaluated at execution time. The
  API should make this explicit, e.g. `body { state -> state.invoiceData }`.
- Retry defaults need to be safe. Retrying POST, shell commands, Codex, or
  Hermes can duplicate side effects. Require explicit retry opt-in for
  non-idempotent step types.
- Shell command should prefer argv/list form over a single string to avoid
  shell injection ambiguity. If shell interpretation is supported, make it an
  explicit mode.
- Output capture needs limits, truncation policy, redaction policy, and where
  large output is stored.
- MCP result typing is vague. Define whether typed decoding uses structured
  module codecs, Kotlin serialization, Jackson, or caller-provided decoder.
- Hermes/Codex step behavior is underspecified because CLI output formats,
  exit codes, model flags, workspace mutation, and permissions vary.

### SPEC-016: Distributed Execution

Good:

- Builds on the existing lease/checkpoint SPI.
- Defines heartbeat, lease renewal, failover bound, and graceful shutdown.
- Catches workflow definition mismatch via digest.

Missing or unclear:

- Work queue state is not specified. Workers poll "pending" or "due"
  workflows, but no store/table/SPI owns pending work.
- Lease operations are not specified with atomicity requirements. Claim must be
  a single conditional write, not load-then-save.
- Partition pinning with `workflowId.hashCode() % N` is unstable if `N`
  changes and if Kotlin/JVM hash behavior is not explicitly controlled. Use a
  stable hash and define rebalance semantics.
- The idempotency example appears wrong: `idempotent = false` is annotated as
  safe for `npm build`, while false should mean unsafe to re-run. This should
  be corrected before implementation.
- "Workflow stays on one worker from start to finish" conflicts with failover
  and graceful handoff. Rephrase as "one active owner at a time."
- Acceptance criteria need split-brain tests: concurrent claim race,
  stale lease renewal, stale checkpoint write rejection, and duplicate tick
  prevention.

### SPEC-017: Platform

Good:

- Correctly defers visual workflow editing.
- Keeps workflows code-defined and CI-deployed.
- Identifies the right operator-facing views.

Missing or unclear:

- Dashboard, plugins, multi-tenancy, auth, API keys, rate limits, audit logs,
  and version management are too much for one spec/task. This should be split
  into at least three specs or milestones.
- Tenant isolation "all queries include team_id" is not strong enough as an
  acceptance criterion. Tests need cross-tenant access attempts across every
  read/write endpoint.
- Plugin isolation is internally contradictory: scope requires classloader
  isolation; risks propose starting with shared classloader. Pick one for v1.
- Plugin lifecycle is not feasible as "drop in JAR and available without code
  changes" for typed workflow DSL. Runtime adapters and compile-time DSL
  extensions need separate contracts.
- Workflow version management needs a compatibility model with checkpoint
  digesting and state schema evolution.
- Dashboard input/output display needs redaction and payload-size policy.

## 3. Feasibility of TASK-025 Through TASK-039

### Realistic With Tightening

- **TASK-025: Cron Schedule DSL and In-Process Timer**
  Realistic, but it conflicts with SPEC-013's design note favoring
  JDBC-polling durability. Scope it as a non-durable dev/local backend or merge
  it with the scheduler backend SPI.

- **TASK-026: Delay Step**
  Realistic if delay is defined as a persisted workflow status plus resume
  timestamp. Needs cancellation and early resume tests.

- **TASK-027: Durable Scheduling**
  Realistic only if backed by a dedicated scheduler store or explicit schedule
  records. Adding `next_fire_time` to checkpoint rows is likely too narrow.

- **TASK-028: Timezone and Calendar-Aware Scheduling**
  Timezone support is realistic. Holiday/business-hour calendars are
  underspecified and should probably be a second task after timezone/DST
  semantics are stable.

- **TASK-029: REST API**
  Realistic, but only after run storage/status/event models exist. OpenAPI
  generation from workflow definitions may be a separate task.

- **TASK-031: Webhook Receiver**
  Realistic if scoped to generic JSON plus one GitHub HMAC adapter. Rate limits
  should move to platform/security unless server owns a local limiter.

- **TASK-032: SSE Streaming**
  Realistic if there is a persisted event log or bounded in-memory buffer with
  documented retention. "Does not miss any events" requires persistence.

- **TASK-033: HTTP Step**
  Realistic. Needs stricter API shape for state-dependent request values,
  retry safety, redaction, and output limits.

- **TASK-034: Shell Step**
  Realistic for local execution, but security and output handling need more
  detail. Streaming output should not be optional if 10MB+ output is a quality
  requirement.

- **TASK-035: MCP Step**
  Realistic if transport scope is small. Supporting stdio/TCP/SSE, reconnect,
  typed results, and config discovery may need sub-tasks.

### Unrealistic or Too Broad As Written

- **TASK-030: MCP Server Adapter**
  Too broad for one task. It includes a JVM MCP server, stdio transport, SSE
  transport, schema generation, multiple tools, workflow-specific tools, client
  compatibility, and REST bridging. Split into: protocol core, stdio transport,
  HTTP/SSE transport, schema/tool generation, compatibility tests.

- **TASK-036: Hermes and Codex Agent Steps**
  Underspecified and integration-fragile. CLI behavior, permissions, model
  flags, workdir mutation, output parsing, and availability differ by machine.
  Treat these as experimental adapters behind separate artifacts or feature
  flags, not core Phase 8 blockers.

- **TASK-037: Distributed Worker Pool**
  Too broad for one task. Worker registry, queue model, lease semantics,
  failover, idempotency enforcement, graceful shutdown, partitioning, and OTel
  attribution should be broken down. This is the highest-risk backend task.

- **TASK-038: Admin Dashboard**
  Too broad unless intentionally thin. A usable dashboard can be a first slice,
  but schedule calendar, worker list, live updates, settings, and 1000-run
  performance need API/storage prerequisites.

- **TASK-039: Plugin System and Multi-Tenancy**
  Not feasible as one task. Plugin loading and tenant isolation are each major
  platform capabilities with separate security, persistence, compatibility,
  testing, and migration concerns. Split immediately.

## 4. API Design Review

### What Feels Right

- `workflow<State>("name") { ... }` remains the right anchor for Kotlin teams.
- `delayStep("cool-down", duration = 5, unit = MINUTES)` is understandable,
  though Kotlin `5.minutes` would be more idiomatic where available.
- `httpStep`, `shellStep`, and `mcpStep` are explicit and auditable. They fit
  the TramAI preference for named step types over hidden side effects.
- `TramaiServer.builder().workflows(...)` and `TramaiWorker.builder()` are
  understandable for standalone users and can map cleanly into Spring beans.

### API Design Concerns

1. **State-dependent DSL values should be lambdas.**

   The current examples imply `state` exists at workflow-build time. Kotlin
   teams will expect compile-time clarity. Prefer:

   ```kotlin
   httpStep("call-api") {
       url { state -> "https://api.example.com/invoices/${state.id}" }
       method = POST
       body { state -> state.invoiceData }
       merge { state, response -> state.copy(apiResult = response.body) }
   }
   ```

2. **Retry should be opt-in for side-effecting steps.**

   `RetryPolicy(maxAttempts = 3)` is natural for GET and pure local work, but
   dangerous for POST, shell deploys, and agent steps. The DSL should force a
   conscious choice when idempotency is unknown.

3. **Shell API should avoid stringly command execution by default.**

   Prefer:

   ```kotlin
   shellStep("deploy") {
       command("helm", "upgrade", "--install", "myapp", "./chart")
       workdir = Paths.get("/home/deploy/app")
   }
   ```

   If users want shell expansion, expose `shell("helm upgrade ...")` as an
   explicit less-safe mode.

4. **Spring Boot teams will want bean-native registration.**

   Builder APIs are fine, but SPEC-014/SPEC-017 should show Spring Boot usage:
   workflow beans discovered by type, server endpoints auto-configured, config
   under `tramai.server.*`, and optional security integration points.

5. **Java-friendly blocking support should remain explicit.**

   New server/worker APIs should not invent generated blocking variants. Follow
   the existing ADR direction: explicit blocking interfaces or builder entry
   points where needed.

6. **MCP and workflow-specific tools need naming rules.**

   Automatically turning workflow names into MCP tools needs stable escaping,
   collision handling, schema titles, and versioning. This matters for agents
   that cache tool lists.

## 5. Risk Assessment

### Hardest Part To Get Right

The hardest part is the interaction between durable scheduling, distributed
workers, retries, checkpointing, and side effects. Once a scheduled workflow
can run on multiple JVMs and execute HTTP/shell/MCP/agent steps, the platform
must prevent or explicitly account for duplicate execution. That requires:

- atomic tick claiming,
- one active worker lease,
- stale checkpoint write rejection,
- clear misfire policy,
- clear retry policy,
- step idempotency metadata,
- cancellation propagation,
- and observable failure payloads.

If this contract is vague, downstream users will see double webhooks, duplicate
deploys, repeated agent actions, or stuck runs.

### Other Material Risks

- **Security risk:** Shell steps, webhooks, MCP, API keys, and plugin loading
  materially expand the threat model. Security cannot wait until Phase 10 if
  server, webhook, and shell capabilities ship earlier.

- **Product-scope risk:** The roadmap jumps from library to Temporal/n8n/Airflow
  competitor. That is a product line, not a module sequence. The docs should
  distinguish "library extensions" from "self-hosted platform product."

- **Testability risk:** Several acceptance criteria depend on live Hermes,
  Codex, MCP clients, PostgreSQL, and browser UI. The specs should require
  deterministic fakes and compatibility smoke tests separately.

- **Optionality risk:** If server/platform decisions pull Spring Boot, Vue,
  OpenTelemetry, or MCP into lower modules, TramAI loses its current library
  shape.

- **Operational data risk:** Run history, event streaming, audit logs, and
  checkpoint state have different retention and privacy requirements. Treating
  them as one persistence concern will make the platform hard to operate.

## Roadmap and Board Issues

- `docs/roadmap.md` links to `../board/orchestrator-board.md`, but from
  `docs/roadmap.md` the board path should be `./board/orchestrator-board.md`.
- The orchestrator board's delivery snapshot says Phase 6 is in progress and
  lists TASK-025/TASK-026 under "In Progress", while the phase table marks all
  Phase 6 tasks as planned. Pick one status model.
- `TASK-025` says "in-process timer"; SPEC-013 design notes prefer
  JDBC-polling durability. Clarify whether TASK-025 is a temporary local
  backend or part of the production scheduler.
- Roadmap timelines are optimistic. The implementation effort implied by
  Phase 9 and Phase 10 is substantially larger than 3-4 and 4-6 weeks unless
  the first slice is intentionally narrow.

## Recommended Changes Before Implementation

1. Add ADRs for:
   - server runtime/framework choice,
   - scheduler store vs checkpoint store,
   - distributed execution and idempotency semantics,
   - plugin model: compile-time DSL plugins vs runtime platform plugins.

2. Revise observability language to preserve dependency-level optionality.

3. Split oversized tasks:
   - TASK-030 into MCP protocol/tool/schema/transport tasks,
   - TASK-037 into queue, lease, worker, failover, idempotency, shutdown tasks,
   - TASK-039 into plugin system, tenant model, auth/API keys, rate limiting,
     audit log, and migrations.

4. Add cross-cutting specs or sections for:
   - workflow run/status/event data model,
   - schema versioning and definition digesting,
   - cancellation semantics,
   - security and redaction,
   - persistence ownership and retention.

5. Tighten DSL examples so build-time configuration and runtime state access
   are visibly different.

6. Re-scope Phase 6 to prove the durable execution model first: one workflow,
   one schedule, one durable store, deterministic clock, restart recovery,
   missed tick policy, and duplicate-tick prevention.

