# TramAI 0.7.0 — Governed AI Control Plane

> **Status:** Release roadmap  
> **Target release:** TramAI 0.7.0  
> **Baseline:** TramAI 0.6.x hardened runtime  
> **Primary objective:** Turn TramAI's governed runtime primitives into an operational control plane that lets authorized operators observe, explain, control, and reconstruct governed AI workloads.  
> **Authoritative scope:** [`ROADMAP-0.7.0-RELEASE-CUT.md`](ROADMAP-0.7.0-RELEASE-CUT.md)  
> **Long-term sequence:** [`LONG-TERM-ROADMAP-0.7-0.10.md`](LONG-TERM-ROADMAP-0.7-0.10.md)

---

## 1. Executive decision

0.7.0 is a **governance operations and control-plane release**.

> **The model proposes. TramAI decides. The control plane shows why.**

0.6 focuses on making the runtime safer to change, harder to misuse, and more internally consistent.

0.7 cashes in on that work by making governed execution visible and controllable through one authoritative operational surface.

A platform/security/operator should be able to answer:

- What governed AI workloads exist?
- Who owns them and which version/configuration is running?
- What data classification is effective?
- Which provider deployments/trust zones are authorized?
- Why was a provider/model allowed, rejected, or selected?
- Which actions are waiting for approval?
- What happened during a particular run?
- Can an authorized operator suspend/quarantine/reactivate supported workloads?
- What authoritative evidence exists for later review?

0.7.0 does **not** attempt to finish developer governance UX, enterprise deployment productization, governed learning, or rich optimization. Those now have explicit later release themes.

---

## 2. Product progression

| Release | Theme |
|---|---|
| **0.7.0** | Governed AI Control Plane |
| **0.8.0** | Governance DX & Intelligence |
| **0.9.0** | Enterprise Deployment & Security |
| **0.10.0** | Governed Learning & Optimization |

The 0.7 implementation must preserve architectural room for later releases without absorbing their implementation scope.

---

## 3. 0.7 operational loop

```text
classify → constrain → authorize → select → execute
        → evidence → observe → control → reconstruct
```

### Release narrative

Before 0.7:

> TramAI governs execution.

After 0.7:

> TramAI also gives operators an authoritative way to see, explain, and control that governance.

---

## 4. Release principles

### 4.1 Runtime authority before UI convenience

The dashboard never directly mutates authoritative persistence or implements policy logic.

It consumes control-plane query APIs and issues typed commands through authentication, authorization, validation, and runtime authority boundaries.

### 4.2 Workload identity before operational governance

A control-plane workload has authoritative identity/version/configuration identity and enough ownership/purpose metadata to attribute runs and controls correctly.

### 4.3 Classification before provider exposure

Classification is resolved before provider/model eligibility is finalized.

Unknown/missing state is not silently permissive where policy requires classification.

### 4.4 Policy before selection

```text
authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
viable     = authorized ∩ required runtime constraints
selected   = selectionStrategy(viable)
```

Selection, fallback, availability, health, cost, latency, and preference can never create governance authority.

### 4.5 Explain decisions, not only outcomes

The control plane exposes safe structured reason paths for supported governance decisions.

Opaque frontend text is not the authoritative explanation model.

### 4.6 Safe metadata by default

Prompts, completions, credentials, tool arguments/results, document contents, and other sensitive payloads are not exposed through generic control-plane telemetry by default.

### 4.7 Headless first

Every 0.7 control-plane capability remains usable through APIs without Dashboard 2.0.

### 4.8 Evidence over compliance claims

TramAI can preserve and expose technical-control evidence. 0.7 does not claim legal certification or automatic regulatory compliance.

---

# Phase 0 — Control-plane authority contract

## Goal

Define the read/write authority boundary before building UI behavior around it.

## Required work

1. Define authoritative sources for workload state, approvals, policy decisions, provider routing, runs, workers/schedules where exposed, controls, and evidence.
2. Define control-plane projection/read-model boundaries.
3. Define which views are strongly consistent, eventually consistent, or informational.
4. Define stale-view command protection using version/etag/precondition semantics where applicable.
5. Define safe field-level exposure categories.
6. Keep module dependency direction explicit.

## Acceptance criteria

