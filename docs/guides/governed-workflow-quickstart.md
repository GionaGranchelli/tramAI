# Governed Workflow Quickstart

> **Status:** Conceptual guide — not a runnable example.
> **Phase:** Phase 3 — Workflow Ergonomics of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [orchestration DSL](orchestration.md) and the [workflow lifecycle model](../workflow-lifecycle-model.md).

---

## What This Guide Is

This guide answers the conceptual questions a new TramAI developer should be able to answer before writing their first governed workflow:

1. What is a governed workflow, and how is it different from a plain workflow?
2. When should you use one?
3. What are the minimum moving parts?
4. How do typed contracts, policy, approval, persistence, and testing fit together?
5. What is intentionally not guaranteed by this quickstart?

It is **not** a runnable tutorial. The complete executable example is in the [`examples/governed-workflow`](../../examples/governed-workflow) module.

---

## What a Governed Workflow Is

A **governed workflow** is a bounded, explicit sequence of steps where AI-backed operations are combined with typed contracts, deterministic local steps, policy gates, optional human approval, persistence, and observable failure paths. With persistence enabled, top-level step boundaries are checkpointed so the workflow can resume after interruption. Every AI call is typed, and every decision point is visible in the workflow definition.

A plain `workflow<S>` (see the [orchestration guide](orchestration.md)) already supports `aiStep`, `localStep`, `gateStep`, `branchStep`, and `parallelStep`. A **governed** workflow adds:

- **Typed contracts** — every AI step has a well-defined input and output shape, validated against a generated schema
- **Policy gates** — deterministic rules that can block execution before an AI call or state transition
- **Optional approval gates** — human-in-the-loop suspension points for high-risk outcomes
- **Persistence and audit** — checkpoints at step boundaries, audit events for policy and approval decisions
- **Testability without real model calls** — fake providers and determinism for CI-safe coverage
- **Observable failure paths** — structured errors for policy denials, approval failures, parse failures, and provider errors

In short: a governed workflow is the orchestration layer with typed governance concepts wired in explicitly.

---

## Minimal Example Domain: Claim Triage

This guide uses **claim triage** as a minimal domain.

A claim comes in. The system classifies it. A policy gate blocks unsafe decisions. A human approves high-risk outcomes. The workflow records what happened. The whole thing can be tested with a fake provider.

This domain:

- Naturally has typed input and typed output
- Can show a policy gate (e.g., restricted claims require manual handling)
- Can show optional human approval (e.g., approve high-risk decisions)
- Can show failure paths without real external systems
- Matches the kind of governed workflow TramAI was designed to explain

> **Important:** This domain is illustrative. It does not prove production readiness, legal compliance, or regulatory compliance.

---

## Architecture at a Glance

```
ClaimInput
  → aiStep: classify claim
  → gateStep: enforce policy
  → gateStep: require approval if high risk
  → localStep: finalize decision
  → ClaimTriageResult
```

Every step is a typed boundary. State is explicit. Decision points are visible in the workflow definition.

---

## Step 1 — Define Typed Input and Output

Every governed workflow starts with typed contracts. These define the shape of data that flows through each step.

```kotlin
data class ClaimInput(
    val claimId: String,
    val amount: Double,
    val type: String,
    val description: String,
)

data class ClaimTriageState(
    val claim: ClaimInput,
    val classification: ClaimClassification? = null,
    val approved: Boolean = false,
    val result: ClaimTriageResult? = null,
)

data class ClaimClassification(
    val risk: String,        // "low", "medium", "high", "restricted"
    val category: String,    // e.g. "general", "medical", "liability"
    val confidence: Double,  // 0.0–1.0
)

data class ClaimTriageResult(
    val status: String,
    val reason: String,
)
```

These types are the workflow's contract. The `@AiService`-backed classifier declares what it takes and what it produces — no implicit state, no hidden fields.

For the full lifecycle of contract generation and validation, see the [Structured Output Contract Lifecycle](../structured-output-contract-lifecycle.md).

---

## Step 2 — Define AI-Backed Services

An AI step is driven by a typed `@AiService` interface:

```kotlin
@AiService
@SystemPrompt("Classify the incoming claim for risk and category.")
interface ClaimClassifier {
    @Operation(
        prompt = "Claim: {claim}",
        model = "local-model",
    )
    suspend fun classify(claim: ClaimInput): ClaimClassification
}
```

The input and output types define the schema that the provider sees. TramAI generates the JSON Schema contract from the Kotlin types, sends it to the provider, parses the response, and validates it against the contract.

This is the same pattern used in single-turn AI calls. The orchestration layer wraps it into a workflow step.

---

## Step 3 — Compose Workflow Steps

The orchestration DSL wires the AI service into a workflow, then adds policy, approval, persistence, and failure handling around it.

```kotlin
workflow<ClaimTriageState>(
    name = "claim-triage",
    definitionVersion = "1",
) {
    aiStep(
        name = "classify",
        input = { state -> state.claim },
        invoke = classifier::classify,
        merge = { state, classification ->
            state.copy(classification = classification)
        },
    )

    gateStep(name = "policy-check") { state, _ ->
        if (state.classification?.risk == "restricted") {
            GateDecision.reject("Restricted claim requires manual handling")
        } else {
            GateDecision.allow()
        }
    }

    gateStep(name = "approval-required") { state, _ ->
        if (state.classification?.risk == "high" && !state.approved) {
            GateDecision.reject("High-risk claim requires human approval")
        } else {
            GateDecision.allow()
        }
    }

    localStep(name = "finalize") { state, _ ->
        state.copy(
            result = ClaimTriageResult(
                status = "ready-for-review",
                reason = "Policy gate passed",
            ),
        )
    }
}.build { state ->
    state.result ?: error("missing result")
}
```

