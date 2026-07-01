# Approval Gateway Golden Path

> **Status:** RC+ Stable golden path. The core approval workflow APIs are RC+ Stable for the Sovereign Runtime RC+ milestone. Operational surfaces such as REST control plane, reviewer UI, Spring auto-configuration, and fallback gateway implementations remain Preview. See [Sovereign Runtime API Stability Boundary](../architecture/sovereign-api-stability-boundary.md).

This guide shows how to request human approval from a governed workflow using the RC+ Stable `ApprovalGateway` API.

---

## Before: Low-Level Persistence

Without the gateway, requesting human approval required manually creating records in three separate stores:

```kotlin
approvalStore.create(approvalRequest)
suspendedInvocationStore.create(metadata, replayEnvelope)
approvalContinuationStore.create(continuation, sensitiveArguments)
```

---

## After: Ergonomic Workflow Code

With the RC+ Stable `ApprovalRequestResult.toWorkflowResult { ... }` mapper,
the workflow becomes a one-liner:

```kotlin
return gateway.requestApproval(
    subject = ApprovalSubject(input.claimId),
    recommendation = ApprovalRecommendation(
        type = "regulated-claim-triage",
        summary = "High-risk claim requires medical review",
    ),
    requiredRole = ApproverRole("medical-reviewer"),
    workflowRunId = WorkflowRunId(input.workflowRunId),
).toWorkflowResult { "approved-continue" }
```

---

## Current Boundaries

The RC+ Stable golden path and related Preview operational surfaces have the following boundaries:

| Area | Status |
|------|--------|
| Full workflow resume runtime | Service-level `ApprovalResumeControlPlane` available |
| Reviewer UI | Preview reviewer UI available, disabled by default |
| REST control plane for approvals | Preview REST control plane available |
| Approval inbox / work queue query API | Preview inbox query API available |
| Cross-store transaction boundary at creation | ✅ Implemented for JDBC-backed stores |
| Approval-requested audit outbox mutation boundary | ✅ Implemented for JDBC-backed stores |
| Generic global `ApprovalGatewayRequestFactory` | Test fixture provided via `tramai-engine` test fixtures |
| Production certification / GA stability | Not GA-certified; RC+ Stable applies only to golden-path API surface |
| Operational control plane / UI / auto-configuration | Preview — may still change |

---

## See Also

- [`ApprovalGateway` SPI source](https://github.com/GionaGranchelli/tramAI/blob/master/tramai-core/src/main/kotlin/dev/tramai/core/approval/gateway/ApprovalGateway.kt)
- [Sovereign Runtime API Stability Boundary](../architecture/sovereign-api-stability-boundary.md)
