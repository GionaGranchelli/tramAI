# POLICY-API-DESIGN.md — TramAI Enterprise

Draft API design for the policy enforcement layer. Not a final spec — this is the Phase 0 design sketch to validate before Phase 1 implementation.

---

## Core Abstractions

### PolicyEngine (SPI in tramai-core)

```kotlin
interface PolicyEngine {
    suspend fun evaluate(context: PolicyContext): PolicyDecision
}

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Deny(val reason: String, val reasonCode: String) : PolicyDecision
    data class RequireApproval(
        val requirement: ApprovalRequirement
    ) : PolicyDecision
}

data class ApprovalRequirement(
    val toolName: String,
    val argumentsDigest: String,
    val reason: String,
    val timeoutMillis: Long
)
```

The `PolicyEngine` returns an `ApprovalRequirement` with binding metadata. The approval subsystem generates `approvalId`, `nonce`, `requestedAt`, and `expiresAt`. This keeps policy evaluation deterministic and security-token generation centralized.

### PolicyContext

```kotlin
data class PolicyContext(
    val enforcementPoint: EnforcementPoint,
    val workflowId: String?,
    val workflowRunId: String?,
    val correlationId: String,
    val actorId: String,
    // Provider context
    val providerId: String?,
    val modelName: String?,
    val fallbackProviderId: String?,
    // Data context
    val dataClassification: DataClassification?,
    val classificationSource: ClassificationSource?,
    // Tool context
    val toolName: String?,
    val toolSecurity: ToolSecurityMetadata?,
    // Network context
    val targetDestination: String?,
    // State context
    val policyVersion: String,
    val workflowDigest: String?,
    // Extensibility
    val attributes: Map<String, String>
)
```

- `workflowId` — stable workflow definition identifier
- `workflowRunId` — identifier for one concrete execution run
- `workflowDigest` — immutable digest of the workflow definition version

### EnforcementPoint

```kotlin
enum class EnforcementPoint {
    BEFORE_PROVIDER_RESOLUTION,
    BEFORE_PROVIDER_INVOCATION,
    BEFORE_FALLBACK,
    BEFORE_TOOL_EXPOSURE,
    BEFORE_TOOL_EXECUTION,
    BEFORE_TOOL_RESULT_REINJECTION,
    BEFORE_RESPONSE_RETURN,
    BEFORE_WORKFLOW_RESUME,
}
```

---

## Tool API — Compatibility with Existing TramaiTool

TramAI currently exposes tools as `TramaiTool` objects. The security model extends this contract rather than replacing it.

### TramaiTool with Security Metadata

```kotlin
data class ToolSecurityMetadata(
    val permission: String,
    val risk: RiskLevel,
    val approval: ApprovalMode,
    val managedNetworkEgress: ManagedNetworkEgress,
    val audit: AuditDetail,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.STRICT,
)

interface TramaiTool<I : Any, O : Any> {
    val name: String
    val description: String
    val inputType: KClass<I>
    val security: ToolSecurityMetadata
    suspend fun execute(input: I, ctx: ToolExecutionContext): O
}
```

Tool failures use existing exception mechanisms. No `ToolResult<O>` wrapper is introduced unless it solves a demonstrated problem.

**Migration path:**
```kotlin
val security: ToolSecurityMetadata
    get() = ToolSecurityMetadata.legacyPermissive()
```

Existing tools get a legacy-permissive default in 0.4.x preview:
```kotlin
val security: ToolSecurityMetadata
    get() = ToolSecurityMetadata.legacyPermissive()
```

