# Secondary System Failure Policy

This page defines the failure policy for secondary systems in Tramai: observability, telemetry, policy audit, approval audit, DLP audit, and outbox delivery. Secondary systems are non-authoritative extensions of the engine; their failure must never corrupt a primary business operation, and the authoritative surfaces among them must never silently drop a record that the governed operation depends on.

## Extension-Point Matrix

| Extension point | Authority | Failure behaviour |
|---|---|---|
| OperationObserver / OperationObservation (engine per-attempt telemetry) | NON_AUTHORITATIVE | FAIL_OPEN + safe diagnostic (FailureIsolatingOperationObserver wraps at EngineComponentFactory composition) |
| EngineEventObserver (engine lifecycle telemetry) | NON_AUTHORITATIVE | FAIL_OPEN + safe diagnostic (FailureIsolatingEngineEventObserver wraps at EngineComponentFactory) |
| WorkflowObserver (workflow lifecycle telemetry) | NON_AUTHORITATIVE | FAIL_OPEN + safe diagnostic (FailureIsolatingWorkflowObserver wraps at WorkflowRunner.run/resume entry) |
| TramaiWorkerObserver (worker lifecycle telemetry, 20 callbacks) | NON_AUTHORITATIVE | FAIL_OPEN + safe diagnostic (FailureIsolatingTramaiWorkerObserver wraps at TramaiWorker construction) |
| RuntimeEvent emission, event declared FAIL_OPEN (default) | NON_AUTHORITATIVE | FAIL_OPEN + safe diagnostic (emission routed through typed onEngineEvent(RuntimeEvent) / onWorkflowEvent(RuntimeEvent, context) overloads so the failure policy is honored) |
| RuntimeEvent emission, event declared FAIL_CLOSED | AUTHORITATIVE | FAIL_CLOSED: emission failure propagates (never contained) |
| Policy decision audit (PolicyEnforcementHelper.evaluate -> PolicyDecisionAuditEmitter) | AUTHORITATIVE | FAIL_CLOSED: audit is emitted before the decision is acted on; audit failure propagates and blocks the operation (proven by tests: audit failure blocks provider invocation / tool execution / fallback transition, zero side effects) |
| Approval lifecycle audit — pre-mutation callbacks (suspension creation, force-cancellation REQUESTED) | AUTHORITATIVE | FAIL_CLOSED: audit runs BEFORE the governed mutation; audit failure propagates and blocks the mutation (e.g. 'denyApproval fails closed when no AuditEngine') |
| Approval lifecycle audit — post-side-effect completion (onToolExecutionCompleted) | AUTHORITATIVE | FAIL_CLOSED declared, terminal-recorded disposition: the tool side effect and COMPLETED transition have already happened, so fail-closed is physically impossible; audit failure is recorded via the safe diagnostic (authoritative, never silently converted to telemetry), resume still succeeds |
| Approval lifecycle audit — post-mutation notification (onSuspensionCancelled) | AUTHORITATIVE | declared FAIL_CLOSED, best-effort disposition: stores already mutated; audit failure recorded via safe diagnostic, never fails the method after the transition committed |
| Approval lifecycle audit — audit while reporting an existing primary failure (onUncertainOutcome) | AUTHORITATIVE | preserve the primary failure: audit failure recorded via safe diagnostic and NEVER substituted for the business failure being reported |
| DLP/security audit | AUTHORITATIVE | FAIL_CLOSED: DLP redaction audit failure propagates |
| Audit outbox enqueue (SovereignOpsAuditOutboxStore.append, PREPARED record) | AUTHORITATIVE | FAIL_CLOSED at enqueue: if the outbox append fails the approval is never mutated (proven: 'denyApproval fails closed when no AuditEngine', 'denyApproval fail-closed leaves approval unchanged') |
| Outbox dispatch (background worker, PENDING/FAILED_RETRYABLE records) | AUTHORITATIVE (delivery) | RETRY / at-least-once: dispatch failure marks FAILED_RETRYABLE, record retained and retried; business operation is NOT retroactively failed (proven: 'dispatcher can retry FAILED_RETRYABLE outbox records', worker 'runOnce catches RuntimeException and returns failure summary') |
| Cancellation path (onCallCancelled inside completeCancellation helpers) | NON_AUTHORITATIVE | CE stays primary; observer failure attached as SUPPRESSED onto the in-flight CancellationException (frozen contract; never replaced) |
| Tool-processing failure path (onCallCompleted inside completeAfterToolProcessing) | NON_AUTHORITATIVE | primary error stays primary; contained observer failure surfaced as SUPPRESSED; on success path, observer failure logged as warning, business result preserved |
| Optional diagnostic telemetry | NON_AUTHORITATIVE | IGNORE / best effort |

All catalogue events currently default to FAIL_OPEN. The FAIL_CLOSED propagation path exists and is unit-tested with a probe event; declaring an event FAIL_CLOSED makes its emission authoritative.

