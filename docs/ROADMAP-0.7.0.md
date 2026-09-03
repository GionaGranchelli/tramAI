# TramAI 0.7.0 — Governed AI Control Plane Roadmap

> **Status:** Draft release roadmap  
> **Target release:** TramAI 0.7.0  
> **Baseline:** TramAI 0.6.0 after maintainability, architecture, compatibility-TCK, persistence-contract, and runtime-event hardening  
> **Primary objective:** Turn TramAI's governed runtime primitives into an operational control plane that lets organizations observe, explain, control, and evidence governed AI workloads.  
> **Theme:** **The model proposes. TramAI decides. The control plane shows why.**

---

## 1. Executive Decision

TramAI 0.7.0 is a **governance operations and control-plane release**.

0.6.0 focuses on making the runtime easier to understand, safer to change, harder to misuse, and consistent across providers, persistence technologies, workflow execution, observability, and failure boundaries. 0.7.0 should cash in on that foundation rather than starting another broad architecture rewrite.

The release thesis is:

> **TramAI 0.7.0 makes governed AI workloads visible, explainable, and controllable from one authoritative operational surface.**

A platform, security, compliance, or engineering operator should be able to answer:

- What AI workloads exist?
- Who owns them?
- What purpose and environment do they belong to?
- What data classifications may they process?
- Which providers, models, tools, and network destinations may they use?
- Which actions require approval?
- Why was a provider, model, tool, fallback, or policy outcome selected?
- What is running now?
- What happened during a specific execution or incident?
- Which governance risks are currently present?
- What does the workload cost?
- Can an authorized operator suspend, quarantine, or otherwise restrict it?
- What runtime evidence exists to support an internal or regulatory review?

0.7.0 does **not** make the dashboard a second policy engine. Runtime policy, routing, approval, authorization, audit, and persistence remain authoritative. The control plane projects that state into read models and issues typed, authorized commands back through runtime control boundaries.

---

## 2. Product Positioning

### 2.1 One-sentence positioning

> **TramAI is a JVM runtime and control plane for governed AI workloads, combining typed contracts, policy-aware execution, human oversight, sovereignty controls, operational observability, and reviewable evidence.**

### 2.2 The 0.7.0 operational loop

```text
classify → constrain → select → execute → approve → observe → control → prove
```

### 2.3 What changes after 0.6.0

| 0.6.0 question | 0.7.0 question |
|---|---|
| Are runtime contracts internally consistent and testable? | Can operators understand and control those contracts in a running system? |
| Are provider/store/event semantics authoritative? | Can those semantics produce a reliable operational governance view? |
| Are policy/audit/approval failures correctly handled? | Can an operator see and act on policy/audit/approval state safely? |
| Is runtime evidence structurally reliable? | Can evidence be navigated, reconstructed, assessed, and exported? |

---

## 3. Release Principles

### 3.1 Runtime authority before UI convenience

The dashboard never directly mutates authoritative persistence or implements policy logic. It consumes control-plane query APIs and issues typed commands through authorization and policy boundaries.

### 3.2 Inventory before governance

A workload must have an authoritative identity, owner, purpose, version, environment, and governance metadata before TramAI claims to provide enterprise governance over it.

### 3.3 Policy before preference

Cost, latency, availability, quality, and operator preference may select among policy-compliant execution candidates. They may never make a policy-ineligible provider/model/tool eligible.

### 3.4 Explain decisions, not only outcomes

The control plane must show why a provider, fallback, tool, approval requirement, denial, or risk finding occurred. Opaque "AI chose this" explanations are insufficient.

### 3.5 Safe metadata by default

Prompts, model completions, tool arguments, tool results, credentials, document content, and sensitive payloads are not exposed by default through telemetry or the control plane. Privileged reveal paths must be explicit, auditable, scoped, and separately authorized.

### 3.6 Deterministic findings before opaque scores

Governance risk starts with explicit, reproducible findings such as "high-risk tool has no approval requirement" or "restricted data can reach a global-cloud provider". A numerical score is optional presentation, never the authoritative finding.

### 3.7 Evidence over compliance claims

TramAI may verify technical controls, preserve runtime evidence, map evidence to governance requirements, and identify gaps. It must not claim legal certification, conformity assessment, or automatic EU AI Act compliance.

### 3.8 Framework-neutral governance core

