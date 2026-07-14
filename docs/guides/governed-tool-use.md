# Governed Tool Use Guide

> **Status:** Runnable example — demonstrates tool permission outcomes (ALLOW, DENY, REQUIRE_APPROVAL) and the dedicated `tool.permission` runtime evidence family.
>
> **Module:** `examples/tool-governance` (PR #201)

---

## Purpose

This guide explains how TramAI enforces tool permission decisions at three enforcement points (`BEFORE_TOOL_EXPOSURE`, `BEFORE_TOOL_EXECUTION`, `BEFORE_TOOL_RESULT_REINJECTION`) and how those decisions are exported as dedicated `tool.permission` runtime evidence.

Tool permission decisions are **independent of each other**: exposure permission is not execution permission. A tool may be exposed to the model (the model knows it exists) but denied or suspended at execution time.

---

## Three Scenarios

The `examples/tool-governance` module demonstrates three deterministic, credential-free scenarios:

### 1. Read-only lookup — ALLOW

| Aspect | Value |
|--------|-------|
| Tool | `customer_lookup` |
| Permission | `customer.read` |
| Risk | LOW |
| Approval | AUTO |
| Enforcement outcome | ALLOW at all three enforcement points |
| Tool execution | Exactly once |
| Evidence family | `tool.permission` |

The policy engine allows exposure, execution, and result reinjection. The tool runs once and the model receives the result.

### 2. Account deletion — DENY

| Aspect | Value |
|--------|-------|
| Tool | `account_delete` |
| Permission | `account.delete` |
| Risk | CRITICAL |
| Approval | HUMAN_REQUIRED |
| Enforcement outcome | Exposure ALLOW, execution DENY |
| Tool execution | Never |
| Exception | `PolicyViolationException` |

A policy wrapper around `DefaultPolicyEngine` denies at `BEFORE_TOOL_EXECUTION` specifically for this tool:

```kotlin
PolicyEngine { context ->
    if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION
        && context.toolName == "account_delete") {
        PolicyDecision.Deny(
            reason = "Account deletion is disabled in this environment",
            reasonCode = "account-delete-disabled"
        )
    } else {
        baselinePolicy.evaluate(context)
    }
}
```

The tool never executes. Denial is audited before the exception propagates. No reinjection occurs.

### 3. Payment — REQUIRE_APPROVAL

| Aspect | Value |
|--------|-------|
| Tool | `payment` |
| Permission | `payment.execute` |
| Risk | HIGH |
| Approval | HUMAN_REQUIRED |
| Enforcement outcome | Exposure ALLOW, execution REQUIRE_APPROVAL |
| Tool execution | Never |
| Exception | `ApprovalSuspendedException` |

The `DefaultPolicyEngine` evaluates the tool's risk (HIGH) and approval mode (HUMAN_REQUIRED) and returns `REQUIRE_APPROVAL`. The workflow is suspended.

> Suspension is the terminal state for this example. See [`examples/approval-resume`](../../examples/approval-resume) for the full approve/resume lifecycle.

---

## Running the Example

```bash
./gradlew :examples:tool-governance:test
```

No credentials, Docker, or network access required.

---

## Evidence Demonstration

After each scenario, the main runner exports evidence using `ToolPermissionRuntimeEvidenceExporter` and prints a compact summary:

```
BEFORE_TOOL_EXPOSURE          ALLOW             customer_lookup
BEFORE_TOOL_EXECUTION         ALLOW             customer_lookup
BEFORE_TOOL_RESULT_REINJECTION ALLOW            customer_lookup
evidence family: tool.permission
```

The cross-check verifies that **no tool enforcement events appear in generic `policy.decision` evidence** — the `PolicyDecisionRuntimeEvidenceExporter` excludes `BEFORE_TOOL_EXPOSURE`, `BEFORE_TOOL_EXECUTION`, and `BEFORE_TOOL_RESULT_REINJECTION` events because they belong to the `tool.permission` family.

---

## Tool Security Metadata

Each governed tool declares its security metadata at definition time:

```kotlin
override val security: ToolSecurityMetadata = ToolSecurityMetadata(
    permission = "customer.read",          // Permission string checked by policy
    risk = RiskLevel.LOW,                  // LOW, MEDIUM, HIGH, CRITICAL
    approval = ApprovalMode.AUTO,          // AUTO, HUMAN_REQUIRED, HUMAN_REQUIRED_WITH_TIMEOUT
    managedNetworkEgress = ManagedNetworkEgress.ALLOW,
    audit = AuditDetail.DECISION_ONLY,
)
```

The `DefaultPolicyEngine` uses this metadata at `BEFORE_TOOL_EXECUTION`:

- Risk levels in `requireApprovalForRiskLevel` (default: HIGH, CRITICAL) trigger `REQUIRE_APPROVAL`
- `ApprovalMode.HUMAN_REQUIRED` (or `HUMAN_REQUIRED_WITH_TIMEOUT`) also triggers `REQUIRE_APPROVAL`
- `ApprovalMode.AUTO` with LOW/MEDIUM risk allows execution
- Unknown tools are denied (deny-by-default)

---

## Evidence Boundaries

- Raw tool arguments are never included in audit metadata or evidence records
- Secrets, API keys, prompts, and tokens are excluded from durable evidence
- Only safe metadata keys (`toolName`, `enforcementPoint`, `riskLevel`, `classification`, `classificationSource`) appear in `tool.permission` records
- The `PolicyDecisionRuntimeEvidenceExporter` has its own allowlist that excludes tool-specific metadata
- The `ToolPermissionRuntimeEvidenceExporter` uses a separate allowlist (`TOOL_PERMISSION_ALLOWED_METADATA_KEYS`) that does not include generic policy attributes

---

## Non-Compliance Boundaries

This example demonstrates:

- ✅ ALLOW at all three enforcement points
- ✅ DENY at BEFORE_TOOL_EXECUTION via policy wrapper
- ✅ REQUIRE_APPROVAL at BEFORE_TOOL_EXECUTION via high-risk metadata
- ✅ Tool execution count verification (0 vs 1)
- ✅ Dedicated `tool.permission` runtime evidence family
- ✅ No tool events in generic `policy.decision` evidence

This example does **not** demonstrate:

- ❌ Durable approval storage or resume workflow
- ❌ `REDACT_RESULT` or `ALLOW_INTERNAL_ONLY` decisions (not yet implemented)
- ❌ MCP-connected tool governance
- ❌ Sovereign deployment or offline routing
- ❌ Durable audit export to file bundles
- ❌ compliance, certification, or production-readiness claims

---

## Privacy

- The `DeterministicToolProvider` never calls a real model — no data leaves the process
- No credentials, API keys, or external services are required
- Tool arguments in the example are hardcoded test data (e.g., "CUST-001", "ACC-001")
- The example does not process real user data or production traffic
- Raw arguments, secrets, tokens never appear in evidence output

---

## Key Takeaways

1. **Exposure permission is not execution permission** — a tool can be exposed to the model and still denied at execution time
2. ALLOW, DENY, and REQUIRE_APPROVAL are the three current permission decisions
3. Tool enforcement events are partitioned into the `tool.permission` evidence family
4. The `PolicyDecisionRuntimeEvidenceExporter` does not include tool events
5. Tool metadata (risk, approval mode, permission) drives the policy engine's execution decisions
