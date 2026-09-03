# TramAI 0.7.0 — Authoritative Release Cut and Document Map

> **Status:** Authoritative release-priority companion for TramAI 0.7.0  
> **Target release:** TramAI 0.7.0  
> **Purpose:** Normalize the 0.7.0 roadmap into one implementation boundary, dependency graph, and document map.  
> **Priority rule:** If detailed roadmap documents differ on whether a capability is a 0.7.0 blocker, this file defines release priority. Detailed companions remain authoritative for the semantics of their own capability.

---

## 1. Release thesis

TramAI 0.7.0 turns the hardened 0.6.x runtime into an operational governed-AI control plane.

> **The model proposes. TramAI decides. The control plane shows why.**

Supporting principles:

> **The framework may orchestrate. TramAI still governs the boundary.**

> **Prompts express intent. Contracts enforce invariants.**

> **Categories provide portable defaults. Named zones model real trust boundaries.**

> **The developer describes the workflow. TramAI owns the execution mechanics.**

> **Making a tool available is a capability. Requiring a tool is a contract.**

> **The model may describe what should happen. Trusted policy determines what must happen. TramAI proves whether it actually happened.**

> **Shared governance concepts should have one typed meaning across routing, tools, approvals, data handling, learning, egress, control, and evidence.**

> **Governance determines the permitted set. Runtime viability determines the possible set. Optimization determines the preferred candidate.**

> **Learn from governed outcomes without turning governance into surveillance.**

> **Approval lifetime follows policy. Replacement creates new authority; it never revives old authority.**

The release is successful when TramAI can identify a governed workload, classify its data, compose policy, determine eligible execution boundaries, execute through the authoritative runtime, explain resulting decisions, expose safe operational state, control the workload, and reconstruct what happened later.

---

## 2. Authoritative document map

This file is the entry point for 0.7.0 implementation planning.

### Core roadmap

- [`ROADMAP-0.7.0.md`](ROADMAP-0.7.0.md) — detailed control-plane roadmap, runtime/control-plane architecture, phases, dashboard, security, governance, evidence, FinOps, and release gates.

### Architectural companions

- [`ROADMAP-0.7.0-ECOSYSTEM-GOVERNANCE-ADDENDUM.md`](ROADMAP-0.7.0-ECOSYSTEM-GOVERNANCE-ADDENDUM.md) — cross-runtime governance contract, policy simulation, governance contract testing, developer-local governance UX, and later tool-effect semantics.
- [`ROADMAP-0.7.0-STRUCTURED-SEMANTIC-CONTRACTS-ADDENDUM.md`](ROADMAP-0.7.0-STRUCTURED-SEMANTIC-CONTRACTS-ADDENDUM.md) — typed semantic validators, invocation-aware invariants, safe repair, and validation evidence.
- [`ROADMAP-0.7.0-DOCUMENT-METADATA-CLASSIFICATION.md`](ROADMAP-0.7.0-DOCUMENT-METADATA-CLASSIFICATION.md) — narrow local document-metadata classification before provider selection.
- [`ROADMAP-0.7.0-TRUST-ZONES-AND-POLICY-COMPOSITION.md`](ROADMAP-0.7.0-TRUST-ZONES-AND-POLICY-COMPOSITION.md) — named trust zones, provider-deployment identity, topology/policy/preference separation, and restrictive policy composition.
- [`ROADMAP-0.7.0-ADAPTIVE-ROUTING-AND-SELECTION.md`](ROADMAP-0.7.0-ADAPTIVE-ROUTING-AND-SELECTION.md) — policy-constrained adaptive routing, authorized/viable/selected stages, availability/health/cost/latency/quality/capacity/budget signals, failover re-evaluation, and explainable selection strategies.
- [`ROADMAP-0.7.0-GOVERNANCE-RECONSTRUCTION-AND-REPLAY.md`](ROADMAP-0.7.0-GOVERNANCE-RECONSTRUCTION-AND-REPLAY.md) — forensic reconstruction, deterministic governance replay, replay evidence, and later sandbox execution replay.
- [`ROADMAP-0.7.0-WORKFLOW-DX-AND-DSL.md`](ROADMAP-0.7.0-WORKFLOW-DX-AND-DSL.md) — Kotlin workflow DSL direction, state ergonomics, immutable run context, framework-owned step context, and resume-context integrity.
- [`ROADMAP-0.7.0-TOOL-INVOCATION-CONTRACTS.md`](ROADMAP-0.7.0-TOOL-INVOCATION-CONTRACTS.md) — provider-neutral required-tool semantics, provider-native mapping, engine-level enforcement, evidence, and adversarial provider/TCK coverage.
- [`ROADMAP-0.7.0-TOOL-OBLIGATION-LIFECYCLE-REFINEMENT.md`](ROADMAP-0.7.0-TOOL-OBLIGATION-LIFECYCLE-REFINEMENT.md) — design-sensitive refinement of trusted dynamic tool obligations, phase-aware provider forcing, resume semantics, idempotency, and fail-closed tool-contract handling.
- [`ROADMAP-0.7.0-GOVERNANCE-VOCABULARY-AND-FACTS.md`](ROADMAP-0.7.0-GOVERNANCE-VOCABULARY-AND-FACTS.md) — canonical governance vocabulary, typed governance facts, provenance, constrained organization extensions, restrictive constraint composition, and an explicit boundary against general ontology/graph reasoning.
- [`ROADMAP-0.7.0-LEARNING-TRACES-AND-GOVERNED-DATASET-CAPTURE.md`](ROADMAP-0.7.0-LEARNING-TRACES-AND-GOVERNED-DATASET-CAPTURE.md) — governed semantic learning traces, evaluation/training dataset curation, and a mandatory privacy-design gate before production raw-content capture.
- [`ROADMAP-0.7.0-APPROVAL-REPLACEMENT-AND-LIFECYCLE-REMEDIATION.md`](ROADMAP-0.7.0-APPROVAL-REPLACEMENT-AND-LIFECYCLE-REMEDIATION.md) — policy-driven approval lifetime, exact single-use authority, execution-time safety revalidation, safe replacement, lineage, continuation fencing, and reconstruction.