EU AI Act support must be implemented as one governance framework mapping over generic requirements, controls, evidence, and assessments. The control model must remain extensible to ISO/IEC 42001, NIST AI RMF, OWASP guidance, customer policies, and internal governance frameworks.

### 3.9 Headless first

Every control-plane capability must remain usable through APIs without the dashboard. The dashboard is a first-party operational client, not the product boundary.

---

## 4. Must-Ship Boundary

The following six capabilities define the minimum credible 0.7.0 release and must not slip behind optional expansion work:

1. **Policy-aware provider/model selection** with explainable eligibility and routing decisions.
2. **Governed workload registry** with identity, ownership, version, environment, purpose, and governance metadata.
3. **Control-plane read model** projected from authoritative runtime state.
4. **Governance telemetry** built on the typed runtime-event catalogue and safe projection rules.
5. **Dashboard 2.0 governance command center** consuming only the control-plane API.
6. **Safe runtime control commands with authorization/RBAC** and auditable state transitions.

The following are important but may land incrementally without blocking the architectural core:

- classification pipeline 2.0;
- deterministic governance risk findings;
- EU AI Act readiness/evidence mapping;
- incident reconstruction and evidence packs;
- FinOps and budget-aware selection preferences;
- MCP/tool/DLP boundary completion;
- signing/attestation v2.

---

## 5. Target Architecture

### 5.1 Read path

```text
                           TramAI Runtime
                                │
              ┌─────────────────┼──────────────────┐
              │                 │                  │
        Runtime Events       Audit/Evidence      Runtime Stores
              │                 │                  │
              │                 │         approvals/checkpoints/
              │                 │         leases/workers/runs/etc.
              └─────────────────┼──────────────────┘
                                ▼
                    Control Plane Projectors
                                │
                                ▼
                      Materialized Read Model
                                │
                  ┌─────────────┴─────────────┐
                  ▼                           ▼
             Query REST API               SSE/stream
                  │                           │
                  └─────────────┬─────────────┘
                                ▼
                         TramAI Dashboard
```

### 5.2 Command path

```text
Dashboard / API client
        │
        ▼
Typed Control Command
        │
        ▼
Authentication + Authorization
        │
        ▼
Command validation / policy
        │
        ▼
Authoritative runtime control API
        │
        ▼
State transition / execution control
        │
        ▼
Audit + runtime event + evidence
```

### 5.3 Architectural boundary

The control plane must not:

- bypass runtime policy;
- write directly to workflow/approval/lease/audit tables as a shortcut;
- duplicate the policy engine in frontend code;
- infer authoritative state solely from best-effort telemetry;
- expose raw sensitive payloads through generic traces;
- silently perform privileged actions without actor identity and audit evidence.

---

# Phase 0 — Control Plane Contract

## Epic 0.1: Define the control-plane boundary

**Priority:** P0  
**Goal:** Establish the read/write architecture and keep UI, policy, and runtime authority separated.

### Tasks

1. Add an ADR defining the control plane as projection + query + typed commands.
2. Define authoritative sources for workload state, run state, approvals, policy decisions, provider routing, workers, schedules, risk findings, and evidence.
3. Define which control-plane projections are rebuildable from authoritative stores/events and which require dedicated durable state.
4. Define consistency expectations: strongly consistent, eventually consistent, and informational views.
5. Define stale-view behavior for commands using optimistic version/etag/precondition semantics.
6. Define safe field-level exposure rules for public metadata, privileged metadata, and sensitive payloads.
7. Define module boundaries and dependency direction.

### Proposed deliverables

- `docs/architecture/control-plane.md`
- ADR: control-plane authority and projection model
- initial `tramai-control-plane` module or equivalent API boundary
- control-plane API stability classification

### Acceptance criteria

- The dashboard can be replaced without changing governance semantics.
- No control-plane write path bypasses runtime authorization/policy.
- Projection lag and consistency guarantees are explicit.
- Sensitive content has no generic default query surface.

---

# Phase 1 — Policy-Aware Provider and Model Selection

## Epic 1.1: Candidate eligibility model

**Priority:** P0  
**Goal:** Move from configured routing toward an explicit selection model that separates eligibility from preference.

### Required inputs

- effective data classification;
- provider trust zone;
- provider/model registration state;
- required model capabilities;
- tool-calling/structured-output/vision/streaming needs;
- policy configuration;
- fallback rules;
- runtime health/availability;
- optional cost/latency/operator preferences.

### Invariant

