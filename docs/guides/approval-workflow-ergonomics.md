# Approval Workflow Ergonomics

> **Status:** Guide — explains practical approval workflow patterns and lifecycle.
> **Phase:** Phase 4 — Approval & Human Gates of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [approval gateway golden path](approval-gateway-golden-path.md), the [governed workflow quickstart](governed-workflow-quickstart.md), and the [workflow lifecycle model](../workflow-lifecycle-model.md).

---

## What This Guide Covers

This guide explains the **conceptual** approval workflow patterns that TramAI supports: when to request human approval, what lifecycle states exist, how each outcome should be handled in a governed workflow, and what approval does and does not prove.

It is **not** an API reference. The [Approval Gateway Golden Path](approval-gateway-golden-path.md) explains the `ApprovalGateway` API, Spring Boot wiring, persistence records, and Java interop. This guide focuses on the **when** and **why** of approval decisions.

---

## When to Use Human Approval

Not every workflow needs human approval. Approval adds latency, operational complexity, and human judgment to automated systems. Use it when the cost of an incorrect automatic decision outweighs the cost of the delay.

Common triggers for human approval:

| Situation | Example |
|-----------|---------|
| Human-impacting decision | Claim rejection, account suspension, credit decision |
| Risk escalation | AI classifies input as high-risk or restricted |
| External side effect | Sending a message, creating a ticket, changing external state |
| Policy boundary | Accessing restricted data, executing a sensitive workflow path |
| Cost or provider escalation | Switching from a local model to a paid cloud model |
| Scope ambiguity | The AI output falls outside the expected range of confidence or certainty |

These are guidelines, not rules. The decision to require approval is always domain-specific.

---

## Approval Lifecycle at a Glance

An approval request moves through a small number of well-defined states:

| State | Meaning | Workflow Behaviour |
|-------|---------|-------------------|
| Requested | Approval was created, no decision yet | Workflow suspends or returns a pending state |
| Approved | A human or authorised actor accepted the request | Workflow resumes or proceeds with the approved continuation |
| Denied | A human or authorised actor rejected the request | Workflow stops or follows a denial path |
| Expired | No decision arrived within the configured window | Workflow stops or follows a timeout path |
| Invalid actor | A decision was received from an unauthorised source | Decision/control-plane validation rejects the decision before it changes approval state |
| Missing role | A decision was received but the required role was not satisfied | Decision/control-plane validation rejects the decision before it changes approval state |

The `ApprovalRequestResult` sealed interface exposes the workflow-facing request outcomes: `Suspended`, `AlreadyApproved`, `AlreadyDenied`, and `Expired`. Actor and role validation belongs to the decision/control-plane boundary and should be handled before a decision is accepted.

---

## Pattern 1 — Approval Before High-Risk Action

Use this pattern when the AI proposes an action, but the system should not execute it without human review.

The workflow calls `approvalGateway.requestApproval(...)` and uses the result mapper to handle every outcome:

```kotlin
return approvalGateway.requestApproval(
    subject = ApprovalSubject(input.claimId),
    recommendation = ApprovalRecommendation(
        type = "claim-triage",
        summary = "High-risk claim requires medical review",
        payload = mapOf("riskLevel" to "HIGH"),
    ),
    requiredRole = ApproverRole("medical-reviewer"),
    workflowRunId = WorkflowRunId(input.workflowRunId),
).toWorkflowResult { approvedValue ->
    // Only invoked when AlreadyApproved — lazy
    "approved-continue"
}
```

The four possible outcomes are:

| Outcome | What Happens |
|---------|-------------|
| `Suspended` | Workflow paused, awaiting human decision |
| `AlreadyApproved` | Workflow continues with the approved value |
| `AlreadyDenied` | Workflow terminates with the denial reason |
| `Expired` | Workflow terminates with the expiry reason |

The `approvedValue` lambda is **lazy** — it executes only for `AlreadyApproved`. Terminal outcomes (`Suspended`, `AlreadyDenied`, `Expired`) never invoke the lambda, preventing accidental side effects.

---

## Pattern 2 — Approval After AI Classification

Use this pattern when the AI first classifies an input, then a gate decides whether approval is required based on the classification result.

This is the pattern used in the governed workflow quickstart and example:

```
classify → policy-check → approval-required → finalize
```

The `approval-required` gate is a deterministic `gateStep` that checks the classification result:

```kotlin
gateStep(name = "approval-required") { state, _ ->
    if (state.classification?.risk == "high" && !state.approved) {
        GateDecision.reject("High-risk claim requires human approval")
    } else {
        GateDecision.allow()
    }
}
```

