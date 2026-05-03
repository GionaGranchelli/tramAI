# Orchestrator Design Solutions

- Review date: 2026-05-03
- Source review: [Orchestrator Design Review](./orchestrator-design-review.md)
- Target docs:
  - `docs/architecture/orchestrator-vision.md`
  - `docs/specs/spec-013-scheduler.md`
  - `docs/specs/spec-014-server.md`
  - `docs/specs/spec-015-agent-steps.md`
  - `docs/specs/spec-016-distributed-execution.md`
  - `docs/specs/spec-017-platform.md`
  - `docs/board/tasks/task-030.md`
  - `docs/board/tasks/task-037.md`
  - `docs/board/tasks/task-039.md`

## 1. Observability Optionality

The specs should distinguish mandatory instrumentation contracts from optional
OpenTelemetry dependencies.

Replace broad statements such as:

> Observability is non-optional.

or:

> Every new module emits OpenTelemetry traces.

with:

> Every orchestrator module must emit stable TramAI observer events for
> externally visible lifecycle transitions. OpenTelemetry export remains
> dependency-optional: modules must expose instrumentation hooks and semantic
> attributes, and `tramai-observability` maps those hooks to OTel spans,
> events, and metrics when present.

Add this cross-cutting acceptance criterion to SPEC-013 through SPEC-017:

- The module compiles and runs without `tramai-observability` on the runtime
  classpath.
- The module emits stable `WorkflowObserver` or module-specific observer events
  for all required lifecycle transitions.
- When `tramai-observability` is present, those observer events are bridged to
  OTel spans/events/metrics with documented names and attributes.
- Observer payloads redact configured secret fields and never include full
  request bodies, shell output, workflow state, API keys, webhook secrets, or
  MCP payloads by default.
- High-cardinality values such as workflow IDs, run IDs, step IDs, schedule
  IDs, worker IDs, tenant IDs, and tool names are explicitly documented before
  they are exported.

Module wording should use "observer event" for the core contract and "OTel"
only for the optional bridge. For example, SPEC-013 should say:

> The scheduler emits `onScheduleRegistered`, `onScheduledTickClaimed`,
> `onScheduledTickSkipped`, `onScheduledTickMisfired`, and
> `onScheduledTickCompleted` observer events. The optional observability module
> converts these events into OTel spans and metrics.

## 2. Scheduler Store vs Checkpoint Store

The scheduler needs its own SPI because schedule state exists independently of
workflow-run checkpoints. A schedule can be enabled before any run exists, and
each scheduled tick needs its own durable identity before it becomes a workflow
run.

### Ownership Boundary

- `WorkflowCheckpointStore` owns execution state for a specific workflow run:
  current step, serialized state, definition digest, run status, completed
  steps, and resume metadata.
- `WorkflowSchedulerStore` owns schedule definitions and tick records:
  registered schedules, next fire time, claimed ticks, skipped ticks, misfires,
  and the mapping from a tick to a started workflow run.
- Both stores may share one JDBC database and transaction manager, but they
  must not share the same logical table.

### Tables

`tramai_schedules`

| Column | Purpose |
| --- | --- |
| `schedule_id` | Stable schedule identifier, unique within workflow definition scope |
| `workflow_name` | Registered workflow name |
| `workflow_version` | Optional semantic version from workflow registration |
| `definition_digest` | Digest including workflow graph and schedule config |
| `schedule_type` | `cron`, `interval`, `one_time`, or `delay` |
| `schedule_expression` | Cron string, interval ISO-8601 duration, or timestamp |
| `zone_id` | IANA timezone for local-time schedules |
| `calendar_id` | Optional calendar reference for skip rules |
| `misfire_policy` | `skip`, `fire_once`, `catch_up_bounded`, or `fail` |
| `max_catch_up_ticks` | Required for bounded catch-up |
| `enabled` | Whether new ticks may be produced |
| `next_fire_at` | Next due instant in UTC |
| `created_at` | Audit timestamp |
| `updated_at` | Audit timestamp |

