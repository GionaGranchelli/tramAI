# Orchestration

`tramai-orchestration` is TramAI's optional workflow layer.

It exists for backend workflows that need explicit coordination across multiple AI-backed or deterministic steps without turning TramAI into an agent framework.

It is now a stable Tramai capability with an intentionally narrow, explicit contract.

---

## Stable Surface

The current stable orchestration surface in `0.3.x` is:

- `workflow(name, definitionVersion, configure)` and `WorkflowBuilder`
- `WorkflowContext(workflowId, attributes)`
- `StopPolicy(maxStepExecutions, maxParallelBranches)`
- step shapes: `localStep(...)`, `aiStep(...)`, `gateStep(...)`, `branchStep(...)`, `parallelStep(...)`
- `WorkflowObserver`
- checkpoint/resume through `WorkflowPersistence`, `WorkflowCheckpointStore`, `WorkflowStateCodec`, and `WorkflowCheckpoint`
- optional active ownership through `WorkflowLeaseStore`, `WorkflowLeasePolicy`, and `WorkflowLease`

What is explicitly not part of this frozen contract:

- deadline or budget-hint fields in `WorkflowContext`
- max-round or terminal-predicate controls in `StopPolicy`
- mid-step replay or token-level stream resume
- free-form agent chat or hidden memory

---

## What It Is For

Good orchestration use cases:

- `plan -> execute -> review -> finalize`
- `route -> specialist -> validate`
- bounded candidate generation followed by judging or reduction
- workflows that need explicit checkpoint/resume boundaries

What it is not for:

- autonomous agent swarms
- hidden memory systems
- peer-to-peer agent chat
- replacing `tramai-engine` as the owner of provider execution, retries, or tool loops

---

## Basic Workflow

```kotlin
fun buildWorkflow(planner: PlannerService, reviewer: ReviewerService) =
    workflow<ReviewState>("review-workflow") {
        aiStep(
            name = "plan",
            input = { state -> state.request },
            invoke = planner::plan,
            merge = { state, plan -> state.copy(plan = plan) },
        )
        localStep(
            name = "prepare",
            transform = { state, _ -> state.copy(prepared = true) },
        )
        aiStep(
            name = "review",
            input = { state -> ReviewInput(state.request, state.plan ?: error("missing plan")) },
            invoke = reviewer::review,
            merge = { state, result -> state.copy(result = result) },
        )
    }.build { state ->
        state.result ?: error("missing result")
    }
```

The important property is that workflow state stays explicit and typed.

---

## End-To-End Pattern

This is the core stable orchestration story in TramAI: typed planning, bounded execution, typed review, and explicit finalization.

```kotlin
fun buildPlanExecuteReviewWorkflow(
    planner: PlannerService,
    worker: WorkerService,
    reviewer: ReviewerService,
) = workflow<JobState>(
    name = "plan-execute-review",
    definitionVersion = "1",
) {
    aiStep(
        name = "plan",
        input = { state -> state.request },
        invoke = planner::plan,
        merge = { state, plan -> state.copy(plan = plan) },
    )
    parallelStep(
        name = "execute",
        items = { state -> state.plan?.items.orEmpty() },
        invoke = worker::execute,
        merge = { state, results -> state.copy(results = results) },
    )
    aiStep(
        name = "review",
        input = { state -> ReviewRequest(state.request, state.results) },
        invoke = reviewer::review,
        merge = { state, review -> state.copy(review = review) },
    )
}.build(
    stopPolicy = StopPolicy(
        maxStepExecutions = 16,
        maxParallelBranches = 8,
    ),
) { state ->
    state.review ?: error("missing review")
}
```

That pattern stays bounded and auditable:

- the planner, worker, and reviewer remain normal typed services
- branch width is explicit
- workflow state is explicit
- checkpoint/resume happens at top-level step boundaries
- engine-owned retries, routing, caching, budgets, and provider observation still apply inside each AI-backed step

---

## Available Step Shapes

The current orchestration DSL supports:

- `localStep(...)`: deterministic application logic
- `aiStep(...)`: one typed AI-backed step over extracted state input
- `gateStep(...)`: first-class approval or policy gates
- `branchStep(...)`: explicit conditional routing
- `parallelStep(...)`: bounded fan-out/fan-in execution

Execution bounds are controlled by `StopPolicy`.

The current stable counting rule is explicit:

- each top-level workflow step consumes one `maxStepExecutions` slot
- each branch executed inside `parallelStep(...)` consumes one additional `maxStepExecutions` slot
- `maxParallelBranches` is enforced before branch invocation, and oversized lazy iterables are only consumed far enough to detect overflow

---

## Engine Boundary

`tramai-engine` still owns:

- provider execution
- structured parsing and structured retry
- fallback routing
- circuit breaking
- tool calling
- token budgets
- caching
- operation-level observability

`tramai-orchestration` owns:

- workflow state
- step ordering
- branching and bounded parallelism
- workflow-level observation
- checkpoint and resume
- optional active ownership through leases

That boundary is the reason orchestration fits TramAI without changing the product into an agent runtime.

---

## Persistence And Resume

Persistence is storage-agnostic and explicit.

The current boundary is intentionally narrow:

- checkpoints are written at top-level workflow step boundaries
- completed top-level steps can be skipped on resume
- resume requires matching definition-version and structural-digest metadata
- partially emitted streams are not resumed token-by-token

For stores, codecs, leases, file backends, and JDBC backends, see [Orchestration Persistence](./orchestration-persistence.md).

---

## Observability

Workflow execution has its own observation seam through `WorkflowObserver`.

The repository also includes `OpenTelemetryWorkflowObserver` in `tramai-observability` so workflow spans and workflow events can sit above the normal provider-attempt spans emitted by the engine.

Workflow-level OpenTelemetry correlation uses the pair `(workflow name, workflow id)`, not `workflowId` alone.

That means you can observe:

- workflow start and completion
- checkpoint load/save events
- lease claim/renew/release events
- step start, completion, and failure

---

## Current Maturity

The orchestration layer is now a stable, bounded workflow capability.

Treat it as:

- shipped
- tested
- optional
- stable

That is the honest boundary today.

### Next steps

| Topic | Link |
|-------|------|
| Governed workflow quickstart | [Governed Workflow Quickstart](governed-workflow-quickstart.md) |
| Orchestration persistence | [Orchestration Persistence](orchestration-persistence.md) |
