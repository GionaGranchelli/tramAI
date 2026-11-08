# D7-C — Durable MCP Effect Evidence (Boundaries E3 & E4) Design Contract

**Status:** Completed & Ratified  
**Base:** `release/0.7.0` at `966138155f2720b2829f2dfe32fa1c094dd71c45`  
**Implementation Branch:** `task/0.7.7h-d7-c-mcp-effect-evidence`  
**Scope:** Durable effect evidence, lineage attribution, dual-permit separation, crash-window qualification, safe reconnect discipline, and truthful execution guarantees for Model Context Protocol (MCP) boundaries: Server Start (E4) and Tool Invocation (E3).

---

## 1. Context & Motivation

EPIC-0.7.7 established durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestone D7-A ratified per-effect child evidence provenance for outbound HTTP (E1/E2), D7-B1 ratified durable process effect evidence for Shell execution (E7), and D7-B2 ratified process evidence for built-in Agent CLI boundaries (Codex E5 and Hermes E6).

MCP execution within TramAI (`McpStep.kt`) encompasses two distinct outbound effect boundaries:
- **E4:** MCP Server Start & Handshake (`WorkflowMcpServerStartPermit`, `mcp-server-start`)
- **E3:** MCP Tool Invocation (`WorkflowMcpToolPermit`, `mcp-tool`)

Prior to D7-C, `McpWorkflowStep` acquired admission permits but did not persist per-effect child evidence records in `WorkflowEffectEvidenceRecordStore`, permitting untracked remote and local state transitions, unverified reconnect attempts across outcome-uncertain failures, and potential leakage of sensitive tool arguments or execution prompts into diagnostics.

D7-C extends TramAI's ratified D7 evidence architecture to both MCP boundaries, guaranteeing that every governed MCP server start attempt and tool invocation attempt produces its own durable, correctly attributed evidence record without compromising MCP security policies, cancellation, subprocess cleanup, or ungoverned compatibility.

---

## 2. Evidence States & Schema

### Authoritative Effect States

Every governed MCP server start (E4) and tool invocation (E3) attempt transitions through strictly audited states:

| State | E4 (Server Start) Guarantee | E3 (Tool Call) Guarantee |
| --- | --- | --- |
| `ADMITTED` | Proves effect authorization via `WorkflowMcpServerStartPermit`. Child record written. Subprocess launch not yet confirmed. | Proves effect authorization via `WorkflowMcpToolPermit`. Child record written. Client tool invocation not yet executed. |
| `DISPATCHED` | Transport connection established (`transportProvider.connect()` returned a connection). Transport acquisition observed. | Remote tool call finished and returned (`client.callTool()` returned `CallToolResult`). |
| `COMPLETED` | MCP protocol handshake completed (`client.connect()` succeeded). | Tool result parsed, truncated, and returned to step engine. |
| `UNCERTAIN` | Failure occurred before handshake completed, or connection cancelled/timed out. | Tool call threw an exception, timed out, cancelled, or post-call evidence write failed. |

> [!NOTE]
> For E3 (Tool Call), a tool returning `isError = true` in its `CallToolResult` represents a protocol-level error response from the tool server. The effect itself successfully executed to completion; therefore, the effect evidence state is **`COMPLETED`**.

### Record Identity & Key Schema

Each child evidence record is identified by:

$$\text{Key} = (workflowName, workflowId, checkpointGeneration, admissionSeq)$$

- `workflowName`, `workflowId`: Owning workflow identification.
- `checkpointGeneration`: Generation string from `WorkflowMcpServerStartPermit` or `WorkflowMcpToolPermit` (canonically empty string `""` when absent), preventing collision across multi-generation checkpoint lifecycles.
- `admissionSeq`: The durable admission sequence returned by `WorkflowCheckpointStore.admit()`.

