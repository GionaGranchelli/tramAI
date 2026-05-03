# Orchestrator Integration Review

- Review date: 2026-05-03
- Reviewer: Codex
- Scope:
  - `AGENTS.md`
  - `docs/architecture/orchestrator-vision.md`
  - `docs/specs/spec-013-scheduler.md` through `docs/specs/spec-017-platform.md`
  - `docs/board/orchestrator-board.md`
  - `docs/roadmap.md`
  - `docs/board/tasks/task-030a-mcp-protocol-core.md`
  - `docs/board/tasks/task-037e-crash-recovery.md`
  - `docs/board/tasks/task-039a-tenant-model.md`
  - `docs/reviews/orchestrator-solutions.md`

## Executive Summary

The integration is incomplete. The updated docs apply some of the previous
solutions, especially the scheduler store heading, scheduler edge cases, plugin
split wording, and sample sub-task creation, but the main specs still carry
older wording and weaker contracts in several places.

The most important gaps are:

- The cross-cutting observability fix did not propagate to all specs. SPEC-013,
  SPEC-015, SPEC-016, and SPEC-017 still mention OpenTelemetry as if it were the
  direct contract rather than an optional bridge.
- SPEC-013 added a `WorkflowSchedulerStore` section, but it still says durable
  scheduling uses the checkpoint store in requirements and acceptance criteria.
- SPEC-014 is still too thin for MCP, webhook security, cancellation semantics,
  protocol layering, and observer events.
- SPEC-015 still has shell and agent examples that can be read as string-shell
  or prompt interpolation APIs, and it lacks the explicit shell-mode and
  security criteria from the solution.
- SPEC-016 did not integrate the durable step-attempt / unknown side-effect
  model strongly enough. The spec still says failover resumes from the last
  checkpoint, which is exactly the unsafe assumption the solution tried to
  remove.
- The board and roadmap do not agree with the new split sub-tasks. The files
  exist, but the board and roadmap still show only the old epic-level tasks.
- There is one broken relative link in `docs/roadmap.md`.

## AGENTS.md Compliance

### Overall Compliance

The overall orchestrator direction can fit `AGENTS.md` if the new modules stay
optional and the core remains typed, framework-agnostic, explicit, and
observable through optional dependencies. The vision document does state that
every new module is optional and that workflows remain typed and testable.

### Compliance Risks

- Optional observability is not consistently preserved. The vision correctly
  says observer hooks are mandatory and OTel dependency is optional
  (`docs/architecture/orchestrator-vision.md:36`), but the positioning table
  still says OpenTelemetry is "Built-in" (`docs/architecture/orchestrator-vision.md:100`),
  and multiple specs require OpenTelemetry events directly. This conflicts with
  the `AGENTS.md` guardrail that `tramai-observability` remains optional.
- SPEC-017 scopes `tramai-platform` as a Spring Boot application
  (`docs/specs/spec-017-platform.md:22`). That is acceptable only because
  platform is optional. The docs should explicitly state that this does not add
  a Spring dependency to core, server, scheduler, agent, or distributed modules.
- SPEC-014 says every workflow becomes an MCP tool automatically
  (`docs/specs/spec-014-server.md:60`). Without an allowlist/denylist and
  security default, this risks hidden exposure and conflicts with explicit,
  fail-loud behavior.
- SPEC-015 Hermes/Codex examples use prompt strings containing `${state...}`
  (`docs/specs/spec-015-agent-steps.md:123`, `docs/specs/spec-015-agent-steps.md:134`).
  That undermines the solution's build-time vs runtime-state distinction and
  can pull application code back toward raw prompt plumbing.
- SPEC-016 assumes resumption from the last checkpoint after a crash
  (`docs/specs/spec-016-distributed-execution.md:70`). That is not explicit
  enough for external side effects and weakens the library-grade failure-mode
  standard in `AGENTS.md`.

## Solution Integration Accuracy

