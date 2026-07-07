# Module: `tramai-orchestration`

> **One-liner:** Multi-step workflow engine with checkpoint/resume, distributed worker support, and a declarative DSL for composing AI, local, gate, branch, parallel, and delay steps.
> **Module type:** `optional`
> **Group:** `dev.tramai`, **Version:** `0.3.1`
> **Source files:** 18, **LOC:** 6,143
> **Dependencies:** `tramai-core`

---

## L1: Quick Start (30-second read)

### What

`tramai-orchestration` is a **multi-step workflow engine** for the JVM. It lets you compose long-running, stateful workflows as a sequence of typed steps — AI calls, local transforms, HTTP requests, shell commands, agent CLI invocations (Hermes, Codex), MCP tool calls, plugin-based extensions, gated decisions, conditional branches, parallel fan-outs, and timed delays — all under a single execution context with automatic checkpointing and resume.

### Why

A single `@AiService` invocation handles one AI turn. Real-world tasks often need multiple: fetch data → analyze with AI → transform → gate on result → branch → call another AI → persist. Without a workflow engine, you hand-roll state management, error recovery, and retry logic. `tramai-orchestration` provides:

- **Durability** — every top-level step boundary saves a checkpoint; crashed workflows resume from the last completed step, not from the start
- **Observability** — `WorkflowObserver` SPI for step-level events, timing, and failure tracking
- **Safety** — `StopPolicy` prevents runaway executions; `StepCounter` enforces execution budgets; definition-digest checking prevents resume from incompatible workflow versions
- **Distribution** — `TramaiWorker` polls a shared checkpoint catalog, claims leases, and executes workflows across a pool of nodes
- **Composability** — steps are plain functions; the DSL nests via `branchStep`, `parallelStep`, and nested `AbstractWorkflowBuilder`

### When to use

Use `tramai-orchestration` when your AI-backed task needs **more than one step** and you care about:

- Surviving process crashes mid-way through a multi-step task
- Distributing workflow execution across multiple machines
- Auditing step-by-step progress with checkpoint metadata
- Enforcing resource budgets (max step executions, max parallel branches)
- Delaying execution (e.g., wait for approval, polling, scheduled resume)
- Gating workflow progression on dynamic conditions
- Branching workflow paths based on state

Do **not** use it for single-turn AI calls — `tramai-engine` with an `@AiService` interface is the right choice for that. Reach for orchestration when you need a **sequence** of operations with shared state.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-orchestration:0.3.1")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-orchestration</artifactId>
    <version>0.3.1</version>
</dependency>
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.3.1"))
implementation("dev.tramai:tramai-orchestration")
```

### Where to go next

| Topic | Link |
|-------|------|
| Quickstart with all modules | `docs/guides/getting-started.md` |
| Understanding workflow basics | `docs/specs/spec-005.md` |
| Agent CLI step types (Hermes, Codex, MCP, Shell) | `docs/specs/spec-009.md` |
| Governed workflow quickstart | `docs/guides/governed-workflow-quickstart.md` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

The entry point is the `workflow<S>()` DSL function. It returns a `WorkflowBuilder<S>` that you configure with steps and then call `build<R>()` to produce a typed `Workflow<S, R>`.

```kotlin
import dev.tramai.orchestration.*
import dev.tramai.orchestration.WorkflowContext
import java.time.Clock

data class AnalysisState(
    val rawText: String = "",
    val summary: String = "",
    val score: Double = 0.0,
    val flagged: Boolean = false,
)

val analysisWorkflow = workflow<AnalysisState>(
    name = "text-analysis",
    definitionVersion = "1",
) {
    // 1. Summarize the input text using an AI call
    aiStep(
        name = "summarize",
        input = { state -> state.rawText },
        invoke = { text -> llmService.summarize(text) },
        merge = { state, summary -> state.copy(summary = summary) },
    )

    // 2. Gate: reject if summary is empty
    gateStep(name = "validate-summary") { state, _ ->
        if (state.summary.isBlank()) {
            GateDecision.reject("Summary was empty")
        } else {
            GateDecision.allow()
        }
    }

    // 3. Local transform: compute score from summary length
    localStep(name = "compute-score") { state, _ ->
        state.copy(score = state.summary.length.toDouble() / 100.0)
    }

    // 4. Branch: flag if score exceeds threshold
    branchStep(name = "quality-gate", select = { state ->
        if (state.score > 0.5) "flag" else "pass"
    }) {
        branch("flag") {
            localStep(name = "set-flagged") { state, _ ->
                state.copy(flagged = true)
            }
        }
        default {
            localStep(name = "set-passed") { state, _ ->
                state.copy(flagged = false)
            }
        }
    }
}.build { state ->
    AnalysisResult(
        summary = state.summary,
        score = state.score,
        flagged = state.flagged,
    )
}