The sovereign profile rejects `CompatibilityMode.LEGACY_PERMISSIVE` metadata unless explicitly overridden. Secure mode requires `CompatibilityMode.STRICT`.
```

### @AiTool — Optional Convenience Layer

For Spring-managed methods, `@AiTool` auto-generates a `TramaiTool` with security metadata:

```kotlin
@AiTool(
    permission = "invoice.payment.schedule",
    risk = RiskLevel.HIGH,
    approval = ApprovalMode.HUMAN_REQUIRED,
    managedNetworkEgress = ManagedNetworkEgress.DENY,
    audit = AuditDetail.FULL
)
suspend fun schedulePayment(command: PaymentCommand): PaymentResult
```

This is a convenience layer — it produces the same `TramaiTool` contract. No two unrelated tool models exist.

---

## Classification Authority

Runtime classification always wins over annotation defaults.

### @Operation Annotation (revised)

```kotlin
@AiService
@ModelPolicy("local-approved-models")
interface InvoiceReviewAgent {
    @Operation(
        modelPolicy = "local-only",
        classificationRequired = true,    // requires ClassifiedDocument input
        audit = AuditDetail.FULL
    )
    suspend fun analyze(invoice: ClassifiedDocument<InvoiceDocument>): InvoiceAssessment
}
```

Rules:
- `classificationRequired = true` → caller must provide a `ClassifiedDocument<T>`
- If the runtime `ClassifiedDocument` says RESTRICTED but annotation said CONFIDENTIAL → **runtime wins**
- Classification downgrade is rejected
- Classification source is audited
- The annotation expresses a requirement or default policy, never an override

### ClassifiedDocument

```kotlin
data class ClassifiedDocument<T>(
    val payload: T,
    val classification: DataClassification,
    val source: ClassificationSource
)

enum class ClassificationSource { DECLARED, RULE_BASED, LOCAL_MODEL_ASSISTED }
```

---

## Approval Domain Model (PR #14)

### ApprovalBinding (mandatory fields, tramai-core)

```kotlin
data class ApprovalBinding(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val approvalTokenDigest: Sha256Digest,
)
```

All fields are non-nullable. `argumentsDigest`, `workflowDigest`, and `approvalTokenDigest` are validated as SHA-256 format (`sha256:<64 hex chars>`) via `Sha256Digest.of()`.

### ApprovalRequest

```kotlin
data class ApprovalRequest(
    val approvalId: String,
    val binding: ApprovalBinding,
    val status: ApprovalStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionComment: String?,
    val consumedBy: String?,
    val consumedAt: Instant?,
    val version: Long,
)