```text
eligible = policy ∩ classification ∩ capability ∩ registration ∩ runtime constraints
selected = preference(eligible)
```

Preference can never expand the eligible set.

### Tasks

1. Introduce a typed candidate/eligibility decision model.
2. Preserve existing classification-routing semantics as policy constraints.
3. Define model capability metadata and selection requirements using existing provider capability contracts where possible.
4. Add stable reason codes for candidate rejection.
5. Add an explainable `SelectionDecision`/equivalent representation.
6. Define fallback as a new selection decision over a constrained fallback candidate set.
7. Record policy version/config digest and selected workload version in routing evidence.
8. Add deterministic tests proving preference cannot override a policy denial.

### Example explanation

```text
Selected:
  mistral-large @ eu-provider-a

Eligible because:
  CONFIDENTIAL permits EU_CLOUD
  STRUCTURED_OUTPUT supported
  TOOL_CALLING supported
  provider registered and healthy

Rejected:
  openai-global
  reason: classification-routing-blocked
```

### Acceptance criteria

- Selection is deterministic for deterministic inputs/preferences.
- Every rejected candidate can expose a safe reason code.
- Every selected candidate can expose the policy/constraint basis for selection.
- RESTRICTED/local-only paths can never silently escape to global cloud through preference or fallback.
- Existing provider compatibility contracts remain valid.

---

# Phase 2 — Classification Pipeline 2.0

## Epic 2.1: Effective classification pipeline

**Priority:** P0/P1  
**Goal:** Make classification easier to integrate without weakening the current explicit trust model.

### Baseline

TramAI already has explicit classification metadata, rule-based classification, classification sources, provider trust zones, and classification-aware provider/fallback policy. 0.7.0 extends integration and explainability rather than reimplementing those capabilities.

### Proposed pipeline

```text
Declared classification
        +
Rule-based classification
        +
Optional classifier extensions
        ↓
Conservative merge
        ↓
Effective classification
        ↓
Policy-aware provider/model selection
```

### Rules

- A less-authoritative or probabilistic classifier must not silently downgrade an explicit higher sensitivity classification.
- Every classification decision records source and matched rules/decision basis without recording sensitive source content.
- `LOCAL_MODEL_ASSISTED` classification, if implemented, is optional and local-policy constrained.

### Tasks

1. Define automatic integration hooks for request classification without forcing every caller to manually wrap every value.
2. Preserve explicit caller declarations as first-class evidence.
3. Add conservative merge semantics and tests.
4. Add safe classification decision telemetry.
5. Add explainability projection for matched classification rules.
6. Evaluate bounded-regex or safer-regex execution for administrative classifier rules.
7. Define extension SPI for local classifier implementations.

### Acceptance criteria

- A classifier cannot downgrade an explicitly more restrictive classification without an explicit administrative override mechanism designed for that purpose.
- Classification happens before provider eligibility is finalized.
- Classification source and decision reason are observable without exposing classified content.

---

# Phase 3 — Governed Workload Registry

## Epic 3.1: Authoritative workload identity

**Priority:** P0  
**Goal:** Give every independently governed AI workload a durable identity and accountable metadata.

### Internal product concept

Use a broad concept such as `GovernedWorkload`, not a generic `Agent` runtime abstraction. A workload may represent:

- an AI service;
- a workflow;
- a scheduled workflow;
- an agent loop;
- an MCP-exposed workflow;
- a background AI worker;
- another independently governed execution surface.

### Minimum descriptor

```text
id
name
version
type
owner
purpose
environment
deployment identity
risk tier / governance metadata
allowed data classifications
provider/model policy references
tool/permission policy references
createdAt
lastDeployedAt
lastSeenAt
status
```

### Tasks

1. Define workload identity/version semantics.
2. Define owner and purpose metadata without forcing organization-specific directory models into core.
3. Define workload registration/discovery from Spring/runtime composition where safe and deterministic.
4. Define deployment identity so the same logical workload can be separated by environment/deployment.
5. Define ACTIVE/SUSPENDED/QUARANTINED/RETIRED lifecycle states or equivalent.
6. Define version/configuration digest relationship.
7. Emit registration/version/lifecycle audit events.
8. Add list/detail/query APIs.
9. Add stale/inactive workload detection as projection logic, not hidden deletion.

### Acceptance criteria