`tramai_schedule_ticks`

| Column | Purpose |
| --- | --- |
| `tick_id` | Stable tick ID, preferably derived from schedule ID and scheduled time |
| `schedule_id` | Owning schedule |
| `scheduled_fire_at` | The intended fire instant in UTC |
| `actual_fire_at` | Time the tick was claimed or emitted |
| `status` | `pending`, `claimed`, `started`, `completed`, `skipped`, `misfired`, `failed`, `cancelled` |
| `claim_owner` | Scheduler or worker ID that owns the tick |
| `claim_token` | Fencing token for stale write rejection |
| `claimed_until` | Claim expiry time |
| `workflow_run_id` | Run created from this tick, if any |
| `misfire_reason` | Machine-readable reason when status is `misfired` |
| `attempt` | Claim/start attempt count |
| `created_at` | Audit timestamp |
| `updated_at` | Audit timestamp |

`tramai_delay_wakeups`

This can be a view over schedule ticks or a small dedicated table. The
important difference from recurring schedules is that the identity is a
workflow run and step, not a workflow definition.

| Column | Purpose |
| --- | --- |
| `workflow_run_id` | Suspended run |
| `step_id` | Delay step |
| `resume_at` | Wake time in UTC |
| `status` | `waiting`, `claimed`, `resumed`, `cancelled`, `expired` |
| `claim_token` | Fencing token |
| `claimed_until` | Claim expiry time |

### SPI

```kotlin
interface WorkflowSchedulerStore {
    suspend fun upsertSchedule(schedule: ScheduleRecord): ScheduleRecord
    suspend fun disableSchedule(scheduleId: ScheduleId, expectedDigest: DefinitionDigest? = null)
    suspend fun getSchedule(scheduleId: ScheduleId): ScheduleRecord?
    suspend fun listEnabledSchedules(limit: Int, cursor: PageCursor? = null): Page<ScheduleRecord>

    suspend fun claimDueTicks(
        now: Instant,
        ownerId: SchedulerOwnerId,
        claimDuration: Duration,
        limit: Int
    ): List<ClaimedScheduleTick>

    suspend fun markTickStarted(
        tickId: ScheduleTickId,
        claimToken: ClaimToken,
        workflowRunId: WorkflowRunId
    )

    suspend fun markTickCompleted(tickId: ScheduleTickId, claimToken: ClaimToken)
    suspend fun markTickSkipped(tickId: ScheduleTickId, claimToken: ClaimToken, reason: SkipReason)
    suspend fun markTickMisfired(tickId: ScheduleTickId, claimToken: ClaimToken, reason: MisfireReason)
    suspend fun releaseTick(tickId: ScheduleTickId, claimToken: ClaimToken)

    suspend fun scheduleDelayWakeup(wakeup: DelayWakeupRecord)
    suspend fun claimDueDelayWakeups(
        now: Instant,
        ownerId: SchedulerOwnerId,
        claimDuration: Duration,
        limit: Int
    ): List<ClaimedDelayWakeup>
    suspend fun markDelayResumed(workflowRunId: WorkflowRunId, stepId: StepId, claimToken: ClaimToken)
    suspend fun cancelDelayWakeup(workflowRunId: WorkflowRunId, stepId: StepId, reason: CancellationReason)
}
```

`claimDueTicks` must be a single atomic operation. The JDBC implementation
should use `SELECT ... FOR UPDATE SKIP LOCKED` or an equivalent conditional
`UPDATE ... WHERE status = 'pending' AND scheduled_fire_at <= ? RETURNING *`.
It must not perform load-then-save claiming.

### Relation To Checkpoints

The scheduler creates or claims ticks. The workflow runtime creates or resumes
runs. The handoff is:

1. Scheduler store claims a due tick and receives a fencing token.
2. Runtime starts a workflow run with trigger metadata:
   `trigger = schedule(scheduleId, tickId, scheduledFireAt)`.
3. Runtime writes the initial checkpoint or run record.
4. Scheduler store marks the tick `started` with the `workflow_run_id`.
5. If the runtime fails before step execution begins, the tick remains
   claimable after its claim expires unless it was marked `started`.
