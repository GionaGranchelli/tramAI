# Execution Sequence — Runtime Ownership Map

This document describes the runtime execution flows at the **ownership/seam level**: which component owns each stage, where the authoritative contracts live, and where flows diverge and rejoin. It is not a private-call trace.

## A. Typed service invocation

Ownership: `tramai-engine` (`TramaiEngine`, `InvocationExecutionCoordinator`); contracts in `tramai-core`.

```
caller
  → @AiService interface method (tramai-core annotations)
  → JDK dynamic proxy (TramaiInvocationHandler, tramai-engine/invocation)
  → operation planning (OperationDefinitionCompiler → OperationExecutionPlan, tramai-engine/planning)
  → governance / input processing (PolicyEnforcementHelper, tramai-engine)
  → provider routing (ProviderRegistry, tramai-core/provider + ProviderExecutionCoordinator, tramai-engine/provider)
  → admitted provider attempt (ProviderAttemptExecutor)
  → provider transport (provider adapter module, e.g. tramai-openai)
  → structured / raw processing (StructuredResponseCoordinator + tramai-structured handler)
  → governance / output processing (PolicyEnforcementHelper, before-response-return)
  → observation / audit / evidence (EngineEventObserver, AuditEngine, evidence)
  → caller
```

Key seams:

- **Invocation:** `TramaiInvocationHandler` (proxy) → `InvocationExecutionCoordinator` (governance enforcement points: before-provider-resolution, before-provider-invocation, before-response-return).
- **Provider execution:** `ProviderExecutionCoordinator` owns routing/retry/fallback; `ProviderAttemptExecutor` owns a single admitted attempt. Provider adapters own transport/vendor translation **only**.
- **Structured output:** `StructuredResponseCoordinator` (engine) consumes `StructuredOutputHandler` (tramai-structured, e.g. `JacksonStructuredOutputHandler`); the compiled descriptor is authoritative.
- **Observation:** `EngineEventObserver` / `FailureIsolatingEngineEventObserver`; OTel via `tramai-observability` (`OpenTelemetryOperationObserver`).

## B. Streaming

Ownership: `tramai-engine/streaming` (`StreamingExecutionCoordinator`).

- Diverges from the invocation flow at provider admission: the admitted attempt streams tokens instead of returning a full response. Token chunks are forwarded to the caller as they arrive (`StreamChunk.Token` → `onToken`); only the terminal assembled response passes through the response-interceptor path (`interceptResponse` on `StreamChunk.Complete`). Per-token validation/sanitization/audit before caller visibility is NOT claimed.
- Rejoins at structured/output processing and observation on the terminal chunk: the completed response is intercepted, observed, token-budget-enforced, and emitted before the stream finishes.
- Streaming models: `StreamingExecutionModels` in the same package.

## C. Tool invocation

Ownership: `tramai-engine/tool` (`ToolExposureCoordinator`, `ToolAuthorizationCoordinator`, `ToolInvocationExecutor`); contracts in `tramai-core` (`Tool`, `AiTool`, `ToolResult`, `ModelVisibleToolMessage`).

```
model tool request (tool call in provider response)
  → tool resolution (ToolExposureCoordinator; registry-based, tramai-core)
  → policy / governance (ToolAuthorizationCoordinator + PolicyEnforcementHelper)
  → execution (ToolInvocationExecutor)
  → safe model-visible result (ModelVisibleToolMessage — DLP-sanitized)
  → evidence / observation (audit + evidence emitters)
```

The engine owns tool lifecycle; tools must not bypass policy or return unsanitized content to the model.

## D. Approval / replay

Ownership: `tramai-engine/approval` (`ApprovalSuspensionCoordinator`, `ApprovalResumeCoordinator`, `DefaultApprovalGateway`, `ReplayAuthorizationService`, `ContinuationClaimService`); durable contracts in `tramai-core/approval`; store semantics under `tramai-security/approval` + store TCKs.

- Suspension: an invocation that requires approval is suspended with a durable `ApprovalContinuation`; state transitions are governed by the **approval-continuation** lifecycle model — `ApprovalContinuationStoreTck` + `ApprovalContinuationLifecycleModel` in `tramai-testing` (distinct from `ApprovalStoreTck`, which covers the approval store itself).
- Replay: incoming replays are validated (`ReplayEnvelopeFactory` / `ReplayEnvelopeValidator`) and deduplicated; claimed resumes route through `ClaimedResumeExecutor`.
- Authority: `DefaultApprovalGateway` composes approval/suspension/continuation stores; audit decisions flow through `AuditEngineApprovalLifecycleAuditEmitter`.

## E. Workflow / worker

Ownership: `tramai-orchestration`.

- **Supervision:** `WorkflowExecutionSupervisor` owns workflow execution; `WorkerLifecycleController` owns worker lifecycle state; `WorkerShutdownCoordinator` owns coordinated shutdown.
- **Recovery/fencing:** `WorkflowRecoveryCoordinator` + lease-aware execution (`LeaseCoordinator`, `LeaseRenewalLoop`); persistence seams via checkpoint/lease/step-attempt stores (file, JDBC, Markdown variants).
- **Workers:** `TramaiWorker` + observers (`FailureIsolatingTramaiWorkerObserver`, `LoggingTramaiWorkerObserver`); OTel via `OpenTelemetryTramaiWorkerObserver`.
- **Persistence contracts:** `JdbcWorkflowCheckpointStore`, `FileWorkflowCheckpointStore`, step-attempt record stores — behavior enforced by store TCKs, not by copying.

## Stable ownership statements

- Provider admission and completion are owned by the engine's provider-execution boundary; retries/fallbacks must not be implemented inside provider adapters.
- Structured output validation is owned by `tramai-structured`; the engine consumes its contracts.
- Circuit-breaker OPEN/HALF_OPEN generation semantics are intentionally not frozen while 8.2g is in flight; the ownership seam above is stable.