- Every control-plane run can be attributed to a workload identity and version when operating in control-plane mode.
- Ownership and purpose are visible and queryable.
- Distinct deployments cannot accidentally collapse into one authoritative identity.
- Lifecycle transitions are versioned and auditable.

---

# Phase 4 — Governance Telemetry and Read Model

## Epic 4.1: Governance projections

**Priority:** P0  
**Goal:** Convert authoritative runtime events and stores into stable operational views.

### Required projection families

- workload inventory and health;
- workload effective policy summary;
- current provider/model eligibility/selection summary;
- tool permission summary;
- pending approvals;
- run/execution timeline;
- worker/schedule operational state;
- risk findings;
- model/provider usage;
- cost/FinOps summary where available;
- evidence availability and audit-chain status.

### Tasks

1. Define projection schemas independent of Vue components.
2. Define projection rebuild/recovery behavior.
3. Add idempotent event application.
4. Add ordering/version handling.
5. Define retention and compaction semantics.
6. Expose query APIs.
7. Add SSE/event-stream support for selected live operational views.
8. Map safe runtime events to OpenTelemetry without making OTEL the authoritative control-plane store.
9. Add tests proving duplicate/out-of-order projection events cannot create impossible authoritative-looking states.

### Acceptance criteria

- Dashboard views do not query many unrelated runtime stores and reconstruct semantics client-side.
- Projection state can be rebuilt or its durability model is explicitly documented.
- Control-plane state distinguishes authoritative state from eventually consistent display state.
- Raw prompt/tool payloads do not enter generic projection storage by default.

---

## Epic 4.2: Explainable execution timeline

**Priority:** P0  
**Goal:** Replace flat event history with a semantic governance trace.

### Example

```text
Request received
  ↓
Classification: CONFIDENTIAL
  ↓
Policy evaluation
  ↓
Provider eligibility
  ↓
Model selected: mistral-large @ eu-provider-a
  ↓
Tool requested: create_invoice / HIGH
  ↓
Approval required
  ↓
Approved by finance-admin
  ↓
Tool execution completed
  ↓
DLP result processed
  ↓
Workflow completed
```

### Acceptance criteria

- Timeline is derived from typed runtime events/evidence rather than string heuristics.
- Policy/routing/tool/approval entries can expose safe reason codes and relevant policy references.
- Missing telemetry cannot be silently rendered as a successful governance decision.

---

# Phase 5 — Dashboard 2.0 Governance Command Center

## Epic 5.1: Information architecture

**Priority:** P0  
**Goal:** Evolve `tramai-dashboard` from workflow administration into the first-party operational governance client.

### Proposed navigation

```text
OVERVIEW

AI WORKLOADS
  Inventory
  Activity

GOVERNANCE
  Risk
  Policies
  Permissions
  Approvals

OPERATIONS
  Runs
  Workers
  Schedules

OBSERVABILITY
  Traces
  Models & Providers
  Tools

EVIDENCE
  Audit
  Incidents
  Evidence Packs

FINOPS
  Usage
  Cost

SETTINGS
```

### Overview must answer

1. What AI is running?
2. What needs attention?
3. What is currently risky or blocked?
4. What changed recently?
5. What is the current provider/locality/cost posture?

### Workload detail must expose

- identity/version/owner/purpose/environment;
- lifecycle state;
- effective classification policy;
- eligible/blocked provider trust zones;
- models/providers used recently;
- tools and effective permissions;
- approval requirements;
- policy references;
- recent executions;
- risk findings;
- cost/usage where available;
- audit/evidence links;
- authorized control actions.

### Acceptance criteria

- Dashboard consumes only documented control-plane APIs.
- UI does not embed policy rules.
- Views distinguish current authoritative state from historical/observed state.
- Sensitive content is not displayed by default.
- Existing workflows/workers/schedules/audit use cases remain accessible.

---

# Phase 6 — Runtime Control and Human Oversight

## Epic 6.1: Control command model

**Priority:** P0  
**Goal:** Make the control plane capable of safe intervention, not only observation.

### Initial command candidates

- suspend workload;
- quarantine workload;
- reactivate workload;
- retire workload;
- cancel running execution where runtime semantics permit;
- require/reinforce approval for a policy-controlled action;
- revoke or narrow tool permission through an authoritative configuration/control mechanism;
- restrict provider/model eligibility through an authoritative configuration/control mechanism.

Not every action must land in the first slice. The command framework and safe lifecycle controls are P0; higher-level policy mutation may be staged.

### Command requirements