## Delivery Semantics

Tramai makes **no blanket exactly-once guarantee** anywhere in this policy. Each surface has an explicit, concrete semantic:

- **Outbox dispatch: at-least-once.** Records are retained as FAILED_RETRYABLE on dispatch failure and retried by the background worker; a record is only removed once the sink acknowledges it. Duplicate dispatch is possible under lease takeover (a lease may expire between send and acknowledgement and another worker picks the record up) and is handled idempotently where the sink permits. The business operation that produced the record is never retroactively failed because a later dispatch attempt failed.
- **Outbox enqueue: fail-closed, never best-effort.** The PREPARED record append into SovereignOpsAuditOutboxStore is authoritative; if it fails, the approval mutation is not applied. Enqueue is a single, synchronous, transactional step — not a fire-and-forget path.
- **Observers (OperationObserver, EngineEventObserver, WorkflowObserver, TramaiWorkerObserver): best effort.** FAIL_OPEN means observer failure is contained and diagnosed; telemetry may be lost and no retry is attempted.
- **RuntimeEvent emission: best effort for FAIL_OPEN events (default), fail-closed for FAIL_CLOSED events.** The declared policy of the event, not the mood of the caller, decides.
- **Audit surfaces (policy decision audit, approval lifecycle audit, DLP audit): fail-closed.** These are evidence-generation/persistence surfaces the governed operation depends on; their failure blocks the operation.
- **Optional diagnostic telemetry: best effort (IGNORE).** No guarantees of any kind.

The evidence generation and persistence surfaces that policy enforcement depends on are authoritative and FAIL_CLOSED; the telemetry surfaces that observe execution without governing it are NON_AUTHORITATIVE and FAIL_OPEN/best effort.

## Safe Diagnostic

A contained secondary failure is reported through `SecondaryFailureDiagnostic`, which carries **only**:

- `extensionPoint`
- `callback`
- `errorType`
- `failurePolicy`
- `authority`

It never carries messages, stack traces, workflow state, prompts, or tool arguments. A diagnostic therefore cannot leak prompt or tool content into logs, and the diagnostic's own construction cannot resurrect the contained failure into the business path. `CancellationException` always escapes unchanged through every wrapper.

## Architecture

Secondary-failure isolation is enforced at single wiring points, not scattered through call sites:

- **Failure-isolating wrappers.** Four wrapper classes contain observer failures and emit a safe diagnostic:
  - `FailureIsolatingOperationObserver` — wired at EngineComponentFactory composition.
  - `FailureIsolatingEngineEventObserver` — wired at EngineComponentFactory.
  - `FailureIsolatingWorkflowObserver` — wired at WorkflowRunner.run/resume entry.
  - `FailureIsolatingTramaiWorkerObserver` — wired at TramaiWorker construction.
- **Emission extension routing.** RuntimeEventEmission.kt in tramai-engine and tramai-orchestration route emission through the typed `onEngineEvent(RuntimeEvent)` / `onWorkflowEvent(RuntimeEvent, context)` overloads so the declared failure policy of each event is honored at the emission point.
- **SecondaryFailureRecording.** The `onCallCompleted` containment records the failure so `completeAfterToolProcessing` can attach it as SUPPRESSED onto a primary business error (or log it as a warning on the success path, preserving the business result). Cancellation containment attaches the observer failure as SUPPRESSED onto the in-flight `CancellationException`; the CE is never replaced.
- **Boundary architecture guard.** `SecondaryFailureBoundaryArchitectureTest` in tramai-observability scans the sources and forbids raw observer/observability/`engineEventObserver`/`request.observer` callback invocation outside the wrappers and the wired-through files. The guard is what keeps future call sites from bypassing the policy.

## Verification

- **Lifecycle matrix tests:**
  - `FailureIsolatingOperationObserverTest` — operation lifecycle matrix
  - `FailureIsolatingWorkflowObserverTest` — workflow lifecycle matrix
  - `FailureIsolatingTramaiWorkerObserverTest` — worker 20-callback matrix
- **Preservation tests:**
  - `SecondaryFailurePreservationEngineTest` — engine success/failure preservation
  - `WorkflowRunnerPreservationTest` — workflow success/failure preservation through the public API
  - `SecondaryFailureBoundaryArchitectureTest` — boundary guard
- **Pre-existing audit fail-closed tests:**
  - `PolicyAuditWiringTest` — e.g. 'audit failure blocks provider invocation' (also tool execution / fallback transition, zero side effects)
  - `SovereignOpsAuditOutboxTest` — e.g. 'denyApproval fails closed when no AuditEngine', 'denyApproval fail-closed leaves approval unchanged', 'dispatcher can retry FAILED_RETRYABLE outbox records'
  - `SovereignOpsAuditOutboxBackgroundWorkerTest` — 'runOnce catches RuntimeException and returns failure summary'