### Reading rule

Use this file to answer:

> **Must this block the 0.7.0 release?**

Use the detailed companion to answer:

> **What are the exact semantics, invariants, tests, security requirements, and acceptance criteria?**

---

## 3. Scope model

```text
P0 MUST SHIP
    ↓
required for a coherent governed control-plane product

P1 TARGET
    ↓
high-value 0.7.0 capability allowed to move to first 0.7.x follow-up

ARCHITECTURE COMMITMENT
    ↓
boundary must be preserved now; full breadth not required

P2 / LATER
    ↓
explicit non-blocker
```

No capability becomes P0 merely because it appears in a detailed companion.

---

# 4. P0 — Must-Ship Release Boundary

## P0.1 Governed workload identity and version

Every independently governed execution surface must have authoritative identity/version/configuration identity and enough owner/purpose metadata for the supported control-plane deployment.

A run must be attributable to the governed workload that produced it.

## P0.2 Classification before execution eligibility

Classification must be resolved before provider/model eligibility is finalized.

Explicit classifications remain first-class evidence. Weaker signals must not silently downgrade stronger classifications.

## P0.3 Named trust zones and provider-deployment identity

`LOCAL`, `EU_CLOUD`, and `GLOBAL_CLOUD` remain coarse portable categories.

Concrete provider deployments must be assignable to organization-defined named trust zones. Provider brand alone never establishes trusted location.

## P0.4 Restrictive policy composition

Organization, environment, and workload constraints compose restrictively:

```text
organization ∩ environment ∩ workload = effective policy
```

A lower scope cannot silently widen a higher-level denial.

## P0.5 Policy-aware provider/model eligibility and non-widening selection

Routing must preserve three distinct stages:

```text
authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
viable     = authorized ∩ availability ∩ health ∩ quota ∩ capacity ∩ runtime constraints
selected   = selectionStrategy(viable)
```

Core invariants:

```text
selected ∈ viable
viable ⊆ authorized
```

Cost, latency, quality, capacity, locality, provider preference, availability, health, budget, operator preference, and fallback may constrain or choose only within the governance-authorized set according to their declared semantics.

A hard constraint such as a budget ceiling or latency SLO is not the same thing as an optimization preference such as `LOWEST_COST` or `LOWEST_LATENCY`.

Retry/failover is another routing decision under the same effective governance authority; it is never permission to escape policy.