Every privileged command must include or derive:

- authenticated actor;
- authorization decision;
- workload identity and expected version;
- stable reason code;
- optional human reason/comment where policy permits;
- previous state;
- resulting state;
- timestamp;
- audit event;
- runtime event/evidence linkage.

### Acceptance criteria

- Commands cannot mutate state by directly editing persistence implementation details.
- Stale commands fail safely rather than overwriting newer state.
- Every successful privileged command is auditable.
- Failed authorization never produces partial runtime mutation.
- Quarantine/suspend behavior is explicitly defined for new, queued, and active work.

---

## Epic 6.2: Authentication and RBAC

**Priority:** P0  
**Goal:** Make privileged control-plane operations enforceable in enterprise environments.

### Candidate baseline roles

- `VIEWER`
- `OPERATOR`
- `APPROVER`
- `AUDITOR`
- `SECURITY_ADMIN`
- `PLATFORM_ADMIN`

Exact role names may change; the core requirement is capability-based authorization rather than hard-coded frontend checks.

### Tasks

1. OIDC/Spring Security integration boundary.
2. Actor identity propagation into commands and approvals.
3. Permission model for queries, sensitive reveals, approvals, runtime controls, evidence exports, and governance configuration.
4. Authorization tests proving the UI cannot bypass server checks.
5. Audit all privileged actions.

### Acceptance criteria

- Authorization is server-side and independent of UI visibility.
- Approvers cannot automatically perform unrelated platform-admin actions unless explicitly permitted.
- Sensitive reveal and evidence export permissions are independently enforceable.

---

# Phase 7 — Governance Risk and Framework Assessments

## Epic 7.1: Deterministic governance findings

**Priority:** P1  
**Goal:** Surface reproducible risks derived from effective runtime/control-plane state.

### Initial finding candidates

**CRITICAL**
- RESTRICTED data can reach a provider outside its permitted trust zone.
- a policy bypass makes an explicitly denied execution path reachable.

**HIGH**
- HIGH/CRITICAL-risk tool can execute without required human approval.
- workload has unrestricted external egress where policy requires managed egress.
- privileged control plane is enabled without enforceable authorization.

**MEDIUM**
- workload has no accountable owner.
- provider fallback crosses a trust-zone boundary that should require explicit review.
- workload has been inactive beyond configured lifecycle policy.
- deprecated/unapproved model remains eligible.

**LOW/INFO**
- non-critical configuration hygiene or review recommendations.

### Finding contract

Every finding should expose:

```text
what
why
severity
evidence / observed state
affected workload/version
stable rule id
remediation guidance
firstSeen
lastSeen
status
```

### Acceptance criteria

- Findings are deterministic for the same control-plane state and ruleset.
- No LLM judgment is required for core severity/findings.
- Findings do not expose sensitive source payloads.
- Findings can be acknowledged/resolved without deleting historical evidence.

---

## Epic 7.2: Governance framework engine

**Priority:** P1  
**Goal:** Support versioned governance/control frameworks without coupling the core to one regulation.

### Generic model

```text
Framework
  ↓
Requirement
  ↓
Applicability
  ↓
Controls
  ↓
Evidence mappings
  ↓
Assessment
  ↓
Gaps / manual evidence / legal review
```

### Required assessment statuses

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `MANUAL_EVIDENCE_REQUIRED`
- `GAP`
- `NOT_APPLICABLE`
- `LEGAL_REVIEW_REQUIRED`

Avoid a single "87% compliant" number as the authoritative result.

### Acceptance criteria

- Framework definitions are versioned.
- Technical verification and manual attestation are distinct concepts.
- Framework updates do not rewrite historical assessment results.
- The engine cannot emit a legal certification claim.

---

## Epic 7.3: EU AI Act readiness and evidence mapping

**Priority:** P1 / may continue beyond 0.7.0  
**Goal:** Make the EU AI Act the first governance-framework implementation.

### Product boundary

TramAI should help answer:

- Which declared system/workload characteristics are relevant to an applicability review?
- Which technical controls are implemented?
- Which controls have runtime evidence?
- Which evidence is missing?
- Which requirements depend on organizational/manual evidence?
- Which questions require legal or conformity-assessment review?
- Has a previously verified technical control regressed?

TramAI must **not** claim:

- legal advice;
- automatic legal classification of all systems;
- EU AI Act certification;
- conformity assessment;
- guaranteed compliance.