enum class ApprovalStatus { PENDING, APPROVED, DENIED, TIMED_OUT }
```

### ApprovalStore SPI

```kotlin
interface ApprovalStore {
    suspend fun create(request: ApprovalRequest): ApprovalRequest
    suspend fun get(approvalId: String): ApprovalRequest?
    suspend fun transition(approvalId: String, expectedVersion: Long, transition: ApprovalTransition): ApprovalRequest
    suspend fun consumeApproved(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalRequest
}
```

### ApprovalTransition (with targetStatus())

```kotlin
sealed interface ApprovalTransition {
    fun targetStatus(): ApprovalStatus
    data class Approve(val decidedBy: String, val comment: String? = null) : ApprovalTransition
    data class Deny(val decidedBy: String, val comment: String? = null) : ApprovalTransition
    data object Timeout : ApprovalTransition
}
```

### Sha256Digest Validation

```kotlin
@JvmInline
value class Sha256Digest(val value: String) {
    companion object {
        fun of(raw: String): Sha256Digest  // requires format ^sha256:[0-9a-f]{64}$
    }
}
```

### State Machine

PENDING → APPROVED/DENIED/TIMED_OUT (all terminal). Timeout succeeds only when `now >= expiresAt`. Expired pending approvals reject approve/deny.

### InMemoryApprovalStore

- `create()`: full validation (version==0, status==PENDING, no decision fields, non-blank IDs, SHA-256 digests, future expiry, no surrounding whitespace). Uses `putIfAbsent` for atomic insert.
- `transition()`: uses `ConcurrentHashMap.compute()` for atomic read-modify-write — no CAS retry loop.
- `resolveNextStatus()`: checks expiry first for PENDING state, uses `transition.targetStatus()` uniformly.

### Key design decisions

- Approval token (`approvalTokenDigest`) is a SHA-256 digest of a generated nonce. The raw token is provided to the requestor at creation time. PR #15 consumes and verifies the raw token at the coordinator level via `consumeApproved()`.
- `consumeApproved()` uses constant-time `MessageDigest.isEqual()` with explicit `StandardCharsets.US_ASCII` encoding to prevent digest-comparison timing attacks.
- `consumedBy` and `consumedAt` are recorded atomically with the version increment. Second consume of the same approval fails with clear message.
- `decidedBy` is non-nullable on `Approve`/`Deny` transitions.
- Comments are optional on transitions.
- Version starts at 0 and is incremented atomically. No CAS retry loop — `ConcurrentHashMap.compute()` provides the atomicity.
- PR #14 scope: domain model, store SPI, validation, expiry, deterministic tests.
- PR #15 scope: token generation/hashing, coordinator, binding revalidation, consumed-result contract validation, non-interfering observer, recursive leakage traversal, safe exception taxonomy (all coordinator-facing exceptions extend ApprovalException).
- PR #16 scope: continuation metadata/payload split, metadata-only `ApprovalContinuation`, `ApprovalContinuationStore` SPI, `ClaimedApprovalContinuation`, `InMemoryApprovalContinuationStore`, `SensitiveToolArguments` wrapper, `Sha256ToolArgumentsDigester`, approval-expiry binding via `approvalExpiresAt`, lazy expiry on touch paths, atomic one-time payload release and scrubbing semantics, completion/expiry/cancel metadata retention, strict validation, and leakage tests.
- PR #17 scope: TramaiEngine suspension, `ApprovalRequiredException` replacement, create `ApprovalChallenge` at `BEFORE_TOOL_EXECUTION`, persist continuation, expose engine resume API, call `ApprovalGateCoordinator.authorizeResume()`, call `ApprovalContinuationStore.claimForExecution()`, enforce `BEFORE_WORKFLOW_RESUME`, execute tool once, complete continuation, reinject result into provider loop, lifecycle audit events, idempotency strategy for uncertain outcomes.

---

## Approval Gate Coordinator (PR #15)

### ApprovalToken

```kotlin
@JvmInline
value class ApprovalToken private constructor(
    private val rawValue: String,
) {
    fun reveal(): String = rawValue
    override fun toString(): String = "[REDACTED]"
    companion object {
        fun parsePresented(raw: String): ApprovalToken
        internal fun generated(raw: String): ApprovalToken
    }
}
```

### SPIs

```kotlin
fun interface ApprovalTokenGenerator { fun generate(): ApprovalToken }
fun interface ApprovalTokenDigester { fun digest(token: ApprovalToken): Sha256Digest }
fun interface ApprovalIdGenerator { fun generate(): String }
fun interface ApprovalDecisionValidator { fun validate(request: ApprovalRequest, consumedBy: String) }
```

### Coordinator Commands

```kotlin
data class CreateApprovalCommand(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
    val requestedBy: String,
    val expiresAt: Instant,
)

data class ApprovalChallenge(
    val approvalId: String,
    val token: ApprovalToken,
    val expiresAt: Instant,
)

data class AuthorizeResumeCommand(
    val approvalId: String,
    val expectedVersion: Long,
    val presentedToken: ApprovalToken,
    val consumedBy: String,
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: Sha256Digest,
    val policyVersion: String,
    val workflowDigest: Sha256Digest,
)

data class ApprovalAuthorization(
    val approvalId: String,
    val consumedBy: String,
    val consumedAt: Instant,
    val version: Long,
)
```

### ApprovalGateCoordinator

```kotlin
interface ApprovalGateCoordinator {
    suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge
    suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization
}
```

### PR #15 Changes Summary

**Security hardening applied in the coordinator and related SPIs:**

| Area | Change |
|------|--------|
| Token entropy | Minimum raised from 128 to 256 bits (`tokenBytes >= 32`) |
| Token validation | Whitespace (leading, trailing, internal) now rejected, not silently trimmed |
| Exception mapping | Store failures caught as typed `ApprovalStoreException` subtypes, not via `message.contains()` |
| Exception boundary hardening | Sealed store exceptions separated from safe public exceptions; cause-chain sanitized |
| Approval lifetime | `maxApprovalTtl: Duration` parameter (default 15 min) bounds `expiresAt` |
| AuthorizeResume validation | All 5 ID fields + `expectedVersion >= 0` validated before store interaction |
| Exception hierarchy | Sealed `ApprovalStoreException` subtypes + safe coordinator-facing `ApprovalException` subclasses |
| SPI boundaries | `ApprovalTokenGenerator`, `ApprovalTokenDigester`, `ApprovalDecisionValidator`, `ApprovalStore`, `ApprovalIdGenerator` documented as trusted computing-base extensions |
| Diagnostic observer | `ApprovalFailureObserver` records original failures before sanitization; wrapped in `RuntimeException`-only try/catch for non-interference |
| Consumed-result validation | Full 6-field contract check after `store.consumeApproved()` — approvalId, binding, status, consumedBy, consumedAt, version |
| Recursive leakage traversal | `containsSecret()` helper traverses message, toString(), suppressed, and cause chain |
| Bounded store TTL | `InMemoryApprovalStore` validates `maxCreationTtl` as defense-in-depth |
| InMemoryApprovalStore init | `require(maxCreationTtl > Duration.ZERO)` added to reject zero/negative TTL at construction |

### Exception Hierarchy (PR #15)

**Internal store exceptions (sealed, extends RuntimeException):**

```
ApprovalStoreException (sealed)
├── ApprovalStoreNotFoundException(approvalId)
├── ApprovalStoreTokenRejectedException(approvalId)
├── ApprovalStoreConflictException(approvalId)
└── ApprovalStoreNotConsumableException(approvalId)
```

- No message or cause parameters — only `approvalId`.

**Safe coordinator-facing exceptions (extend ApprovalException → TramaiException):**

```
ApprovalNotFoundException(approvalId)                          — fixed safe message
ApprovalTokenRejectedException(approvalId)                     — fixed safe message
ApprovalBindingMismatchException(approvalId, field)            — fixed safe message
ApprovalAuthorizationException(approvalId?)                    — fixed safe message
ApprovalCreationException(approvalId?)                         — fixed safe message
```

- All extend `ApprovalException` (which extends `TramaiException` → `RuntimeException`).
- Not part of the `ApprovalStoreException` sealed hierarchy.
- Fixed safe messages. No caller-provided message strings.
- No `cause` parameter. Store internal details never leak.
- No inheritance from `ApprovalStoreException`.

**Separate exception table:**

| Exception | Message | Cause-safe? | Contains store internals? |
|-----------|---------|------------|--------------------------|
| `ApprovalStoreNotFoundException` | — | ✅ (no message param) | ❌ |
| `ApprovalStoreTokenRejectedException` | — | ✅ (no message param) | ❌ |
| `ApprovalStoreConflictException` | — | ✅ (no message param) | ❌ |
| `ApprovalStoreNotConsumableException` | — | ✅ (no message param) | ❌ |
| `ApprovalNotFoundException` | `"Approval not found: '<id>'"` | ✅ (no cause param) | ❌ |
| `ApprovalTokenRejectedException` | `"Approval token rejected for '<id>'"` | ✅ (no cause param) | ❌ |
| `ApprovalBindingMismatchException` | `"Approval binding mismatch for '<id>': <field>"` | ✅ (no cause param) | ❌ |
| `ApprovalAuthorizationException` | `"Approval authorization failed"` | ✅ (no cause param) | ❌ |
| `ApprovalCreationException` | `"Approval creation failed"` | ✅ (no cause param) | ❌ |

Raw tokens, token digests, arguments, and workflow payloads are NEVER included in exception messages or cause chains.

### ApprovalFailureObserver (trusted diagnostic SPI)

`DefaultApprovalGateCoordinator` accepts an optional `ApprovalFailureObserver`:

```kotlin
fun interface ApprovalFailureObserver {
    fun record(operation: String, approvalId: String?, failure: RuntimeException)
}
```

- Records the original exception before it is sanitized into a safe exception.
- Called in every catch block: `createApproval()`, `authorizeResume()` store.get, `authorizeResume()` store.consumeApproved.
- Trusted diagnostic SPI. Not part of the public API contract.

### mapStoreError

```kotlin
private fun mapStoreError(
    approvalId: String,
    exception: RuntimeException,
): RuntimeException {
    return when (exception) {
        is ApprovalStoreNotFoundException -> ApprovalNotFoundException(approvalId)
        is ApprovalStoreTokenRejectedException -> ApprovalTokenRejectedException(approvalId)
        is ApprovalStoreConflictException -> ApprovalAuthorizationException(approvalId)
        is ApprovalStoreNotConsumableException -> ApprovalAuthorizationException(approvalId)
        else -> ApprovalAuthorizationException(approvalId)
    }
}
```

No `cause` parameter passed to safe constructors. Original exception is discarded after observer recording.

### Non-Interfering Diagnostic Observer (PR #15)

```kotlin
private fun observeFailure(
    operation: String,
    approvalId: String?,
    failure: RuntimeException,
) {
    try {
        failureObserver?.record(operation, approvalId, failure)
    } catch (_: RuntimeException) {
        // Diagnostic observers must not replace safe public failures.
    }
}
```

The `observeFailure()` helper wraps all `failureObserver?.record()` calls in `try/catch` catching only `RuntimeException`, so a throwing observer can never bypass the safe public exception boundary. If the observer throws a `RuntimeException`, it is silently swallowed and the caller always receives the safe exception. Fatal `Error` types are NOT caught — they propagate to the caller.

### Consumed-Result Contract Validation (PR #15)

After `store.consumeApproved()` returns, the coordinator validates the consumed result against the command and stored request:

```kotlin
if (consumed.approvalId != command.approvalId) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.binding != request.binding) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.status != ApprovalStatus.APPROVED) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.consumedBy != command.consumedBy) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.consumedAt == null) throw ApprovalAuthorizationException(command.approvalId)
if (consumed.version != Math.addExact(command.expectedVersion, 1L)) throw ApprovalAuthorizationException(command.approvalId)
```

All 6 checks use the fixed safe message. No mismatch details are exposed to the caller. 7 fake-store tests verify each contract breach.

## Original Design (Phase 0) — Approval Request — Full Binding

An approval authorizes one exact action, not vaguely "continue the workflow."

**Required semantics:**
- **Single-use.** An approval token grants one execution. No replay.
- **Arguments binding.** `argumentsDigest` locks the exact parameters. Changed parameters → new approval required.
- **Expiration.** `expiresAt` is mandatory. Expired approvals are rejected.
- **Policy revalidation.** Resuming a workflow revalidates the active policy version.
- **Workflow digest binding.** A changed workflow definition requires a new approval.
- **Idempotent execution.** Tool execution must be idempotent to prevent double execution after retries.
- **Separation of duties.** For critical actions, `requester != approver` must be enforced. Both identities are recorded in the approval record for auditability.

---

## Audit Event (v2)

```kotlin
data class AuditEvent(
    val schemaVersion: String,
    val hashAlgorithm: String,
    val auditStreamId: String,
    val eventId: String,
    val sequenceNumber: Long,
    val workflowRunId: String?,
    val correlationId: String,
    val actor: String,
    val enforcementPoint: EnforcementPoint,
    val decision: PolicyDecision,
    val policyVersion: String,
    val workflowDigest: String?,
    val previousEventHash: String?,
    val eventHash: String,
    val timestamp: Instant,
    val reasonCode: String?,
    val metadata: Map<String, String>
)
```

### Hash Chain

The hash covers **every immutable field except `eventHash` itself:**

```kotlin
fun computeHash(event: AuditEvent): String {
    val canonical = event.copy(eventHash = "")
    return sha256(canonicalJson(canonical))
}
```

This means: `eventHash = sha256(canonicalJson(event.copy(eventHash = "")))`.

Tampering with any field — actor, enforcement point, workflow digest, policy version, correlation ID, reason code, metadata — invalidates the chain.

### Sequencing

| Question | Decision |
|----------|----------|
| One global sequence? | No |
| Sequence scope | Per `auditStreamId` (typically per `workflowRunId`) |
| Concurrent events within a stream | Serialize writes |
| Cross-stream integrity | Signed checkpoints or external sink (Phase 4) |
| Metadata | Allowlisted and redacted before persistence |

---

## Enums

```kotlin
enum class DataClassification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }
enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
enum class ApprovalMode { AUTO, HUMAN_REQUIRED, HUMAN_REQUIRED_WITH_TIMEOUT }
enum class ManagedNetworkEgress { ALLOW, DENY, ALLOWLIST_ONLY }
enum class ProviderPolicy { LOCAL_ONLY, EU_ONLY, APPROVED_CLOUD, ANY_APPROVED }

