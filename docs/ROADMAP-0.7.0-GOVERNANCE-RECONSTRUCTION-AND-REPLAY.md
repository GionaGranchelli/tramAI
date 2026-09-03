# TramAI 0.7.0 — Governance Reconstruction and Replay

> **Status:** Draft roadmap slice  
> **Target release:** TramAI 0.7.0  
> **Relationship:** Complements the control-plane semantic timeline, incident reconstruction/evidence packs, policy simulation, named trust zones, classification, approvals, and policy-aware provider/model selection  
> **Scope:** Make governed decisions reconstructable and deterministic governance decisions replayable without re-executing model/tool side effects.

---

## 1. Decision

TramAI 0.7.0 should distinguish three different capabilities that are often conflated under the word **replay**:

1. **Forensic reconstruction** — reconstruct what happened from durable authoritative evidence, without executing anything.
2. **Governance / policy replay** — re-evaluate a recorded governance-decision context against the historical policy/configuration semantics and compare the result with the recorded decision.
3. **Sandbox execution replay** — optionally re-run application/model flow later under explicit isolation, with provider/tool side effects blocked, stubbed, or otherwise controlled.

The 0.7.0 priority is the first two. Full execution replay is not a deterministic product guarantee and is not a 0.7.0 blocker.

The product promise should be:

> **Any authoritative governed decision can be reconstructed, and deterministic governance decisions can be replayed against their recorded decision context without re-executing external side effects.**

TramAI must not claim that arbitrary LLM executions can always be reproduced byte-for-byte.

---

## 2. Why this matters

Governance incidents rarely start with the question:

> "Can we regenerate exactly the same tokens?"

They start with questions such as:

- Why was this document allowed to reach this provider deployment?
- Which classification was effective and where did it come from?
- Which policy/configuration was active?
- Which named trust zones and provider deployments were candidates?
- Why was each candidate eligible or rejected?
- Which fallback rules were considered?
- Which semantic validators ran and what did they reject?
- Which tool was requested and what risk/permission metadata applied?
- Why was approval required or not required?
- Who authorized the action?
- Which runtime version and governance semantics produced the decision?
- Can the same deterministic governance inputs still produce the same decision?

TramAI already has audit/evidence foundations. 0.7.0 should turn those foundations into an explicit reconstruction and replay contract instead of treating audit records as an append-only history that operators must interpret manually.

---

## 3. Replay modes

### 3.1 RECONSTRUCT — P0

`RECONSTRUCT` is a read-only forensic operation.

It performs **zero provider, tool, workflow, network, or approval side effects**.

Conceptually:

```text
runId
  ↓
authoritative runtime/audit/evidence records
  ↓
ordered semantic reconstruction
  ↓
GovernanceTimeline / IncidentView
```

A reconstruction should be able to show, where evidence exists:

```text
Request received
  ↓
Workload/version identified
  ↓
Classification = CONFIDENTIAL
source = DECLARED / document metadata
  ↓
Policy/configuration identity resolved
  ↓
Candidate provider deployments
  ├─ openai-global      REJECTED: CLASSIFICATION_ZONE_DENIED
  ├─ azure-eu-prod      ELIGIBLE
  └─ local-amsterdam    ELIGIBLE
  ↓
Selected = azure-eu-prod
reason = configured preference among eligible candidates
  ↓
Structured semantic validation
  ↓
Tool requested = schedulePayment / HIGH
  ↓
Approval required
  ↓
Approved by authorized actor
  ↓
Tool execution completed
  ↓
Run completed
```

Reconstruction is evidence presentation, not re-execution.

### 3.2 POLICY_REPLAY — P0/P1

`POLICY_REPLAY` re-evaluates a recorded deterministic governance-decision context using the same authoritative evaluation contract used by execution.

Conceptually:

```text
recorded decision context
        +
historical policy/configuration identity
        +
compatible evaluator semantics
        ↓
production governance evaluator
        ↓
replayed decision
        ↓
compare with recorded decision
```

For deterministic supported cases, the core invariant is:

```text
policyReplay(recordedDecisionContext).decision
    == recordedGovernanceDecision
```

A replay mismatch is not silently normalized. It is a first-class finding that may indicate:

- incomplete historical evidence;
- changed evaluator semantics;
- incompatible runtime version;
- policy/configuration corruption or mismatch;
- serialization/normalization drift;
- hidden nondeterministic policy behaviour;
- an implementation defect.

Policy replay executes no model and no external tool.

### 3.3 SANDBOX_REPLAY — later / P2

Sandbox execution replay may later help developers or incident responders reproduce a broader application flow.

