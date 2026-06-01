# Reference Workflow: Sovereign Document Intelligence

This document defines the canonical reference workflow for TramAI Enterprise. Every release, every security test, and every benchmark is validated against this workflow.

---

## Overview

The **Sovereign Document Intelligence** workflow receives a sensitive document, classifies its data, routes it exclusively to an approved local model, generates a typed result, requests human approval before any high-risk action, and records every decision in an append-only audit trail.

It is an evolution of the existing `InvoiceAnalyzer` example at `examples/kotlin-springboot-example/`.

---

## Workflow Steps

```
DOCUMENT IN
    │
    ▼
┌─────────────────────────────┐
│ 1. DATA CLASSIFICATION      │
│    Source: DECLARED by      │
│    caller, or RULE_BASED    │
│    (regex, metadata, DLP)   │
│    Output: ClassifiedDoc    │
│    Policy: local model only │
│    Audit: classification    │
│           decision emitted  │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│ 2. PROVIDER SELECTION       │
│    Based on classification  │
│    RESTRICTED → local-only  │
│    CONFIDENTIAL → local/EU  │
│    INTERNAL → approved-cloud│
│    PUBLIC → any approved    │
│    Audit: routing decision  │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│ 3. DOCUMENT ANALYSIS        │
│    @AiService operation     │
│    Typed output:            │
│    InvoiceAssessment        │
│    Model: registry-approved │
│    Audit: model used,       │
│           tokens consumed   │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│ 4. ACTION DETERMINATION     │
│    Risk classification      │
│    LOW → auto-proceed       │
│    MEDIUM → audit + proceed │
│    HIGH → human approval    │
│    CRITICAL → block +       │
│              escalation     │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│ 5. HUMAN APPROVAL (if HIGH) │
│    Workflow suspends        │
│    Awaiting approval token  │
│    Timeout → auto-deny      │
│    Audit: approval decision │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────┐
│ 6. ACTION EXECUTION         │
│    Tool invocation          │
│    Permission check         │
│    Egress check             │
│    Threshold check          │
│    Audit: action + result   │
└────────────┬────────────────┘
             │
             ▼
       TYPED RESULT OUT
```

---

## Typed Contracts

### Classification Modes

Classification can originate from three sources, all producing the same typed contract:

| Mode | Source | Use Case |
|------|--------|----------|
| `DECLARED` | Caller passes classification explicitly | MVP, highly regulated contexts |
| `RULE_BASED` | Regex, metadata, DLP engine, deterministic rules | Controlled production |
| `LOCAL_MODEL_ASSISTED` | Approved local model proposes classification | Future extension (post-MVP) |

The MVP uses DECLARED and RULE_BASED only. Classification is never delegated to an unapproved model.

```kotlin
data class ClassifiedDocument<T>(
    val payload: T,
    val classification: DataClassification,
    val source: ClassificationSource
)

enum class ClassificationSource { DECLARED, RULE_BASED, LOCAL_MODEL_ASSISTED }
```

### Service Contracts

```kotlin
@AiService
@ModelPolicy("local-approved-models")
interface InvoiceReviewAgent {

    @Operation(
        modelPolicy = "local-only",
        classificationRequired = true,
        audit = AuditDetail.FULL
    )
    suspend fun analyze(
        invoice: ClassifiedDocument<InvoiceDocument>
    ): InvoiceAssessment
}

@AiTool(
    permission = "invoice.payment.schedule",
    risk = RiskLevel.HIGH,
    approval = ApprovalMode.HUMAN_REQUIRED,
    managedNetworkEgress = ManagedNetworkEgress.DENY,
    audit = AuditDetail.FULL
)
suspend fun schedulePayment(command: PaymentCommand): PaymentResult
```

---

## Security Properties Demonstrated

| # | Property | How Demonstrated |
|---|----------|------------------|
| 1 | **Data classification enforcement** | RESTRICTED document → local-only routing; attempting cloud routing blocked |
| 2 | **Model allowlist** | Unapproved model → rejected at provider selection |
| 3 | **Tool deny-by-default** | Unregistered tool invocation → blocked before execution |
| 4 | **Human approval gate** | HIGH risk action → workflow suspends, approval required |
| 5 | **Audit trail** | Every step (classify, route, analyze, approve, execute) emits an AuditEvent |
| 6 | **Offline operation** | Full workflow runs with zero external network egress |
| 7 | **Provider swap** | Same @AiService works with Ollama, vLLM, or approved cloud |
| 8 | **Incident replay** | Audit events reconstruct entire workflow execution |

---

## Test Scenarios

### Happy Path
1. Submit CONFIDENTIAL invoice document
2. Classification → CONFIDENTIAL
3. Routing → local model (Ollama)
4. Analysis → typed InvoiceAssessment
5. No high-risk actions → auto-complete
6. All audit events present and verifiable

### Blocked Scenarios
1. RESTRICTED document routed to cloud provider → **BLOCKED**
2. Unapproved model requested → **BLOCKED**
3. Unknown tool invoked → **BLOCKED**
4. HIGH-risk action without approval → **SUSPENDED**
5. Network egress attempt during offline mode → **BLOCKED**
6. Approval timeout → **AUTO-DENIED**

### Negative Tests
1. Policy engine unavailable → fail closed
2. Audit storage full → fail closed
3. Model checksum mismatch → fail closed
4. Malformed structured output → retry then fail

---

## Evolution from Current InvoiceAnalyzer

| Current | Sovereign |
|---------|-----------|
| `@AiService` with manual model selection | `@ModelPolicy` annotation enforces allowlist |
| No data classification | Classification step before routing |
| No tool authorization | `@AiTool` with permission, risk, approval |
| Workflow events (in-memory) | Versioned AuditEvent (append-only) |
| Direct model invocation | Policy-enforced provider selection |
| No human approval gates | Suspension + approval flow |
| Local or cloud, no enforcement | Classification-driven routing |

---

## Implementation Mapping

The existing `InvoiceWorkflowCoordinator` becomes the **orchestration layer**. New modules provide:

| Existing Code | New Module | New Capability |
|---------------|------------|----------------|
| `InvoiceAnalyzer.kt` | `tramai-security` | `@ModelPolicy`, `ClassifiedDocument<T>`, annotation-driven allowlist. Runtime classification wins over annotation defaults. |
| `InvoiceWorkflowModels.kt` | `tramai-security/audit` | `AuditEvent` replaces `InvoiceWorkflowEventView` |
| `WorkflowLane` enum | `tramai-security/approval` | Approval state machine |
| `InvoiceWorkflowCoordinator` | Extended through orchestration hooks | Policy enforcement, approval suspension, timeout handling, resume validation, idempotent tool execution, and audit reconstruction added. Core business flow preserved. |

---

*Reference workflow adopted June 2026. Evolved from the existing `examples/kotlin-springboot-example/` invoice analyzer.*
