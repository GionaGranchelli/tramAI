# Orchestrator Final Review

- Review date: 2026-05-03
- Inputs read:
  - `AGENTS.md` complete
  - `docs/architecture/orchestrator-vision.md` first 50 lines
  - `docs/specs/spec-013-scheduler.md` complete
  - `docs/specs/spec-014-server.md` complete
  - `docs/specs/spec-015-agent-steps.md` complete
  - `docs/specs/spec-016-distributed-execution.md` complete
  - `docs/specs/spec-017-platform.md` complete

## Overall Assessment

CONDITIONAL.

The updated orchestrator specs are much closer to the `AGENTS.md` guardrails than
the earlier drafts. They preserve optional modules, keep orchestration out of
`tramai-core`, use typed workflow/state patterns, make observability hook-first
rather than OTel-first in most places, and now define the important durable
execution concepts: unified `WorkflowStore`, leases, step attempt records,
replay policies, cancellation, redaction, and versioning.

The remaining issues are mostly specification consistency and contract precision,
not fundamental architecture rejection. They should be fixed before implementation
because this is library/platform infrastructure and ambiguity here will become
observable downstream behavior.

## Per-Spec Grades

### SPEC-013: Workflow Scheduling

Grade: CONDITIONAL.

Strong points:

- Correctly keeps scheduling in an optional `tramai-scheduler` module.
- Moves toward a unified transactional `WorkflowStore`, which aligns with
  scheduler, checkpoint, lease, cancellation, and step-attempt coupling.
- Covers durable ticks, misfire policy, DST behavior, duplicate tick prevention,
  delay wakeups, and scheduler recovery.

Conditions:

- The functional requirement still says the scheduler emits "OpenTelemetry
  events" directly, while the vision and later specs require TramAI observer
  events with optional OTel bridging.
- The text still says durable scheduling is via "checkpoint store" in several
  places, despite the later unified `WorkflowStore` decision. Use one term.
- Observer event names drift: requirements mention scheduled/skipped/started/
  missed ticks, acceptance criteria mention `onScheduledTick`, `onSkippedTick`,
  `onMissedTick`, while the misfire design distinguishes skipped and misfired.
  Define the exact event names and payloads.
- The heading has a typo: `## Acceptance Criteria## Acceptance Criteria`.

### SPEC-014: TramAI Server

Grade: CONDITIONAL.

Strong points:

- Correctly keeps server capabilities in optional `tramai-server`.
- MCP is now a peer protocol with protocol core, in-process adapter, and
  transports, not a thin REST wrapper.
- Cancellation, idempotency, webhook security, audit logging, redaction, and
  observer hook requirements are substantially specified.
- Versioning and in-flight run semantics align with SPEC-017.

Conditions:

- The SSE endpoint path is inconsistent: REST lists
  `/workflows/{name}/runs/{id}/events`, but the SSE section says
  `/workspace/{name}/runs/{id}/events`.
- Run state transitions are incomplete. `delayed` and `waiting_for_gate` have no
  documented transition back to `running`; `cancelling` is only shown from
  `running`, but cancellation should also apply from `pending`, `delayed`, and
  `waiting_for_gate`.
- `skipped` is listed as a run state for scheduled ticks, but skipped ticks are
  not workflow runs in SPEC-013 unless a run exists. Clarify whether skipped is
  a tick state, run state, or both.
- "SSE reconnection: at most one missed event" is an odd quality target for a
  durable event stream. It should either guarantee no missed retained events or
  explicitly define gap detection.

### SPEC-015: Agent Step Types

Grade: CONDITIONAL.

Strong points:

- The step model is explicit and typed: HTTP, shell, MCP, Hermes, and Codex are
  named step types, not hidden orchestration.
- Shell safety is materially improved: argv default, shell mode opt-in, named
  variables, workdir/env policy, timeout cleanup, and process group handling.
- Replay policies, output caps, redaction, cancellation hooks, and observer
  events are now integrated.

Conditions:

- Replay policy defaults conflict with SPEC-016 in small but important ways:
  SPEC-015's table says GET defaults to `PURE`, while SPEC-016 says HTTP
  GET/HEAD/OPTIONS are `IDEMPOTENT`. Use the same default everywhere.
- HTTP DELETE is classified as `IDEMPOTENT`; that is common protocol language
  but not universally true for arbitrary APIs. The spec should state this is
  the default only when caller accepts HTTP method semantics or overrides it.
- Hermes/Codex structured output says "if Hermes returns JSON, parse via
  decoder" but does not show how the decoder is declared, unlike MCP. The
  contract should be explicit if structured output is part of the feature.
- Audit events for every step execution are required, but their relation to
  observer events and platform audit logging is not defined.

### SPEC-016: Distributed Execution

Grade: CONDITIONAL.

Strong points:

- The spec now addresses the core distributed safety issue: started but
  uncheckpointed external side effects.
