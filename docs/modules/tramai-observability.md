# Module: `tramai-observability`

> **One-liner:** Optional, opt-in OpenTelemetry integration — traces, metrics, and events for operations and workflows.
> **Module type:** `observability`
> **Group:** `dev.tramai`, **Version:** `0.3.1`
> **Source files:** 3 (all in `dev.tramai.observability`), **LOC:** ~280

---


## Architecture

### Responsibility

Optional, opt-in OpenTelemetry integration: spans, metrics and events for operations, workers and workflows.

### Public entry points

- `OpenTelemetryOperationObserver` — operation/engine observation (implements core observation SPI)
- `OpenTelemetryTramaiWorkerObserver` — worker observation
- `OpenTelemetryWorkflowObserver` — workflow observation
- `OpenTelemetryAttributes` — semantic attribute constants

### Internal extension points

- Operation/worker/workflow observer implementations over the core observation SPI

### Significant dependencies

- `api(tramai-core)`; orchestration in implementation scope (see [module-catalog.yml](../../config/quality/module-catalog.yml))

### Lifecycle ownership

- Observer lifecycle follows the OpenTelemetry SDK; no engine state ownership

### Thread-safety and concurrency

- Observers must be safe for concurrent callbacks from the engine

### Failure semantics

- Observability must not break the happy path (failure-isolated observers); OTel absence degrades gracefully

### Contract tests / TCKs

- Span/attribute tests in `tramai-observability/src/test`

### Do not

- Do not make observability a hard dependency of core/runtime modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — operations-observability layer

---

## L1: Quick Start (30-second read)

### What

`tramai-observability` is an **optional, opt-in module** that bridges Tramai's observer SPIs (`OperationObserver` from `tramai-core`, `WorkflowObserver` from `tramai-orchestration`) to OpenTelemetry. It emits:

- **Traces** — spans for every provider call (`OpenTelemetryOperationObserver`) and every workflow execution (`OpenTelemetryWorkflowObserver`)
- **Metrics** — counters and histograms for attempt counts, duration, input/output token usage, parse failures, engine events, workflow runs, and workflow events
- **Events** — structured span events for parse failures, engine resilience/routing events, step lifecycle, checkpoints, leases, and delays

### Why

Without this module, Tramai runs with no-op observers — operations and workflows execute silently. Add `tramai-observability` when you need:

- **Production monitoring** — track latency, token consumption, and error rates per service method and workflow
- **Root-cause analysis** — distributed traces connecting AI calls to the business operation that triggered them
- **Cost attribution** — token usage broken down by service interface, method, and model
- **Workflow debugging** — step-by-step execution traces with checkpoint and lease lifecycle events
- **Structured parse failure visibility** — see exactly which raw responses failed validation and why

### When to use

- **Production applications** — always recommended when deploying to production
- **Development** — optional; the no-op observers keep test runs lightweight
- **Never forced** — `tramai-observability` is never pulled transitively. Add it explicitly when needed.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-observability:0.5.0")
    // You also need an OpenTelemetry SDK + exporter runtime dependency:
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp:...")
    runtimeOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:...")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-observability</artifactId>
    <version>0.5.0</version>
</dependency>
```

### Wiring

Wire into `tramai-engine` (operations) and `tramai-orchestration` (workflows) at construction time:

```kotlin
val openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setMeterProvider(meterProvider)
    .build()

// Operations
val engine = TramaiEngine(
    modelProviderRegistry = registry,
    sessionFactory = sessionFactory,
    operationObserver = OpenTelemetryOperationObserver(openTelemetry),
)

// Workflows
val workflow = workflow<MyState, MyResult>(name = "my-workflow") {
    // ... step definitions
}.build { ... }