// Execute
val result = analysisWorkflow.run(
    initialState = AnalysisState(rawText = "Long article text..."),
)
```

#### Suspend execution with persistence

To enable checkpoint/resume, pass a `WorkflowPersistence` object:

```kotlin
import dev.tramai.orchestration.FileWorkflowCheckpointStore
import java.nio.file.Path

val persistence = WorkflowPersistence(
    checkpointStore = FileWorkflowCheckpointStore(Path.of("./checkpoints")),
    stateCodec = JacksonWorkflowStateCodec(),
)

val result = analysisWorkflow.run(
    initialState = AnalysisState(rawText = "..."),
    persistence = persistence,
)
```

#### Resume a suspended workflow

```kotlin
val result = analysisWorkflow.resume(
    context = WorkflowContext(workflowId = "previous-workflow-id"),
    persistence = persistence,
)
```

### Step types

All step types are defined as **private** data classes (`InternalWorkflowStep<S>`). They are constructed only through **public builder methods** on `AbstractWorkflowBuilder<S>` (the superclass of `WorkflowBuilder<S>` and `BranchWorkflowBuilder<S>`).

| Builder method | Step type | Purpose |
|----------------|-----------|---------|
| `localStep(name, transform)` | `LocalWorkflowStep` | Pure state transformation `(S, WorkflowContext) -> S` |
| `aiStep(name, input, invoke, merge)` | `AiWorkflowStep` | AI call: extract `I` from state, invoke LLM to get `O`, merge `O` back into state |
| `httpStep(name, config, request, merge)` | `HttpWorkflowStep` | HTTP request with retry, URL validation, and response size limits |
| `shellStep(name, config, definition, command, merge)` | `ShellWorkflowStep` | Shell command execution with sandboxing, timeouts, and output limits |
| `hermesStep(name, config, prompt, merge)` | `HermesWorkflowStep` | Invoke Hermes agent CLI (streaming agent output) |
| `codexStep(name, config, prompt, merge)` | `CodexWorkflowStep` | Invoke Codex agent CLI |
| `mcpStep(name, config, definition, toolCall, merge)` | `McpWorkflowStep` | Call a tool via MCP server |
| `pluginStep(name, type, config, merge)` | `PluginWorkflowStep` | Extensible step routed through `ExternalStepExecutorResolver` |
| `gateStep(name, decide)` | `GateWorkflowStep` | Conditionally reject execution with `GateDecision` |
| `delayStep(name, duration, unit)` | `DelayWorkflowStep` | Pause workflow for a duration; checkpoint + suspend if persisted |
| `branchStep(name, select, configure)` | `BranchWorkflowStep` | Route execution to a named branch based on state |
| `parallelStep(name, items, invoke, merge)` | `ParallelWorkflowStep` | Fan-out: map `I -> O` across items, merge results |

#### Example: delay step with automatic checkpoint/suspend

```kotlin
workflow<MyState>(name = "approval-flow") {
    aiStep(name = "analyze") { /* ... */ }

    // Suspends the workflow for 1 hour; requires WorkflowPersistence
    delayStep(name = "wait-for-approval", duration = 1, unit = TimeUnit.HOURS)

    aiStep(name = "finalize") { /* ... */ }
}.build { it.result }
```

When a `delayStep` is reached and the resume time is in the future, the engine:
1. Saves a checkpoint at the current step index
2. Calls `WorkflowDelayWakeupScheduler.scheduleDelayWakeup()` if configured
3. Throws `WorkflowSuspendedException` to yield execution
4. On resume, reads the delay metadata and skips the step if the wait is over

#### Example: parallel step

```kotlin
workflow<AnalysisState>(name = "parallel-analysis") {
    parallelStep(
        name = "analyze-sources",
        items = { state -> state.sourceUrls },
        invoke = { url -> httpClient.fetch(url) },
        merge = { state, results -> state.copy(analyses = results) },
    )
}.build { it }
```

Each item runs in a `coroutineScope { async { ... } }`. The `StopPolicy.maxParallelBranches` limits concurrency.

### Checkpoint stores

`tramai-orchestration` ships with three `WorkflowCheckpointStore` implementations:

| Store | Backend | Features |
|-------|---------|----------|
| `FileWorkflowCheckpointStore` | Local filesystem (`.properties` envelope) | Atomic file-locked writes, revision-optimistic concurrency, `WorkflowCheckpointCatalog` support |
| `MarkdownWorkflowCheckpointStore` | Local filesystem (Markdown + frontmatter) | Human-auditable format, revision-optimistic concurrency |
| `JdbcWorkflowCheckpointStore` | JDBC (relational database) | Optimistic concurrency via SQL `UPDATE ... WHERE revision = ?`, `WorkflowCheckpointCatalog` support |
| `InMemoryWorkflowCheckpointStore` | In-memory `LinkedHashMap` | For tests and lightweight local use |

#### FileWorkflowCheckpointStore

```kotlin
val store = FileWorkflowCheckpointStore(
    rootDirectory = Path.of("/var/data/tramai/checkpoints"),
)