| # | Solution area | Integration status | Notes |
|---|---|---|---|
| 1 | Observability optionality | Partial | Vision uses the right hook-vs-OTel wording, but SPEC-013, SPEC-015, SPEC-016, and SPEC-017 still use direct OpenTelemetry wording. SPEC-014 has no observer/OTel acceptance criteria at all. |
| 2 | Scheduler store vs checkpoint store | Partial | SPEC-013 has a `WorkflowSchedulerStore` section and atomic claim language, but requirements and acceptance criteria still say scheduling is durable via/recovered from the checkpoint store. The SPI is summarized, not specified with the required operations and full fields. |
| 3 | Plugin system split | Partial | SPEC-017 includes compile-time DSL and runtime platform plugin wording, but the code contract still shows a generic `TramaiPlugin` with `stepTypes()` and no `TramaiRuntimePlugin`, stable plugin IDs, version ranges, DSL artifact constraints, or startup failure contract. |
| 4 | Task splits | Partial | The split files exist for TASK-030A-F, TASK-037A-H, and TASK-039A-I, but the board and roadmap still list only TASK-030, TASK-037, and TASK-039. Traceability omits all split sub-tasks. |
| 5 | Cancellation model | Mostly missing | SPEC-014 has `DELETE` returning 202, but the durable cancellation request, workflow `cancelling` status, idempotent DELETE behavior, active-step propagation, shell/MCP cancellation, and lease-expiry cancellation semantics are not integrated. |
| 6 | DSL API shape | Partial | HTTP, shell, and MCP examples use the intended runtime lambdas. Hermes/Codex still use interpolated prompt strings, and the spec does not state the build-time config vs runtime-state rule. |
| 7 | Shell injection | Mostly missing | SPEC-015 uses `executable` plus `args`, and mentions `ProcessBuilder`, but it does not state argv is the default, shell mode requires explicit opt-in, interpolation into shell scripts is disallowed by default, logs are redacted, or shell mode emits a warning observer event. |
| 8 | Security acceptance criteria | Mostly missing | The specs do not include the cross-cutting security acceptance criteria for shell, webhooks, API keys, MCP, and dashboard. TASK-030F exists, but the main specs do not carry the requirements. |
| 9 | Scheduler edge cases | Partial | SPEC-013 includes misfire policies, DST handling, deterministic tick IDs, unique constraint, insert-if-absent, and atomic claim. It omits grace period/max catch-up config, timezone database source, explicit DST tests, `actual_fire_at`, `misfire_reason`, attempt fields, and full delay wakeup states. |
| 10 | Distributed idempotency | Partial | TASK-037E includes started-but-not-checkpointed and replay policy levels, but SPEC-016 is still shallow and contradicts the unknown-attempt model. TASK-037E also lacks `attempt_id`, lease token, `unknown`/`cancelled` status, idempotency key persistence, stale checkpoint rejection, and the detailed terminal exception payload. |

## Internal Consistency

### Specs vs Board and Roadmap

- `docs/board/orchestrator-board.md` still lists only `TASK-030`, `TASK-037`,
  and `TASK-039` for the areas that were split (`docs/board/orchestrator-board.md:44`,
  `docs/board/orchestrator-board.md:61`, `docs/board/orchestrator-board.md:68`).
  It should list the A-F, A-H, and A-I sub-tasks or explicitly nest them under
  the epic rows.
- Board traceability omits all split sub-tasks
  (`docs/board/orchestrator-board.md:70` through `docs/board/orchestrator-board.md:88`).
- `docs/roadmap.md` still summarizes Phase 9 as only worker pool,
  idempotency, and shutdown (`docs/roadmap.md:61` through
  `docs/roadmap.md:63`) and Phase 10 as dashboard, plugin system, and
  multi-tenancy (`docs/roadmap.md:69` through `docs/roadmap.md:71`). That is
  now stale relative to the split task files.
- `docs/roadmap.md` says Phase 6 uses "JDBC checkpoint store integration"
  (`docs/roadmap.md:36`), while SPEC-013 says the scheduler has a dedicated
  scheduler store (`docs/specs/spec-013-scheduler.md:62`).
- `docs/roadmap.md` has a broken link at line 104:
  `[Orchestrator Board](../board/orchestrator-board.md)` should be
  `./board/orchestrator-board.md` from `docs/roadmap.md`.

### Spec-Level Inconsistencies

- SPEC-013 says the scheduler uses a dedicated scheduler store, then later says
  restart recovery comes from the checkpoint store (`docs/specs/spec-013-scheduler.md:147`).
- SPEC-014 says MCP is a thin adapter over REST
  (`docs/specs/spec-014-server.md:91`), while TASK-030A correctly frames a
  protocol core that transports and in-process adapters build on
  (`docs/board/tasks/task-030a-mcp-protocol-core.md:11`). The spec should not
  force MCP through REST if the task split explicitly includes an in-process
  adapter.
- SPEC-017's acceptance criterion says a plugin JAR makes step types available
  without code changes (`docs/specs/spec-017-platform.md:151`). That is only
  true for runtime-configured/plugin steps, not typed Kotlin DSL functions.
  The criterion needs to distinguish runtime plugin capability availability
  from compile-time DSL availability.
