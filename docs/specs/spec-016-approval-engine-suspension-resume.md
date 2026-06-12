# SPEC-016 — Engine Approval Suspension & Safe Resume

## Status
Implemented in PR #17
Updated in PR #28 — Trusted Replay Envelope and Operation Registry
Updated in PR #29 — Encrypted FileSuspendedInvocationStore and restart-safe recovery

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
    val operationReference: ResumeOperationReference,  // PR #28
    val replayEnvelopeDigest: Sha256Digest,            // PR #28
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

**SensitiveReplayEnvelope** (PR #28) — opaque messages-only wrapper, replaces SensitiveResumeContext:
```kotlin
class SensitiveReplayEnvelope private constructor(
    private val messages: List<Message>,
) {
    fun revealForResume(): ReplayPayload
    override fun toString(): String = "[REDACTED]"
    companion object { fun of(messages: List<Message>): SensitiveReplayEnvelope }
}
```
- Contains only replayable message-model data.
- Historical message-level ToolCall values may exist for provider continuity.
- The selected suspended ToolCall arguments are replaced with a sentinel.
- The selected arguments are rehydrated from ApprovalContinuationStore only after claim.
- No executable runtime objects are stored: no OperationDefinition, ResolvedTool,
  reflection objects, callbacks, providers, or registries.

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

### ResumeOperationReference (PR #28)

Stable, serializable reference that identifies a resume-able operation without runtime objects:

```kotlin
data class ResumeOperationReference(
    val serviceInterface: String,
    val methodName: String,
    val jvmMethodDescriptor: String,
    val resumeDefinitionDigest: Sha256Digest,
)
```

Computed deterministically from the `ServiceDefinition` and `OperationDefinition` via `ResumeDefinitionDigestHelper`,
including: service interface, method name, JVM method descriptor, return kind, model, provider, timeout,
retries, cache settings, prompts, annotations, and sorted tool definitions.

### ResumeOperationRegistry (PR #28)

Thread-safe runtime registry keyed by `(serviceInterface, methodName, jvmMethodDescriptor)`:

```kotlin
internal data class RegisteredResumeOperation(
    val reference: ResumeOperationReference,
    val serviceDefinition: ServiceDefinition,
    val operation: OperationDefinition,
    val handler: TramaiInvocationHandler,
)
```

Rules:
- Missing key → `ConfigurationException("resume-operation-not-registered")`
- Same key + same digest → idempotent registration
- Same key + different digest → fail closed (`ConfigurationException`)

Created during `TramaiEngine.create()` and `TramaiEngine.registerService()`.

### SuspendedInvocationStore (engine-level)
```kotlin
interface SuspendedInvocationStore {
    suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,          // PR #28: replaces SensitiveResumeContext
    )
    suspend fun get(approvalId: String): SuspendedInvocationMetadata?
    suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope?  // PR #28
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

## PR #28 — Trusted Replay Envelope and Operation Registry

Implemented in PR #28.

### Motivation

Previously, `SensitiveResumeContext` stored executable runtime objects (`OperationDefinition`, `ResolvedTool`, `ToolCall`). The engine resolved the correct handler via a single mutable `resumeHandler` field set to the last created proxy.

This meant:
- Runtime objects survived in opaque context across the JVM lifecycle
- Resume relied on a single handler, not a registered service map
- Multi-service setups were fragile (last-created proxy wins)
- Definition drift was not detectable before token consumption

### Changes

**SensitiveReplayEnvelope** replaces `SensitiveResumeContext`:
- Contains ONLY `List<Message>` — no OperationDefinition, no ResolvedTool, no ToolCall
- ToolCall is constructed at resume time from `metadata.toolCallId`, `metadata.toolName`, and `claimed.arguments.reveal()`
- Tool is resolved from the runtime `ToolRegistry`
- Operation is resolved from the registry at resume time
- Replay envelope digest verified after claim (tamper detection)

**ResumeOperationRegistry** replaces `resumeHandler`:
- Keyed by `(serviceInterface, methodName, jvmMethodDescriptor)` for unambiguous overload resolution
- Operation registered during `create()` and `registerService()`
- Missing key → fail before token consumption
- Definition drift → fail before token consumption
- Same key + same digest → idempotent (safe for service restart registration)

**Cross-store integrity checks** (before token consumption):
- workflowRunId matches across continuation, metadata, and identity
- correlationId matches across continuation and metadata
- workflowDigest matches across continuation and identity
- policyVersion matches across continuation and identity
- toolName and toolCallId match across continuation and metadata
- resumeDefinitionDigest matches between metadata and registered operation

**Pre-token-drift detection**:
- Before `validateResume()`, verify `metadata.operationReference.resumeDefinitionDigest` == `registered.reference.resumeDefinitionDigest`

**Post-claim digest verification**:
- After `revealReplayEnvelope()`, recompute digest via `ReplayEnvelopeDigestHelper` and compare with `metadata.replayEnvelopeDigest`

**registerService() API**:
- `TramaiEngine.registerService(serviceType: KClass<*>)` — registers operations without creating a proxy
- `TramaiRuntime.registerService()` + reified overload
- `SovereignTramaiRuntime.registerService()` + reified overload
- Use after restart: `runtime.registerService<MyService>()` before `runtime.resumeApproval(command)`

### Resume Ordering (PR #28)

1. Load metadata from `SuspendedInvocationStore` (read-only)
2. Resolve `ResumeOperationReference` from `metadata.operationReference` via `ResumeOperationRegistry`
3. Cross-store integrity checks (workflowRunId, correlationId, digest, policyVersion, tool name, tool call ID)
4. Pre-token-drift check: verify `resumeDefinitionDigest`
5. Resolve tool from runtime `ToolRegistry`
6. Validate continuation: status == PENDING, version matches
7. `validateResume()` — validates token and binding without consuming
8. Evaluate BEFORE_WORKFLOW_RESUME
9. `authorizeResume()` — consumes the one-time token
10. `claimForExecution()` — atomically marks continuation CLAIMED
11. `revealReplayEnvelope()` — get messages
12. Verify replay envelope digest (tamper detection)
13. Verify payload integrity (re-digest claimed arguments)
14. Construct ToolCall from metadata + claimed arguments
15. Execute tool with `registered.operation`
16. Continue provider loop with `registered.operation` and `replayPayload.messages`
17. Complete continuation → emit audit → cleanup

Implemented in PR #22.

### Problem

The original resume authorization flow called `consumeApproved()` (a one-time, destructive consume) before `claimForExecution()`. If `consumeApproved()` succeeded but `claimForExecution()` failed before mutating the continuation store, the one-time token was consumed but the continuation remained PENDING. The caller had no way to recover — retrying the exact same resume command would fail because the token was already consumed.

### Solution: `consumeApprovedOrReplay()`

Replace `consumeApproved()` with `consumeApprovedOrReplay()` that returns `ApprovalConsumptionReceipt(request, replayed)`.

**Fresh consumption semantics:**
- status == APPROVED
- request.version == expectedVersion
- consumedAt == null, consumedBy == null
- now < expiresAt
- Token digest matches (constant-time)
- Persists consumedBy, consumedAt, version + 1
- Returns replayed=false

**Exact-replay semantics:**
- status == APPROVED
- consumedAt != null
- stored consumedBy == command consumedBy
- stored version == expectedVersion + 1
- Token digest matches (constant-time)
- Returns existing request UNCHANGED (no version increment, no consumedAt replacement)
- Returns replayed=true

### Engine Integration

`TramaiEngine.authorizeResume()` captures the `replayed` flag. When `replayed == true`, the engine emits `tramai.approval.authorization_replayed` with safe attributes only (approvalId, workflowRunId, toolName). The emitter failure never blocks execution.

### Guarantees

- Wrong tokens fail BEFORE policy evaluation
- Exact replay never mutates consumedAt or version
- CLAIMED continuations are never automatically retried
- Concurrent exact replays: one fresh consume, remaining replays
- Concurrent different actors: only original consumer accepted
- Replay after approval expiry returns same receipt (continuation store is authoritative for execution expiry)
- **The durable receipt guarantees authorization consumption recovery, not unconditional workflow completion.**
  Policy, validator, and BEFORE_WORKFLOW_RESUME are re-evaluated on each retry — this is fail-closed by design.
- CancellationException propagates unchanged
- Checked adapter failures sanitized without secret leakage

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