### Example control mapping areas

- system inventory / intended-purpose metadata;
- risk-management support evidence;
- data governance and classification controls;
- logging / record keeping;
- human oversight;
- transparency-support metadata;
- robustness/security runtime controls;
- provider/model registry evidence;
- incident and change history.

### Manual/organizational evidence examples

- quality-management system;
- organizational governance procedures;
- training/competence evidence;
- legal role determination;
- registration/conformity-assessment steps;
- contractual/vendor documentation.

### Continuous regression example

```text
Requirement support: Human oversight
Previous: VERIFIED
Current: GAP
Reason: execute_payment changed from approval REQUIRED to NONE
Affected workload: finance-agent 4.8.1
```

### Acceptance criteria

- Every "verified" result links to technical evidence or an explicit trusted attestation.
- Manual evidence is never silently treated as machine-verified.
- Historical assessments preserve framework version and workload/config version.
- The UI uses language such as readiness, controls, evidence, gaps, and review — not certification.

---

# Phase 8 — Incident Reconstruction and Evidence Packs

## Epic 8.1: Incident reconstruction

**Priority:** P1  
**Goal:** Reconstruct a governed execution from authoritative events/evidence and relevant runtime state.

### Incident timeline should include where available

- workload identity/version;
- input classification decision metadata;
- policy decisions;
- provider/model eligibility and selection;
- fallback decisions;
- tool exposure/execution decisions;
- approval requests/decisions;
- DLP/redaction events;
- workflow/step state;
- operator control commands;
- outcome;
- audit/evidence verification status.

### Acceptance criteria

- Incident reconstruction does not invent missing events.
- Missing evidence is displayed explicitly.
- Reconstruction can distinguish runtime truth from best-effort telemetry.
- Sensitive payloads remain redacted unless an independently authorized reveal path is used.

---

## Epic 8.2: Assessment/evidence export

**Priority:** P1  
**Goal:** Export a reviewable bundle for internal governance, customer due diligence, incident review, or framework assessment.

### Candidate contents

```text
system-inventory.json
workload-descriptor.json
control-matrix.json
technical-controls.json
human-oversight-evidence.json
provider-routing-evidence.json
risk-findings.json
audit-verification.json
model-registry.json
open-gaps.json
manual-attestations.json
assessment-summary.json
manifest.sha256
```

A rendered report may be added, but machine-readable evidence remains primary.

### Acceptance criteria

- Export includes workload/config/framework versions.
- Export is deterministic where input records are deterministic.
- Manifest verification detects tampering/corruption.
- Export wording preserves existing evidence claim boundaries.

---

# Supporting Track A — AI FinOps and Budgets

## Epic A.1: Usage and cost model

**Priority:** P1  
**Goal:** Make model usage economically observable without weakening governance constraints.

### Dimensions

- workload;
- team/owner metadata where configured;
- provider;
- model;
- environment;
- request count;
- input/output/reasoning tokens where supported;
- latency;
- retries/fallbacks;
- blocked requests;
- local/EU/global execution mix;
- estimated/actual provider cost where configured.

### Selection integration

Cost may become a **preference** over the already-eligible candidate set:

```text
policy eligibility → capability eligibility → candidate set → cost preference
```

Never:

```text
cheap model → bypass trust-zone or policy restriction
```

### Acceptance criteria

- Cost data declares source/estimation semantics.
- Missing pricing is represented as unknown, not zero.
- Budget preferences never override policy eligibility.

---

# Supporting Track B — MCP, Tool, Network, and DLP Hardening

## Epic B.1: Effective permission graph

**Priority:** P1  
**Goal:** Make provider/tool/network permissions visible and explainable in the control plane.

### Views

```text
DATA
  PUBLIC ✓
  INTERNAL ✓
  CONFIDENTIAL ✓
  RESTRICTED ✗

PROVIDERS
  local-llama ✓
  mistral-eu ✓
  global-cloud ✗

TOOLS
  search ✓ LOW
  create_invoice ⚠ HIGH / APPROVAL
  delete_customer ✗

NETWORK
  company.internal ✓
  unrestricted internet ✗
```

### Acceptance criteria

- Effective permission view is derived from authoritative metadata/policy.
- "Why?" links to safe policy/rule/reason information.
- Dashboard representation cannot grant permissions.

---

## Epic B.2: Remaining DLP boundary completion

