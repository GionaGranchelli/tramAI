# Human Approval Workflow Ergonomics — Design Boundary

> This document defines the developer-facing API shape for human-in-the-loop sovereign workflows.
>
> It is a **design document**, not an implementation plan. It answers the question:
> *"How should a developer express a sovereign human approval workflow without manually wiring low-level stores?"*

---

## 1. Current Problem

The Sovereign Runtime RC+ milestone ([closure boundary](../releases/sovereign-runtime-closure-boundary.md)) proves that the low-level runtime works:

- Approval persistence (`ApprovalStore`)
- Suspended invocation persistence (`SuspendedInvocationStore`)
- Approval continuation persistence (`ApprovalContinuationStore`)
- Audit chain (`AuditStore`)
- Audit outbox (`SovereignOpsAuditOutboxStore`)
- Transactional approval mutation + outbox boundary (`SovereignOpsApprovalMutationStore`)
- Worker lease coordination
- Spring Boot auto-configuration
- JDBC E2E restart proof

However, a developer wanting to build a sovereign workflow today must understand **all of these** — their contracts, lifecycle, wiring, and restart semantics — before writing any application logic.

This is the gap this design addresses.

The capabilities are proven. The ergonomics are not.

---

## 2. Design Goal

Make the proven Sovereign Runtime capabilities **pleasant and understandable** for developers.

A developer should be able to write:

```kotlin
val result = approvalGateway.requestApproval(
    subject = ApprovalSubject(input.claimId),
    recommendation = route.recommendation,
    requiredRole = ApproverRole("medical-reviewer"),
)
```

And get back a type that makes the suspension explicit — without understanding continuation stores, audit outbox internals, or worker lease mechanics.

---

## 3. Target API Shape

### 3.0. Domain Types

```kotlin
@JvmInline
value class ApprovalSubject(val value: String)

@JvmInline
value class Recommendation(val value: String)

@JvmInline
value class ApproverRole(val value: String)

@JvmInline
value class ApprovalId(val value: String)

@JvmInline
value class WorkflowRunId(val value: String)

@JvmInline
value class AuditStreamId(val value: String)

@JvmInline
value class ResumeToken(val value: String)
```

### 3.1. Approval Gateway

```kotlin
interface ApprovalGateway {

    /**
     * Request human approval for a workflow step.
     *
     * This function does **not** block waiting for a human.
     * It persists the approval request, emits audit intent, and returns
     * a result that makes the suspension explicit.
     *
     * @param subject  the business object requiring approval (e.g. claim ID)
     * @param recommendation  the AI/rule recommendation to be reviewed
     * @param requiredRole  the role permitted to approve or deny
     * @return  [ApprovalRequestResult] indicating suspension or pre-existing decision
     */
    suspend fun requestApproval(
        subject: ApprovalSubject,
        recommendation: Recommendation,
        requiredRole: ApproverRole,
    ): ApprovalRequestResult
}
```

### 3.2. Return Type

```kotlin
sealed interface ApprovalRequestResult {
    /**
     * The request was persisted and is waiting for a human decision.
     * The workflow is suspended and should not proceed until resumed.
     */
    data class Suspended(
        val approvalId: ApprovalId,
        val workflowRunId: WorkflowRunId,
        val auditStreamId: AuditStreamId,
        val resumeToken: ResumeToken,
    ) : ApprovalRequestResult

    /**
     * The subject was already approved in a prior workflow run
     * (idempotent replay). No suspension needed.
     */
    data class AlreadyApproved(
        val decision: HumanDecision,
    ) : ApprovalRequestResult

    /**
     * The subject was already denied in a prior workflow run.
     * No suspension needed; the workflow should terminate.
     */
    data class AlreadyDenied(
        val decision: HumanDecision,
    ) : ApprovalRequestResult

    /**
     * The approval request expired before a human decision was recorded.
     * The caller decides whether to restart, reject, or request a new approval.
     */
    data class Expired(
        val approvalId: ApprovalId,
        val expiredAt: Instant,
        val reason: String,
    ) : ApprovalRequestResult
}
```