This pattern keeps the AI and the approval decision separate: the AI classifies, the gate decides whether approval is needed, and the approval gateway handles the human interaction.

---

## Pattern 3 — Denial as a First-Class Outcome

A denied approval is a **valid business outcome**, not necessarily a system failure. The workflow should handle denial explicitly, just as it handles a successful continuation.

```kotlin
when (val result = approvalGateway.requestApproval(...)) {
    is ApprovalRequestResult.AlreadyDenied -> {
        logger.info("Denied: ${result.decision.reason}")
        // Emit audit event, trigger fallback, or stop
    }
    is ApprovalRequestResult.AlreadyApproved -> { ... }
    is ApprovalRequestResult.Suspended -> { ... }
    is ApprovalRequestResult.Expired -> { ... }
}
```

Denial is a terminal outcome — the workflow does not retry unless a new approval request is created. This is intentional: denial represents a human decision that the proposed action should not proceed.

---

## Pattern 4 — Expired Approval Windows

Expired approvals should not silently proceed. They produce an `Expired` outcome with a reason string, which the workflow should handle as a terminal state:

```kotlin
is ApprovalRequestResult.Expired -> {
    // No decision arrived in time
    // Log, notify, or create a new approval request
}
```

Expiry applies per-approval-request. If the workflow needs another attempt, it creates a new approval request with a fresh window. The expiry window is determined by the persistence store configuration.

---

## Pattern 5 — Role-Based or Actor-Constrained Approval

TramAI supports constraining approval decisions to specific roles or actors.

**Role-based:** The approval request specifies a `requiredRole`. Only decision-makers with that role can approve or deny the request:

```kotlin
requiredRole = ApproverRole("medical-reviewer")
```

**Actor constraint:** The approval persistence model can restrict which actors are allowed to act on a request. An `invalid actor` outcome occurs when a decision arrives from an unauthorised source.

This pattern is useful for:

- Segregating duties between AI outputs and human reviewers
- Ensuring the right domain expertise reviews each decision
- Preventing self-approval or approval by unauthorised parties

---

## Pattern 6 — Approval Evidence and Audit Notes

Every approval decision produces evidence that can be recorded in the audit trail:

| Event | Evidence |
|-------|----------|
| Approval requested | Request metadata, workflow context, required role, timestamp |
| Approval approved | Decision record, actor identity, comment, timestamp |
| Approval denied | Decision record, actor identity, reason, timestamp |
| Approval expired | Expiry event, original request metadata, timestamp |

The evidence is **structural** — it records that a decision was made and who made it. It does **not** prove:

- That the decision was correct (legally, medically, financially)
- That the reviewer had sufficient information
- That the review process meets regulatory standards

See the [Lifecycle Model](../workflow-lifecycle-model.md) for the full evidence chain from request through approval or denial.

---

## What Approval Does Not Prove

Human approval is a governance tool, not a correctness guarantee. It is important to be precise about what TramAI approval semantics provide and what they do not:

| Approval Proves | Approval Does Not Prove |
|-----------------|------------------------|
| A decision was requested | The decision was correct |
| A human or authorised actor responded | The decision is compliant with external regulation |
| The workflow recorded the decision outcome | The domain policy behind the decision is valid |
| The workflow can continue or stop based on the decision | The AI output that triggered the request is true |
| The decision was made by an actor meeting the required role (where configured) | The reviewer had sufficient context to make an informed decision |

In short: approval records **process**, not **truth**. TramAI provides the evidence scaffolding; domain experts validate the correctness.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Approval Gateway Golden Path | [Golden Path Guide](approval-gateway-golden-path.md) |
| Runnable approval example | [`examples/approval-resume`](../../examples/approval-resume) |
| Approval failure taxonomy | [Failure Taxonomy](approval-failure-taxonomy.md) |
| Governed workflow quickstart | [Quickstart](governed-workflow-quickstart.md) |
| Governed workflow troubleshooting | [Troubleshooting Guide](governed-workflow-troubleshooting.md) |
| Workflow lifecycle model | [Lifecycle Model](../workflow-lifecycle-model.md) |
| Human approval workflow ergonomics design | [Design Doc](../architecture/human-approval-workflow-ergonomics.md) |
| Regulated claim triage scenario | [Reference Scenario](../scenarios/regulated-claim-triage.md) |
| Sovereign Runtime Quickstart | [Quickstart](sovereign-runtime-quickstart.md) |
