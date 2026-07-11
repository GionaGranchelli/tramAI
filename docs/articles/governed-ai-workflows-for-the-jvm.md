# Beyond the Model Call: Governed AI Workflows for the JVM

> **Status:** Draft for external publication.
> **Audience:** JVM developers, application architects, platform engineers, and security/governance practitioners.
> **Estimated reading time:** 8–12 minutes.
> **Canonical product boundary:** [TramAI Product Positioning](../product/positioning.md).
> **Technical maturity:** [Project Status](../STATUS.md).

---

## The Model Call Is the Easy Part

Calling an LLM from a Kotlin or Java service is straightforward. Annotate an interface, add a provider dependency, write a one-line invocation, and you have a working AI integration in under a minute.

The harder questions begin after the first successful request:

- Was this model allowed to see this data?
- Was this provider allowed for this classification?
- Was the requested tool authorized before it executed?
- Should a human approve this side effect before it happens?
- Can the workflow recover safely if a human decision is pending?
- Can an operator reconstruct exactly what happened — model by model, tool by tool, decision by decision — days or weeks later?

These are not prompt-engineering questions. They are runtime architecture questions. Answering them requires moving governance from a prompt instruction into the execution infrastructure itself.

---

## Governance Cannot Live Only in Prompts

A common instinct is to add governance as system prompt instructions:

```
You are a claims processing assistant.
Never process claims above €10,000 without human approval.
Do not send restricted data to external models.
Log every decision you make.
```

This instruction is a request, not enforcement. The model might follow it. It might not. It might follow it today and deviate tomorrow after a provider update or a temperature change. The model cannot halt its own network call, prevent a tool invocation, or produce a cryptographically verifiable audit record.

The gap is not between a good prompt and a bad prompt. The gap is between probabilistic model behavior and deterministic application decisions:

| Prompt instruction | Runtime policy |
|---|---|
| Text embedded in a conversation | Decision evaluated in the execution path |
| Model may or may not comply | Policy engine returns ALLOW, DENY, or REQUIRE_APPROVAL |
| No audit trail beyond the prompt log | Durable governance evidence with event sequencing |
| Cannot stop a network call | Refuses provider invocation before the request leaves the runtime |

A governed AI workflow moves these decisions into the runtime boundary. When governance components are configured, the runtime can classify the workload, evaluate policy before the model or tool proceeds, route to an allowed provider zone, suspend for human approval, and capture each decision in structured evidence — regardless of what the prompt says.

This does not mean every TramAI deployment automatically prevents every possible data leak. It means that governance decisions become explicit, auditable, and deterministic rather than probabilistic and unverifiable.

---

## What Makes a Workflow Governed

A governed workflow is a bounded sequence of typed AI and deterministic application steps where policy, approval, routing, persistence, and evidence are explicit execution concerns.

The execution path:

```
Typed input
  ↓
Classification
  ↓
Configured policy and DLP
  ↓
Allowed provider route — or denial
  ↓
Typed model result validated against a contract
  ↓
Tool exposure and execution policy
  ↓
Optional human approval and suspension
  ↓
Replay-safe continuation
  ↓
Audit, evidence, and operational recovery
```

Every step in this path is an explicit concern rather than an implicit assumption. The runtime owns the transitions, not the model.

---

## A Concrete Example: Claim Triage

Consider an insurance claim that arrives at a JVM service. The claim needs classification, policy evaluation, and potentially human approval before an automated result is produced.

Here is the actual Kotlin workflow:

```kotlin
fun buildClaimTriageWorkflow(
    classifier: ClaimClassifier,
): Workflow<ClaimTriageState, ClaimTriageResult> =
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
            if (state.classification?.risk == ClaimRisk.RESTRICTED) {
                GateDecision.reject(
                    "Restricted claim requires manual handling"
                )
            } else {
                GateDecision.allow()
            }
        }

        gateStep(name = "approval-required") { state, _ ->
            if (state.classification?.risk == ClaimRisk.HIGH &&
                !state.approved
            ) {
                GateDecision.reject(
                    "High-risk claim requires human approval"
                )
            } else {
                GateDecision.allow()
            }
        }

        localStep(name = "finalize") { state, _ ->
            state.copy(
                result = ClaimTriageResult(
                    status = "ready-for-review",
                    reason = "Policy and approval gates passed",
                ),
            )
        }
    }.build { state ->
        state.result ?: error("missing result")
    }
```