6. If the workflow run later fails, that is run state in
   `WorkflowCheckpointStore`; the tick links to the run and does not create a
   second run unless an explicit retry policy says to do so.

## 3. Plugin System

The plugin model should split compile-time typed DSL plugins from runtime
platform plugins.

### Compile-Time DSL Plugins

Typed Kotlin functions require a compile-time dependency. A Slack step DSL such
as `slackMessageStep { ... }` must live in a normal library artifact that the
application compiles against:

```kotlin
implementation("dev.tramai.plugins:tramai-plugin-slack-dsl:1.0.0")
runtimeOnly("dev.tramai.plugins:tramai-plugin-slack-runtime:1.0.0")
```

The DSL artifact contributes extension functions:

```kotlin
fun <S : Any> WorkflowBuilder<S>.slackMessageStep(
    name: String,
    configure: SlackMessageStepBuilder<S>.() -> Unit
) {
    externalStep(name, SlackMessageStepSpec.from(configure))
}
```

This preserves Kotlin compile-time safety because the application source must
import the extension and compile successfully. The workflow digest includes the
plugin step type ID, plugin version, and serialized step spec.

Compile-time plugin acceptance criteria:

- A plugin DSL function is only available when its DSL artifact is on the
  compilation classpath.
- The generated step spec is serializable and included in the workflow
  definition digest.
- Removing the runtime plugin for a compiled workflow fails loudly at startup
  with plugin ID, required version range, workflow name, and step name.
- The DSL artifact does not depend on `tramai-platform`.

### Runtime Platform Plugins

Runtime plugins are discovered by the platform and register named capabilities:

- external step executors for already-compiled step specs,
- webhook adapters,
- dashboard panels,
- MCP/server adapters,
- secret resolvers or credential providers, if explicitly allowed.

Runtime plugins do not add Kotlin symbols to already-compiled workflow code.
They register factories by stable IDs:

```kotlin
interface TramaiRuntimePlugin {
    val pluginId: PluginId
    val version: SemVer
    fun stepExecutors(): List<ExternalStepExecutorFactory<*>>
    fun webhookAdapters(): List<WebhookAdapterFactory>
    fun dashboardExtensions(): List<DashboardExtension>
}
```

Generic runtime-configured steps are allowed, but they are not typed DSL
functions:

```kotlin
pluginStep("notify-slack", type = "slack.message") {
    config("channel", "#deploys")
    config("text") { state -> state.summary }
}
```

This API is weaker than a typed extension, so specs should describe it as a
dynamic escape hatch. It must validate config at workflow build or startup
using a plugin-provided schema.

### Registration Flow

1. Application compiles workflow definitions with core DSL plus any typed DSL
   plugin artifacts.
2. Each plugin DSL function emits a `StepSpec` with a stable `typeId`, for
   example `com.slack.message`.
3. Runtime scans installed runtime plugins and registers executor factories in
   `ExternalStepExecutorRegistry`.
4. Workflow startup validates every external step type against the registry.
5. Execution resolves the executor by `typeId` and `versionRange`.

No runtime JAR can add a typed DSL function to source code that has already
been compiled. The platform UI can expose installed dynamic plugin step types
for inspection and future template generation, but not for retroactively
changing compiled workflows.

## 4. TASK Splits

Use hierarchical task names that preserve the original task number as an epic
prefix:

- `TASK-030A`, `TASK-030B`, etc. for MCP server sub-tasks.
- `TASK-037A`, `TASK-037B`, etc. for distributed execution sub-tasks.
- `TASK-039A`, `TASK-039B`, etc. for platform sub-tasks.

File names should be lowercase and include the suffix:

- `docs/board/tasks/task-030a-mcp-protocol-core.md`
- `docs/board/tasks/task-037a-work-queue-store.md`
- `docs/board/tasks/task-039a-platform-tenant-model.md`

### TASK-030: MCP Server