**Fields:**
- `key`: Compound primary key.
- `stepName`: Attributed step name.
- `stepAttemptId`: Authoritative parent attempt ID from `PersistedStepAttempt`.
- `effectKind`: `MCP_SERVER_START_EFFECT_KIND` (`"mcp-server-start"`) or `MCP_TOOL_EFFECT_KIND` (`"mcp-tool"`).
- `state`: `ADMITTED`, `DISPATCHED`, `COMPLETED`, `UNCERTAIN`.
- `createdAtEpochMillis`: Monotonic clock sample at record creation.
- `completedAtEpochMillis`: Monotonic clock sample at normal completion (null until `COMPLETED`).
- `diagnosticSummary`: Fixed, safe diagnostic category (no raw arguments, prompts, results, or exception messages).

---

## 3. Key Architectural Decisions

### 3.1 Distinct Permits and Effect Boundaries (E4 vs E3)
- E4 (`WorkflowMcpServerStartPermit`) authorizes only server start and transport acquisition. It cannot be substituted for or reused to authorize E3.
- E3 (`WorkflowMcpToolPermit`) authorizes only tool execution over an already-connected client.
- Both permits carry their respective `checkpointGeneration`.
- In a step attempt that starts a server and executes a tool, exactly two distinct child evidence records are written with strictly monotonic admission sequences ($S_{\text{start}} < S_{\text{tool}}$).

### 3.2 Fail-Closed Lineage Attribution
When D7 evidence tracking is enabled (`evidenceStore != null`):
- `requireEvidenceLineageIfEnabled(workflowName, context.workflowId, stepName, admissionAvailable = mcpAdmission != null, stepAttempt = stepAttempt)` runs before any permit admission is requested.
- If `mcpAdmission == null` or `stepAttempt == null`: `WorkflowEvidenceResolutionException` is thrown immediately.
- No admission counter is incremented, no permit is granted, no subprocess is launched, and no tool is invoked.

### 3.3 Strict Phase-Aware Reconnect Discipline & Non-Reconnection on Uncertainty
- Reconnection eligibility is strictly phase-aware, never derived from raw exception types alone:
  - **Safe-to-reconnect phase (E4 connect):** Only proven transient transport/setup failures occurring during initial transport connection acquisition (`transportProvider.connect()`) before E4 completion may trigger a reconnect attempt when `config.reconnect = true`. Each retry receives fresh durable admissions and distinct child evidence records.
  - **Evidence persistence failure (non-reconnectable):** Evidence store operations (`recordAdmitted`, `updateState`) wrap store errors in `McpEvidencePersistenceException`, which strictly forbids reconnection.
  - **E4 completion write failure (non-reconnectable):** A successful E4 connection followed by an E4 completion-evidence write failure fails closed immediately and does not reconnect.
  - **E3 phase (non-reconnectable):** Once E3 admission begins, any subsequent failure (E3 permit admission, E3 child record persistence, tool invocation, or result processing) is wrapped in `McpToolExecutionException` (`isTransientForReconnect() == false`), strictly forbidding an implicit new E4 connection or tool retry.
  - **Post-result cleanup phase (non-reconnectable):** Client or transport cleanup failures are surfaced as `McpPostCallCleanupException` and never retry.

### 3.4 Bounded Cleanup & Secondary Cancellation Isolation
- Bounded client shutdown: `client.close()` is executed under `NonCancellable` with an explicit 5-second deadline (`withTimeoutOrNull(5_000L)`). A stalled client close cannot indefinitely block subsequent cleanup.
- Unconditional transport cleanup: transport and subprocess cleanup (`cleanupConnection`) runs in a `finally` block, guaranteeing that process trees and pipes are cleaned up regardless of how client close finishes.
- Secondary cancellation isolation: If client close or transport cleanup throws `CancellationException` while an earlier `primaryFailure` already exists, the secondary cancellation is sanitized and suppressed onto `primaryFailure` via `primaryFailure.suppressCleanupDiagnostic(error)`. It never replaces the primary execution failure.
- Active evidence uncertainty: `recordUncertainty` runs in an inner `finally` block, ensuring that any incomplete effect transitions to `UNCERTAIN` before cleanup completes.
- Standalone cleanup failures: Surfaced as `McpPostCallCleanupException` only when no primary failure occurred.