// Optional: custom path strategy
val customPathStore = FileWorkflowCheckpointStore(
    rootDirectory = Path.of("./checkpoints"),
    pathStrategy = DefaultWorkflowCheckpointPathStrategy("state.props"),
)
```

Files are stored as `<root>/<workflowName>/<workflowId>/checkpoint.properties`. Writes use `FileChannel.lock()` and atomic file moves.

#### MarkdownWorkflowCheckpointStore

```kotlin
val store = MarkdownWorkflowCheckpointStore(
    rootDirectory = Path.of("./audit-checkpoints"),
)
```

Files are stored as Markdown with YAML-style front matter and a fenced code block for the serialized state payload. The format is human-readable and can be inspected in version control.

#### JdbcWorkflowCheckpointStore

```kotlin
val store = JdbcWorkflowCheckpointStore(
    dataSource = dataSource,
    table = JdbcWorkflowCheckpointTable(tableName = "my_checkpoints"),
)

// Create the required table:
store.createTableSql() // → CREATE TABLE my_checkpoints ( ... )
```

Applications are responsible for supplying a `javax.sql.DataSource` and creating the target table. Column names are configurable via `JdbcWorkflowCheckpointTable`.

### Distributed workers

`TramaiWorker` enables multi-node workflow execution. Workers poll a shared checkpoint catalog, claim workflows via lease fencing, and execute resumes.

```kotlin
import dev.tramai.orchestration.*

// 1. Define and register your workflow
val workflow = workflow<MyState>(name = "my-workflow") {
    // ... steps
}.build { it.result }

workflow.registerWorkerBinding(
    stateCodec = JacksonWorkflowStateCodec(),
)

// 2. Create shared stores (e.g., JDBC-backed)
val checkpointStore = JdbcWorkflowCheckpointStore(dataSource)
val leaseStore = JdbcWorkflowLeaseStore(dataSource)

// 3. Create the worker
val worker = TramaiWorker(
    config = WorkerConfig(
        workerId = "worker-1",
        poolName = "default",
        pollIntervalMillis = 5_000,
        leaseDurationMillis = 30_000,
        drainTimeoutMillis = 60_000,
    ),
    leaseStore = leaseStore,
    checkpointStore = checkpointStore,
    workflowRegistry = mapOf("my-workflow" to workflow),
)

// 4. Start polling
worker.start()

// 5. On shutdown
worker.shutdown()
```

#### WorkerConfig

| Property | Default | Description |
|----------|---------|-------------|
| `workerId` | *(required)* | Unique worker identifier |
| `poolName` | *(required)* | Logical pool for partitioned routing |
| `capabilityLabels` | `emptySet()` | Worker capabilities for scheduling hints |
| `pollIntervalMillis` | `5_000` | Interval between checkpoint catalog polls |
| `leaseDurationMillis` | `30_000` | Duration of workflow lease ownership |
| `drainTimeoutMillis` | `60_000` | Grace period for in-flight work on shutdown |
| `partitionEnabled` | `false` | Enable hash-based partition routing |
| `workerCount` | `1` | Total workers in the partition group |

#### Lease stores

| Store | Backend |
|-------|---------|
| `FileWorkflowLeaseStore` | Local filesystem with file locking |
| `JdbcWorkflowLeaseStore` | JDBC with SQL row-level fencing |
| `InMemoryWorkflowLeaseStore` | In-memory (tests) |

Lease stores implement `WorkflowLeaseCheckpointFence` to atomically combine lease ownership checks with checkpoint mutations — preventing split-brain scenarios where a stale worker writes a checkpoint after losing its lease.

#### Worker registry

Workers registered via `WorkerRegistryStore` (co-located with lease stores) support:
- Heartbeat-based liveness detection
- Stale worker identification
- Partition-aware scheduling via `WorkflowCheckpointCatalog`

### Observer SPI

The `WorkflowObserver` interface provides lifecycle hooks:

```kotlin
class LoggingObserver : WorkflowObserver {
    override fun onWorkflowStarted(name: String, context: WorkflowContext) {
        println("[WORKFLOW] $name started: ${context.workflowId}")
    }

    override fun onStepStarted(name: String, stepName: String, context: WorkflowContext) {
        println("[STEP] $name/$stepName started")
    }

    override fun onStepCompleted(name: String, stepName: String, context: WorkflowContext) {
        println("[STEP] $name/$stepName completed")
    }

    override fun onStepFailed(name: String, stepName: String, error: Throwable, context: WorkflowContext) {
        println("[STEP] $name/$stepName failed: ${error.message}")
    }

    override fun onWorkflowCompleted(name: String, context: WorkflowContext) {
        println("[WORKFLOW] $name completed: ${context.workflowId}")
    }

    override fun onWorkflowFailed(name: String, error: Throwable, context: WorkflowContext) {
        println("[WORKFLOW] $name failed: ${error.message}")
    }

    override fun onWorkflowEvent(name: String, eventName: String, attributes: Map<String, Any?>, context: WorkflowContext) {
        println("[EVENT] $name: $eventName $attributes")
    }
}