- `TASK-030A: MCP Protocol Core`
  - JSON-RPC request/response handling.
  - Tool listing and tool call abstraction.
  - Error mapping to MCP error codes.
  - Deterministic fake client tests.

- `TASK-030B: Workflow Tool Schema Generation`
  - Workflow input/output JSON Schema generation.
  - Stable MCP tool names, escaping, collisions, and version suffixes.
  - Schema validation failure payloads.

- `TASK-030C: In-Process Workflow MCP Adapter`
  - Map `list_workflows`, `run_workflow`, `resume_workflow`, and
    `get_workflow_status` to workflow registry/runtime APIs without requiring
    REST.
  - Add compatibility tests against fake workflows.

- `TASK-030D: Stdio MCP Transport`
  - Local stdio transport.
  - Graceful shutdown.
  - Client compatibility smoke test with one supported local MCP client.

- `TASK-030E: HTTP/SSE MCP Transport`
  - Remote MCP transport.
  - Connection lifecycle.
  - SSE reconnect behavior.
  - Request size limits.

- `TASK-030F: MCP Server Security and Compatibility Matrix`
  - Tool exposure allowlist/denylist.
  - Redaction policy.
  - Transport compatibility matrix.
  - Golden protocol fixtures.

### TASK-037: Distributed Execution

- `TASK-037A: Work Queue Store SPI`
  - Pending work model.
  - Atomic claim operations.
  - Status transitions.
  - Pagination and polling queries.

- `TASK-037B: Worker Registry and Heartbeat`
  - Worker registration table.
  - Heartbeat writes.
  - Stale worker detection.
  - Version and definition digest reporting.

- `TASK-037C: Lease Fencing Semantics`
  - Claim, renew, release, and expire operations.
  - Fencing tokens.
  - Stale renewal rejection.
  - Stale checkpoint write rejection.

- `TASK-037D: Worker Poll Loop and Execution Handoff`
  - `TramaiWorker` lifecycle.
  - Polling due work.
  - Starting/resuming workflow runs.
  - Observer events.

- `TASK-037E: Crash Recovery and Idempotency Policy`
  - Started-but-not-checkpointed step model.
  - Step attempt records.
  - Re-execution policy for idempotent, non-idempotent, and externally
    idempotent steps.

- `TASK-037F: Graceful Shutdown and Cancellation`
  - SIGTERM handling.
  - Stop accepting new work.
  - Drain to checkpoint.
  - Cancellation propagation.

- `TASK-037G: Partitioning and Rebalancing`
  - Stable hash algorithm.
  - Worker group membership.
  - Rebalance behavior when worker count changes.
  - Tests for even distribution and no duplicate owners.

- `TASK-037H: Distributed Observability`
  - Worker lifecycle events.
  - Lease events.
  - Failover events.
  - Optional OTel bridge attributes.

### TASK-039: Platform

`TASK-039` should stop being "Plugin System and Multi-Tenancy". Split it into
separate platform epics:

- `TASK-039A: Platform Tenant Model`
  - Team/project schema.
  - Tenant-scoped workflow registry.
  - Tenant-aware run queries.
  - Cross-tenant negative tests.

- `TASK-039B: API Keys and Authorization`
  - API key hashing.
  - Scope model: `run`, `read`, `admin`.
  - Key rotation and revocation.
  - Auth middleware integration.

- `TASK-039C: Audit Log`
  - Append-only audit records.
  - Actor/resource/action schema.
  - Retention configuration.
  - Query API.

- `TASK-039D: Rate Limiting`
  - Per-key and per-tenant limits.
  - 429 response shape.
  - Burst and refill semantics.

- `TASK-039E: Runtime Plugin Registry`
  - Runtime plugin descriptor.
  - Discovery.
  - Enable/disable lifecycle.
  - Version compatibility checks.

- `TASK-039F: Compile-Time Plugin DSL Contract`
  - DSL artifact conventions.
  - Step spec serialization.
  - Registry validation.
  - Example plugin.

- `TASK-039G: Dashboard Run History Slice`
  - Workflow list.
  - Run list.
  - Run detail.
  - Redaction and payload truncation.