### 3.3. Workflow-Level Result

For workflows that wrap human approval as the top-level control flow:

```kotlin
sealed interface SovereignWorkflowResult<out T> {
    data class Completed<T>(val value: T) : SovereignWorkflowResult<T>

    data class SuspendedForApproval(
        val approvalId: ApprovalId,
        val workflowRunId: WorkflowRunId,
        val auditStreamId: AuditStreamId,
        val resumeToken: ResumeToken,
    ) : SovereignWorkflowResult<Nothing>

    data class Rejected(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>

    data class Expired(
        val reason: String,
    ) : SovereignWorkflowResult<Nothing>
}
```

### 3.4. Example Workflow

```kotlin
@SovereignWorkflow
class ClaimTriageWorkflow(
    private val approvalGateway: ApprovalGateway,
    private val policy: SovereignPolicy,
    private val audit: SovereignAudit,
) {
    suspend fun triage(input: ClaimInput): SovereignWorkflowResult<ClaimRecommendation> {
        val route = policy.evaluate(input)

        if (route.requiresHumanApproval) {
            return approvalGateway.requestApproval(
                subject = ApprovalSubject(input.claimId),
                recommendation = route.recommendation,
                requiredRole = ApproverRole("medical-reviewer"),
            ).toWorkflowResult()
        }

        return SovereignWorkflowResult.Completed(route.recommendation)
    }
}
```

---

## 4. Non-Blocking Semantics

`requestApproval()` **must not block** a thread waiting for a human.

The call:

1. Persists the approval request to `ApprovalStore`.
2. Persists the suspended invocation replay envelope (safe invocation metadata) to `SuspendedInvocationStore`.
3. Persists approval-specific continuation metadata (sensitive or claimed arguments required to resume safely) to `ApprovalContinuationStore`.
4. Emits audit intent via the audit outbox.
5. Returns `ApprovalRequestResult.Suspended` immediately.

The coroutine suspension is a **data model suspension**, not a thread-blocking wait. The workflow must be able to:

- Terminate the current invocation.
- Accept an external decision later.
- Resume from the persisted continuation state.

---

## 5. Resume Semantics

Two candidate approaches for resume:

### Approach A: Workflow Runtime Resume

```kotlin
workflowRuntime.resumeApproval(
    approvalId = approvalId,
    decision = HumanDecision.Approved(recommendation, reviewerId),
)
```

The runtime:
1. Loads the persisted continuation.
2. Validates the decision against the stored approval request.
3. Reconstructs the workflow context.
4. Re-executes the workflow from the suspension point.
5. Emits audit/outbox events.

### Approach B: Gateway-Mediated Resume

```kotlin
approvalGateway.recordDecision(
    approvalId = approvalId,
    decision = HumanDecision.Approved(recommendation, reviewerId),
)
workflowRuntime.resume(approvalId)
```

Separation of concerns: recording the decision and triggering the resume are distinct operations. This is useful when:
- The decision is recorded through one channel (reviewer UI, API, batch import).
- The resume is triggered through another (scheduler, webhook, manual operator command).

### Recommendation

Start with **Approach A** (single `resumeApproval` method) for simplicity. Approach B can be added later if separation proves necessary.

### Important Constraints

- **Idempotent replay:** Resuming an already-resumed approval must be a no-op.
- **Versioned decisions:** An approval decision recorded after the stored continuation has been updated (by another resume) must be rejected with a version conflict.
- **Durable resume tokens:** The resume token must contain enough information to reconstruct workflow context without relying on ephemeral in-memory state.

---

## 6. Failure Modes