// Usage
val result = workflow.run(
    initialState = ...,
    observer = LoggingObserver(),
)
```

Event names emitted by the engine:
- `tramai.workflow.checkpoint.saved` — after each top-level step completes
- `tramai.workflow.checkpoint.loaded` — on resume
- `tramai.workflow.suspended` — delay step yielded execution
- `tramai.workflow.delay.started` / `tramai.workflow.delay.waiting` / `tramai.workflow.delay.resumed` — delay lifecycle
- `tramai.workflow.lease.claimed` / `tramai.workflow.lease.renewed` / `tramai.workflow.lease.released` / `tramai.workflow.lease.conflict` — lease lifecycle

### Stop policy

`StopPolicy` enforces execution bounds:

```kotlin
workflow<MyState>(name = "bounded") {
    // ...
}.build(
    stopPolicy = StopPolicy(
        maxStepExecutions = 500,      // total step invocations across all retries/resumes
        maxParallelBranches = 32,      // max concurrent items in parallelStep
    ),
    resultSelector = { it },
)
```

When exceeded, a `WorkflowLimitExceededException` is thrown with the workflow name, step name, and the exceeded limit.

### External step executors (plugin system)

The `pluginStep` builder method delegates execution to an `ExternalStepExecutor` registered by type ID:

```kotlin
// Register a custom executor
val registry = ExternalStepExecutorRegistry()
registry.register(object : ExternalStepExecutorFactory {
    override val typeId = "my-custom-executor"
    override fun create() = ExternalStepExecutor { spec ->
        // Execute based on spec map
        mapOf("result" to "processed")
    }
})

// Use in workflow
workflow<MapState>(name = "plugin-demo") {
    pluginStep(
        name = "custom-step",
        type = "my-custom-executor",
        config = mapOf("param1" to "value1"),
    )
}.build(
    externalStepExecutorResolver = registry,
    resultSelector = { it },
)
```

### Agent CLI steps

`tramai-orchestration` includes built-in steps for agent CLI tools:

- **HermesStep** — calls the `hermes` CLI with a prompt; streams output with timeout and size limits
- **CodexStep** — calls the `codex` CLI; configurable CLI path, workdir, timeout
- **McpStep** — connects to an MCP server process, calls a tool, and returns the result
- **ShellStep** — executes arbitrary shell commands with sandboxing (allowed/denied commands, max output size, timeout)

All agent steps include:
- Configurable timeouts and output size limits
- `WorkflowObserver` events for lifecycle tracking
- Replay policy classification for safe distributed resumption

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-orchestration` is designed as a **deterministic step sequencer with durable checkpointing**. It deliberately decouples:

1. **Step definition** — what each step does (public builder DSL)
2. **Step dispatch** — how the engine loops through steps (internal execution loop)
3. **Checkpoint persistence** — where and how state is saved (`WorkflowCheckpointStore` SPI)
4. **Distributed ownership** — who gets to run which workflow (`WorkflowLeaseStore` SPI)
5. **Observability** — what gets reported (`WorkflowObserver` SPI)

This separation means you can swap persistence backends (filesystem vs JDBC), add worker nodes, or attach custom monitoring without changing workflow definitions.

### Module boundary

```
tramai-orchestration
  ├── api: tramai-core (used for provider resolution in aiStep)
  ├── impl: kotlinx-coroutines-core
  ├── impl: java.net.http (HttpClient for httpStep)
  └── test: [various providers for integration tests]
```

**Owns:**
- `Workflow<S, R>` — the typed workflow runtime with `run()` and `resume()`
- `workflow<S>()` — DSL entry point, `WorkflowBuilder<S>`, `AbstractWorkflowBuilder<S>`, `BranchBuilder<S>`, `BranchWorkflowBuilder<S>`
- All step implementations (`InternalWorkflowStep<S>` subtypes — all `private`)
- `WorkflowPersistence<S>` — persistence configuration combining checkpoint store, codec, lease store, and scheduler
- `WorkflowCheckpoint`, `WorkflowCheckpointStore`, `WorkflowStateCodec`, `WorkflowCheckpointCatalog` — checkpoint SPIs
- `WorkflowLease`, `WorkflowLeaseStore`, `WorkflowLeasePolicy`, `WorkflowLeaseCheckpointFence` — lease SPIs
- `WorkflowObserver`, `NoOpWorkflowObserver` — observer SPI
- `StopPolicy`, `StepCounter`, `StepAttemptRecord`, `ReplayPolicy`, `WorkflowStepReplayDescriptor` — execution controls
- `FileWorkflowCheckpointStore`, `MarkdownWorkflowCheckpointStore`, `JdbcWorkflowCheckpointStore`, `InMemoryWorkflowCheckpointStore` — checkpoint store implementations
- `FileWorkflowLeaseStore`, `JdbcWorkflowLeaseStore`, `InMemoryWorkflowLeaseStore` — lease store implementations
- `TramaiWorker`, `WorkerConfig`, `WorkerRegistryStore`, `TramaiWorkerObserver` — distributed worker system
- `ExternalStepExecutorResolver`, `ExternalStepExecutorRegistry`, `ExternalStepExecutor` — plugin step system
- Agent CLI steps: `HermesStep`, `CodexStep`, `McpStep`, `ShellStep`
- HTTP step: `HttpStep`
- `GateDecision`, all workflow exceptions (`WorkflowLimitExceededException`, `WorkflowBranchSelectionException`, `WorkflowGateRejectedException`, `WorkflowSuspendedException`, `WorkflowResumeException`, `WorkflowCheckpointConflictException`, `WorkflowLeaseConflictException`, `StaleWorkflowLeaseException`, `NonReplayableStepStateUnknownException`)