### 3.5 Sensitive Data Isolation & Safe Fixed Diagnostics
Evidence records persist fixed, safe diagnostic categories:
- `mcp_server_start_failed`: Subprocess or transport provider failed to start.
- `mcp_initialization_failed`: MCP client handshake or initialization failed.
- `mcp_tool_execution_failed`: Tool invocation threw an exception or remote protocol failed.
- `mcp_timeout`: Execution exceeded configured timeout deadline.
- `mcp_cancelled`: Execution was interrupted by coroutine cancellation.
- `mcp_cleanup_failed`: Client close or process termination failed.

No tool arguments, execution prompts, result content, environment variables, or raw exception messages are ever written to `diagnosticSummary`. Raw details remain strictly confined to the in-memory `failureDiagnosticObserver` boundary.

### 3.6 Missing Evidence Record Fails Closed
When updating evidence state (`DISPATCHED` or `COMPLETED`), if the expected child record is missing from the store (`getEvidence() == null`), `McpEvidenceOps.updateState()` throws `WorkflowEvidenceResolutionException`. It never silently ignores missing records.

---

## 4. Crash-Window & Adversarial Matrix (E3 & E4)

| Window | Scenario | Forced Interleaving | Expected Evidence Verdict |
| --- | --- | --- | --- |
| **W1 (E4)** | Crash after E4 admission, before E4 child write | Injected store failure on `recordAdmitted(E4)` | Checkpoint admission sequence advanced; child store empty; classified as outcome uncertain |
| **W1 (E3)** | Crash after E3 admission, before E3 child write | Injected store failure on `recordAdmitted(E3)` | Checkpoint admission sequence advanced; E4 record `COMPLETED`; E3 record absent; tool never invoked |
| **W2 (E4)** | Crash after E4 `ADMITTED`, before transport connect | Injected transport failure | E4 record remains `ADMITTED` in store (or transitions to `UNCERTAIN`); process never started |
| **W2 (E3)** | Crash after E3 `ADMITTED`, before tool call | Injected failure in `client.callTool` | E4 record `COMPLETED`; E3 record remains `ADMITTED` (or transitions to `UNCERTAIN`); never `DISPATCHED` |
| **W3 (E4)** | Crash after handshake, before E4 `COMPLETED` write | Injected store failure on `updateState(COMPLETED)` | E4 record remains `DISPATCHED`; step fails; tool never invoked |
| **W3 (E3)** | Crash after tool return, before E3 `COMPLETED` write | Injected store failure on `updateState(COMPLETED)` | E3 record remains `DISPATCHED`; step fails; reconnect strictly forbidden |

---

## 5. Qualification Matrix (MCE-01 through MCE-20)

All 20 qualification scenarios are implemented and verified in `WorkflowMcpEffectEvidenceTest.kt`:

| Test ID | Scenario Description | Verified Invariant |
| --- | --- | --- |
| **MCE-01** | Happy path execution of MCP step | Two distinct records (E4 `mcp-server-start` then E3 `mcp-tool`), both `COMPLETED`, monotonic admission sequences. |
| **MCE-02** | Monotonic admission sequencing and lineage | E4 and E3 have distinct admission sequences ($S_{\text{start}} < S_{\text{tool}}$) and identical `stepAttemptId` matching parent. |
| **MCE-03** | Fail-closed lineage without step attempt | Throws `WorkflowEvidenceResolutionException` before admission; zero evidence records written. |
| **MCE-04** | W1 crash window: E4 child write fails | Checkpoint admission sequence advanced; child store empty; execution halted. |
| **MCE-05** | W2 crash window: E4 process start crash | E4 record remains `ADMITTED` when crash occurs before launch; never `DISPATCHED`. |
| **MCE-06** | E4 server start failure | E4 transitions `ADMITTED` -> `UNCERTAIN` (`mcp_server_start_failed`); subprocess cleaned up; E3 never admitted. |
| **MCE-07** | E4 client initialization failure | E4 transitions `ADMITTED` -> `DISPATCHED` -> `UNCERTAIN` (`mcp_initialization_failed`); connection cleaned up; E3 never admitted. |
| **MCE-08** | W1/W2 crash window: failure writing E3 ADMITTED | E4 record `COMPLETED`; E3 admission sequence committed on checkpoint; tool call never invoked (`toolInvoked == false`). |
| **MCE-09** | W2 crash window: E3 tool call crash | E3 record remains `ADMITTED` when crash occurs before dispatch; never `COMPLETED`. |
| **MCE-10** | E3 tool invocation failure | E3 transitions `ADMITTED` -> `UNCERTAIN` (`mcp_tool_execution_failed`); client cleaned up; reconnect suppressed. |
| **MCE-11** | Retry on reconnectable failure allocates fresh sequences | Transient E4 failure with `reconnect=true` allocates fresh admission sequence on retry; distinct child records. |
| **MCE-12** | Non-reconnect on outcome-uncertain failure | E3 tool execution failure does not trigger reconnection even with `reconnect=true`. Exactly 1 tool call executed. |
| **MCE-13** | Cleanup before secondary uncertainty persistence | Connection cleanup executes before uncertainty persistence; cleanup ordering verified. |
| **MCE-14** | Missing evidence record fails closed | Updating state when record is absent in store throws `WorkflowEvidenceResolutionException`. |
| **MCE-15** | W3 crash window: E4 completion write failure | E4 record remains `DISPATCHED` when completion write fails; execution halted before tool call. |
| **MCE-16** | W3 crash window: E3 completion write failure | E3 record remains `DISPATCHED` when completion write fails; step fails without retry. |
| **MCE-17** | MCP step timeout | Step times out -> active record transitions to `UNCERTAIN` (`mcp_timeout`); resources cleaned up. |
| **MCE-18** | Protocol `isError=true` tool result | Tool returning `isError=true` produces `COMPLETED` evidence record; truthful protocol result preserved. |
| **MCE-19** | Sensitive data isolation | Tool arguments, secret tokens, and error messages do not leak into `diagnosticSummary` (verifies safe category). |
| **MCE-20** | Ungoverned compatibility | Ungoverned execution (`evidenceStore == null`) executes cleanly with zero evidence records written. |

---

## 6. Mutation Proofs (M1 through M11)

All 11 negative-control mutations were applied, verified, and killed by the qualification suite:

| Mutation ID | Mutation Description | Target Test | Observed Kill Mechanism |
| --- | --- | --- | --- |
| **M1** | Remove pre-admission lineage validation | MCE-03 | `AssertionError: Expecting code to raise a throwable` |
| **M2** | Skip E4 `recordAdmitted` | MCE-01 | `AssertionError: Expected size: 2 but was: 1` |
| **M3** | Skip E3 `recordAdmitted` | MCE-02 | `AssertionError: Expected size: 2 but was: 1` |
| **M4** | Reuse E4 permit sequence for E3 | MCE-02 | `AssertionFailedError: expected: 3L but was: 2L` |
| **M5** | Record E4 `COMPLETED` before initialization | MCE-06 | `AssertionFailedError: expected: UNCERTAIN but was: COMPLETED` |
| **M6** | Record E3 `COMPLETED` before tool invocation | MCE-10 | `AssertionFailedError: expected: UNCERTAIN but was: COMPLETED` |
| **M7** | Reconnect on outcome-uncertain tool failure | MCE-10 | `AssertionError: expected: 1 but was: 2` |
| **M8** | Reuse admission sequence on step retry | MCE-11 | `WorkflowMcpException: duplicate key in evidence store` |
| **M9** | Skip connection cleanup on refusal | MCE-06 | `AssertionFailedError: expected: true but was: false` |
| **M10** | Swallow missing record lookup in `updateState` | MCE-14 | `AssertionError: Expecting code to raise a throwable` |
| **M11** | Persist raw exception message in `diagnosticSummary` | MCE-19 | `AssertionError: Expecting "secret-credential-leak-67890" not to be in diagnosticSummary` |