enum class AuditDetail {
    MINIMAL,        // eventId + decision + timestamp only
    DECISION_ONLY,  // full decision context, no payload
    FULL            // decision context + payload metadata
}
```

`AuditDetail.NONE` is **not available in secure mode.** The event itself is mandatory. The enum controls payload detail.

`NONE` is allowed only in explicitly configured `LEGACY_PERMISSIVE` mode.

---

## Fail Modes

```kotlin
enum class AuditFailMode {
    FAIL_CLOSED,           // Block the operation
    FAIL_SAFE_READ_ONLY    // Allow read-only, block writes
}
```

Per-operation configuration:

```kotlin
data class FailModeConfig(
    val defaultMode: AuditFailMode = AuditFailMode.FAIL_CLOSED,
    val readOnlyMode: AuditFailMode = AuditFailMode.FAIL_SAFE_READ_ONLY,
    val bufferMaxEvents: Int = 10_000,  // durable local buffer
    val bufferMaxSizeBytes: Long = 10_000_000
)
```

---

## Enforcement in tramai-engine

Every enforcement point is a mandatory call from `TramaiEngine` internals:

```kotlin
// Inside TramaiEngine, before tool execution:
val context = PolicyContext(
    enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
    correlationId = currentCorrelationId(),
    actorId = currentActorId(),
    toolName = tool.name,
    toolSecurity = tool.security,
    policyVersion = policyVersion,
    workflowDigest = currentWorkflowDigest(),
    attributes = operation.attributes
)