- `TASK-039H: Dashboard Worker and Schedule Views`
  - Worker list.
  - Schedule list.
  - Upcoming ticks.
  - Live updates via existing SSE.

- `TASK-039I: Platform Migrations`
  - Tenant columns.
  - API key tables.
  - Audit tables.
  - Backward-compatible migration tests.

## 5. Cancellation Model

Cancellation should be a single workflow-level concept with step-specific
propagation.

### Core Types

```kotlin
enum class CancellationSource {
    REST_API,
    WORKER_SHUTDOWN,
    LEASE_EXPIRED,
    MCP_CLIENT,
    SCHEDULER,
    DELAY_ABORT,
    INTERNAL_TIMEOUT
}

enum class CancellationMode {
    REQUESTED,
    GRACEFUL,
    FORCEFUL
}

data class CancellationRequest(
    val workflowRunId: WorkflowRunId,
    val source: CancellationSource,
    val mode: CancellationMode,
    val reason: String?,
    val requestedBy: ActorRef?,
    val requestedAt: Instant,
    val deadline: Instant?
)
```

Workflow run statuses:

- `running`: no cancellation requested.
- `cancelling`: cancellation requested and current step is being interrupted or
  allowed to reach a checkpoint.
- `cancelled`: terminal cancellation checkpoint written.
- `failed`: cancellation attempted but cleanup or terminal checkpoint failed.

Every running step receives a `StepExecutionContext`:

```kotlin
interface StepExecutionContext {
    val cancellationToken: CancellationToken
    suspend fun checkpointCancellation(request: CancellationRequest)
}
```

`CancellationToken` supports:

- `isCancellationRequested`
- `throwIfCancellationRequested()`
- `onCancel(handler)`
- `deadline`

### REST DELETE

`DELETE /workflows/{name}/runs/{id}` writes a durable cancellation request and
returns `202 Accepted` unless the run is already terminal. The worker observes
the request through its poll loop, lease renewal, or active execution context.

Acceptance criteria:

- Deleting a running workflow moves it to `cancelling`.
- If no step is active, the next poll writes terminal `cancelled`.
- If a cancellable step is active, the step receives the cancellation token.
- Repeated DELETE calls are idempotent.

### Shell Step

Shell cancellation maps to process termination:

1. On graceful cancellation, close stdin and send SIGTERM.
2. Wait `terminationGracePeriod`.
3. If still running, send SIGKILL.
4. Capture bounded stdout/stderr and exit metadata.
5. Mark the step cancelled, not failed, when cancellation was requested.

On Windows, use `Process.destroy()` then `destroyForcibly()` with equivalent
semantics.

### MCP Call Cancellation

MCP steps propagate cancellation to the client transport:

- If the MCP protocol/client supports request cancellation, send cancellation
  for the active request ID.
- If not supported, close the request scope or transport connection.
- A cancelled MCP call must not be retried unless the step explicitly declares
  cancellation retry behavior.

### Lease Expiry

Lease expiry is not user intent, but it is cancellation from the old worker's
perspective. When a worker fails to renew its lease:

- The old worker must stop checkpoint writes for that run.
- Any stale checkpoint write must be rejected by fencing token.
- The old worker should request forceful cancellation of active subprocesses or
  remote calls it still controls.
- The new worker resumes according to the distributed idempotency model.

### Delay Step Abort

A delay step is cancelled by removing or marking the delay wakeup record:

- If the run is cancelled while delayed, mark the wakeup `cancelled`.
- The scheduler must ignore cancelled wakeups.
- A race between wakeup claim and cancellation is resolved by the claim token:
  only one terminal transition wins.

## 6. DSL API Shape

Build-time config should be assigned directly. Runtime state access should use
lambdas that are visibly evaluated at execution time.

### HTTP Step

