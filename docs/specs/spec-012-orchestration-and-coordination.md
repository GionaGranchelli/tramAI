# SPEC-012: Orchestration and Coordination

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-22
- Related roadmap milestone: stable orchestration foundation
- Related ADRs: [ADR-017](../adr/adr-017.md), [ADR-001](../adr/adr-001.md), [ADR-009](../adr/adr-009.md), [ADR-014](../adr/adr-014.md)
- Related docs: [Roadmap Summary](../roadmap.md), [Current Limitations](../reference/limitations.md)

## Problem

Tramai is already strong at single typed AI-backed operations, but many backend workflows need explicit coordination across multiple AI services or multiple execution stages. Examples include planning and execution, parallel specialist review, and draft-plus-review pipelines.

Today users can hand-roll those flows in application code, but that creates repeated scheduling, branching, stop-condition, and observability logic outside the library. A Tramai-owned orchestration layer could make these workflows more explicit, testable, and portable without turning the project into a general agent framework.

## Scope

- an optional `tramai-orchestration` module
- explicit workflow definitions over typed AI services and local application steps
- sequential, conditional, and bounded parallel step composition
- typed workflow state and typed handoff contracts between steps
- workflow-level observability and stop-policy handling
- checkpoint and resume SPI at explicit workflow step boundaries
- optional lease and claim SPI for multi-node ownership
- coordination patterns such as planner-worker-reviewer and candidate-generation-plus-judge

## Non-Goals

- open-ended autonomous agent swarms
- hidden or long-lived memory systems as part of the first orchestration milestone
- mid-step replay or token-level stream resume
- provider-specific orchestration APIs as the primary user-facing model
- replacing `tramai-engine` as the owner of retries, routing, tools, or provider execution
- free-form peer-to-peer agent chat as the default coordination primitive

## Functional Requirements

- Tramai must keep orchestration as an optional module rather than making it a new mandatory core abstraction.
- The orchestration layer must compose typed `@AiService` methods and typed local steps rather than normalizing everything into raw prompt strings.
- Workflows must support explicit sequential composition, conditional branching, and bounded fan-out/fan-in patterns.
- Workflow execution must have explicit stop controls for maximum step executions and maximum branch width.
- The orchestration API must support both AI-backed steps and deterministic local application steps.
- Workflow state must be explicit and inspectable rather than hidden inside an agent memory abstraction.
- The orchestration layer must integrate with engine-level observability, token budgets, caching, fallback routing, and retries rather than bypassing them.
- The public model must make it possible to insert approval or policy gates as first-class workflow steps.
- The orchestration layer must expose a persistence SPI that allows checkpointing and resuming workflow state without hardwiring a storage backend into `tramai-engine`.
- The checkpoint SPI should support stale-writer protection so database, object-store, and filesystem adapters can enforce safe resume semantics under concurrent executors.
- The orchestration layer should expose an optional lease and claim SPI so distributed executors can enforce active ownership, not just stale-write protection.
- The repository may provide lightweight reference adapters such as filesystem, markdown, or JDBC stores, but these must remain optional conveniences rather than the orchestration boundary itself.

## Quality Requirements

- Orchestration must remain auditable: a reviewer should be able to infer workflow guarantees from types, workflow definitions, and tests.
- The first orchestration design must bias toward determinism and bounded execution over maximum autonomy.
- Step handoffs should remain typed and validation-friendly.
- Workflow execution should be testable with deterministic fake services and explicit assertions on branch decisions, retries, and final outputs.
- Observability should cover both workflow-level spans/events and the underlying provider-attempt spans already emitted by the engine.

## Design Notes

### Proposed Module Shape

- `tramai-engine`
  - single-call provider execution
  - structured retry, tool loops, streaming, routing, budgets, caching, observability hooks
- `tramai-orchestration`
  - workflow graph execution
  - state passing
  - branch scheduling
  - fan-out/fan-in merge logic
  - workflow stop conditions
  - workflow-level observability

### Stable Public Contract

The stable orchestration surface is intentionally narrow and matches the shipped runtime.

Stable public types:

- `Workflow<S, R>`
  - typed workflow over state `S` that yields result `R`
- `WorkflowBuilder<S>` and `workflow(name, definitionVersion, configure)`
  - explicit workflow definition entry points with caller-owned `definitionVersion`
- `WorkflowContext`
  - `workflowId`
  - free-form `attributes`
- `StopPolicy`
  - `maxStepExecutions`
  - `maxParallelBranches`
- `WorkflowObserver`
  - workflow and step lifecycle observation seam
- `GateDecision`
  - explicit allow or reject outcome for gate steps
- `WorkflowPersistence<S>`
  - typed state codec, checkpoint store, optional lease store, optional lease policy, and delete-on-completion control
- `WorkflowCheckpoint`
  - persisted step-boundary snapshot with revision and metadata
- `WorkflowCheckpointStore`
  - storage-agnostic load/save/delete SPI with revision-aware conflict semantics
