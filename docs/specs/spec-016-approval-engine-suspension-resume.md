# SPEC-016 — Engine Approval Suspension & Safe Resume

## Status
Implemented in PR #17

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

### SuspendedInvocationMetadata + SensitiveResumeContext (engine-level)

The original `SuspendedInvocation` is now split into two types:

**SuspendedInvocationMetadata** — safe, serializable metadata stored in `SuspendedInvocationStore`:
```kotlin
data class SuspendedInvocationMetadata(
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val toolCallIndex: Int,
    val correlationId: String,
    val identity: EngineExecutionIdentity,
    val securityContext: ExecutionSecurityContext,
    val conversationId: String? = null,
    val historySize: Int = 0,
    val tokenBudgetSnapshot: TokenBudgetSnapshot? = null,
    val toolSecurity: ToolSecurityMetadata? = null,
)
```
- NO raw arguments — those stay in `ApprovalContinuationStore`
- NO approval tokens
- NO messages (which contain prompts and content)
- `toolSecurity` is used for BEFORE_WORKFLOW_RESUME policy context without revealing sensitive context early

**SensitiveResumeContext** — opaque wrapper stored alongside metadata, only accessible via `revealForResume()`:
```kotlin
class SensitiveResumeContext private constructor(
    private val operation: OperationDefinition,
    private val tool: ResolvedTool,
    private val messages: List<Message>,
    private val toolCall: ToolCall,
)
```
- toString returns `[REDACTED]`
- Only revealed AFTER `claimForExecution()` succeeds
- Never serialized

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
    suspend fun create(metadata: SuspendedInvocationMetadata, sensitiveContext: SensitiveResumeContext)
    suspend fun get(approvalId: String): SuspendedInvocationMetadata?
    suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext?
    suspend fun remove(approvalId: String): SuspendedInvocationMetadata?
}
```

### Evaluate Decision API (PolicyEnforcementHelper addition)
```kotlin
suspend fun evaluate(context: PolicyContext): PolicyDecision
```

## Sensitive Context / Metadata Split

The BEFORE_WORKFLOW_RESUME policy evaluation uses safe metadata only:
- `metadata.toolSecurity` instead of `resumeContext.tool.security`
- Sensitive context is revealed AFTER `claimForExecution()`, not before

This ensures that policy evaluation does not require revealing sensitive data, and
sensitive context is only available once the continuation has been securely claimed.

## Implementation Status

PR #17 delivers:
- Full suspension flow (BEFORE_TOOL_EXECUTION → RequireApproval → create challenge → persist continuation → persist safe metadata → throw ApprovalSuspendedException)
- Full resume flow (authorize → enforce BEFORE_WORKFLOW_RESUME → claim continuation → reveal sensitive context → execute tool → continue provider loop → finalize → complete continuation)
- Uncertain outcome handling (failures after claim emit audit event, continuation stays CLAIMED)
- Nested approval fail-closed (NestedApprovalNotSupportedException)
- Safe metadata / sensitive context split
- ConversationId threading through suspension/resume
- Structured output finalization (parse first, persist only on success)
- Token budget snapshot/restore on resume
- Payload integrity re-verification after claim
- Lifecycle audit events (suspended, resumed, completed, cancelled, uncertain outcome)
- Edge case test coverage for all failure paths

## v1 Limitations

- **conversationId threading**: conversationId is threaded through the execution chain but the initial suspension must have been triggered by a path that has it. The normal `executeRaw`/`executeStructured` paths pass it; direct `suspendToolExecution` callers that don't supply it will get null.
- **Structured parse retry**: Unlike the normal flow, which retries structured parsing with feedback, the resume path makes exactly one parse attempt. If parsing fails, the exception is thrown immediately without retry.
- **Streaming resume**: ReturnKind.STREAMING throws ConfigurationException on resume.
- **Chained approval**: Nested approval (approval during a resumed workflow) is not supported. Any policy decision of RequireApproval during a resumed workflow throws NestedApprovalNotSupportedException.
- **Metadata-only**: `SuspendedInvocationMetadata` does not carry raw tool arguments, approval tokens, or messages — those are split into `ApprovalContinuationStore` and `SensitiveResumeContext`.

## Final Ordering Guarantee (PR #17 Final)

The resume approval flow enforces this ordering to prevent token-free destructive mutations:

1. Load metadata (read-only)
2. Validate continuation: status == PENDING, version matches expectedVersion
3. Resolve non-side-effecting dependencies (digester, coordinator)
4. **authorizeResume()** — validates and consumes the approval token
5. Evaluate BEFORE_WORKFLOW_RESUME:
   - Deny/RequireApproval: atomic cancellation (cancel → remove → audit) — safe because token was already validated
   - Allow: claimForExecution → execute tool → provider loop → finalize → complete
6. Post-completion cleanup is non-authoritative: failures do NOT emit uncertain outcome