| Failure | Expected Behavior |
|---|---|
| Duplicate approval request | Idempotent replay — same `subject` + `workflowRunId` returns existing result |
| Approval version conflict | Reject stale decision with explicit `ApprovalVersionConflictException` |
| Restart before human decision | Resume from durable state — no data loss |
| Denial | Audit event emitted through outbox; workflow terminates with `Rejected` |
| Approval expired | Return `ApprovalRequestResult.Expired`; caller decides whether to restart, reject, or request a new approval |
| Audit outbox dispatch failure | Retried through the background worker |
| Worker lease loss | Current worker cycle is cancelled; next cycle picks up pending work |
| Resume token tampered | Fail closed — refuse to reconstruct context from untrusted data |

---

## 7. Stability Classification

Per the [Sovereign Runtime API Stability Boundary](../architecture/sovereign-api-stability-boundary.md):

| Component | Current Level | Target Level |
|---|---|---|
| Store SPIs (`ApprovalStore`, etc.) | **RC+ Stable** | RC+ Stable |
| `ApprovalGateway` (API shape in this doc) | **Preview** | RC+ Stable (after implementation) |
| Workflow ergonomics (SovereignWorkflow, etc.) | **Preview** | RC+ Stable (post-roadmap) |
| Concrete JDBC implementations | **Internal** | Internal |
| Key rotation, reviewer UI, etc. | **Deferred** | Deferred |

This design proposes the path to move workflow ergonomics from **Preview** toward **RC+ Stable**.

> **Current implementation status:** The minimal SPI described here is now represented by concrete Preview APIs in:
> - `dev.tramai.core.approval.gateway.ApprovalGateway`
> - `dev.tramai.core.approval.gateway.ApprovalRequestResult`
> - `dev.tramai.core.approval.gateway.ApprovalGatewayTypes`
> - `dev.tramai.core.workflow.SovereignWorkflowResult`
>
> **PR #97** adds a minimal store-backed adapter:
> - `DefaultApprovalGateway` — Preview implementation of `ApprovalGateway` that delegates to `ApprovalStore`, `SuspendedInvocationStore`, and `ApprovalContinuationStore`
> - `ApprovalGatewayRequestFactory` — internal seam for constructing low-level persistence records from the ergonomic SPI input
> - `ApprovalGatewayPersistenceRequest` — transport object aggregating records for all three stores
>
> **PR #98** auto-configures the Preview `ApprovalGateway` for Spring Boot when all required backing stores and an `ApprovalGatewayRequestFactory` are available.
>
> The auto-configuration does not create a generic request factory. Applications must provide one because request construction depends on workflow-specific metadata, replay envelopes, digests, and resume-token generation.
>
> **PR #99** proves the Preview gateway can drive the regulated claim triage E2E scenario while preserving restart safety, durable approval state, continuation persistence, audit chain validation, and outbox dispatch.
>
> The example provides a `RegulatedClaimTriageApprovalGatewayRequestFactory` that translates ergonomic gateway calls into low-level persistence records. Spring Auto-configuration creates `DefaultApprovalGateway` when the factory bean is present alongside the JDBC stores.
>
> **PR #100** adds a [developer-facing golden path guide](../guides/approval-gateway-golden-path.md) explaining how to use the Preview ApprovalGateway, how Spring Boot auto-configuration wires it, and what persistence records are created underneath.
>
> **PR #100** adds a developer-facing golden-path guide for the Preview gateway.
>
> **PR #101** adds a JDBC-backed transactional creation boundary for approval requests:
> - `SovereignOpsApprovalRequestMutationStore` — Preview atomic creation seam for approval, suspended invocation, continuation, and optional audit outbox intent
> - `JdbcSovereignOpsApprovalRequestMutationStore` — PostgreSQL implementation that commits approval request creation inside one transaction
> - `SovereignOpsTransactionalApprovalGateway` — Preview gateway adapter that prefers the atomic creation seam when available
> - Spring Boot auto-configuration now prefers the transactional gateway over `DefaultApprovalGateway` when the request mutation store is present
>
> **Limitations:**
> - Does not implement full workflow resume.
> - JDBC-backed approval-request creation now has a transactional boundary, but the generic fallback gateway still does not.
> - Spring Boot auto-configuration is Preview and requires an application-provided `ApprovalGatewayRequestFactory`.
>
> **PR #102** adds approval-requested audit outbox intent emission from the transactional gateway:
> - `ApprovalGatewayAuditIntentFactory` — Preview SPI for creating approval-requested audit outbox records
> - `SovereignOpsTransactionalApprovalGateway` now creates audit intent when an `ApprovalGatewayAuditIntentFactory` is present
> - `RegulatedClaimTriageApprovalGatewayAuditIntentFactory` — example factory that creates `regulated-claim-triage.approval-requested` outbox records
> - The E2E test now asserts the gateway-created approval-requested outbox record exists
>
> **PR #103** adds a preview approval decision control plane service:
> - `ApprovalDecisionControlPlane` — application-facing service boundary for approving or denying pending approvals
> - `ApprovalDecisionAuthorizer` — Preview authorization seam for decision actors
> - `SovereignOpsApprovalDecisionControlPlane` — transactional implementation backed by `ApprovalStore` and `SovereignOpsApprovalMutationStore`
> - Spring Boot auto-configuration now wires the control plane when approval mutations are enabled
> - The regulated claim triage E2E now proves denial through the control plane persists both the approval decision and the denial audit outbox intent
>
> **PR #104** adds a preview approval resume control plane:
> - `ApprovalResumeControlPlane` — application-facing resume boundary
> - `SovereignOpsApprovalResumeControlPlane` — composes engine `ResumeApprovalCommand` and `TramaiRuntime.resumeApproval`
> - Spring Boot auto-configuration wires the resume control plane when `resume-enabled=true`
> - The regulated claim triage E2E now proves request → approve → resume → complete
>
> **Limitations remaining:**
> - Generic fallback gateway (`DefaultApprovalGateway`) still has no cross-store transaction boundary and does not emit audit intent.