- Dashboard can be replaced without changing governance semantics.
- No write path bypasses runtime authorization/policy.
- Sensitive content has no generic default query surface.

---

# Phase 1 — Governed workload identity

## Goal

Give each independently governed control-plane workload authoritative identity and version/configuration identity.

A workload may represent an AI service, workflow, scheduled workflow, worker, agent loop, MCP-exposed flow, or another independently governed execution surface.

## Minimum supported metadata

- id;
- name;
- version/configuration identity;
- owner;
- purpose;
- environment/deployment identity;
- lifecycle state;
- governance metadata required by the supported profile.

## Acceptance criteria

- Runs are attributable to workload identity/version.
- Distinct deployments cannot collapse into one authoritative identity accidentally.
- Lifecycle/control transitions are auditable.

---

# Phase 2 — Classification, trust topology, and restrictive policy

## Goal

Resolve governance constraints before provider exposure/selection.

### Trust topology

Retain portable coarse categories such as:

```text
LOCAL
EU_CLOUD
GLOBAL_CLOUD
```

while allowing concrete provider deployments to be associated with organization-defined named trust zones.

Provider brand alone never proves locality/trust.

### Policy composition

```text
organization ∩ environment ∩ workload = effective policy
```

Lower scopes cannot silently widen higher-level authority.

### Classification

Classification is resolved before provider/model authorization.

The 0.7 slice should use existing explicit/rule-based mechanisms plus only the additional integration needed for the control-plane loop. Rich classification UX belongs later.

---

# Phase 3 — Explainable provider/model authorization and selection

## Goal

Turn routing into an explicit explainable governance decision.

For each supported candidate expose safe structured state such as:

```text
AUTHORIZED
NOT_AUTHORIZED(reason)
SELECTED(reason)
NOT_SELECTED(reason)
```

Runtime non-viability may be represented separately where authoritative data exists.

## Core invariants

```text
selected ∈ viable
viable ⊆ authorized
```

Fallback/retry re-evaluates within current governance authority.

## Acceptance criteria

- A policy-ineligible provider cannot become selected through fallback/preference.
- Selected and rejected routes expose stable safe reason paths.
- Historical routing evidence identifies workload/configuration/policy context sufficiently for supported reconstruction.

---

# Phase 4 — Authoritative governance evidence and read model

## Goal

Project runtime truth into operational views without turning observability into a policy engine.

## Required evidence dimensions

Where applicable:

- workload/run identity;
- classification/source;
- policy/config identity;
- provider deployment/trust-zone decision;
- selection reason;
- approval/tool governance decision;
- actor/control identity;
- relevant lifecycle transitions.

## Projection requirements

- idempotent application;
- ordering/version handling;
- explicit projection lag;
- rebuild/durability semantics;
- safe OpenTelemetry mapping where useful, while OTEL remains non-authoritative.

---

# Phase 5 — Semantic execution timeline and forensic reconstruction

## Semantic timeline

Example:

```text
Request received
  ↓
Classification: CONFIDENTIAL
  ↓
Effective policy resolved
  ↓
Global deployment rejected
  ↓
EU deployment selected
  ↓
High-risk tool requested
  ↓
Approval required
  ↓
Approved by authorized actor
  ↓
Tool execution completed
  ↓
Workflow completed
```

Missing evidence remains explicit.

## Forensic reconstruction

When sufficient evidence exists, TramAI reconstructs historical governed execution without invoking:

- providers;
- tools;
- approvals;
- network actions;
- workflow side effects.

Current configuration is never silently substituted for historical configuration.

---

# Phase 6 — Authentication, authorization, and safe runtime control

## Authentication/authorization

0.7 requires an identity-provider-neutral OIDC/Spring Security integration boundary and server-side capability-based authorization.

Candidate baseline capabilities/roles may include viewer/operator/approver/auditor/security/platform administration distinctions, but exact role names are not the core contract.

## Required privileged-command properties

Every successful privileged action includes or derives:

- authenticated actor;
- authorization result;
- target workload/version/precondition;
- supported reason metadata;
- previous/resulting state;
- audit/runtime evidence linkage.

## Initial control candidates

- suspend;
- quarantine;
- reactivate;
- retire;
- cancel execution where semantics permit.

Broader policy-authoring/change workflows belong later unless required for the supported 0.7 profile.

---