**Priority:** P1/P2  
**Goal:** Close known output/tool-result channels that remain weaker than textual DLP paths.

Candidate scope:

- field-level output sensitivity policies;
- URL query-token/credential detection;
- signed/internal URL minimization rules;
- binary/image/OCR inspection hooks where operationally justified;
- MCP result/content handling;
- safe evidence of redaction without storing redacted secrets.

### Acceptance criteria

- Unprotected channels are documented explicitly until protected.
- No feature claims broader DLP coverage than the implemented channels.

---

# Supporting Track C — Signing / Attestation v2

**Priority:** P2 / stretch  
**Goal:** Build stronger evidence attestation only after control-plane and evidence-pack semantics stabilize.

Potential scope:

- signed assessment/evidence manifests;
- workload/configuration digest attestation;
- external signer integration;
- verification tooling.

This track must not block the 0.7.0 control-plane core.

---

## 6. Dashboard Target Information Architecture

### 6.1 Overview

Candidate cards:

```text
Active workloads
Healthy / attention / quarantined
Pending approvals
Policy denies
Blocked provider egress attempts
Critical/high risk findings
Local/EU/global execution share
AI usage/cost
Recent privileged changes
```

### 6.2 Workload detail

Tabs/sections:

- Overview
- Activity
- Effective Permissions
- Policies
- Runs / Traces
- Approvals
- Risk
- Models & Providers
- Cost
- Audit / Evidence
- Controls

### 6.3 Approval command center

The approval view should show:

- requesting workload/version;
- requested action/tool;
- risk metadata;
- safe policy reason;
- relevant resource metadata where safe;
- waiting time / expiry;
- actor requirements;
- prior execution context that can be safely exposed;
- approve/deny operations through the authoritative approval state machine.

### 6.4 Incident view

The incident view should combine semantic timeline, risk/policy decisions, control actions, and evidence status rather than only raw log entries.

---

## 7. Reference Workflow for 0.7.0

Use one end-to-end reference workflow across development, documentation, demos, conference material, and release verification.

### Scenario: governed sensitive-document action

```text
Sensitive document enters
        ↓
Classification pipeline
        ↓
CONFIDENTIAL
        ↓
Policy-aware candidate eligibility
        ↓
Global cloud rejected
        ↓
Local/EU candidates evaluated
        ↓
Eligible model selected
        ↓
Model requests high-risk tool
        ↓
Tool policy requires approval
        ↓
Approval appears in control plane
        ↓
Authorized human approves
        ↓
Tool executes
        ↓
DLP scans/minimizes result
        ↓
Workflow completes
        ↓
Dashboard shows semantic trace
        ↓
Risk / provider / cost / evidence visible
        ↓
Assessment/evidence pack exportable
```

### Reference-workflow acceptance criteria

- Restricted/confidential routing policy is demonstrably enforced.
- A policy-ineligible global provider never receives the request.
- High-risk tool execution cannot occur before required approval.
- Approval decision is attributable to an actor.
- Control-plane timeline explains classification, routing, policy, approval, and tool execution.
- Dashboard can suspend/quarantine the workload through authorized commands.
- Evidence can reconstruct the execution without requiring raw sensitive payloads.

---

## 8. Proposed Execution Order

The exact PR numbering should be created only after 0.6.0 is frozen, but the dependency order should be preserved.

### Milestone A — Architecture contract

1. ADR: control-plane authority/read-model/command boundary
2. Control-plane module/API scaffold
3. Safe projection data-classification/redaction rules

### Milestone B — Selection and workload identity

4. Candidate eligibility/selection decision model
5. Provider/model selection explainability
6. Governed workload descriptor/registry
7. Workload lifecycle/version/audit contract

### Milestone C — Projection layer

8. Projection store/read model
9. Governance event projectors
10. Semantic execution timeline
11. Query API + SSE

### Milestone D — Dashboard 2.0

12. Dashboard information-architecture shell
13. Workload inventory/detail
14. Policies/permissions/provider-selection views
15. Approval command center
16. Semantic run/trace view

### Milestone E — Safe controls

17. Control command API
18. OIDC/RBAC authorization model
19. Suspend/quarantine/reactivate lifecycle
20. Control audit/evidence and stale-command tests

### Milestone F — Expansion

21. Classification pipeline integration 2.0
22. Deterministic risk findings
23. Incident reconstruction
24. Evidence pack export
25. FinOps usage/cost model
26. Governance framework engine
27. EU AI Act readiness mapping first slice
28. MCP/DLP remaining boundary slices