- `WorkflowStateCodec<S>`
  - typed state serialization boundary for checkpoints
- `WorkflowLease`
  - active ownership record for one workflow run
- `WorkflowLeasePolicy`
  - `ownerId` and `leaseDurationMillis`
- `WorkflowLeaseStore`
  - optional active-ownership SPI for distributed executors

Stable workflow step shapes:

- `localStep(...)`
- `aiStep(...)`
- `gateStep(...)`
- `branchStep(...)`
- `parallelStep(...)`

The stable builder surface does not introduce a separate “agent” abstraction. A role remains a normal typed service used by `aiStep(...)`.

For the current stop-policy contract:

- each top-level workflow step consumes one step-execution slot
- each branch executed inside `ParallelStep` consumes one additional step-execution slot
- maximum branch width should be enforced before branch invocation without requiring full materialization of oversized lazy iterables

### Explicitly Deferred Or Out Of Scope

These concepts are intentionally not part of the frozen `0.1.x` orchestration contract:

- max-round controls separate from `maxStepExecutions`
- terminal predicates in `StopPolicy`
- typed deadline fields or typed budget-hint fields in `WorkflowContext`
- typed human-approval metadata fields in `WorkflowContext`
- mid-step replay, in-flight branch resume, or token-level stream resume
- dynamic graph mutation during workflow execution

These may be revisited in later specs, but they are not implied by the current public types.

### Engine vs Orchestration Boundary

Belongs in `tramai-engine`:

- provider calls
- structured parsing and structured retry
- provider retry pacing and fallback routing
- circuit breaking
- tool execution loops
- token budgets
- response caching
- single-call observation lifecycle

Belongs in `tramai-orchestration`:

- workflow state model
- step ordering and scheduling
- bounded parallelism
- merge and reduction semantics
- workflow stop rules
- step-to-step handoff validation
- workflow-level observation events
- workflow checkpoint and resume SPI
- checkpoint revision and conflict semantics
- optional workflow lease and claim semantics

### First Persistence Boundary

The first persistence milestone should checkpoint only at top-level workflow step boundaries.

That means:

- completed top-level steps can be skipped on resume
- state snapshots and execution counters survive process restarts when the store is durable
- checkpoints persist explicit workflow definition compatibility metadata so resume can reject incompatible workflow changes loudly
- nested branch internals, in-flight parallel branches, and partially emitted streams are not resumed mid-step
- storage adapters can use optimistic concurrency or equivalent revision checks to reject stale checkpoint writers
- multi-node executors can add active ownership through optional lease claims layered above checkpoint revisions

This boundary keeps the first SPI explicit and auditable while leaving room for later durability work.

### Canonical Workflow Examples

These examples prove useful orchestration without turning Tramai into an agent framework:

1. `plan -> execute[] -> review -> finalize`

- `PlannerService` returns a typed `ExecutionPlan`
- each plan item fans out to a typed worker call
- `ReviewerService` checks the combined result set
- `FinalizerService` produces the final user-facing output

2. `route -> specialist -> validate`

- `RouterService` selects one specialist contract based on typed routing output
- exactly one specialist runs
- a validator step checks schema, policy, or confidence thresholds before returning

3. `generateCandidates[] -> judge -> return`

- multiple candidate generators run in parallel, potentially with different providers or prompts
- `JudgeService` scores or selects among typed candidate outputs
- the final result returns the winner plus optional explanation metadata

These patterns provide concrete backend value while keeping execution bounded and auditable.

## Acceptance Criteria

- A prototype orchestration module can execute a typed sequential workflow across multiple `@AiService` methods.
- The orchestration module can execute at least one bounded fan-out/fan-in workflow with typed merge behavior.
- Workflow execution reuses engine-level retries, budgets, caching, and observability instead of duplicating them.
- Automated tests can deterministically assert workflow state transitions, branch choices, and final outputs without live provider dependencies.
- The orchestration module exposes a storage-agnostic checkpoint/resume SPI with tests proving resume from the last completed top-level step.
- Workflow checkpoints persist explicit definition-version and structural-digest metadata, and resume fails loudly when the metadata is missing or incompatible.
- The checkpoint SPI exposes revision-aware save/delete semantics with tests proving stale-write conflicts.
- The orchestration module exposes an optional lease/claim SPI with tests proving competing owners are rejected.
- The first public design does not require open-ended autonomous chat loops or hidden memory to be useful.

## Risks and Follow-Ups

- Users may try to stretch orchestration into a generic autonomous multi-agent runtime if the boundaries are not enforced clearly.
- Parallel orchestration can multiply cost and latency quickly without strong stop policies and observability.
- Human approval is part of the first prototype, but durable checkpoint backends and finer-grained resume semantics may still deserve follow-up specs.
- Lease duration, renewal cadence, and crash recovery policies will vary by backend and deployment model.
- If native-image and startup concerns become more important, workflow composition may increase pressure on future compile-time code generation decisions.