Rejected/non-selected candidates expose safe stable reason paths that distinguish at least governance denial, runtime non-viability, and viable-but-not-selected outcomes.

## P0.6 Authoritative governance decision/evidence model

The runtime must emit enough typed decision evidence to explain:

- effective classification/source;
- policy/configuration identity;
- candidate deployments;
- named zone/category;
- eligibility/rejection reasons;
- runtime viability/rejection where authoritative;
- selected route and selection reason where available;
- relevant tool/approval decisions;
- workload/run correlation.

Raw sensitive payloads remain excluded by default.

## P0.7 Control-plane projection and query API

Authoritative runtime state/events project into a stable read model.

The dashboard consumes control-plane APIs instead of reconstructing governance from unrelated stores. Projection lag and authority remain explicit.

## P0.8 Semantic execution timeline

A governed run is explainable as a typed governance timeline rather than a flat log.

Authoritative evidence and best-effort telemetry are distinguishable; missing evidence is explicit.

## P0.9 Safe runtime control + authentication/authorization

Authorized operators issue typed lifecycle/control commands through runtime authority boundaries.

The dashboard never mutates authoritative stores directly and never becomes a second policy engine.

## P0.10 Forensic reconstruction

When required evidence exists, TramAI can reconstruct a historical governed run without invoking providers, tools, approvals, network actions, or workflow side effects.

Historical policy/topology/classification identities are not replaced with current defaults.

## P0.11 Dashboard 2.0 core governance surface

The first-party dashboard makes the P0 loop usable:

- workload inventory/detail;
- effective governance posture;
- provider/model authorization, viability, selection and reasons where supported;
- semantic timeline;
- approval/runtime state where applicable;
- authorized controls;
- reconstruction/evidence availability.

The dashboard is a client, not authority.

## P0.12 Release proof and compatibility discipline

P0 behavior must be protected by deterministic tests, TCK/compatibility coverage where contracts cross modules, security gates, safe telemetry tests, and mutation evidence for high-value invariants.

0.7.0 must not ship with a known bypass through selection/fallback, lower policy scopes, missing classification, stale projection, dashboard behavior, stale approval authority, or replay/reconstruction behavior.

---

# 5. P1 — Target for 0.7.0, Allowed to Move to 0.7.x

## P1.1 Policy Simulation / Decision Preview

Use the same authoritative evaluator as execution with external side effects disabled.

```text
simulate(input).decision == execute(input).preExecutionDecision
```

where deterministic inputs/semantics make equality meaningful.

## P1.2 Deterministic governance policy replay

Capture replay-critical decision context and immutable configuration identities in the 0.7.0 architecture.

Target:

```text
policyReplay(recordedDecisionContext).decision
    == recordedGovernanceDecision
```

If replay APIs slip, P0 still retains enough evidence to make later replay possible.

## P1.3 Semantic structured-output contracts

Minimum useful slice:

- typed violations;
- class-level semantic validator SPI;
- invocation-aware validation context;
- safe repair integration;
- proof that semantically invalid typed values cannot cross the application boundary.

A broad annotation catalogue is not required.

## P1.4 Document metadata classification — narrow slice

Inspect supported classification metadata locally before provider selection.

> Missing/unreadable/unknown metadata does not silently mean `PUBLIC`.

OCR/content/DLP/model-assisted classification is not required for this slice.

## P1.5 Governance Contract Testing

Establish a production-evaluator testing boundary for deterministic offline assertions over governance decisions.

A polished public testing module may spill to 0.7.x, but P0 invariants must already be testable internally.

## P1.6 Deterministic governance findings

Expose high-value reproducible findings derived from authoritative configuration/evidence.

Avoid opaque aggregate risk scores as primary authority.

## P1.7 Incident/evidence experience

Build useful incident views/evidence packs over P0 reconstruction. Rich export/reporting may continue in 0.7.x.

## P1.8 Developer-local governance debugger

Reuse production control-plane/simulation contracts to explain local policy, authorization, viability, selection, approval, and reason paths.

Do not build a generic prompt playground.

## P1.9 Workflow DSL 2.0 and state/context DX

Evolve the existing orchestration DSL rather than create another workflow runtime.

Target:

- ergonomic Kotlin DSL lowering to the canonical workflow definition;
- consistent step scope rather than manually shuttling `(state, WorkflowContext)` through ordinary code;
- explicit durable state vs immutable run context vs framework-owned step context;
- concise typed immutable state updates;
- typed context access;
- deterministic nested/parallel propagation;
- resume/replay integrity for behavior-affecting context;
- semantic parity tests with the explicit builder API.

If current context drift can bypass P0 governance, that narrow defect becomes P0 even though the broader DX work remains P1.

## P1.10 Typed tool invocation contracts

Separate tool exposure from invocation requirements.

Provider-neutral semantics:

```text
AUTO
REQUIRED_ANY
REQUIRED_NAMED(tool)
```

Core invariant:

```text
terminalSuccess => declared tool contract satisfied
```

Provider-native tool forcing is used where available, but TramAI validates contract satisfaction itself.

Required-tool declarations never bypass policy, permissions, approval, validation, replay/idempotency, or side-effect controls.

The dynamic obligation/lifecycle refinement remains design-sensitive: trusted facts may eventually resolve a tool obligation per invocation, but provider forcing, approval suspension, resume, retry, exactly-once behavior, and evidence semantics must be reviewed together before that API is frozen.

## P1.11 Governed learning traces and dataset capture

Add an opt-in semantic `LearningTrace` boundary combining normalized provider exchanges with authoritative TramAI outcomes such as validation, repair, tool contracts, governance, approvals, tool results, and terminal outcome.

Uses may include evaluation, SFT, tool-use examples, preference pairs, distillation, and failure analysis.

Captured traces are not automatically positive examples.

### Mandatory privacy gate

No production raw-content learning capture is supported before the privacy sub-epic defines and proves:

- deny-by-default/explicit opt-in;
- purpose limitation;
- classification-aware eligibility;
- minimization/redaction;
- separate learning vs audit/evidence stores;
- retention/deletion and derived-dataset lineage;
- privileged raw-content access/export authorization;
- tenant isolation;
- residency/sovereignty;
- provenance/training-use eligibility;
- observable reasoning only, without dependency on hidden chain-of-thought.

> **No production raw-content learning capture before capture, retention, access, deletion, and export boundaries are explicit and proven.**

## P1.12 Approval validity, safe replacement, and lifecycle remediation

Approval lifetime must be policy-driven rather than universally hard-coded to one TTL.

The canonical model distinguishes:

```text
ApprovalStatus
PendingDecisionLifetime
ExecutionAuthorityLifetime
ConsumptionPolicy
```

0.7.0 should support the safe concept of:

```text
INDEFINITE or UNTIL_REVOKED lifetime
+
SINGLE_USE exact-action authority
```

without turning it into reusable permission.

Core invariants:

```text
noExpiry != reusableAuthority
noExpiry != mutableArguments
noExpiry != bypassCurrentHardSafetyControls
```

Approval authority remains bound to the exact governed action through immutable identity/digests such as operation/tool identity and arguments digest.

Execution may revalidate explicit non-negotiable current safety controls before consuming long-lived approval authority.

Replacement remains a first-class remediation path when bounded authority expires.

Initial replacement scope stays narrow:

```text
expired approval
    ↓
TIMED_OUT
    ↓
authorized replacement request
    ↓
new independent PENDING approval
```

Core replacement invariant:

> **Replacement creates new authority; it never revives old authority.**

The replacement's lifetime is resolved from **current policy** and therefore may be bounded, until-revoked, or indefinite. The old historical lifetime/deadline never changes.

The application decides whether the business operation may be attempted again and supplies a fresh request. TramAI owns lifetime semantics, exact-action binding, continuation fencing, single-use consumption, race safety, authorization, lineage, evidence, and reconstruction.

Future replacement reasons may include policy invalidation/request change, but only `EXPIRED` replacement is required initially.

If implementation reveals that expired, revoked, mismatched, or already-consumed authority can execute today, that is a P0 correctness/security defect independent of this P1 capability.

## P1.13 Governance Vocabulary and Governance Facts

Establish a shared typed semantic substrate so routing, tools, approvals, data handling, learning, egress, runtime control, simulation, testing, and evidence do not independently redefine the same governance concepts.

Canonical flow:

```text
Governance Vocabulary
        ↓
Governance Facts + provenance
        ↓
organization/environment/workload policy constraints
        ↓
TramAI restrictive composition
        ↓
Governance Decision
        ↓
Enforcement + evidence
```