---

## 8. Out of Scope

The following are explicitly **not covered** by this design:

- Reviewer UI or dashboard
- Full REST control plane for workflow management
- Broad workflow DSL (state machine language)
- Key rotation
- Enterprise IAM / SSO integration
- Maven Central release
- Production certification

---

## 9. References

- [Sovereign Runtime Closure Boundary](../releases/sovereign-runtime-closure-boundary.md)
- [Sovereign Runtime API Stability Boundary](./sovereign-api-stability-boundary.md)
- [Regulated Claim Triage Scenario](../scenarios/regulated-claim-triage.md)
- [Sovereign JDBC Production Deployment Runbook](../runbooks/sovereign-jdbc-production-deployment.md)

---

## 10. Proposed Implementation Sequence

| PR | Title | Scope |
|---|---|---|
| #95 | Workflow ergonomics design boundary | This document |
| #96 | Minimal approval gateway SPI | Introduce `ApprovalGateway` interface over existing stores |
| #97 | Store-backed approval gateway adapter | Implement `DefaultApprovalGateway` over approval, suspended invocation, and continuation stores |
| #98 | Spring Boot auto-configuration | Wire preview gateway as a Spring bean (`ApprovalGatewayAutoConfiguration`) |
| #99 | Regulated claim triage through gateway | Real example using the gateway |
| #100 | Golden path example | Polished developer-facing example |
| #101 | Transactional request creation boundary | Add JDBC-backed atomic approval-request creation and prefer the transactional gateway when available |
| #102 | Approval-requested audit intent from gateway | Emit approval-requested audit outbox intent atomically from the transactional gateway when an `ApprovalGatewayAuditIntentFactory` is present |
| #103 | Preview approval decision control plane | Add application-facing approve/deny service over the transactional mutation and outbox boundary |
| #104 | Preview approval resume control plane | Add application-facing resume API over the engine resume runtime |
| #105 | Preview REST approval control plane | Add REST endpoints in new `tramai-spring-boot-starter-sovereign-ops-rest` module over the service-level control planes (disabled by default) |
| #106 | Preview approval inbox query API | Add safe reviewer work queue over durable approval records — `ApprovalInboxQueryService` SPI, JDBC implementation, REST list/work-item endpoints |
