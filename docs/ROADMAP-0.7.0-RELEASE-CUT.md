# TramAI 0.7.0 — Authoritative Governance Control Plane Release Cut

> **Status:** Authoritative release boundary for TramAI 0.7.0  
> **Target release:** TramAI 0.7.0  
> **Purpose:** Define the smallest coherent 0.7.0 product increment.  
> **Priority rule:** This file decides what blocks 0.7.0. Detailed companion documents may explore later capabilities, but they cannot expand this release boundary unless this file is explicitly changed.

---

## 1. Release thesis

TramAI 0.7.0 turns the hardened 0.6.x runtime into an operational governed-AI control plane.

> **The model proposes. TramAI decides. The control plane shows why.**

Before 0.7, TramAI can govern execution.

After 0.7, an authorized operator can **see, understand, control, and reconstruct** that governance through one headless control-plane contract and a first-party Dashboard 2.0 client.

0.7.0 is successful when the following story is coherent end to end:

```text
identify → classify → constrain → authorize → select → execute
        → evidence → observe → control → reconstruct
```

---

## 2. Long-term roadmap relationship

The broader architecture work is intentionally split across later releases:

| Release | Theme |
|---|---|
| **0.7.0** | Governed AI Control Plane |
| **0.8.0** | Governance DX & Intelligence |
| **0.9.0** | Enterprise Deployment & Security |
| **0.10.0** | Governed Learning & Optimization |

See [`LONG-TERM-ROADMAP-0.7-0.10.md`](LONG-TERM-ROADMAP-0.7-0.10.md).

A later feature may impose an architecture constraint on 0.7 without becoming a 0.7 implementation commitment.

---

# 3. P0 — Must-Ship 0.7.0 Boundary

## P0.1 Governed workload identity and version

Every independently governed execution surface in control-plane mode has authoritative workload identity, version/configuration identity, owner/purpose metadata sufficient for the supported profile, and run correlation.

Distinct deployments must not silently collapse into one authoritative identity.

## P0.2 Classification before provider exposure

Classification is resolved before provider/model eligibility is finalized.

Explicit stronger classifications cannot be silently downgraded by weaker signals.

Unknown/missing classification state must not be interpreted as permissive where policy requires classification.

## P0.3 Named trust zones and provider-deployment identity

`LOCAL`, `EU_CLOUD`, and `GLOBAL_CLOUD` remain portable coarse categories.

Concrete provider deployments may be assigned to organization-defined named trust zones.

Provider brand alone is never proof of trust, residency, or locality.

## P0.4 Restrictive policy composition

Organization, environment, and workload constraints compose restrictively:

```text
organization ∩ environment ∩ workload = effective policy
```

A lower scope cannot silently widen a higher-level denial.

## P0.5 Policy-aware provider/model authorization and selection

The runtime preserves distinct stages:

```text
authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
viable     = authorized ∩ required runtime constraints
selected   = selectionStrategy(viable)
```

Core invariants:

```text
selected ∈ viable
viable ⊆ authorized
```

Preference, fallback, health, availability, cost, latency, or other runtime signals may never create governance authority.

0.7.0 requires only the selection semantics needed for a coherent governed control plane. Rich adaptive optimization belongs to the later roadmap.

Rejected/non-selected candidates expose safe stable reason paths sufficient to explain governance denial, runtime non-viability where authoritative, and selection.

## P0.6 Authoritative governance decision and evidence model

The runtime emits typed evidence sufficient to explain supported decisions, including where applicable:

- workload/run identity;
- effective classification and source;
- policy/configuration identity;
- candidate provider deployments;
- named zone/category;
- authorization/rejection reason;
- selected route and reason;
- relevant tool/approval decisions;
- actor/control correlation.

Raw sensitive payloads remain excluded by default.

## P0.7 Control-plane projection and query API

Authoritative runtime state/events project into a stable read model.

The dashboard consumes control-plane APIs instead of reconstructing governance independently from unrelated stores.

Projection lag, consistency, and source authority are explicit.

## P0.8 Semantic execution timeline

A governed run is rendered as a typed governance timeline rather than a flat log.

Authoritative evidence and best-effort telemetry remain distinguishable.