The bounded 0.7.0 foundation should:

- identify a minimal TramAI-owned core vocabulary from existing concepts rather than duplicate them;
- define a typed `GovernanceFacts`/equivalent representation;
- preserve fact source/provenance so model-reported, adapter-reported, application-declared, and policy-derived facts are not silently treated as equally authoritative;
- distinguish stable TramAI core semantics from controlled organization extension vocabulary;
- keep final decision/composition semantics TramAI-owned;
- prove the model in a few real boundaries, preferably routing, tool obligation/governance, and approval;
- feed simulation/testing/evidence/reconstruction from the same fact semantics.

Semantic-depth boundary:

```text
Level 0 explicit typed values        → 0.7.0 foundation
Level 1 controlled taxonomy          → 0.7.0 architectural direction
Level 2 bounded explicit derivation  → later, requires dedicated refinement
Level 3 general graph reasoning      → out of TramAI core
```

Core principle:

> **Model the governance world, not the business world.**

The public custom-governance SPI is intentionally not frozen until vocabulary ownership, provenance, extension boundaries, restrictive composition, evidence, and compatibility are reviewed together.

## P1.14 Policy-constrained adaptive routing and selection

Refine routing beyond static provider preference while preserving governance as the outer authority boundary.

Canonical routing stages:

```text
AUTHORIZED
    ↓
VIABLE
    ↓
SELECTED
```

The bounded capability should support typed operational/economic signals where reliable data exists, including:

- availability/health;
- capacity/load;
- rate limit/quota;
- cost and budget;
- latency;
- quality requirements/signals;
- locality/provider/deployment preference;
- required context/modality/capability.

A signal must declare whether it is a **hard constraint** or an **optimization objective**. `latency <= 2s` and `prefer lowest latency` are not the same semantic contract.

Initial strategies should be typed and explainable, for example `LOWEST_COST`, `LOWEST_LATENCY`, `HIGHEST_QUALITY`, `PREFER_LOCAL`, or ordered/lexicographic combinations. Arbitrary weighted scoring is not required.

Failover/retry re-runs routing within the same current authorized set and must never convert availability, cost, latency, quality, capacity, or operator preference into new governance authority.

Evidence/simulation/testing should distinguish:

```text
NOT_AUTHORIZED
NOT_VIABLE
NOT_SELECTED
```

and identify the selected strategy plus relevant sampled facts/provenance where needed for explanation and reconstruction.

---

# 6. Architecture Commitments — Design Now, Breadth Later

## A. Cross-runtime governance

Define a framework-neutral governance boundary and enforcement-strength model so future Spring AI, Koog, LangChain4j, custom JVM, or other adapters do not require a second policy engine.

Production-grade adapters for every framework are not required in 0.7.0.

## B. Governance framework mapping

Core evidence/control concepts remain framework-neutral enough for EU AI Act readiness mapping, ISO/IEC 42001, NIST AI RMF, OWASP guidance, and organization-specific frameworks.

Exhaustive mapping content is not required.

## C. Replay compatibility

Historical content identity and evaluator semantic identity must support deterministic replay without pretending current configuration reproduces historical decisions.

## D. Trust-zone extensibility

Named zones leave room for future attributes/selectors without requiring a generic ABAC expression language now.

## E. Tool effect semantics

Architecture should preserve future distinction among:

```text
READ_ONLY
REVERSIBLE
COMPENSATABLE
IRREVERSIBLE
UNKNOWN
```

A full compensation engine is not required.

These effect/risk signals may later inform safe approval-lifetime defaults, but policy remains authoritative.

## F. Workflow authoring parity and context integrity

New DSL ergonomics remain an authoring layer over the canonical workflow definition/runtime.

Workflow data semantics distinguish:

```text
durable state
immutable run context
framework-owned step context
```

Behavior-affecting context has explicit resume/replay integrity semantics.

## G. Tool contract portability

Core operation semantics are not defined by one provider's `tool_choice` format.

Distinguish:

```text
tool exposure
invocation requirement
provider-native enforcement capability
runtime contract satisfaction
```

Native forcing is an aid, not sole authority.

## H. Learning-trace privacy and portability

Distinguish:

```text
provider exchange observation
      +
authoritative runtime semantics
      ↓
canonical LearningTrace
      ↓
privacy eligibility + quality eligibility
      ↓
dataset/export adapters
```

Audit/evidence retention and learning-data retention remain separate authorities.

## I. Approval authority lifetime and remediation

Approval architecture must not equate authority with a mandatory timestamp.

Distinguish:

```text
decision status
pending decision lifetime
execution authority lifetime
consumption
exact-action binding
execution-time hard-safety revalidation
```

Long-lived authority must remain exact and single-use in the initial contract.

Replacement/remediation uses lineage between independent approvals rather than reopening terminal decisions.

## J. Shared governance vocabulary and facts

Routing, tools, approvals, learning, egress, control, simulation, testing, and evidence should converge on one typed vocabulary/fact model where they refer to the same governance concept.

TramAI owns stable core meanings. Organizations may add controlled extension vocabulary for concepts such as business purpose, data category, tool category, department, custom role, and named trust-zone identity.

Organization extensions may enrich or narrow governance but must not redefine core decisions or silently widen authority.

Typed relationships that directly support governance are allowed; general ontology/knowledge-graph reasoning is not part of the core architecture.

## K. Routing viability and optimization separation

Routing architecture must preserve the distinction between governance authorization, operational viability, and optimization/selection.

```text
authorized -> viable -> selected
```

Availability/health/capacity/quota/cost/latency/quality/budget signals may influence viability or preference only according to typed declared semantics.

Selection and failover remain inside the governance-authorized candidate set.

The architecture should support explainable typed strategies and later richer optimization without requiring a generic optimizer or opaque scoring engine in 0.7.0.

---

# 7. P2 / Later — Explicit Non-Blockers

The following must not delay 0.7.0 unless implementation uncovers a security-critical dependency:

- production adapters for every JVM AI framework;
- generic graph/planner/agent-authoring expansion;
- generic RAG platform;
- generic ABAC/IAM expression language;
- string-expression business rules;
- arbitrary boolean policy DSL;
- RDF/OWL/SPARQL integration;
- generic ontology/knowledge-graph platform;
- arbitrary relation traversal or multi-hop semantic inference;
- general rule chaining;
- Level-2 governance-fact derivation beyond any separately reviewed bounded slice;
- full DLP/content-classification platform;
- OCR/header/footer/watermark sensitivity inference;
- local-model sensitivity inference;
- broad DMS integrations;
- full sandbox execution replay;
- tool compensation engine;
- arbitrary tool-order/constraint graph language;
- reusable/standing approval authority;
- generic long-lived delegation;
- broad denied/approved/completed approval retry semantics;
- approval lifecycle expression language;
- managed fine-tuning/training jobs;
- GPU/checkpoint infrastructure management;
- automatic model promotion from captured traces;
- generic RL/RLHF/RLAIF platform;
- full dataset curation UI;
- Jakarta Bean Validation adapter;
- exhaustive native validation annotations;
- full Kotlin replacement for stable `@AiService` annotations;
- visual workflow builder/source round-tripping;
- sophisticated dynamic FinOps optimization;
- arbitrary weighted multi-objective routing/scoring language;
- machine-learned routing policy;
- global provider-price/cost oracle;
- predictive cross-cloud capacity scheduler;
- universal quality benchmark/scoring platform;
- generic service-mesh/load-balancer replacement;
- exhaustive governance-framework catalogues;
- automatic EU AI Act certification/conformity claims;
- generic SIEM/APM replacement;
- prompt playground/visual agent builder.

---

# 8. Critical Dependency Graph

```text
A. IDENTITY + CONFIGURATION IDENTITY
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
F. RUNTIME VIABILITY
                │
                ▼
G. POLICY-CONSTRAINED SELECTION
                │
                ▼
H. AUTHORITATIVE GOVERNANCE / ROUTING DECISION
                │
        ┌───────┴────────┐
        ▼                ▼
I. EXECUTION        J. TYPED EVIDENCE/EVENTS
                         │
                         ▼
K. CONTROL-PLANE PROJECTION + QUERY API
                         │
              ┌──────────┼───────────┐
              ▼          ▼           ▼
L. TIMELINE   M. CONTROL  N. RECONSTRUCTION
              │          │           │
              └──────────┼───────────┘
                         ▼
O. DASHBOARD 2.0 CORE
```