**Does not own:**
- AI provider execution — the `aiStep` builder receives its `invoke` lambda from the caller (typically backed by `tramai-engine` + a provider)
- Annotations / provider SPIs — owned by `tramai-core`
- Framework integration — owned by adapters (`tramai-spring`, `tramai-standalone`)

### Dependency graph

```
tramai-orchestration
  └── api: tramai-core
        └── kotlinx-coroutines-core

tramai-standalone ─── tramai-orchestration (optional, for agent workflows)
tramai-spring     ─── tramai-orchestration (optional, for agent workflows)
```

### Inner mechanics

#### 1. Workflow execution engine

The core execution loop lives in `Workflow<S, R>` (1642 LOC, single file). The engine's entry points are:

- **`run(initialState, context, observer, persistence)`** — starts fresh execution from step index 0
- **`resume(context, observer, persistence)`** — loads checkpoint, validates definition compatibility, resumes from saved `nextStepIndex`

The internal flow:

```
run()
  ├── observer.onWorkflowStarted()
  ├── StepCounter(stopPolicy) ← enforces maxStepExecutions
  ├── persistence.session() → WorkflowPersistenceSession
  │     ├── acquires lease (if configured)
  │     └── saves initial checkpoint
  ├── executeTopLevelSteps(startIndex=0, state)
  │     └── for each step at index:
  │           ├── stepCounter.beforeStep() ← throws if budget exceeded
  │           ├── observer.onStepStarted()
  │           ├── dispatch to concrete step type
  │           │     ├── LocalWorkflowStep → step.transform(state, ctx)
  │           │     ├── AiWorkflowStep → step.execute(state, ctx)
  │           │     ├── HttpWorkflowStep → execute with httpClient
  │           │     ├── ShellWorkflowStep → execute with ProcessBuilder
  │           │     ├── HermesWorkflowStep → execute agent CLI
  │           │     ├── CodexWorkflowStep → execute codex CLI
  │           │     ├── McpWorkflowStep → execute MCP tool call
  │           │     ├── PluginWorkflowStep → delegate to ExternalStepExecutor
  │           │     ├── GateWorkflowStep → evaluate GateDecision
  │           │     ├── DelayWorkflowStep → checkpoint, suspend, or continue
  │           │     ├── BranchWorkflowStep → select branch, recurse
  │           │     └── ParallelWorkflowStep → fan-out coroutines
  │           ├── observer.onStepCompleted() / onStepFailed()
  │           ├── persistenceSession.saveCheckpoint(state, nextIndex)
  │           └── renew lease if configured
  ├── persistenceSession.complete()
  │     ├── delete checkpoint (if deleteCheckpointOnCompletion)
  │     └── release lease
  ├── observer.onWorkflowCompleted()
  └── resultSelector(finalState)
```

#### 2. StepCounter

`StepCounter` is a private class that tracks total step executions across a workflow run (including across resumes):

```kotlin
private class StepCounter(
    val stopPolicy: StopPolicy,
    initialStepExecutions: Int = 0,  // seeded from checkpoint on resume
) {
    var stepExecutions: Int = initialStepExecutions
        private set

    fun beforeStep(workflowName: String, stepName: String) {
        if (stepExecutions >= stopPolicy.maxStepExecutions) {
            throw WorkflowLimitExceededException(...)
        }
        stepExecutions += 1
    }

    fun beforeParallelBranch(workflowName: String, stepName: String, branchIndex: Int) {
        // same budget check for parallel branches
    }
}
```

The counter is initialized from `checkpoint.stepExecutions` on resume, ensuring the budget is cumulative across all execution attempts.

#### 3. Checkpoint / resume protocol

**Checkpoint contents:**

```
WorkflowCheckpoint(
    workflowName,           // logical workflow name
    workflowId,             // UUID per run (stable across resumes)
    nextStepIndex,          // index into top-level steps list
    stepExecutions,         // cumulative step counter
    lastCompletedStepName,  // last successfully completed step
    statePayload,           // encoded by WorkflowStateCodec<S>
    revision,               // monotonically increasing, used for optimistic concurrency
    metadata,               // definition compatibility digest + delay metadata
    savedAtEpochMillis,
)
```