Four things happen in sequence:

1. **Classification** — the claim text is evaluated and a typed `ClaimClassification` is produced, carrying a risk level (LOW, MEDIUM, HIGH, RESTRICTED), a category, and a confidence score.
2. **Policy gate** — after classification, restricted claims are rejected before finalization or any downstream tool and side-effect steps. In this deterministic demo the classifier itself makes no model call; in a provider-backed workflow, classification would already have occurred before this gate.
3. **Approval gate** — high-risk claims without prior approval are rejected. Those that have been approved proceed.
4. **Finalization** — accepted claims produce a deterministic result.

The workflow is typed end to end. `ClaimTriageState` carries the claim, classification, approval flag, and result. Every step declares what it reads and what it writes. The compiler enforces the contract.

> The introductory example uses a deterministic classifier so readers can exercise the workflow without credentials or network access. It demonstrates composition and failure paths, not the complete durable governance runtime.

---

## Policy Before Side Effects

In an ungoverned integration, policy is often an afterthought — validate the response, check permissions after the fact, or log a warning when something suspicious occurred.

In a governed workflow, policy is evaluated *before* exposure and execution:

- `BEFORE_TOOL_EXPOSURE` — is this tool authorized to be visible to the model at all?
- `BEFORE_TOOL_EXECUTION` — is this specific invocation authorized to proceed?
- Tool policy decisions: `ALLOW`, `DENY`, or `REQUIRE_APPROVAL`

When governance components are configured, the runtime evaluates model and tool policy in the execution path. A configured secure policy engine can deny unknown tools, models, and providers before execution proceeds. The denial is recorded in the audit trail, and the policy violation propagates as a typed exception — the tool never executes, and remaining processing stops.

This is qualitatively different from a prompt instruction saying "do not use this tool." The runtime enforced the decision.

---

## Human Approval Is a Lifecycle, Not a Boolean

A common architectural shortcut is:

```kotlin
if (approved) execute()
```

This treats approval as a Boolean gate that disappears after it passes. A durable approval path needs more:

- a stable approval identity tied to the specific operation;
- an immutable binding between the approval request and the workflow state;
- a suspension point that preserves continuation state;
- distinct outcomes: approved, denied, expired, and already-decided;
- replay-safe continuation so the workflow can resume exactly where it paused;
- idempotent or at-most-once side effects so restarting does not cause double execution;
- observable recovery so operators can see which workflows are suspended and why.

TramAI supports suspension and replay-safe continuation. The [`examples/approval-resume`](../../examples/approval-resume) example demonstrates approved, denied, bypass, and repeated-resume paths using embedded PostgreSQL — no external infrastructure required. Side effects can be protected through idempotency and continuation binding, and the provided approval example proves an at-most-once reimbursement path.

---

## Controlled Routing for Sensitive Workloads

Not every claim should go to the same model provider. A public-information query might safely use any available provider. An internal document with personally identifiable information should remain within a trusted zone. A restricted legal filing should never leave a local deployment.

TramAI maps classification levels to routing zones:

| Classification | Sovereign default allowed zones | Fallback |
|---|---|---|
| PUBLIC | LOCAL, EU_CLOUD, GLOBAL_CLOUD | Any configured zone |
| INTERNAL | LOCAL, EU_CLOUD, GLOBAL_CLOUD | Any configured zone |
| CONFIDENTIAL | LOCAL, EU_CLOUD | Local or EU cloud |
| RESTRICTED | LOCAL | No fallback |

These are the built-in sovereign routing defaults when the routing matrix is enabled. Applications may configure different rules. LOCAL describes a configured trust zone; it does not by itself prove that a deployment is air-gapped.

Configured classification-aware routing can prevent restricted workloads from reaching providers outside the allowed trust zone. The routing decision is evaluated before the provider is invoked — the runtime refuses the call rather than hoping the provider will handle the data responsibly.

The [`examples/sovereign-document-intelligence`](../../examples/sovereign-document-intelligence) example demonstrates this deeper path, combining restricted-document handling, local-only routing, policy evaluation, approval, continuation, audit, and evidence export.

---