```kotlin
workflow<InvoiceState>("invoice") {
    httpStep("submit-invoice") {
        method = HttpMethod.POST
        url { state -> "https://api.example.com/invoices/${state.invoiceId}" }
        header("Authorization") { state -> "Bearer ${state.apiToken}" }
        jsonBody { state -> state.invoiceData }
        timeout = 30.seconds
        retry = RetryPolicy.none()

        onResponse { state, response ->
            state.copy(apiResult = response.bodyAs<InvoiceApiResult>())
        }
    }
}
```

### Shell Step

```kotlin
workflow<DeployState>("deploy") {
    shellStep("helm-upgrade") {
        executable = "helm"
        args("upgrade", "--install", "myapp", "./chart")
        workdir = Path.of("/home/deploy/app")
        env("KUBECONFIG") { state -> state.kubeconfigPath.toString() }
        timeout = 5.minutes
        retry = RetryPolicy.none()

        onResult { state, result ->
            state.copy(deployOutput = result.stdout.truncatedText)
        }
    }
}
```

### MCP Step

```kotlin
workflow<AuditState>("audit") {
    mcpStep<A11yReport>("run-a11y-audit") {
        server = McpServerRef.named("a11y")
        tool = "audit_url"
        argument("url") { state -> state.deployedUrl }
        timeout = 2.minutes
        retry = RetryPolicy.none()
        decodeWith(A11yReport.serializer())

        onResult { state, report ->
            state.copy(a11yReport = report)
        }
    }
}
```

Rule of thumb:

- `method`, `timeout`, `retry`, `server`, `tool`, `workdir`, and static args
  are build-time config.
- `url { state -> ... }`, `header { state -> ... }`, `jsonBody { state -> ... }`,
  `env { state -> ... }`, `argument { state -> ... }`, and `onResult` are
  runtime state access.

## 7. Shell Injection

The shell step should be safe by default by using argv execution, not shell
string execution.

### Default API

```kotlin
shellStep("build") {
    executable = "npm"
    args("run", "build")
}
```

or:

```kotlin
shellStep("deploy") {
    command("helm", "upgrade", "--install", "myapp", "./chart")
}
```

Rules:

- No shell is invoked.
- Arguments are passed directly to `ProcessBuilder`.
- Runtime state values become single argv elements.
- Secrets are passed through environment or secret handles, not string
  interpolation.
- Logs record executable and redacted argument metadata, not a reconstructed
  shell command.

Runtime args must be explicit:

```kotlin
shellStep("tag-image") {
    command("docker", "tag")
    arg { state -> state.sourceImage }
    arg { state -> state.targetImage }
}
```

### Explicit Shell Mode

Shell expansion is allowed only through an explicit API:

```kotlin
shellStep("package") {
    shell {
        interpreter = ShellInterpreter.BASH
        script = """
            set -euo pipefail
            npm run build
            tar -czf dist.tgz dist/*
        """.trimIndent()
    }
    allowShellExpansion = true
}
```

Shell mode acceptance criteria:

- The user must opt in with `shell { ... }` or `mode = ShellMode.SHELL`.
- Specs document that shell mode enables expansion, globbing, pipes, redirects,
  and injection risk.
- State interpolation into shell scripts is disallowed by default.
- If state interpolation is supported, it must use named escaped variables:

  ```kotlin
  shell {
      script = "printf '%s\n' \"$MESSAGE\""
      variable("MESSAGE") { state -> state.message }
  }
  ```

- Shell mode emits a warning observer event unless disabled by explicit
  configuration.

## 8. Security Cross-Cutting Acceptance Criteria

Every spec that touches IO, credentials, execution, remote access, or user UI
should include concrete security acceptance criteria.

### Shell Steps

- Default execution uses argv form and does not invoke a shell.
- Shell mode requires explicit opt-in.
- Runtime state is passed as argv or environment values, not interpolated into
  shell strings by default.
- Stdout and stderr capture has configurable byte limits and deterministic
  truncation.
- Secret-like environment variables and arguments are redacted in observer
  events, logs, run history, and dashboard views.
- Workdir must be explicit or restricted to an allowed base directory when
  platform policy is enabled.
- Timeout cancellation sends graceful termination and then forceful
  termination.