**Save points:** A checkpoint is saved:
1. At the **start** of `run()` (initial state, step index 0)
2. After each **top-level step** completes (step index = completed index + 1)
3. On `delayStep` suspension (same step index — the step re-executes and skips if time has passed)

**Resume protocol:**
1. Load checkpoint from `WorkflowCheckpointStore` by `(workflowName, workflowId)`
2. Validate `nextStepIndex` is in range `[0, steps.size]`
3. Extract and validate `WorkflowDefinitionCompatibility` from metadata:
   - `definitionVersion` must match
   - `digest` (SHA-256 of canonical step definition) must match
   - If the persisted digest differs from the current one, `WorkflowResumeException` is thrown — preventing incompatible workflow definitions from processing old checkpoints
4. Decode state payload via `WorkflowStateCodec<S>`
5. Initialize `StepCounter` with `initialStepExecutions = checkpoint.stepExecutions`
6. Resume execution from `startIndex = checkpoint.nextStepIndex`
7. Pass `resumedCheckpointMetadata` to the first resumed step (used by delay steps to skip elapsed waits)

**Conflict detection:** Checkpoint writes use optimistic concurrency with `expectedRevision`. If the persisted revision differs from the expected one, `WorkflowCheckpointConflictException` is thrown. This prevents two concurrent executors from overwriting each other's progress.

#### 4. Lease fencing

Lease fencing prevents **split-brain** in distributed environments. The protocol involves three components:

1. **`WorkflowLeaseStore`** — manages lease claim, renew, and release
2. **`WorkflowLeaseCheckpointFence`** — optionally implemented by lease stores to atomically combine lease ownership checks with checkpoint mutations
3. **`LeaseFencedCheckpointStore`** — a private decorator used by `TramaiWorker` that wraps any `WorkflowCheckpointStore` with lease verification

The fencing flow on `TramaiWorker`:

```
pollLoop()
  ├── listCheckpoints() ← scan checkpoint catalog
  ├── for each checkpoint without an active lease:
  │     ├── leaseStore.claim() ← acquire lease
  │     ├── launch execution coroutine
  │     │     ├── LeaseFencedCheckpointStore(delegate, leaseStore, leaseProvider)
  │     │     ├── Workflow.resume(persistence = fencedCheckpointStore)
  │     │     │     └── persistenceSession.saveCheckpoint()
  │     │     │           └── fencedStore.save()
  │     │     │                 └── leaseFence.saveCheckpointIfLeaseOwner()
  │     │     │                       ├── lock lease row
  │     │     │                       └── delegate.save()
  │     │     └── releaseLease()
  │     └── renewLeaseLoop() ← periodic renewal
```

The atomic fence guarantees:
- Only the current lease owner can write checkpoints
- Lease renewal updates both `expiresAt` and `checkpointRevision` in a single query
- On lease expiry, a new worker cannot read stale checkpoints written by the old worker

**JDBC fence implementation** (`JdbcWorkflowLeaseStore.saveCheckpointIfLeaseOwner`):
```
BEGIN TRANSACTION
  UPDATE lease_table SET checkpoint_revision = ?
    WHERE workflow_name = ? AND workflow_id = ?
      AND lease_id = ? AND owner_id = ? AND expires_at > NOW()
  If 0 rows updated: throw StaleWorkflowLeaseException
  UPDATE checkpoint_table SET ... WHERE ... AND revision = ?
COMMIT
```

**File-based fence implementation** (`FileWorkflowLeaseStore.saveCheckpointIfLeaseOwner`):
```
lock(lease.properties)
  read lease; validate ownership and expiry
  delegate.save(checkpoint) ← reuses shared file lock
unlock(lease.properties)
```

#### 5. WorkflowObserver SPI

`WorkflowObserver` is a **full lifecycle SPI** with 12 hook methods — all with default no-op implementations via `NoOpWorkflowObserver`. The hooks are:

| Method | Called when | Arguments |
|--------|-------------|-----------|
| `onWorkflowStarted` | `run()` or `resume()` begins | `workflowName`, `context` |
| `onWorkflowCompleted` | Workflow completes normally | `workflowName`, `context` |
| `onWorkflowFailed` | Workflow throws an exception | `workflowName`, `error`, `context` |
| `onStepStarted` | Any step begins execution | `workflowName`, `stepName`, `context` |
| `onStepCompleted` | Any step returns successfully | `workflowName`, `stepName`, `context` |
| `onStepFailed` | Any step throws an exception | `workflowName`, `stepName`, `error`, `context` |
| `onWorkflowEvent` | Engine emits a structured event | `workflowName`, `name`, `attributes`, `context` |
| `onScheduledTick` | Scheduler fires for a scheduled workflow | `workflowName`, `scheduledFireAt`, `context` |
| `onSkippedTick` | Scheduler tick skipped (e.g., workflow still running) | `workflowName`, `scheduledFireAt`, `reason`, `context` |
| `onMissedTick` | Scheduler detects a missed tick | `workflowName`, `scheduledFireAt`, `reason`, `context` |

