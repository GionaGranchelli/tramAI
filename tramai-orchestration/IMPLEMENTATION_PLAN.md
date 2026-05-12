# TramAI Orchestration — Implementation Plan

> **Status:** Final. Reviewed by Giovanni (human), Copilot CLI, Codex CLI, and Gemini.
> **Alignment:** TramAI design principles — typed contracts, explicit over magical, fail loudly, framework-agnostic core.

---

## Phase 1 — P0: aiStep Contract & Replay Correctness

### What
Two related fixes in one contract change:

1. **`WorkflowContext` overload for `aiStep`** — Add an additive overload so `aiStep` can pass `workflowId` and `attributes` through `input`, `invoke`, and `merge`. Current API at `Workflow.kt:621-633` only accepts `(S) -> I`, `suspend (I) -> O`, `(S, O) -> S`. `AiWorkflowStep.execute` explicitly drops `WorkflowContext` at `Workflow.kt:825-828`.

2. **Per-step replay policy** — Replace the hardcoded `ReplayPolicy.IDEMPOTENT` for all `AiWorkflowStep` instances (`Workflow.kt:937-938`) with an explicit per-step setting. The current blanket default is a correctness hole: the worker recovery path uses replay policy to decide whether to re-execute or fail (`TramaiWorker.kt:399-424`), and raw lambdas are not safe to re-run without the caller opting in.

### How
- Keep the existing `aiStep` overload exactly as-is for method-reference ergonomics (`planner::plan`, `reviewer::review`)
- Add an overload accepting `(S, WorkflowContext) -> I`, `suspend (I, WorkflowContext) -> O`, `(S, O, WorkflowContext) -> S`
- Normalize both overloads into one internal `AiWorkflowStep` shape so `executeStep` at `Workflow.kt:457` has one execution path
- Add an explicit `replayPolicy` parameter to the `aiStep` builder, defaulting to a conservative value (proposal: require caller declaration, no silent default; or default to `NON_REPLAYABLE` for raw lambdas)
- Engine-backed typed services can opt into `IDEMPOTENT` or `EXTERNALLY_IDEMPOTENT`

