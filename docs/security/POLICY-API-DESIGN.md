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

- Approval token (`approvalTokenDigest`) is a SHA-256 digest of a generated nonce. The raw token is provided to the requestor at creation time. PR #15 will consume and verify the raw token exactly once.
- `decidedBy` is non-nullable on `Approve`/`Deny` transitions.
- Comments are optional on transitions.
- Version starts at 0 and is incremented atomically. No CAS retry loop — `ConcurrentHashMap.compute()` provides the atomicity.
- No engine integration yet. PR #15 will build workflow suspension/resume on this foundation.

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