P1 capabilities attach to authoritative boundaries:

```text
Simulation ───────────────► authoritative evaluator + sampled routing facts
Policy replay ────────────► recorded decision model + evaluator
Semantic contracts ───────► execution boundary + evidence
Tool invocation contracts ► operation contract + provider mapping + execution boundary
Metadata classification ──► classification stage
Governance vocabulary ────► shared typed facts + provenance + policy/evidence boundaries
Adaptive routing ─────────► authorized set + runtime viability + typed selection strategy
Governance tests ─────────► authoritative evaluator
Debugger ─────────────────► control-plane/simulation APIs
Workflow DSL/DX ──────────► canonical workflow definition + runtime
Learning traces ──────────► provider exchange + execution semantics + privacy/quality gates
Approval lifecycle ───────► policy + approval authority + continuation + control + evidence
```

---

# 9. Recommended Implementation Sequence

## Wave A — governance foundations

1. Workload identity/version/configuration identity.
2. Named trust zones and provider-deployment identity.
3. Restrictive policy composition.
4. Classification/effective-classification integration.

## Wave B — authoritative decision engine

1. Authorized candidate model.
2. Stable governance rejection/selection reason families.
3. Runtime viability boundary that cannot widen authorization.
4. Preference/fallback constrained to viable authorized candidates.
5. Decision/configuration digest.
6. Deterministic evaluator reusable by execution/simulation/testing/replay.

## Wave C — evidence and control plane

1. Typed governance evidence/events.
2. Safe projection rules.
3. Materialized read model.
4. Query APIs / selected streaming surfaces.
5. Semantic timeline.
6. Forensic reconstruction.

## Wave D — runtime control and dashboard

1. Authentication/OIDC.
2. RBAC/authorization.
3. Typed lifecycle/control commands.
4. Dashboard inventory/detail/timeline/control/reconstruction.

## Wave E — high-value P1

1. Policy simulation.
2. Governance policy replay where evidence is ready.
3. Semantic structured-output contracts.
4. Typed tool invocation contracts/provider capability mapping.
5. Workflow DSL 2.0 + state/run/step-context DX.
6. Narrow metadata classification.
7. Governance contract testing/public testing UX.
8. Deterministic findings/richer incident evidence.
9. Governance Vocabulary + Governance Facts foundation: minimal core vocabulary, provenance, organization-extension boundary, restrictive constraint composition, and proof in routing/tools/approvals.
10. Adaptive routing foundation: explicit authorized/viable/selected stages, stage-specific reasons, small typed explainable strategies, and failover re-evaluation.
11. Cost/availability/latency/quality/capacity/budget signals where reliable sources and semantics are available.
12. Approval lifetime contract: pending lifetime, execution lifetime, single-use consumption, exact-action binding, hard-safety revalidation.
13. Expired-approval safe replacement and lineage.
14. Canonical learning-trace model/safe recorder boundary.
15. **Mandatory learning-trace privacy-design sub-epic before production raw capture.**
16. Privacy-safe evaluation dataset export where privacy/quality gates are proven.

## Wave F — follow-up breadth

Cross-runtime adapters, richer governance mappings, bounded vocabulary extensions/derivations where separately justified, richer adaptive routing/FinOps optimization, richer classification, sandbox replay, compensation semantics, richer tool-order semantics, optional service DSLs, reusable/standing approval models, richer approval remediation, dataset curation/export adapters, fine-tuning integrations, and ecosystem bridges.

---

# 10. Release Decision Rule

0.7.0 is ready when this supported-profile story is coherent:

```text
Governed workload identified
        ↓
Input classification resolved
        ↓
Named provider deployments/trust zones known
        ↓
Organization/environment/workload constraints composed
        ↓
Authorized provider/model set calculated with reason paths
        ↓
Runtime viability cannot widen authorization
        ↓
Only viable authorized routes may be selected/executed
        ↓
Authoritative decision/evidence emitted
        ↓
Control plane projects the run safely
        ↓
Operator can inspect semantic timeline
        ↓
Authorized operator can issue supported controls
        ↓
Historical run can be reconstructed without side effects
```

If this loop is incoherent, 0.7.0 is incomplete even if many P1 features are implemented.

If coherent, bounded P1 work may move to first 0.7.x without weakening the 0.7.0 product identity.