when (val decision = policyEngine.evaluate(context)) {
    is PolicyDecision.Allow -> proceed()
    is PolicyDecision.Deny -> {
        auditEngine.emit(decision.toAuditEvent(context))
        throw PolicyViolationException(decision)
    }
    is PolicyDecision.RequireApproval -> {
        val request = approvalEngine.create(
            decision.requirement,
            workflowRunId = requireNotNull(context.workflowRunId) {
                "workflowRunId is required when creating an approval request"
            },
            workflowDigest = requireNotNull(context.workflowDigest) {
                "workflowDigest is required when creating an approval request"
            },
            policyVersion = context.policyVersion,
            actorId = context.actorId
        )
        auditEngine.emit(request.toAuditEvent(context))
        suspendForApproval(request)
    }
}
```

The engine invokes policy hooks directly — not through optional interceptor chains. There is no code path that reaches a provider or tool executor without passing through `policyEngine.evaluate()`.

---

## Module Packaging

```
implementation("dev.tramai:tramai-security")    // policy + approval + audit + model registry
implementation("dev.tramai:tramai-sovereign")    // aggregator: security + offline profile
```

Internal structure:

```
tramai-security/
├── policy/     # PolicyEngine, PolicyDecision, annotations
├── approval/   # ApprovalStateMachine, ApprovalStore SPI, ApprovalRequest
├── audit/      # AuditEngine, AuditEvent, AuditStore SPI, hash chain
└── model/      # ModelRegistry, ModelAllowlist
```

Separate artifacts (`tramai-policy`, `tramai-approval`, `tramai-audit`) are extracted later if independent adoption demands them.

---

### Staged Migration

Default behavior is version-scoped:

| Version | Behavior |
|---------|----------|
| 0.3.x | Existing behavior unchanged |
| 0.4.x security preview | `tramai-security` opt-in; warning logged when absent |
| `tramai-sovereign` profile | Always secure by default |
| 1.0 Enterprise | Secure-by-default documented as a breaking change |
| Compatibility path | Explicit `LEGACY_PERMISSIVE`, deprecated over time |

```kotlin
Tramai.builder()
    .policyMode(PolicyMode.LEGACY_PERMISSIVE)  // explicit opt-in
    .build()
```

Default behavior is version-scoped (see Staged Migration above). In 0.4.x preview, existing consumers remain permissive with a warning. Applications using `tramai-sovereign` always run in SECURE mode. In 1.0 Enterprise, SECURE becomes the default. LEGACY_PERMISSIVE must be explicitly configured — it cannot be the accidental default.

---

*Phase 0 design draft. Finalized during Phase 1 implementation. See ADR-018 for architectural rationale and migration path.*