Missing evidence is explicit rather than inferred as success.

## P0.9 Authentication, authorization, and safe runtime control

The control plane supports an identity-provider-neutral OIDC/Spring Security boundary and capability-based server-side authorization for privileged operations.

Every privileged command must include or derive:

- authenticated actor;
- authorization decision;
- workload identity and expected version/precondition;
- supported reason information;
- previous/resulting state;
- audit/runtime evidence linkage.

The dashboard never mutates authoritative stores directly and never becomes a second policy engine.

## P0.10 Forensic reconstruction

When required evidence exists, TramAI can reconstruct a historical governed run without invoking providers, tools, approvals, network actions, or workflow side effects.

Historical policy/topology/classification identities are not silently replaced by current defaults.

Deterministic public policy-replay tooling is a later release concern unless a narrow internal mechanism is necessary for P0 reconstruction correctness.

## P0.11 Dashboard 2.0 core governance surface

The first-party dashboard makes the P0 loop usable while remaining a client of control-plane APIs.

Minimum supported surface:

- workload inventory/detail;
- effective governance posture;
- provider/model authorization and selection reasons where supported;
- semantic timeline;
- approval/runtime state where applicable;
- authorized lifecycle/control actions;
- reconstruction/evidence availability.

The dashboard is replaceable without changing governance semantics.

## P0.12 Release proof and compatibility discipline

P0 behavior is protected by deterministic tests, compatibility/TCK coverage where contracts cross modules, security gates, safe telemetry tests, and mutation evidence for high-value invariants.

0.7.0 must not ship with a known bypass through:

- classification timing;
- policy composition;
- provider selection/fallback;
- stale projection presented as authority;
- dashboard-only authorization;
- stale privileged commands;
- approval authority;
- reconstruction side effects;
- sensitive payload exposure.

---

# 4. Explicitly not part of 0.7.0

The following do **not** block 0.7.0 unless implementation proves that a narrow slice is required for P0 security/correctness:

## Targeted primarily at 0.8.0 — Governance DX & Intelligence

- Workflow DSL 2.0 and broad state/context ergonomics;
- policy simulation / decision preview;
- public deterministic policy replay;
- developer-local governance debugger;
- governance contract-testing UX;
- Governance Vocabulary + Governance Facts public foundation/extension model;
- semantic structured-output expansion;
- typed provider-neutral required-tool contracts;
- dynamic tool-obligation lifecycle refinement;
- broader metadata-classification ergonomics;
- richer deterministic findings/incident analysis;
- approval lifetime/replacement redesign beyond existing safety requirements.

## Targeted primarily at 0.9.0 — Enterprise Deployment & Security

- Entra/Okta/Keycloak productized compatibility beyond generic OIDC;
- native/direct SAML stack;
- key rotation engine and broad KMS/Vault/HSM adapters;
- Docker Compose reference product profile;
- Helm packaging;
- Kubernetes operator;
- Spring Boot five-minute adoption layer beyond what P0 implementation itself requires;
- enterprise deployment/security/CISO packaging.

## Targeted primarily at 0.10.0 — Governed Learning & Optimization

- governed learning traces;
- raw-content dataset capture/export;
- evaluation/training dataset productization;
- rich adaptive routing/FinOps optimization;
- broad cost/latency/quality/capacity/budget strategies;
- machine-learned routing;
- managed training/fine-tuning infrastructure.

---

# 5. Architecture commitments required now

Later work constrains 0.7 design only where failing to preserve the boundary would create future incompatibility or weaken correctness.

## A. Identity-provider neutrality

P0 authorization is capability based and does not encode Entra-, Okta-, or Keycloak-specific authority semantics into core governance.

## B. Cryptographic continuity

Where encrypted persisted records already carry cryptographic identity/version metadata, 0.7 changes must not destroy the ability to introduce key rotation later.

0.7 does not need to implement the rotation engine.

## C. Authoring-surface neutrality

Runtime governance semantics cannot depend on one future DSL, annotation catalogue, or YAML representation.

## D. Structured explanation

Reason paths remain structured/stable enough for future simulation, contract testing, debuggers, and evidence without screen-scraping UI text.

## E. Replay-compatible evidence