---

# 11. Release Gates

No 0.7.0 shipment is acceptable if any of the following are known to be possible:

- classification resolves after provider content exposure;
- selection/optimization expands the governance-authorized set;
- availability/health/capacity/cost/latency/quality facts are treated as new governance authority;
- retry/fallback selects a candidate outside the current authorized set;
- provider brand is treated as proof of trust/location;
- workload/environment policy silently widens organization policy;
- dashboard bypasses runtime authorization/policy or independently re-scores candidates as authority;
- generic telemetry exposes sensitive payloads by default;
- missing historical evidence is silently replaced by current/default configuration;
- forensic reconstruction performs side effects;
- projection state is presented as stronger authority than its source;
- compatibility changes silently alter stable governance semantics;
- behavior-affecting workflow context silently changes across resume/replay and changes authoritative behavior;
- required-tool contracts silently degrade to model-selected `AUTO`;
- required-tool declarations bypass policy/approval/validation/side-effect safety;
- a model-reported governance fact silently substitutes for a stronger authoritative application/policy/runtime fact;
- organization extension vocabulary redefines TramAI core decision semantics or silently widens authority;
- a governance boundary requiring a fact treats missing/unknown fact state as permissive without explicit policy;
- expired/revoked/mismatched/already-consumed approval authority can execute;
- no-expiry approval is interpreted as reusable permission or allows changed arguments;
- old human approval bypasses an explicit current hard safety deny;
- approval replacement revives old continuation authority or creates two viable continuations;
- ordinary audit/telemetry silently enables raw learning-data capture;
- learning data exports without explicit privacy/export eligibility;
- audit retention becomes a loophole for indefinite raw training-data retention.

---

# 12. Change-Control Rule

New 0.7.0 proposals should answer:

1. **Which missing edge in the P0 governance loop does it complete?**
2. **Why can it not safely ship in 0.7.x instead?**
3. **Which detailed roadmap document owns its semantics?**

If a proposal cannot answer those questions, default it to P1/P2 rather than expanding P0.

---

## Final normalized boundary

```text
0.7.0 P0
IDENTITY
  → CLASSIFICATION
  → NAMED TRUST TOPOLOGY
  → EFFECTIVE POLICY
  → AUTHORIZED CANDIDATES
  → RUNTIME VIABILITY
  → POLICY-CONSTRAINED SELECTION
  → EXECUTION
  → EVIDENCE
  → CONTROL PLANE
  → EXPLAINABILITY
  → CONTROL
  → RECONSTRUCTION

0.7.0 / 0.7.x P1
SIMULATION
SEMANTIC CONTRACTS
TOOL INVOCATION CONTRACTS
TOOL OBLIGATION LIFECYCLE REFINEMENT
WORKFLOW DSL / STATE-CONTEXT DX
METADATA CLASSIFICATION
POLICY REPLAY
GOVERNANCE CONTRACT TESTING
FINDINGS / INCIDENT ENRICHMENT
DEVELOPER GOVERNANCE DEBUGGING
GOVERNANCE VOCABULARY / GOVERNANCE FACTS
ADAPTIVE ROUTING / COST / AVAILABILITY / LATENCY / QUALITY / CAPACITY / BUDGET
APPROVAL LIFETIME / SINGLE-USE AUTHORITY / SAFE REPLACEMENT
LEARNING TRACES / PRIVACY-SAFE DATASET CAPTURE

LATER
ADAPTER BREADTH
FULL CONTENT CLASSIFICATION
GENERIC POLICY DSL
GENERAL ONTOLOGY / GRAPH REASONING
BOUNDED SEMANTIC DERIVATION UNTIL SEPARATELY REVIEWED
GENERIC ROUTING OPTIMIZER / ARBITRARY WEIGHTED SCORING
MACHINE-LEARNED ROUTING POLICY
SANDBOX EXECUTION REPLAY
COMPENSATION ENGINE
RICH TOOL-ORDER CONSTRAINTS
REUSABLE / STANDING APPROVAL AUTHORITY
MANAGED FINE-TUNING / TRAINING ORCHESTRATION
RICH DATASET CURATION UI
OPTIONAL SERVICE DSL / ECOSYSTEM BRIDGES
```

> **0.7.0 should prove the governance control-plane loop, not finish every future governance capability.**