## Evidence and Operational Recovery

Application logs show what happened. Governance evidence proves what was decided, by whom, and in what order.

TramAI includes exporters that can transform captured policy, approval, and provider-routing decision sources into runtime-evidence.v1 records:

- **Policy decisions** — which classifications triggered which ALLOW/DENY/REQUIRE_APPROVAL outcomes, with safe metadata (classification, risk level, provider name) and without raw prompts, arguments, or secrets.
- **Approval decisions** — who approved or denied what, when, and with what identity binding, preserving decision digests rather than raw comments.
- **Provider routing decisions** — which provider was selected or blocked, with digest-only provider and model identifiers to protect operational details.

Those records can be serialized as JSONL with allowlisted metadata and tamper-evident SHA-256 digests. The runtime-evidence bundle map defines where these JSONL files belong inside an evidence bundle. Automatic exporter scheduling, bundle-lifecycle integration, and verifier enforcement remain separate concerns.

Durable approval and audit source events can survive restarts through configured persistence and outbox components. The existing worker observability surfaces cover audit-outbox recovery and dispatch; it does not currently represent an evidence-export worker.

This is not a log file. It is a structured, verifiable record of each captured and exported governance decision — designed for operator review, not model consumption.

---

## Why the JVM

Enterprises already encode business contracts on the JVM. Spring Boot applications already own identity, transaction boundaries, data access, and operational infrastructure. Adding AI governance as an external service or a separate prompt layer introduces a split between business rules and AI execution that is difficult to reconcile.

A JVM-native governed runtime integrates into the application stack directly:

- **Typed interfaces** make AI inputs and outputs reviewable by the same type system that governs the rest of the application.
- **Deterministic testing** can exercise policy and failure paths without real model calls, using fake classifiers and structured assertions — the same test infrastructure the team already uses.
- **No hosted control plane required** — governance runs inside the application process, adopting the application's existing deployment, monitoring, and identity infrastructure.
- **Evidence lives alongside application data** — audit records and runtime evidence can sit in the same PostgreSQL instance as business data, with the same backup and recovery policies.

TramAI is one Kotlin-first implementation of this architecture. It is not the only possible approach, but it demonstrates that governed AI workflows do not require a separate platform.

---

## Composable Adoption

Teams do not need to adopt every capability immediately. The adoption path can be gradual:

1. Start with a typed AI service and structured output.
2. Add deterministic tests with fake classifiers.
3. Compose steps into a governed workflow.
4. Configure policy gates for known risk classifications.
5. Add approval and persistence for high-risk paths.
6. Enable audit and evidence export for operator visibility.
7. Configure classification-aware routing for sensitive workloads.

Each layer adds value without requiring the layers above it. The governed workflow example exercises the first four layers with zero credentials. The approval-resume example adds persistence. The sovereign document-intelligence example exercises the full stack.

---

## What This Does Not Claim

TramAI is under active development. The sovereign runtime is an RC+ enterprise-proof milestone rather than a stable 1.0 contract.

TramAI does not itself:

- make an organization compliant;
- certify a deployment;
- guarantee that every deployment is sovereign or air-gapped;
- provide production-grade enterprise IAM;
- govern imported remote MCP tools today;
- authorize tool calls based on raw argument thresholds.

These maturity boundaries are canonical. The project provides governance *support* — structured evidence, policy enforcement points, approval lifecycle management, and routing controls. It does not replace organizational compliance processes, security reviews, or certification programs.

---

## Try It

```bash
git clone https://github.com/GionaGranchelli/tramAI.git
cd tramAI
./gradlew :examples:governed-workflow:run
```

No credentials. No network access. Deterministic output:

```
✓ Low-risk claim: ready-for-review
✓ Restricted claim: rejected
✓ High-risk unapproved claim: rejected
✓ High-risk approved claim: ready-for-review
```

From there, explore deeper:

- [README](../../README.md) — product overview and governed execution model
- [Governed Workflow Quickstart](../guides/governed-workflow-quickstart.md) — conceptual walkthrough
- [Project Status](../STATUS.md) — detailed implementation maturity
- [Product Positioning](../product/positioning.md) — canonical audiences, pillars, and boundaries
- [Source workflow](../../examples/governed-workflow) — the runnable claim-triage implementation
