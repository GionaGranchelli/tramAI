# Orchestration

`tramai-orchestration` is TramAI's optional workflow layer.

It exists for backend workflows that need explicit coordination across multiple AI-backed or deterministic steps without turning TramAI into an agent framework.

The module is shipped and tested, but it should currently be treated as experimental while the API settles.

---

## Opt In Explicitly

The orchestration module is marked experimental in code.

In Kotlin, opt in where you define or consume workflows:

```kotlin
@OptIn(ExperimentalTramAIOrchestration::class)
fun workflowExample() {
    // workflow code here
}
```

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
@OptIn(ExperimentalTramAIOrchestration::class)
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

## Available Step Shapes

The current orchestration DSL supports:

- `localStep(...)`: deterministic application logic
- `aiStep(...)`: one typed AI-backed step over extracted state input
- `gateStep(...)`: first-class approval or policy gates
- `branchStep(...)`: explicit conditional routing
- `parallelStep(...)`: bounded fan-out/fan-in execution

Execution bounds are controlled by `StopPolicy`.

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
- partially emitted streams are not resumed token-by-token

For stores, codecs, leases, file backends, and JDBC backends, see [Orchestration Persistence](./orchestration-persistence.md).

---

## Observability

Workflow execution has its own observation seam through `WorkflowObserver`.

The repository also includes `OpenTelemetryWorkflowObserver` in `tramai-observability` so workflow spans and workflow events can sit above the normal provider-attempt spans emitted by the engine.

That means you can observe:

- workflow start and completion
- checkpoint load/save events
- lease claim/renew/release events
- step start, completion, and failure

---

## Current Maturity

The orchestration layer is already useful for explicit backend workflows, but it is still the youngest major public surface in the repository.

Treat it as:

- shipped
- tested
- optional
- experimental

That is the honest boundary today.