Signing/attestation v2 remains a stretch milestone after the evidence model stabilizes.

---

## 9. Release Gates

0.7.0 should not ship only because the UI looks complete.

### 9.1 Security/control gates

- No privileged command bypasses server-side authorization.
- No control command directly mutates implementation-specific persistence as a shortcut.
- No high-risk control action lacks actor attribution and audit evidence.
- Sensitive prompts/tool payloads are absent from generic control-plane telemetry by default.
- Policy-ineligible candidates cannot be selected through preference, FinOps, fallback, or UI configuration.

### 9.2 Consistency gates

- Projection idempotency verified.
- Out-of-order/duplicate event handling tested.
- Stale command protection verified.
- Workload identity/version collision tests pass.
- Control-plane views do not present eventual projection state as stronger authority than the underlying runtime contract.

### 9.3 Compatibility gates

- Existing provider TCKs remain green.
- Existing persistence TCKs remain green.
- Runtime event catalogue remains the canonical event source.
- Existing stable workflow APIs remain compatible unless a separately approved 0.7 breaking change is documented.

### 9.4 Governance claim gates

- No automatic legal-compliance or certification claims.
- EU AI Act UI/report language distinguishes technical verification, manual evidence, and legal review.
- Framework version is attached to every stored assessment.
- Evidence exports preserve existing structural-evidence claim boundaries.

---

## 10. Success Metrics

### Runtime/control metrics

| Metric | Target |
|---|---:|
| Policy-ineligible provider selections | 0 |
| Unauthorized privileged control commands succeeding | 0 |
| High-risk tool executions bypassing required approval | 0 |
| Control actions without actor audit evidence | 0 |
| Generic telemetry records containing protected raw payloads | 0 |

### Product/operational metrics

| Metric | Target |
|---|---:|
| Governed workloads attributable to owner + version in control-plane mode | 100% |
| Explainable provider/model selections | 100% |
| Rebuildable/idempotent governance projections | 100% of declared rebuildable projections |
| Risk findings with stable rule ID + evidence + remediation | 100% |
| Framework VERIFIED statuses linked to evidence/attestation | 100% |

### Reference workflow

- One complete reference workflow proves classify → constrain → select → approve → execute → observe → control → prove.
- The workflow can be demonstrated without implying certification or legal compliance.

---

## 11. Explicit Non-Goals

0.7.0 does **not** aim to deliver:

- a general-purpose `Agent` framework replacing workflows/services;
- a visual agent builder;
- an agent/skill marketplace;
- discovery of every external/shadow AI agent in an enterprise;
- broad Microsoft/Google/Salesforce agent inventory connectors;
- a generic SIEM/APM replacement for Grafana, Datadog, or similar tools;
- full SaaS billing/account management;
- automatic EU AI Act certification or conformity assessment;
- legal advice;
- opaque LLM-generated compliance/risk scores as authority;
- policy logic embedded in Vue;
- silent prompt/completion/tool-payload collection for observability;
- a complete signing/attestation platform before the evidence model stabilizes;
- broad provider expansion unrelated to selection/control-plane validation.

---

## 12. Deferred Beyond 0.7.0

Potential later tracks:

- cross-platform external-agent discovery/ingestion;
- enterprise connector inventory for third-party agent ecosystems;
- policy/configuration rollback and change-approval workflows;
- richer separation-of-duties governance;
- continuous regulatory content distribution/update service;
- ISO/IEC 42001 framework mapping;
- NIST AI RMF framework mapping;
- customer-defined governance packs;
- stronger signed attestation and external trust anchors;
- multi-tenant hosted control-plane packaging;
- organization-wide AI asset graph across non-TramAI systems.

---

## 13. Final Release Definition

TramAI 0.7.0 is successful when it can demonstrate, end to end:

> A governed workload receives sensitive input; TramAI determines the effective classification; policy constrains provider/model eligibility; an eligible execution route is selected and explainable; a high-risk tool request is blocked pending human approval; an authorized human acts through the control plane; execution completes through governed runtime boundaries; the operator can inspect the semantic trace, effective permissions, risks, provider usage, and evidence; an authorized operator can intervene through typed control commands; and the execution can later be reconstructed from reviewable evidence.

The release should make this sentence true without relying on dashboard-only logic:

> **The model proposes. TramAI decides. The control plane shows why.**