It must not be confused with deterministic governance replay.

Requirements if implemented:

- disabled by default;
- explicitly isolated from production side effects;
- tool invocations blocked, stubbed, simulated, or resolved from recorded safe fixtures;
- provider invocation either disabled, mocked, or explicitly authorized;
- network access independently controlled;
- approvals simulated rather than mutating production approval state;
- resulting run clearly marked as a replay/simulation and never represented as original enforcement evidence.

Example:

```text
historical run
   ↓
recorded safe inputs/references
   ↓
sandbox
   ↓
provider mock / pinned model where available
   ↓
tool call intercepted
   ↓
recorded result or deterministic stub
   ↓
comparison output
```

A payment, deletion, email, deployment, database mutation, or other side effect must never be repeated merely because a historical run is being replayed.

---

## 4. Decision replay snapshot

To make governance replay possible, TramAI must record the inputs necessary to explain and re-evaluate each authoritative governance decision.

The exact API names are not frozen, but the conceptual model should include a safe immutable snapshot such as:

```text
DecisionReplaySnapshot
  decisionId
  workflowRunId / correlationId
  workloadId
  workloadVersion / configuration digest
  operation identity / fingerprint
  runtime version
  evaluator contract/semantic version

  classification
  classification source
  classification decision/mapping identity where relevant

  provider/model candidate identities
  provider deployment identities
  named trust-zone identities
  trust-zone categories

  policy version
  policy digest
  policy-scope contributions
  effective policy/configuration digest

  tool security metadata where relevant
  actor/authorization context where relevant
  approval requirement inputs where relevant
  semantic-validator identities/codes where relevant

  recorded decision
  stable reason codes / reason path
```

The snapshot is not required to contain raw prompts, raw documents, credentials, full tool arguments, or arbitrary sensitive payloads.

---

## 5. Historical identity must be content-addressable

A human-readable version such as:

```text
policyVersion = "17"
```

is useful but insufficient for replay.

Where feasible, replay-critical configuration must also have an immutable digest or equivalent content identity:

```text
policyVersion = "17"
policyDigest = sha256:...
trustTopologyDigest = sha256:...
classificationMappingDigest = sha256:...
workloadConfigurationDigest = sha256:...
```

The runtime must preserve enough information to distinguish:

> "policy named v17 today"

from:

> "the exact policy/configuration content that produced this historical decision."

0.7.0 should define retention/lookup contracts for replay-critical configuration artifacts or immutable snapshots rather than assuming current configuration can reconstruct historical state.

---

## 6. Evaluator semantic identity

Policy/configuration identity alone is not sufficient if evaluation semantics change between runtime releases.

A replay record should therefore identify the relevant runtime/evaluator semantics, for example through:

- TramAI runtime version/build identity;
- evaluator contract version;
- policy schema version;
- decision-snapshot schema version;
- compatibility metadata needed to determine whether replay is authoritative, compatible, partial, or unavailable.

A future implementation may retain historical evaluator artifacts, migrate historical snapshots through explicit compatibility rules, or declare a replay unsupported when exact semantic compatibility cannot be established.

TramAI must prefer an explicit **not replayable with current evaluator** result over presenting a potentially different modern evaluation as if it reproduced history.

---

## 7. Reconstruction evidence model

The audit/evidence model should record decision inputs and reason paths, not only outcomes.

Insufficient:

```text
provider = azure-eu-prod
```

Preferred:

```text
classification = CONFIDENTIAL
classificationSource = DECLARED
policyDigest = sha256:...

candidate openai-global
  zone = global-cloud
  decision = REJECTED
  reason = CLASSIFICATION_ZONE_DENIED

candidate azure-eu-prod
  zone = eu-production
  category = EU_CLOUD
  decision = ELIGIBLE

candidate local-amsterdam
  zone = local-amsterdam
  category = LOCAL
  decision = ELIGIBLE

selected = azure-eu-prod
selectionReason = configured preference among eligible candidates
```

The control plane should be able to show the contribution of organization/environment/workload policy scopes where policy composition applies.

---

## 8. Named trust zones and replay

Named trust zones make replay materially more useful because a coarse category alone cannot explain the historical deployment boundary.

A reconstruction should preserve both:

```text
providerDeployment = azure-openai-prod-westeurope
namedTrustZone = azure-eu-production
trustZoneCategory = EU_CLOUD
```

Provider brand is not sufficient historical evidence.

If a provider is later moved to a different named trust zone, historical reconstruction must continue to show the zone that applied at decision time.

---

## 9. Classification and document metadata replay