Key points:

- Every step operates on explicit `ClaimTriageState`
- `aiStep` bridges AI output into workflow state
- `gateStep` provides deterministic guard conditions
- `localStep` runs pure application logic
- The workflow state stays typed and observable at every step boundary

> **Note:** This snippet is illustrative. The complete runnable version is in the [`examples/governed-workflow`](../../examples/governed-workflow) module.

---

## Step 4 — Add a Policy Gate

A policy gate is a deterministic local guard that runs before or after other steps. In the example above, the `policy-check` gate blocks `restricted` claims from proceeding further.

Policy gates are useful for:

- Blocking unsupported or unsafe inputs
- Enforcing data classification boundaries
- Preventing accidental escalation to expensive or unapproved providers
- Early rejection of invalid state transitions

A `gateStep` returns `GateDecision.allow()` or `GateDecision.reject(reason)`. Rejection throws `WorkflowGateRejectedException`, which is observable through workflow failure hooks.

For policy enforcement across multiple workflow boundaries, see the `PolicyEngine` and `PolicyDecision` types documented in the [workflow API stability boundary](../workflow-api-stability-boundary.md).

---

## Step 5 — Add an Optional Approval Gate

Some outcomes require human oversight. An approval gate suspends the workflow until a human decision is received.

In the claim triage example, `approval-required` rejects high-risk claims that have not been explicitly approved. To make this an actual approval gate (suspending the workflow for human review), the step would use the `ApprovalGateway` API instead of a simple `gateStep`:

```kotlin
// The approval gateway creates a suspension point.
// PR #174 will show the full ApprovalGateway wiring.
val result = approvalGateway.requestApproval(/* ... */)
```

Approval gates support:

- **Suspended** — workflow pauses, awaiting human decision
- **Approved** — workflow resumes with continuation
- **Denied** — workflow terminates with denial outcome
- **Expired** — workflow terminates after approval window elapses

For the full approval API surface, see the [Approval Gateway Golden Path guide](approval-gateway-golden-path.md).

> **Important:** Human approval records a decision. It does **not** prove the business or legal correctness of the approved action.

---

## Step 6 — Add Persistence and Audit Notes

When `WorkflowPersistence` is supplied, top-level step boundaries are checkpointed so the workflow can resume after interruption. Without persistence, the workflow still runs as a typed bounded sequence, but it does not durably save checkpoints.

This means:

- Workflows can be checkpointed and resumed after a process restart
- Each step's input, output, and decision are observable
- Audit events can be emitted for policy decisions and approval outcomes
- The workflow's execution history is available for review

The orchestration layer supports pluggable persistence stores:

| Store | Purpose |
|-------|---------|
| `WorkflowCheckpointStore` | Step-boundary checkpoints for resume |
| `WorkflowLeaseStore` | Distributed worker lease coordination |
| Audit stores | Policy decisions, approval decisions, step outcomes |

For the full persistence model, see [Orchestration Persistence](orchestration-persistence.md).

---

## Step 7 — Test Without Real Model Calls

A governed workflow can be tested with a fake provider, no real model credentials required.

```kotlin
val fakeClassifier = FakeClassifier()
val workflow = buildClaimTriageWorkflow(fakeClassifier)

val result = workflow.run(
    initialState = ClaimTriageState(claim = lowRiskClaim),
)

assertEquals("ready-for-review", result.status)
```

The `tramai-testing` module provides deterministic mock providers that return predictable typed results. This means:

- The workflow logic (step ordering, gate evaluation, merge functions) is CI-tested on every commit
- Policy gates, approval conditions, and failure paths are exercised without model calls
- The test suite does not depend on network access, API keys, or model availability

The [testing guide](testing.md) covers fake providers and workflow-level testing patterns in detail.

---

## Common Failure Paths

A governed workflow can fail at multiple points. Each failure has a distinguishable outcome:

| Failure | Where It Occurs | Outcome |
|---------|----------------|---------|
| Input validation | Contract binding | `TramaiException` |
| Policy/gate denial | `gateStep` | `WorkflowGateRejectedException` |
| Provider unavailable | AI step | Provider exception |
| Parse failure | Structured output | `StructuredOutputException` |
| Validation failure | Output validation | `StructuredOutputException` |
| Approval required | Approval gate | `ApprovalRequiredException` / `ApprovalSuspendedException` |
| Approval denied | Approval decision | Denial outcome |
| Approval expired | Approval window | Expired outcome |
| Step exception | Any step | Step exception propagated to caller |

Failure outcomes are typed and observable. Each exception or terminal result carries enough context to understand what failed and why.

---

## What This Quickstart Does Not Prove

This quickstart is a conceptual introduction. It does **not**:

- Prove production readiness
- Prove legal or business correctness
- Replace domain review
- Require real model calls
- Provide a complete runnable application — see the [`examples/governed-workflow`](../../examples/governed-workflow) module
- Claim that approval means the AI output is correct

See the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md) for status, non-goals, and claim boundaries.

---

## Next Steps

| Topic | Link |
|-------|------|
| Runnable governed workflow example | [`examples/governed-workflow`](../../examples/governed-workflow) |
| Orchestration DSL reference | [Orchestration Guide](orchestration.md) |
| Workflow lifecycle model | [Lifecycle Model](../workflow-lifecycle-model.md) |
| Structured output contracts | [Contract Lifecycle](../structured-output-contract-lifecycle.md) |
| Approval gateway | [Golden Path Guide](approval-gateway-golden-path.md) |
| Testing with fake providers | [Testing Guide](testing.md) |
| Orchestration persistence | [Persistence Guide](orchestration-persistence.md) |
| Workflow API stability | [Stability Boundary](../workflow-api-stability-boundary.md) |