- Step attempt records, replay policies, lease-token checkpoint fencing,
  worker registration, heartbeat, takeover, graceful shutdown, and exception
  context are all directionally correct.
- It agrees with SPEC-013's unified `WorkflowStore` direction and SPEC-015's
  replay-policy concept.

Conditions:

- The `WorkflowStore` sketch in SPEC-013 includes `saveStepAttempt` and
  `getLastAttempt`, but SPEC-016 requires status transitions from started to
  completed/failed/cancelled/unknown. The store contract needs explicit attempt
  update/claim/query operations.
- Workers "poll WorkflowStore for pending/expired runs", but SPEC-013's
  `WorkflowStore` sketch does not include a claim-pending-run or list-expired-
  leases operation.
- `aiStep: IDEMPOTENT` is underspecified. LLM calls are stateless, but re-running
  them can produce different output and external provider side effects such as
  cost. The spec should justify this default or classify AI calls separately.
- `unknown` is listed as an attempt status, but the text says a started attempt
  with expired lease is "treated as unknown." Clarify whether `unknown` is a
  persisted status or a derived recovery state.

### SPEC-017: TramAI Platform

Grade: CONDITIONAL.

Strong points:

- Correctly treats the platform as an optional human/operations layer after
  SPEC-013 through SPEC-016.
- The plugin split is much clearer: compile-time DSL plugins vs runtime
  platform plugins, with serializable specs and startup failure for missing
  executors.
- Multi-tenancy, audit logging, API keys, secret management, worker management,
  workflow versioning, dashboard scope, and retention are concrete enough to
  guide implementation.

Conditions:

- Secret references using `{secret: path/to/key}` can become an untyped string
  convention unless the DSL/state contract wraps it in a typed secret reference.
  This needs tightening to stay aligned with typed contracts over raw plumbing.
- Row-level tenant isolation says cross-tenant reads return empty results, but
  this may conflict with admin/debug needs and auditability unless privileged
  cross-tenant access is explicitly modeled.
- Runtime plugin lifecycle says install/enable/update by API, but workflows are
  code-defined and CI-deployed. Clarify which plugin changes are allowed without
  redeploy and what happens to existing workflow definition digests.
- Platform dashboard depends on server SSE and observer/audit event contracts;
  those payload schemas are not yet versioned in the earlier specs beyond the
  broad versioning table.

## Internal Consistency

Mostly consistent, with fixable contradictions.

Cross-references:

- SPEC-013 correctly defers distributed cron coordination to SPEC-016.
- SPEC-014 correctly defers dashboard work to SPEC-017.
- SPEC-017 correctly positions itself after SPEC-013 through SPEC-016.
- All five specs link to the orchestrator vision. However, none list concrete
  ADRs despite changing major module boundaries; ADR updates should be added
  before implementation.

Shared concepts:

- `WorkflowStore`: The specs now broadly agree on one transactional store for
  checkpoints, runs, step attempts, schedules, leases, cancellation, and workers.
  The remaining issue is contract completeness: SPEC-016 needs operations not
  present in SPEC-013's interface sketch.
- Replay policies: SPEC-015 and SPEC-016 agree on the four policy names and the
  `NON_REPLAYABLE` recovery failure behavior. They disagree on HTTP GET default
  naming (`PURE` vs `IDEMPOTENT`) and need a clearer `aiStep` policy.
- Observer events: The specs agree that observer hooks exist without requiring
  `tramai-observability`, and OTel is an optional bridge. SPEC-013 still has one
  direct-OTel requirement and scheduler event names are not stable enough.
- Cancellation: SPEC-014, SPEC-015, and SPEC-016 agree on durable cancellation,
  shell/MCP cancellation propagation, delay aborts, and lease fencing. The run
  state transition table needs to include cancellation from delayed/gated/pending
  states.
- Versioning: SPEC-014 and SPEC-017 agree that workflow definitions are SemVer,
  in-flight runs finish on their starting version, and API responses include the
  workflow version.

Contradictions to resolve:

1. `GET /workflows/.../events` vs `GET /workspace/.../events` in SPEC-014.
2. Direct OpenTelemetry event wording in SPEC-013 vs hook-first observability in
   the vision and other specs.
3. Scheduler tick observer names and skipped/misfired terminology are not stable.
4. HTTP GET replay default differs between SPEC-015 and SPEC-016.
5. `skipped` as a server run state conflicts with SPEC-013's skipped tick model
   unless a skipped tick intentionally creates a run record.
6. SPEC-016 requires store behaviors absent from SPEC-013's `WorkflowStore`
   sketch.

## Recommendation

Proceed after a cleanup pass that normalizes terminology and closes the store,
event, replay, and run-state contracts. No spec requires a full rewrite, but the
conditions above should be resolved before implementation tasks are treated as
ready.