When a classification decision affects routing, reconstruction should retain safe provenance such as:

```text
classification = RESTRICTED
classificationSource = DECLARED
classifier/detector = document-metadata
externalLabel = SECRET          # only if policy permits safe retention
mappingVersion/digest = ...
```

Raw document content is not required for governance replay when the replayed decision depends only on the authoritative classification result and its safe provenance.

Missing historical classification evidence must not be silently replaced with today's inferred/default classification.

---

## 10. Structured semantic validation replay

Where a structured-output semantic validator affects acceptance, reconstruction should expose safe evidence such as:

```text
validator = PaymentDecisionValidator
violationCode = RISK_THRESHOLD
path = risk
attempt = 1
repairRequested = true
```

Raw candidate values and sensitive invocation arguments remain subject to separate retention policy.

Governance reconstruction must be able to prove that an invalid candidate did not cross the authoritative typed boundary without requiring the invalid payload itself to be globally queryable.

---

## 11. Approval and tool replay boundary

Approval reconstruction should distinguish historical evidence from a new approval action.

Policy replay may reproduce:

```text
REQUIRE_APPROVAL
```

but it must not create, approve, consume, or replay a production approval token.

Similarly, replay may reconstruct:

```text
tool = schedulePayment
risk = HIGH
permission = payments.schedule
approvalRequired = true
```

without executing `schedulePayment` again.

If sandbox replay later supports recorded tool responses, those responses must be explicitly marked as replay fixtures and must not be confused with newly executed production effects.

---

## 12. Privacy and retention

Replay must not become a justification for indiscriminate sensitive-data retention.

Default audit/replay evidence should prefer:

- stable IDs;
- digests;
- classifications;
- policy/configuration versions and content identities;
- safe reason codes;
- named trust-zone/provider-deployment identities;
- model/provider identity;
- validator identities/codes;
- approval/tool decision metadata;
- protected references to separately governed evidence where required.

It should avoid storing by default:

- raw prompts;
- raw completions;
- full document content;
- credentials/secrets;
- unrestricted tool arguments/results;
- sensitive internal diagnostics.

Where an organization explicitly retains sensitive replay material, retention, encryption, access control, deletion, and privileged reveal must be independently governed and audited.

Core principle:

> **Auditability must not silently become data duplication.**

---

## 13. Replayability status

Not every historical decision will necessarily be replayable forever.

The control plane should distinguish statuses such as:

```text
RECONSTRUCTABLE
POLICY_REPLAYABLE
PARTIALLY_REPLAYABLE
NOT_REPLAYABLE
```

with stable reasons, for example:

```text
HISTORICAL_POLICY_MISSING
EVALUATOR_VERSION_UNSUPPORTED
DECISION_CONTEXT_INCOMPLETE
SENSITIVE_EVIDENCE_NOT_RETAINED
EXTERNAL_STATE_REQUIRED
```

A missing prerequisite must never be rendered as a successful deterministic replay.

---

## 14. Control-plane experience

A run/incident view should eventually support:

```text
Run 8f21

Timeline
  classification
  policy evaluation
  candidate eligibility
  route selection
  structured validation
  tools
  approvals
  outcome

Historical context
  workload version
  runtime version
  policy digest
  topology digest
  classification mapping digest

Replay
  Reconstruction: AVAILABLE
  Policy replay: PASS
  Execution replay: NOT SUPPORTED
```

When a policy replay differs:

```text
Policy replay: MISMATCH

Recorded: ALLOW azure-eu-prod
Replayed: DENY
Reason: evaluator semantic incompatibility / decision mismatch
```

The dashboard must consume authoritative replay/query APIs and must not implement replay semantics itself.

---

## 15. Relationship to policy simulation

Policy simulation and policy replay reuse the same authoritative evaluator but answer different questions.

Simulation asks:

> **What would TramAI decide for this hypothetical/current input?**

Replay asks:

> **Given the recorded historical decision context, can TramAI reproduce the historical deterministic governance decision?**

Conceptually:

```text
simulate(hypotheticalInput)
    -> current/hypothetical decision

replay(recordedHistoricalContext)
    -> historical-decision verification
```

Neither mode executes providers/tools by default.

---

## 16. Tasks

### P0 — forensic reconstruction

1. Define a stable decision/run reconstruction model derived from authoritative runtime/audit/evidence sources.
2. Define ordering/correlation rules across runtime events, policy decisions, approvals, structured validation, provider routing, tools, and evidence.
3. Define safe reason-path projection into the control plane.
4. Define explicit evidence-gap semantics rather than silently inventing missing history.
5. Integrate reconstruction with incident views/evidence packs.