The `TramaiWorker` uses an internal `WorkerExecutionObserver` (implements `WorkflowObserver`) that relays step lifecycle events to the `ExecutionTracker`, which records `StepAttemptRecord`s.

#### 6. Step replay policy

Every step type declares its replay behavior via `WorkflowStepReplayDescriptor`:

| ReplayPolicy | Meaning | Applied to |
|-------------|---------|------------|
| `PURE` | No side effects; safe to re-execute | `LocalWorkflowStep`, `GateWorkflowStep`, `DelayWorkflowStep`, `BranchWorkflowStep` |
| `IDEMPOTENT` | Side effects are safe to repeat | `AiWorkflowStep`, HTTP GET/HEAD/OPTIONS/PUT/DELETE |
| `EXTERNALLY_IDEMPOTENT` | Safe with an idempotency key | HTTP POST/PATCH with `Idempotency-Key` header |
| `NON_REPLAYABLE` | Cannot safely re-execute after interruption | `ShellWorkflowStep`, `HermesWorkflowStep`, `CodexWorkflowStep`, `McpWorkflowStep`, `PluginWorkflowStep`, `ParallelWorkflowStep` |

When `TramaiWorker` acquires a checkpoint with a `NON_REPLAYABLE` step in `STARTED` or `UNKNOWN` status, it throws `NonReplayableStepStateUnknownException`, forcing operator intervention.

#### 7. Definition compatibility digest

Before resuming from a checkpoint, the engine computes a canonical digest of the current workflow definition and compares it against the digest stored in the checkpoint metadata:

```
canonical form example:
workflow:text-analysis
stop_policy.max_step_executions:100
stop_policy.max_parallel_branches:16
schedule:none
local:validate-input
ai:summarize
gate:quality-gate
delay:wait:1:HOURS
```

The digest uses SHA-256. If either `definitionVersion`, `digestAlgorithm`, or the digest itself differs, `WorkflowResumeException` is thrown. This prevents:
- Running a modified workflow against old checkpoints
- Loading checkpoints created by a different workflow with the same name
- Silent data corruption from incompatible step reorderings

#### 8. Step type visibility

All step type implementations (`LocalWorkflowStep`, `AiWorkflowStep`, `GateWorkflowStep`, `DelayWorkflowStep`, `BranchWorkflowStep`, `ParallelWorkflowStep`, `HttpWorkflowStep`, `ShellWorkflowStep`, `HermesWorkflowStep`, `CodexWorkflowStep`, `McpWorkflowStep`, `PluginWorkflowStep`) are declared as **`private` or `internal` sealed subtypes** of `InternalWorkflowStep<S>`. The sealed interface itself is `internal`. This means:

- Application code **cannot** instantiate step types directly
- Step creation is only possible through the **public builder methods** on `AbstractWorkflowBuilder<S>`
- The set of step types is closed; new step types can only be added by modifying the module
- External extensions should use `pluginStep` with `ExternalStepExecutor`

#### 9. Distributed worker internals

`TramaiWorker` (849 LOC) implements a **poll-based work-stealing** pattern:

```
TramaiWorker.start()
  ├── registerWorker(workerId, poolName, version, capabilities, host)
  ├── heartbeatLoop()
  │     └── every pollInterval/2 ms: workerRegistryStore.updateHeartbeat()
  └── pollLoop()
        └── every pollInterval ms:
              ├── checkpointCatalog.listCheckpoints()
              ├── filter: not already active on this worker
              ├── filter: ownsPartition(workflowId) ← hash-based routing
              ├── filter: no current lease → leaseStore.currentLease() == null
              ├── claim: leaseStore.claim() ← try to acquire
              └── on success:
                    ├── launch execution coroutine
                    │     ├── ExecutionTracker.prepareForCheckpoint()
                    │     ├── recoverAttemptIfNeeded() ← handle prior crashes
                    │     ├── LeaseFencedCheckpointStore
                    │     ├── workflow.resume()
                    │     └── releaseLease()
                    └── launch renewLeaseLoop()
                          └── every leaseDuration/2 ms: leaseStore.renew()
```

**Partitioning:** When `partitionEnabled = true`, workflow IDs are hash-routed to a specific worker index via `stableHash(workflowId) % workerCount`. This reduces contention on the lease store.

**Drain protocol on shutdown:**
1. Stop accepting new work (`acceptingWork = false`)
2. Cancel poll loop
3. Wait up to `drainTimeoutMillis` for in-flight executions to complete
4. Cancel any residual executions
5. Unregister worker and cancel heartbeat

**ExecutionTracker** records `StepAttemptRecord`s at the step level:
- `STARTED` — when the step begins
- `COMPLETED` — when the checkpoint after the step is persisted
- `FAILED` — if the step throws
- `CANCELLED` — if the workflow is suspended or the worker shuts down
- `UNKNOWN` — set on recovery when a prior worker's lease expired mid-step

#### 10. Exception hierarchy