workflow.run(
    initialState = ...,
    observer = OpenTelemetryWorkflowObserver(openTelemetry),
)
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Learn the observer SPIs | `tramai-core` (OperationObserver) / `tramai-orchestration` (WorkflowObserver) |
| Configure OpenTelemetry SDK | [OpenTelemetry Java SDK Docs](https://opentelemetry.io/docs/languages/java/) |
| See observability in a tutorial | [Observability Guide](../guides/observability.md) |
| Write tests with mock providers | `tramai-testing` |

---

## L2: Usage Guide (5-minute read)

### OpenTelemetry setup

Both observers are constructable two ways:

| Constructor | When to use |
|---|---|
| `OpenTelemetryOperationObserver(tracer, meter)` / `OpenTelemetryWorkflowObserver(tracer, meter)` | Custom instrumentation scope or pre-existing `Tracer`/`Meter` instances |
| `OpenTelemetryOperationObserver(openTelemetry)` / `OpenTelemetryWorkflowObserver(openTelemetry)` | Standard OpenTelemetry SDK setup — derives `Tracer` and `Meter` from the instance |

The default instrumentation name is `dev.tramai.observability`. Override via the second constructor parameter.

### Operation spans

Every call to an `@AiService` method that reaches a provider produces a span named `ai.{methodName}` (e.g., `ai.getWeather`).

**Span attributes set at start:**

| Attribute | Source | Example |
|---|---|---|
| `gen_ai.system` | `OperationCallContext.providerId` | `openai` |
| `gen_ai.request.model` | `OperationCallContext.requestedModel` | `gpt-4o` |
| `tramai.operation.interface` | `OperationCallContext.serviceInterface` | `com.example.WeatherService` |
| `tramai.operation.method` | `OperationCallContext.methodName` | `getWeather` |
| `tramai.retry.attempt` | `OperationCallContext.attempt` | `0` (zero-based) |

**Span attributes set during execution:**

| Attribute | Set when | Example |
|---|---|---|
| `gen_ai.response.model` | On provider response | `gpt-4o-2024-08-06` |
| `gen_ai.usage.input_tokens` | On provider response | `127` |
| `gen_ai.usage.output_tokens` | On provider response | `45` |
| `tramai.structured.parse_success` | On call completion | `true` / `false` |

**Span status:** Set to `ERROR` with the exception message when `onProviderFailure` is called.

### Workflow spans

Every workflow run produces a span named `workflow.{workflowName}` (e.g., `workflow.invoice-analysis`).

**Span attributes:**

| Attribute | Source | Example |
|---|---|---|
| `tramai.workflow.name` | Workflow name | `invoice-analysis` |
| `tramai.workflow.id` | `WorkflowContext.workflowId` | `uuid-string` |
| `tramai.workflow.context.*` | `WorkflowContext.attributes` | `tramai.workflow.context.userId` |
| `tramai.workflow.outcome` | On completion/failure | `success` / `failure` |

**Span status:** Set to `ERROR` with exception message when workflow fails.

### Operation metrics

All metrics are emitted via the `Meter` obtained from OpenTelemetry and carry dimension attributes for filtering.

| Metric name | Type | Unit | Description | Dimension attributes |
|---|---|---|---|---|
| `tramai.operation.attempts` | LongCounter | `{attempt}` | Completed provider attempts | `gen_ai.system`, `gen_ai.request.model`, `gen_ai.response.model`, `tramai.operation.interface`, `tramai.operation.method`, `tramai.retry.attempt`, `tramai.outcome`, `tramai.structured.parse_success`, `tramai.error.type` |
| `tramai.operation.duration` | DoubleHistogram | `ms` | Duration per provider attempt | Same as above |
| `tramai.operation.input_tokens` | LongCounter | `{token}` | Total input tokens observed | Same as above |
| `tramai.operation.output_tokens` | LongCounter | `{token}` | Total output tokens observed | Same as above |
| `tramai.operation.input_tokens.per_attempt` | LongHistogram | `{token}` | Distribution of input tokens per attempt | Same as above |
| `tramai.operation.output_tokens.per_attempt` | LongHistogram | `{token}` | Distribution of output tokens per attempt | Same as above |
| `tramai.operation.parse_failures` | LongCounter | `{failure}` | Structured parse/validation failures | Same as above |
| `tramai.engine.events` | LongCounter | `{event}` | Engine resilience/routing events | Same as above, plus `tramai.event.name` |

### Workflow metrics

| Metric name | Type | Unit | Description | Dimension attributes |
|---|---|---|---|---|
| `tramai.workflow.runs` | LongCounter | `{workflow}` | Completed workflow runs | `tramai.workflow.name`, `tramai.workflow.id`, `tramai.workflow.outcome`, `tramai.error.type` |
| `tramai.workflow.duration` | DoubleHistogram | `ms` | Duration per workflow run | Same as above |
| `tramai.workflow.events` | LongCounter | `{event}` | Workflow events emitted | `tramai.workflow.name`, `tramai.workflow.id`, `tramai.event.name`, plus event-specific attributes |

### Workflow span events

In addition to the root workflow span, the observer records structured span events for lifecycle transitions:

| Event name | When emitted | Attributes |
|---|---|---|
| `tramai.workflow.step.started` | Before each step executes | `step_name` |
| `tramai.workflow.step.completed` | After each step succeeds | `step_name` |
| `tramai.workflow.step.failed` | When a step throws | `step_name`, `error_type` |
| `tramai.workflow.checkpoint.saved` | After state checkpoint persisted | `workflow_id`, `next_step_index`, `step_executions`, `revision`, `has_last_completed_step` |
| `tramai.workflow.checkpoint.loaded` | On workflow resume | Same as saved + `definition_version`, `definition_digest_algorithm` |
| `tramai.workflow.lease.claimed` | Lease acquired for exclusive execution | `workflow_id`, `lease_id`, `owner_id`, `checkpoint_revision`, `acquired_at_epoch_millis`, `expires_at_epoch_millis` |
| `tramai.workflow.lease.renewed` | Lease heartbeat | Same as claimed |
| `tramai.workflow.lease.released` | Lease released on completion/abort | Same as claimed |
| `tramai.workflow.lease.conflict` | Lease claim rejected | `workflow_id`, `owner_id`, `checkpoint_revision`, `error_type` |
| `tramai.workflow.delay.started` | Delay step begins | `workflow_id`, `step_name`, `resume_at_epoch_millis` |
| `tramai.workflow.delay.resumed` | Delay duration elapsed | Same as started |
| `tramai.workflow.delay.waiting` | Checkpoint resumed but delay not yet elapsed | Same as started |
| `tramai.workflow.suspended` | Workflow suspended at a delay step | `workflow_id` |

User-defined workflow events (emitted via `observer.onWorkflowEvent(name, attributes, context)`) also appear as span events and contribute to `tramai.workflow.events` metric.

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-observability` follows the **observer pattern** — it implements two SPI interfaces (`OperationObserver`, `WorkflowObserver`) and translates their callback methods into OpenTelemetry spans, metrics, and events. The module is intentionally thin:

- **No shared state** beyond the active workflow span bookkeeping map
- **No configuration parsing** — OpenTelemetry SDK configuration is left entirely to the application
- **No exporter decisions** — the module works with any `OpenTelemetry` instance; the application decides where and how to export
- **Minimal surface** — three source files totaling ~280 LOC

### Dependency graph

```
tramai-observability
    ├── api: tramai-core           (OperationObserver, OperationCallContext, ModelResponse)
    ├── impl: tramai-orchestration (WorkflowObserver, WorkflowContext)
    └── impl: opentelemetry-api    (Tracer, Span, Meter, Attributes, etc.)
```

The module depends on `tramai-core` as an `api` dependency (exporting the implemented interfaces) and `tramai-orchestration` and `opentelemetry-api` as `implementation` (internal usage only). It has **no runtime dependency on the OpenTelemetry SDK** — only the API jar is required.

### Source file breakdown

#### `OpenTelemetryAttributes.kt`

A single internal utility function that converts `Map<String, Any?>` to `io.opentelemetry.api.common.Attributes` with type-aware mapping:

| Kotlin type | OTel attribute type |
|---|---|
| `String` | `stringKey` |
| `Boolean` | `booleanKey` |
| `Int` / `Long` | `longKey` |
| `Double` / `Float` | `doubleKey` |
| `null` | Skipped |
| Any other type | `.toString()` as `stringKey` |

This is used by both `SpanBackedObservation` and `ActiveWorkflowSpan` when adding event attributes.

#### `OpenTelemetryOperationObserver`

Implements `dev.tramai.core.observation.OperationObserver`. The lifecycle:

```
onCallStarted(context)
    │
    ├── Creates a span: "ai.{context.methodName}"
    ├── Sets span attributes: gen_ai.system, gen_ai.request.model,
    │   tramai.operation.interface, tramai.operation.method, tramai.retry.attempt
    └── Returns SpanBackedObservation
                 │
                 ├── onProviderResponse(response)
                 │   ├── Updates span: gen_ai.response.model, gen_ai.usage.*
                 │   └── Records metrics: input_tokens, output_tokens, *per_attempt
                 │
                 ├── onProviderFailure(error)
                 │   ├── span.recordException(error)
                 │   └── span.setStatus(StatusCode.ERROR)
                 │
                 ├── onStructuredParseFailure(rawResponse, errorSummary)
                 │   ├── span.addEvent("tramai.parse.failure", {raw, error})
                 │   └── metrics.parseFailures.add(1)
                 │
                 ├── onEngineEvent(name, attributes)
                 │   ├── span.addEvent(name, attributes)
                 │   └── metrics.engineEvents.add(1)
                 │
                 └── onCallCompleted(parseSuccess)
                     ├── span.setAttribute("tramai.structured.parse_success", ...)
                     ├── metrics.attempts.add(1)
                     ├── metrics.duration.record(...)
                     └── span.end()
```

**Internal classes:**

- **`SpanBackedObservation`** — implements `OperationObservation`, holds the active `Span`, `OpenTelemetryMetrics`, `baseAttributes`, and timing state (`startedAtNanos`)
- **`OpenTelemetryMetrics`** — holds all pre-built meter instrument instances (8 metrics total: 4 counters, 2 histograms, 2 LongHistograms)

**`tramai.outcome` calculation:**

Computed in `SpanBackedObservation.currentOutcome()`:
- `"failure"` — if `onProviderFailure` was called
- `"parse_failure"` — if `onStructuredParseFailure` was called (but the attempt may still be counted separately via `tramai.operation.parse_failures`)
- `"success"` — if `onProviderResponse` was called (and no failure)
- `"unknown"` — if none of the above

#### `OpenTelemetryWorkflowObserver`

Implements `dev.tramai.orchestration.WorkflowObserver`. The lifecycle:

```
onWorkflowStarted(name, context)
    │
    ├── Creates span: "workflow.{name}"
    ├── Sets attributes: tramai.workflow.name, tramai.workflow.id,
    │   tramai.workflow.context.* (one per context.attributes entry)
    └── Registers ActiveWorkflowSpan in concurrent map keyed by (name, workflowId)
                 │
                 ├── onStepStarted / onStepCompleted / onStepFailed
                 │   └── Adds span event: tramai.workflow.step.{lifecycle}
                 │
                 ├── onWorkflowEvent(name, attributes, context)
                 │   ├── span.addEvent(name, attributes)
                 │   └── metrics.events.add(1)
                 │
                 └── onWorkflowCompleted / onWorkflowFailed
                     ├── Removes from active map
                     ├── (on failure): span.recordException, StatusCode.ERROR
                     ├── Sets tramai.workflow.outcome
                     ├── metrics.runs.add(1)
                     ├── metrics.duration.record(...)
                     └── span.end()
```

**Internal classes:**

- **`WorkflowRunKey`** — immutable data class `(workflowName, workflowId)` used as the key in `ConcurrentHashMap` for tracking active workflows
- **`ActiveWorkflowSpan`** — holds the `Span`, `startedAtNanos`, and `baseAttributes`; provides a `recordEvent(name, attributes)` helper
- **`OpenTelemetryWorkflowMetrics`** — holds meter instruments for runs (counter), duration (histogram), and events (counter)

**Thread safety:** The `activeWorkflows` map uses `ConcurrentHashMap`, so multiple workflow runs can be observed concurrently without data races. Each active workflow is tracked by its unique `(name, workflowId)` pair and removed on completion or failure.

### Event mapping reference

| Observer callback | Span event | Metric instrument |
|---|---|---|
| `onProviderResponse` | — (span attributes only) | `input_tokens`, `output_tokens`, `*_per_attempt` |
| `onProviderFailure` | — (span exception + status) | — |
| `onStructuredParseFailure` | `tramai.parse.failure` | `parse_failures` |
| `onEngineEvent` | Pass-through (`name`) | `engine.events` |
| `onCallCompleted` | — (span end) | `attempts`, `duration` |
| `onWorkflowStarted` | — (span start) | — |
| `onStepStarted` | `tramai.workflow.step.started` | — |
| `onStepCompleted` | `tramai.workflow.step.completed` | — |
| `onStepFailed` | `tramai.workflow.step.failed` | — |
| `onWorkflowEvent` | Pass-through (`name`) | `workflow.events` |
| `onWorkflowCompleted` | — (span end) | `workflow.runs`, `workflow.duration` |
| `onWorkflowFailed` | — (span end + error) | `workflow.runs`, `workflow.duration` |

### What `tramai-observability` does NOT include

- **No OpenTelemetry SDK or exporter** — the module depends only on `opentelemetry-api`. SDK, exporters, and auto-configuration are the application's responsibility.
- **No Spring Boot auto-configuration** — that lives in `tramai-spring`.
- **No logging integration** — spans and metrics are the delivery mechanism, not log statements.
- **No workflow span nesting** — operation spans (from the engine) and workflow spans (from orchestration) are independent. Workflow steps that make AI calls produce their own `ai.*` spans under the workflow step's context if propagation is configured.