### Webhooks

- Signature verification includes timestamp tolerance and replay prevention.
- Request body size limit is enforced before buffering/deserialization.
- Unsupported content types return 415.
- Invalid signatures return 401 or 403 without revealing expected signatures.
- Webhook trigger records include source, delivery ID, signature status, and
  workflow name.
- Webhook payload display is redacted and truncated in dashboard/run history.

### API Keys

- API keys are stored only as salted hashes or verifier digests.
- Newly created keys are shown once and never retrievable in plaintext.
- Key scopes are enforced on every endpoint.
- Key rotation and revocation are tested.
- Authentication failures use uniform error messages.
- Audit log records key creation, revocation, failed auth, and scoped access
  denial.

### MCP

- Exposed tools are allowlisted by default or explicitly configured.
- Tool names are stable, escaped, and collision-checked.
- MCP request and response payload sizes are bounded.
- Invalid tool arguments fail validation before workflow execution.
- Transport-specific auth expectations are documented for stdio, HTTP, and
  SSE.
- MCP cancellation and disconnects cannot leave a run permanently stuck in
  `running`.

### Dashboard

- Dashboard endpoints enforce the same authz checks as external APIs.
- Cross-tenant read and write attempts are denied in tests.
- Workflow state, request bodies, shell output, and MCP payloads are redacted
  and truncated by default.
- CORS defaults deny cross-origin access unless configured.
- CSRF posture is documented for cookie-based auth.
- The dashboard never displays full API keys, webhook secrets, or bearer
  tokens after creation.

## 9. Scheduler Edge Cases

SPEC-013 should explicitly define cron format, misfires, DST, and duplicate
tick prevention.

### Misfire Policy

A misfire occurs when a scheduled fire time is discovered after its acceptable
lateness window.

```kotlin
data class ScheduleMisfireConfig(
    val policy: MisfirePolicy,
    val gracePeriod: Duration,
    val maxCatchUpTicks: Int = 1
)

enum class MisfirePolicy {
    SKIP,
    FIRE_ONCE,
    CATCH_UP_BOUNDED,
    FAIL_SCHEDULE
}
```

Policies:

- `SKIP`: mark missed ticks as `misfired`; compute the next future fire time.
- `FIRE_ONCE`: create one tick for the latest missed fire time; skip older
  missed ticks.
- `CATCH_UP_BOUNDED`: create up to `maxCatchUpTicks` missed ticks in order,
  then skip the rest.
- `FAIL_SCHEDULE`: disable the schedule and emit a terminal schedule failure.

Default:

- For cron schedules: `FIRE_ONCE` with a bounded grace period is a practical
  default.
- For high-frequency interval schedules: `SKIP` is safer unless the user opts
  into catch-up.

### DST Handling

Use IANA `ZoneId` and store all claimed tick instants in UTC. Local cron
evaluation should be deterministic:

- Skipped local time during spring-forward: do not fire at a nonexistent local
  time; mark the local occurrence `skipped_dst_gap` and compute the next valid
  occurrence.
- Repeated local time during fall-back: fire once per local cron occurrence by
  default, using the earlier offset. Provide `repeatedTimePolicy` for advanced
  users:
  - `ONCE_EARLIER_OFFSET` default.
  - `ONCE_LATER_OFFSET`.
  - `TWICE`.
- Timezone database source must be the JVM timezone database unless a scheduler
  backend explicitly documents another source.

Acceptance tests should include:

- Europe/Rome spring-forward skipped 02:30.
- Europe/Rome fall-back repeated 02:30.
- UTC schedules unaffected by DST.
- Restart after DST transition does not create duplicate ticks.

### Duplicate-Tick Prevention

Tick identity should be deterministic:

```text
tick_id = sha256(schedule_id + scheduled_fire_at_utc + occurrence_index)
```

Add a unique constraint:

```text
unique(schedule_id, scheduled_fire_at, occurrence_index)
```

For schedules where `TWICE` repeated-time behavior is enabled,
`occurrence_index` distinguishes the two valid instants.