| Exception | Cause |
|-----------|-------|
| `WorkflowLimitExceededException` | `maxStepExecutions` or `maxParallelBranches` exceeded |
| `WorkflowBranchSelectionException` | `branchStep` selected an unconfigured branch key |
| `WorkflowGateRejectedException` | `gateStep` returned `allowed = false` |
| `WorkflowSuspendedException` | `delayStep` yielded execution (expected, non-error) |
| `WorkflowResumeException` | Resume failed: missing checkpoint, incompatible definition, invalid metadata |
| `WorkflowCheckpointConflictException` | Stale revision on checkpoint write/delete |
| `WorkflowLeaseConflictException` | Failed to claim, renew, or release a lease |
| `StaleWorkflowLeaseException` | Lease expired or fenced by another owner |
| `NonReplayableStepStateUnknownException` | Worker recovered a non-replayable step in indeterminate state |
| `WorkflowHttpException` | HTTP step failed after all retries |
| `AgentCliTimeoutException` | Agent CLI step exceeded its timeout |

---

### Configuration reference

#### `StopPolicy`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxStepExecutions` | `Int` | `100` | Total step invocations allowed across all resumes |
| `maxParallelBranches` | `Int` | `16` | Max concurrent items in `parallelStep` |

#### `WorkerConfig`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `workerId` | `String` | *(required)* | Unique identifier for this worker |
| `poolName` | `String` | *(required)* | Logical pool for routing and discovery |
| `capabilityLabels` | `Set<String>` | `emptySet()` | Worker capability labels |
| `pollIntervalMillis` | `Long` | `5_000` | Catalog poll interval |
| `leaseDurationMillis` | `Long` | `30_000` | Lease ownership duration |
| `drainTimeoutMillis` | `Long` | `60_000` | Graceful shutdown drain timeout |
| `partitionEnabled` | `Boolean` | `false` | Enable hash-based partition routing |
| `workerCount` | `Int` | `1` | Total partition group size |

#### `WorkflowLeasePolicy`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ownerId` | `String` | *(required)* | Owner identity for lease claims |
| `leaseDurationMillis` | `Long` | `30_000` | Lease duration in milliseconds |

#### `WorkflowPersistence<S>`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `checkpointStore` | `WorkflowCheckpointStore` | *(required)* | Backend for checkpoint persistence |
| `stateCodec` | `WorkflowStateCodec<S>` | *(required)* | Serialization for workflow state |
| `delayWakeupScheduler` | `WorkflowDelayWakeupScheduler?` | `null` | Scheduler bridge for delayed wakeups |
| `leaseStore` | `WorkflowLeaseStore?` | `null` | Lease coordination store (must pair with leasePolicy) |
| `leasePolicy` | `WorkflowLeasePolicy?` | `null` | Lease policy (must pair with leaseStore) |
| `deleteCheckpointOnCompletion` | `Boolean` | `true` | Auto-delete checkpoint on successful completion |

#### Step config reference

**`HttpStepConfig`:**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `timeoutSeconds` | `Long` | `30` | Request timeout |
| `maxResponseBytes` | `Long` | `1_048_576` | Max response body size (1 MB) |
| `retryOnStatus` | `Set<Int>` | `emptySet()` | HTTP status codes that trigger retry |
| `maxRetries` | `Int` | `0` | Max retry attempts |
| `allowedHosts` | `Set<String>?` | `null` | Allowlist of hostnames (null = allow all) |

**`ShellStepConfig`:**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `timeoutSeconds` | `Long` | `30` | Command timeout |
| `maxOutputBytes` | `Long` | `1_048_576` | Max stdout size (1 MB) |
| `failOnNonZeroExit` | `Boolean` | `true` | Treat non-zero exit as failure |
| `failOnStderr` | `Boolean` | `true` | Treat stderr output as failure |
| `allowedCommands` | `Set<String>?` | `null` | Command allowlist (null = allow all) |
| `deniedCommands` | `Set<String>` | `emptySet()` | Command denylist |

**`HermesStepConfig` / `CodexStepConfig`:**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `timeoutSeconds` | `Long` | `120` | Agent CLI timeout |
| `maxOutputBytes` | `Long` | `10_485_760` | Max output size (10 MB) |
| `cliPath` | `String` | `"hermes"` / `"codex"` | Path to CLI binary |
| `model` | `String` | varies | Model override |
| `workdir` | `String?` | `null` | Working directory (Codex only) |

**`McpStepConfig`:**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `timeoutSeconds` | `Long` | `120` | MCP tool call timeout |
| `maxOutputBytes` | `Long` | `10_485_760` | Max output size (10 MB) |
| `reconnect` | `Boolean` | `true` | Reconnect on server disconnect |
| `toolAllowlist` | `Set<String>?` | `null` | Allowlist of tool names |
| `allowedCommands` | `Set<String>?` | `null` | MCP server command allowlist |
| `deniedCommands` | `Set<String>` | `emptySet()` | MCP server command denylist |