### P0/P1 — deterministic governance replay

1. Define `DecisionReplaySnapshot` or equivalent immutable decision-context contract.
2. Record policy/configuration content identities/digests needed for supported replay scenarios.
3. Record runtime/evaluator semantic identity needed to establish replay compatibility.
4. Reuse the production policy/eligibility evaluator; do not implement a second replay evaluator.
5. Add replay result/status/reason models.
6. Add deterministic comparison between recorded and replayed decisions.
7. Prove policy replay performs no provider/tool/approval side effects.
8. Add governance-contract tests and mutation tests for omitted inputs, changed reason paths, policy drift, and false replay success.

### Later / P2 — sandbox execution replay

1. Define isolated execution semantics.
2. Integrate tool-effect metadata.
3. Require side-effect interception/stubbing.
4. Distinguish replay-generated evidence from original runtime enforcement evidence.

---

## 17. Acceptance criteria

### Reconstruction

- A governed run can be reconstructed into an ordered semantic timeline when required evidence exists.
- The timeline identifies authoritative evidence versus informational/best-effort telemetry.
- Missing evidence is explicit.
- Historical provider-deployment and named trust-zone identities are preserved.
- Historical policy/configuration identity is preserved.
- Reconstruction performs zero side effects.

### Policy replay

- Supported deterministic decisions can be re-evaluated using the production governance evaluator.
- Replay compares the re-evaluated decision with the recorded decision.
- A mismatch is a first-class result, not silently accepted.
- Replay compatibility with historical evaluator semantics is explicit.
- Policy replay invokes no real model, provider, tool, network action, approval transition, or workflow side effect.
- Preference/fallback replay cannot expand historical policy eligibility.
- Missing replay-critical evidence produces `NOT_REPLAYABLE`/equivalent rather than guessed inputs.

### Privacy

- Replay-critical metadata has a safe default projection.
- Raw sensitive payload retention remains separate and explicitly governed.
- Auditability does not require raw prompts/documents/tool arguments to be globally retained.

---

## 18. Contract tests and mutation evidence

Required tests should include:

1. Same snapshot + same compatible evaluator semantics -> same governance decision.
2. Changed policy digest -> replay is not represented as replay of the original policy.
3. Changed trust-zone mapping -> historical replay still uses recorded historical topology identity.
4. Missing policy artifact/context -> explicit replay-unavailable result.
5. Missing classification evidence -> no fallback to current/default classification.
6. Replay never invokes provider.
7. Replay never invokes tool.
8. Replay never creates/consumes approval state.
9. Reconstructed timeline retains stable ordering/correlation.
10. Sensitive raw payloads are absent from default replay evidence.
11. Mutation skipping one policy-scope contribution is detected.
12. Mutation replacing historical named trust zone with current zone is detected.
13. Mutation that converts mismatch into success is killed.
14. Mutation that accidentally executes a side-effecting tool during replay is killed.

---

## 19. Non-goals

This roadmap slice does not make TramAI:

- a deterministic LLM-token reproduction system;
- a general event-sourcing framework;
- a database time-machine product;
- a recorder of every application object by default;
- a mechanism for automatically repeating historical external side effects;
- a replacement for infrastructure snapshots or provider-specific model reproducibility guarantees.

---

## 20. Recommended 0.7.0 boundary

Minimum credible scope:

1. **P0 forensic reconstruction contract** for governed runs and incidents.
2. **P0/P1 immutable decision replay snapshot** for deterministic governance decisions.
3. **Policy/configuration/runtime semantic identity** sufficient to establish whether replay is valid.
4. **Production-evaluator policy replay** with zero external side effects.
5. **Explicit replayability/mismatch states** rather than optimistic claims.
6. **Privacy-safe evidence defaults** that do not require raw sensitive payload retention.
7. **TCK/mutation evidence** proving reconstruction/replay cannot invent history or execute side effects.

Sandbox/full execution replay remains a later capability.

---

## 21. Core invariants

```text
RECONSTRUCT(runId)
    performs zero execution side effects
```

```text
POLICY_REPLAY(snapshot)
    performs zero provider/tool/approval side effects
```

```text
for supported deterministic contexts:
replayedDecision == recordedDecision
```

```text
missing historical evidence
    != permission to substitute current/default state
```

```text
replayability cannot be claimed
    unless policy/configuration/evaluator compatibility is established
```

```text
auditability
    != indiscriminate sensitive-payload retention
```

> **Reconstruct what happened. Replay what is deterministic. Never repeat side effects by accident.**