### Files to Modify
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/Workflow.kt`
  - Builder overloads: lines 621-633
  - `AiWorkflowStep` data class: lines 819-828
  - `replayDescriptor` when branch: lines 937-939
  - Canonical fingerprint for ai steps: lines 1150-1154

### Risks
- Existing workflows using `aiStep` with the old signature must compile and behave identically — no breakage allowed
- Workflows that relied on implicit idempotent replay will see behavior change: non-replayable steps will fail loudly on worker takeover instead of re-executing silently
- Digest compatibility for checkpoint resume: the canonical fingerprint `ai:<name>` at `Workflow.kt:1150-1154` must remain stable for the old overload

### Test Strategy
- **Compatibility**: existing engine-backed aiStep tests (`WorkflowTest.kt:872-946`) must stay green unchanged, proving the old overload compiles and behaves identically
- **New overload**: add tests proving `workflowId` and `attributes` from `WorkflowContext` are visible inside `input`, `invoke`, and `merge`
- **Digest stability**: add test proving the canonical fingerprint path is unchanged for the old overload (existing digest mismatch tests at `WorkflowTest.kt:531`, `566`, `760`)
- **Worker replay**: add `TramaiWorkerTest` takeover cases for `aiStep` with:
  - Non-replayable default → fails loudly
  - Explicit `IDEMPOTENT` → re-executes
  - `EXTERNALLY_IDEMPOTENT` with/without stable key
  - Current worker replay coverage exists for `localStep` and `httpStep` only (`TramaiWorkerTest.kt:103`, `147`, `196`)

### Dependencies
None. Goes first.

---

## Phase 2 — P1: Subprocess Process Tree Cleanup

### What
Fix descendant-process leaks in `shellStep` and `mcpStep`, then extract shared lifecycle helpers.

### Current State
- **ShellWorkflowStep**: destroys only the parent process on timeout (`ShellStep.kt:183-196`) and in `finally` (`ShellStep.kt:224-230`). Child processes are orphaned.
- **SubprocessMcpTransportProvider**: parent-only cleanup in `cleanup` callback (`McpStep.kt:144-151`)
- **AgentCliSupport**: already does correct full process-tree cleanup (`AgentCliSupport.kt:188-210`)
- MCP tests use piped-stream fakes, never real subprocesses (`WorkflowMcpStepTest.kt:297-413`)

### How
1. Fix shell step: replace `process.destroyForcibly()` with process-tree enumeration + graceful shutdown + forced kill, reusing the pattern from `AgentCliSupport.kt:188-210`
2. Fix MCP transport: same fix in `SubprocessMcpTransportProvider` cleanup
3. Extract shared helpers into `internal ProcessSupport.kt` inside `tramai-orchestration`:
   - Process tree discovery
   - Graceful kill + forced kill with timeout
   - Interrupt-safe wait
   - Do NOT unify stream capture or stderr handling — shell captures stdout/stderr for results (`ShellStep.kt:173`), MCP fire-and-forget drains stderr (`McpStep.kt:134`), these are semantically different

### Files to Modify
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ShellStep.kt` — lines 183-196, 224-230
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/McpStep.kt` — lines 144-151
- `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/AgentCliSupport.kt` — lines 188-210 (reference pattern)
- **NEW:** `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ProcessSupport.kt`
- `tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/WorkflowShellStepTest.kt` — add descendant assertions
- `tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/WorkflowMcpStepTest.kt` — add real subprocess cleanup tests

### Risks
- Timeout/cancellation behavior is easy to regress — keep extraction narrow
- MCP's fire-and-forget stderr drain is not the same as shell output capture; do not merge them
- Observer events (`tramai.workflow.shell.timeout`, `tramai.workflow.mcp.*`) must remain unchanged

### Test Strategy
- Shell: add descendant-process assertions after timeout and cancellation — current tests only verify parent cleanup (`WorkflowShellStepTest.kt:88`, `113`)
- MCP: add real subprocess cleanup tests using `SubprocessMcpTransportProvider` instead of piped fakes — current tests use `createSlowServer()` and `createJsonServer()` with piped transports (`WorkflowMcpStepTest.kt:360-471`)
- Agent tests must remain unchanged (`WorkflowAgentStepTest.kt`)

### Dependencies
Independent of Phase 1.

---

## Phase 3 — P2: Structured Outputs (Spec/Design Track)

### What
Hermes/Codex steps produce raw strings (`HermesStep.kt:28`, `CodexStep.kt:30`). MCP returns `McpToolResult` with string fields (`McpStep.kt:60-64`). This conflicts with TramAI's "structured output as a first-class capability" principle.

### How
- Do NOT change the string APIs — keep them for compatibility
- Add typed overloads for agent/MCP steps that accept a decoder function
- Update `spec-015-agent-steps.md` to match the chosen decoder boundary
- Define the module boundary first so `tramai-orchestration` does not absorb `tramai-structured` responsibilities

### Files to Modify
- `docs/specs/spec-015-agent-steps.md` — update with decoder boundary
- New typed overloads in `Workflow.kt` (hermesStep, codexStep, mcpStep)
- `WorkflowAgentStepTest.kt` — add decode success/failure tests
- `WorkflowMcpStepTest.kt` — add decode success/failure tests

### Risks
- Structured decoding can leak serialization dependencies into orchestration
- Spec must be settled before implementation

### Test Strategy
Spec/ADR first, then decode success/failure tests.

### Dependencies
After Phase 1 and Phase 2.

---

## Phase 4 — P3: Parallel Branches ADR

### What
`parallelStep` is a typed fan-out map/reduce: `items: (S) -> Iterable<I>`, `invoke: suspend (I) -> O`, `merge: (S, List<O>) -> S` (`Workflow.kt:767-778`, `994-1027`). It supports ordinary execution but has hard limits:
- Replay is `NON_REPLAYABLE` (`Workflow.kt:948`)
- Nested durable suspension is unsupported (`Workflow.kt:438-440`)
- Canonical rendering is minimal (`Workflow.kt:1263-1267`)

### How
- Write an ADR that decides whether durable branch recovery is a goal
- Introduce a new construct (`parallelBranches` or `fanOutWorkflow`) rather than widening `parallelStep`
- Define branch-state mapper, branch result reducer, and explicit restrictions (no nested delay, resume-limited in v1)

### Files to Create/Modify
- **NEW:** `docs/adr/adr-parallel-branches.md`
- Future: new DSL construct + tests

### Risks
- Cuts across checkpointing, stop-policy accounting, and worker takeover semantics
- Must preserve backward compatibility for existing `parallelStep` users

### Test Strategy
ADR/spec first. Only then add tests for nested shell/http/MCP execution, checkpoint boundaries, and exact step-budget accounting.

### Dependencies
After structured-output direction is settled.

---

## Items Explicitly Out of Scope

These were evaluated by all four reviewers and rejected:

| Item | Reason |
|------|--------|
| **Hermes/Codex dedup template** | Already deduplicated via `AgentCliSupport.kt:33-112`. HermesStep and CodexStep are thin wrappers (~50 lines each). A new template adds regression surface for marginal gain. |
| **Metadata on step implementations** | Centralized digest/canonical rendering (`Workflow.kt:1112-1288`) is easier to audit. Splitting across step classes makes digest stability harder, not easier. |
| **orchestration depends on engine** | Architectural boundary by design. Engine owns provider execution; orchestration owns workflow state. They connect through `aiStep`. |
| **Visitor pattern for dispatch** | Sealed interface + exhaustive when is idiomatic Kotlin. The multiple `when` sites serve different semantic concerns (execution, replay, fingerprint). |
| **New `tramai-process` module** | Premature. Internal `ProcessSupport.kt` inside `tramai-orchestration` is sufficient for code used by one optional module. |

---

## Execution Order Summary

```
Phase 1 (P0)   aiStep WorkflowContext + replay policy    ─┐
                                                           ├── Independent
Phase 2 (P1)   Shell + MCP process tree cleanup          ─┘
                                                           ↓
Phase 3 (P2)   Structured outputs (spec/design)           ─┐
                                                           ├── Sequential
Phase 4 (P3)   Parallel branches ADR                      ─┘
```