- The vision says "Visual builder" is Phase 10
  (`docs/architecture/orchestrator-vision.md:99`), while SPEC-017 explicitly
  excludes a visual workflow builder (`docs/specs/spec-017-platform.md:32`).
  This is a stale positioning claim.

## Cross-Cutting Completeness

### Observability Wording

The observability wording fix did not propagate to all specs.

- SPEC-013 requires OpenTelemetry events directly
  (`docs/specs/spec-013-scheduler.md:48`) and only mentions observer events in
  acceptance criteria (`docs/specs/spec-013-scheduler.md:151`).
- SPEC-014 has no acceptance criteria for observer events, optional OTel
  bridging, redaction, or high-cardinality attribute documentation.
- SPEC-015 requires OpenTelemetry observability/events directly
  (`docs/specs/spec-015-agent-steps.md:34`,
  `docs/specs/spec-015-agent-steps.md:157`,
  `docs/specs/spec-015-agent-steps.md:187`).
- SPEC-016 requires step attribution in OpenTelemetry
  (`docs/specs/spec-016-distributed-execution.md:133`) and has no core
  observer-event requirement.
- SPEC-017 dashboard run detail requires OpenTelemetry span ID
  (`docs/specs/spec-017-platform.md:47`) without stating it is optional or
  absent when `tramai-observability` is not present.

### Scheduler Store SPI

SPEC-013 did add a scheduler store section, but not the full SPI from the
solution. The current doc lists tables and `claimDueTicks`, but it does not
define the operations for upserting/disabling/listing schedules, marking tick
started/completed/skipped/misfired, releasing ticks, scheduling delay wakeups,
claiming delay wakeups, marking delay resumed, or cancelling delay wakeups.

It also still contains checkpoint-store wording in the functional requirement
and acceptance criterion (`docs/specs/spec-013-scheduler.md:42`,
`docs/specs/spec-013-scheduler.md:147`). That means the scheduler-store fix was
not completely applied.

## Spec Grades

| Spec | Grade | Reason |
|---|---|---|
| SPEC-013: Workflow Scheduling | CONDITIONAL | Good progress on scheduler store, misfires, DST, and duplicate ticks. Still internally inconsistent about checkpoint store vs scheduler store, missing full SPI operations, and missing the required optional-observability acceptance criteria. |
| SPEC-014: TramAI Server | FAIL | Too much of the previous solution is absent: MCP task split implications, security acceptance criteria, cancellation model, observer/OTel optionality, and protocol layering. The current spec is still an early outline. |
| SPEC-015: Agent Step Types | FAIL | The DSL examples are partly corrected, but shell injection safety, explicit shell mode, security criteria, cancellation, observer optionality, and distributed replay policy are not integrated. Hermes/Codex examples still use unsafe/ambiguous prompt interpolation. |
| SPEC-016: Distributed Execution | FAIL | The core distributed idempotency solution is not integrated into the spec. It still assumes crash recovery resumes from the last checkpoint and lacks step attempt records, unknown side-effect handling, lease-token checkpoint fencing, and detailed failure payloads. |
| SPEC-017: Platform | CONDITIONAL | The plugin split and tenant model are partially represented, and the sample tenant task is aligned at a high level. The plugin contract remains too generic, runtime vs compile-time plugin behavior is blurred in acceptance criteria, dashboard/security criteria are incomplete, and OTel optionality is missing. |

## Recommended Fix Order

1. Apply the observer-event/optional-OTel acceptance block to SPEC-013 through
   SPEC-017, replacing direct "emit OpenTelemetry events" requirements with
   core observer event requirements plus optional bridge behavior.
2. Finish SPEC-013's `WorkflowSchedulerStore` contract and remove remaining
   checkpoint-store durability wording from scheduler requirements, roadmap,
   and task titles.
3. Update SPEC-016 around step attempt records, replay policies, unknown
   attempts, checkpoint fencing, and `NonReplayableStepStateUnknownException`.
4. Rewrite SPEC-015 shell and agent sections around argv defaults, explicit
   shell mode, runtime state lambdas, cancellation, redaction, bounded output,
   and replay defaults.
5. Expand SPEC-014 with MCP protocol core vs transports vs in-process adapter,
   security defaults, webhook validation, durable cancellation semantics, and
   observer events.
6. Replace SPEC-017's generic plugin contract with explicit compile-time DSL
   artifact and runtime plugin contracts, then make plugin acceptance criteria
   precise about what can and cannot appear without code changes.
7. Update `docs/board/orchestrator-board.md` and `docs/roadmap.md` so they
   include the split sub-tasks and fix the broken roadmap link.
