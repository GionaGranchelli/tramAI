# SPEC-016 — Engine Approval Suspension & Safe Resume

## Status
Proposed for PR #17

## Executive Summary

Connect the continuation-store foundation (PR #16) to the TramaiEngine runtime,
replacing the `ApprovalRequiredException` placeholder with real suspension and
resume. Deliver the first human-approval vertical slice inside the non-streaming
tool loop.

## Security Considerations

- Raw tool arguments stored only behind `SensitiveToolArguments`
- Suspended invocation state holds safe metadata only — no raw payloads
- Approval token redacted in all toString(), exceptions, logs
- Claimant-fenced completion prevents wrong-runner completion
- Uncertain outcomes leave CLAIMED — never retry automatically

## Domain Models

### SuspendedInvocation (engine-level)
```kotlin
data class SuspendedInvocation(
    val approvalId: String,
    val operation: OperationDefinition,
    val toolCall: ToolCall,
    val tool: ResolvedTool,
    val messages: List<Message>,
    val toolCallIndex: Int,
    val correlationId: String,
)
```
- Holds safe state needed to resume the provider loop
- NO raw arguments — those stay in ApprovalContinuationStore
- NO approval tokens

### EngineExecutionIdentity
```kotlin
data class EngineExecutionIdentity(
    val workflowRunId: String,
    val correlationId: String,
    val workflowDigest: Sha256Digest,
    val policyVersion: String,
    val actorId: String,
)
```

### ApprovalSuspendedException (replaces ApprovalRequiredException)
```kotlin
class ApprovalSuspendedException(
    val challenge: ApprovalChallenge,
    val approvalId: String,
    val workflowRunId: String,
    val toolCallId: String,
    val toolName: String,
    val continuationVersion: Long,
) : TramaiException(...)
```

### ResumeApprovalCommand
```kotlin
data class ResumeApprovalCommand(
    val approvalId: String,
    val approvalExpectedVersion: Long,
    val continuationExpectedVersion: Long,
    val presentedToken: ApprovalToken,
    val resumedBy: String,
)
```

## SPIs

### SuspendedInvocationStore (engine-level)
```kotlin
interface SuspendedInvocationStore {
    suspend fun create(invocation: SuspendedInvocation): SuspendedInvocation
    suspend fun get(approvalId: String): SuspendedInvocation?
    suspend fun remove(approvalId: String): SuspendedInvocation?
}
```

### Evaluate Decision API (PolicyEnforcementHelper addition)
```kotlin
suspend fun evaluate(context: PolicyContext): PolicyDecision
```

## Tasks

| ID | Task | Spec § | Deps | Effort |
|----|------|--------|------|--------|
| T1 | Add EngineExecutionIdentity + thread through engine | §5.2 | — | M |
| T2 | Add SuspendedInvocationStore SPI + InMemory impl | §5.1 | T1 | M |
| T3 | Add evaluate() to PolicyEnforcementHelper | §5.3 | — | S |
| T4 | Replace ApprovalRequiredException with suspension flow | §5.4-5.5 | T1, T2, T3 | L |
| T5 | Add resumeApproval() to TramaiEngine | §5.7-5.8 | T4 | L |
| T6 | Add lifecycle audit events | §6 | T4 | S |
| T7 | Add workflow digest helper | §5.2 | — | S |
| T8 | Tests: suspension happy path | — | T4 | M |
| T9 | Tests: resume happy path | — | T5 | M |
| T10 | Tests: uncertain outcomes, failures, edge cases | — | T5 | M |