# Phase 7 — Dashboard 2.0

## Goal

Make the P0 control-plane loop usable through a first-party governance client.

## Minimum surface

### Workload inventory/detail

- identity/version/owner/purpose/environment;
- lifecycle state;
- effective governance posture;
- authorized/rejected provider deployments and reasons;
- recent runs;
- approvals/runtime state;
- evidence/reconstruction links;
- authorized controls.

### Semantic run view

Show typed governance transitions rather than raw logs only.

### Approval surface

Where approvals apply, expose safe request context, actor requirements, policy reason, status/lifetime information supported by current semantics, and authoritative approve/deny actions.

### Controls

Only actions authorized by server-side control APIs are executable.

## Acceptance criteria

- Dashboard consumes documented control-plane APIs.
- UI contains no policy engine.
- Sensitive payloads remain hidden by default.
- UI cannot bypass server-side authorization.

---

# 8. Reference 0.7 workflow

Use one end-to-end scenario across implementation, docs, demos, and release verification.

```text
Sensitive document/request enters
        ↓
Classification resolves CONFIDENTIAL
        ↓
Effective policy composed
        ↓
Global provider deployment rejected
        ↓
Allowed local/EU route selected
        ↓
Model requests high-risk tool
        ↓
Existing policy requires approval
        ↓
Approval appears in control plane
        ↓
Authorized actor approves
        ↓
Tool executes
        ↓
Execution completes
        ↓
Dashboard shows semantic governance trace
        ↓
Authorized operator can issue supported control
        ↓
Execution can later be reconstructed from evidence
```

## Acceptance criteria

- Policy-ineligible provider never receives the governed request.
- Required approval cannot be bypassed.
- Approval/control action is attributable to an authorized actor.
- Timeline explains classification, routing, approval, execution, and control where applicable.
- Reconstruction does not repeat side effects.

---

# 9. Explicitly deferred to later releases

## 0.8 — Governance DX & Intelligence

- Workflow DSL 2.0;
- policy simulation;
- deterministic public policy replay;
- governance debugger;
- governance contract testing;
- Governance Vocabulary + Governance Facts public foundation;
- semantic structured-output expansion;
- provider-neutral required-tool expansion;
- tool-obligation refinement;
- richer metadata classification;
- richer findings/incident analysis;
- approval-lifecycle refinements beyond P0 safety.

## 0.9 — Enterprise Deployment & Security

- Entra/Okta/Keycloak productization;
- key rotation and KMS/Vault/HSM breadth;
- Docker Compose reference profile;
- Helm;
- optional Kubernetes operator;
- Spring Boot five-minute adoption layer;
- enterprise deployment/security/CISO packaging.

See [`ROADMAP-0.9.0-ENTERPRISE-DEPLOYMENT-AND-SECURITY.md`](ROADMAP-0.9.0-ENTERPRISE-DEPLOYMENT-AND-SECURITY.md).

## 0.10 — Governed Learning & Optimization

- governed learning traces;
- privacy-safe dataset capture/export;
- rich FinOps;
- cost/latency/quality/capacity/budget optimization;
- richer adaptive routing strategies;
- training/evaluation integrations where justified.

---

# 10. Release gates

0.7.0 must not ship with a known supported-profile path where:

- classification occurs after provider exposure;
- lower policy scopes widen higher-level denial;
- fallback/selection escapes governance authorization;
- provider brand is treated as proof of trust/location;
- dashboard mutates authoritative stores directly;
- UI visibility substitutes for authorization;
- stale control commands overwrite newer state;
- generic telemetry leaks protected payloads;
- projection state is shown as stronger authority than its source;
- reconstruction repeats external side effects;
- current configuration is substituted for missing historical evidence.

---

# 11. Success definition

TramAI 0.7.0 is successful when it can demonstrate, end to end:

> A governed workload receives sensitive input; TramAI resolves classification before provider exposure; restrictive policy determines authorized provider deployments; an authorized execution route is selected with an explainable reason; approval/runtime state is projected into the control plane; an authenticated and authorized operator can inspect and control the workload through typed APIs; Dashboard 2.0 exposes those capabilities without becoming an authority; and the execution can later be reconstructed from authoritative evidence without repeating side effects.

> **0.7.0 makes governed AI execution visible, explainable, controllable, and reconstructable.**