Duplicate prevention rules:

- Tick creation must use insert-if-absent semantics.
- Tick claim must be an atomic conditional update.
- A claimed tick must have a fencing token.
- Starting a run from a tick must be idempotent by `tick_id`: if the tick
  already has `workflow_run_id`, the scheduler returns the existing run rather
  than creating another.
- In distributed mode, scheduler instances may race, but only one can insert
  or claim a given tick.

## 10. Distributed Idempotency

The distributed execution model must treat "started but not checkpointed" as a
first-class state. The runtime cannot assume a crashed worker did not perform
the external side effect.

### Step Attempt Records

Add durable step attempt metadata to the checkpoint/work queue store:

| Column | Purpose |
| --- | --- |
| `workflow_run_id` | Owning run |
| `step_id` | Step being attempted |
| `attempt_id` | Unique attempt identifier |
| `worker_id` | Worker that started the attempt |
| `lease_token` | Fencing token active when attempt started |
| `status` | `started`, `completed`, `failed`, `cancelled`, `unknown` |
| `started_at` | Start time |
| `finished_at` | Finish time, if known |
| `idempotency_key` | Key passed to external systems when supported |
| `result_ref` | Optional reference to captured output/result |

Before executing a step, the worker writes `status = started` with the current
lease token. After successful execution and checkpoint write, it updates the
attempt to `completed`.

If the worker crashes after the side effect but before checkpointing, the next
worker sees a `started` attempt with an expired lease and no completed
checkpoint. That state is `unknown`, not automatically safe.

### Step Idempotency Classes

```kotlin
enum class StepReplayPolicy {
    PURE,
    IDEMPOTENT,
    EXTERNALLY_IDEMPOTENT,
    NON_REPLAYABLE
}
```

- `PURE`: safe to re-run because the step has no external side effects.
- `IDEMPOTENT`: safe to re-run by the step's own semantics.
- `EXTERNALLY_IDEMPOTENT`: safe only when TramAI provides the same
  idempotency key to the external system.
- `NON_REPLAYABLE`: not safe to re-run automatically.

Defaults:

- `localStep`: `PURE` only if declared or inferred from a restricted API;
  otherwise require an explicit policy for distributed execution.
- `aiStep`: `EXTERNALLY_IDEMPOTENT` only if provider idempotency is supported;
  otherwise `IDEMPOTENT` is a user decision.
- `httpStep GET`: `IDEMPOTENT` by default.
- `httpStep POST/PATCH/DELETE`: `NON_REPLAYABLE` by default unless an
  idempotency key header is configured.
- `shellStep`: `NON_REPLAYABLE` by default.
- `mcpStep`: `NON_REPLAYABLE` by default unless the tool declares idempotency
  and accepts an idempotency key.

### Resume Rules

When a worker resumes a run and the next step has a previous `started` attempt
without a completed checkpoint:

- `PURE`: re-run the step.
- `IDEMPOTENT`: re-run the step and link the new attempt to the prior attempt.
- `EXTERNALLY_IDEMPOTENT`: re-run with the same idempotency key.
- `NON_REPLAYABLE`: fail the workflow with
  `NonReplayableStepStateUnknownException`.

The exception payload must include:

- workflow name,
- run ID,
- step ID,
- prior attempt ID,
- prior worker ID,
- lease token,
- started time,
- replay policy,
- recovery instructions.

### Checkpoint Fencing

Every checkpoint write in distributed mode must include the active lease token.
The store accepts the write only if the token still owns the run. This prevents
a stale worker from overwriting a checkpoint after another worker has taken
over.

Required tests:

- Worker A starts a shell step, lease expires, worker B refuses to replay
  because the step is `NON_REPLAYABLE`.
- Worker A starts an HTTP POST with idempotency key, lease expires, worker B
  retries with the same key.
- Worker A finishes a step but loses lease before checkpoint write; stale write
  is rejected.
- Two workers race to resume the same expired run; only one acquires the new
  lease.
- A completed checkpoint always wins over a stale unknown attempt.