Historical configuration/decision identities required for later deterministic replay are not silently discarded.

## F. Learning privacy separation

Audit and ordinary telemetry must not become implicit learning-data capture.

## G. Selection-stage separation

Authorization, viability, and optimization remain semantically distinct so later FinOps/adaptive routing cannot widen governance authority.

## H. Headless operation

Every P0 control-plane capability remains usable without Dashboard 2.0.

---

# 6. Critical dependency graph

```text
A. WORKLOAD IDENTITY / CONFIGURATION IDENTITY
        │
        ├───────────────┐
        ▼               ▼
B. CLASSIFICATION   C. NAMED TRUST TOPOLOGY
        │               │
        └───────┬───────┘
                ▼
D. RESTRICTIVE EFFECTIVE POLICY
                │
                ▼
E. AUTHORIZED CANDIDATE SET + REASON PATHS
                │
                ▼
F. POLICY-CONSTRAINED SELECTION
                │
                ▼
G. EXECUTION + AUTHORITATIVE EVIDENCE
                │
                ▼
H. CONTROL-PLANE PROJECTION + QUERY API
                │
        ┌───────┼───────────┐
        ▼       ▼           ▼
I. TIMELINE  J. CONTROL  K. RECONSTRUCTION
        │       │           │
        └───────┼───────────┘
                ▼
L. DASHBOARD 2.0 CORE
```

---

# 7. Recommended implementation sequence

## Wave A — governance foundations

1. Workload identity/version/configuration identity.
2. Named trust zones/provider-deployment identity.
3. Restrictive policy composition.
4. Classification-before-exposure integration.

## Wave B — authoritative decisions

1. Authorized candidate model.
2. Stable rejection/selection reason families.
3. Policy-constrained selection/fallback.
4. Decision/configuration identity/digest where required for evidence.

## Wave C — evidence and control plane

1. Typed governance evidence/events.
2. Safe projection rules.
3. Materialized read model.
4. Query APIs and selected streaming surfaces.
5. Semantic timeline.
6. Forensic reconstruction.

## Wave D — control and dashboard

1. Generic OIDC authentication boundary.
2. Capability-based authorization.
3. Typed lifecycle/control commands.
4. Privileged-action audit/evidence.
5. Dashboard 2.0 inventory/detail/timeline/control/reconstruction.

No Wave E exists in the 0.7.0 release cut. Additional capability belongs to the long-term roadmap unless required to close a P0 defect.

---

# 8. Release decision rule

0.7.0 is ready when this supported-profile story is coherent:

```text
Governed workload identified
        ↓
Input classification resolved before provider exposure
        ↓
Named provider deployments/trust zones known
        ↓
Organization/environment/workload constraints composed
        ↓
Authorized provider/model set calculated with reason paths
        ↓
Only authorized routes may be selected/executed
        ↓
Authoritative decision/evidence emitted
        ↓
Control plane projects the run safely
        ↓
Operator can inspect the semantic timeline
        ↓
Authorized operator can issue supported controls
        ↓
Historical run can be reconstructed without side effects
```

If that loop is coherent, 0.7.0 should ship rather than waiting for 0.8/0.9/0.10 capabilities.

---

# 9. Change-control rule

Any proposal to add work to 0.7.0 must answer:

1. Which missing edge in the P0 control-plane loop does it complete?
2. Is 0.7 unsafe or incoherent without it?
3. Why is an architecture commitment insufficient?
4. Why can implementation not move to 0.8.0 or later?

If those questions do not have strong answers, the work does not belong in 0.7.0.

---

## Final boundary

```text
0.7.0
GOVERNED AI CONTROL PLANE

IDENTITY
  → CLASSIFICATION
  → NAMED TRUST TOPOLOGY
  → EFFECTIVE POLICY
  → AUTHORIZED CANDIDATES
  → POLICY-CONSTRAINED SELECTION
  → EXECUTION
  → EVIDENCE
  → CONTROL PLANE
  → EXPLAINABILITY
  → AUTHORIZED CONTROL
  → RECONSTRUCTION
  → DASHBOARD 2.0
```

> **0.7.0 proves that governed AI execution can be operated, explained, controlled, and reconstructed. It does not attempt to finish every future governance capability.**
