# TramAI 0.7.0 — Ecosystem Governance, Simulation, and Contract-Testing Addendum

> **Status:** Normative companion to `docs/ROADMAP-0.7.0.md`  
> **Target:** TramAI 0.7.0 architecture and sequencing  
> **Scope:** Strategic/architectural amendments derived from the 2026 JVM AI framework landscape  
> **Primary decision:** TramAI must remain valuable even when AI workloads are authored with another JVM framework.  
> **Positioning consequence:** **Build AI workloads with the framework that fits. Govern their execution with TramAI.**

---

## 1. Why this addendum exists

The JVM AI ecosystem is rapidly converging on capabilities that were previously differentiating:

- model/provider abstraction;
- structured output;
- tool/function calling;
- agent loops;
- graph/workflow orchestration;
- memory;
- checkpointing/persistence;
- tracing;
- MCP/A2A integration;
- testing/mocking;
- Spring integration;
- local developer UIs.

Large ecosystem actors can expand these capabilities faster than TramAI should attempt to compete feature-for-feature.

The strategic implication is not to abandon TramAI's runtime. It is to define a stronger boundary around the runtime:

> TramAI's durable differentiation is **governance authority**, not generic agent authoring.

A company should not need to rewrite a Koog, Spring AI, LangChain4j, ADK, Genkit, or custom JVM workload merely to gain TramAI governance.

This addendum therefore strengthens four areas of the 0.7.0 roadmap:

1. **cross-runtime governance boundary**;
2. **policy simulation / decision preview**;
3. **governance contract testing**;
4. **developer-local governance UX**.

It also records a later architectural opportunity around **tool reversibility and compensation semantics**.

---

## 2. Strategic category boundary

### 2.1 TramAI should not try to win the generic agent-framework race

0.7.0 should explicitly avoid making these primary differentiators:

- graph DSL sophistication;
- planner implementations;
- generic autonomous-agent loops;
- memory strategies;
- RAG breadth;
- provider count;
- MCP client convenience alone;
- generic tracing alone;
- generic LLM mocking alone;
- prompt playgrounds;
- a visual agent builder.

These remain useful integration capabilities when required by governed execution, but they are not the moat.

### 2.2 The category TramAI should own

TramAI should become the JVM layer that can answer and enforce:

- What AI workloads exist?
- Who owns each workload?
- What is each workload allowed to do?
- What data classifications may it process?
- Which providers/models are eligible for a request?
- Which providers/models were rejected, and why?
- Which tools may be exposed to the model?
- Which actions require human approval?
- Which network destinations may be reached?
- Which action was executed by which workload/version?
- What policy/configuration version governed the decision?
- Can an operator suspend or quarantine the workload?
- Can the organization prove what happened later?
- Which controls currently support a governance/regulatory requirement?
- Which controls have regressed?

### 2.3 Product boundary statement

The internal product statement should be:

> **TramAI is the governance boundary around AI execution on the JVM.**

The public positioning may remain broader, but architecture should be evaluated against this statement.

### 2.4 Compatibility is strategic, not merely technical

TramAI should prefer the following adoption path:

```text
Existing JVM application
        +
Existing AI framework/runtime
        +
TramAI governance boundary
        ↓
Governed execution + control-plane evidence
```

rather than requiring:

```text
Existing JVM application
        ↓
Rewrite AI workloads into TramAI abstractions
        ↓
Governed execution
```

Native TramAI workloads remain first-class and may expose richer semantics, but external-runtime participation must be an architectural possibility from 0.7.0 onward.

---

## 3. Roadmap amendment: new release principles

The following principles extend Section 3 of `ROADMAP-0.7.0.md`.

### 3.10 Govern runtimes; do not require owning them

TramAI governance semantics must not depend on TramAI being the framework that authored the agent/workflow.

Where technically enforceable, another runtime should be able to cross a TramAI governance boundary for:

- classification;
- provider/model eligibility;
- tool authorization;
- approval;
- network/egress policy;
- runtime telemetry;
- evidence generation.

### 3.11 One evaluator for execution and simulation

Policy simulation must call the same authoritative decision logic used by execution.

Forbidden architecture:

```text
Runtime policy engine        Dashboard simulation engine
        │                              │
        └── slightly different rules ──┘
```

Required architecture:

```text
              Authoritative evaluator
                /              \
      execution path        simulation path
       side effects            no effects
```

Simulation may change inputs such as hypothetical classification, model availability, policy version, or tool request, but not decision semantics.

### 3.12 Governance tests before production incidents

Any deterministic governance rule that can block or permit production behavior should be testable in CI without making provider calls or executing external side effects.

### 3.13 Framework integration must declare its enforcement strength

TramAI must not imply equivalent enforcement across all integration modes.

Each integration should explicitly declare whether it is:

- **AUTHORITATIVE** — TramAI is in the execution path and can enforce the decision;
- **INSTRUMENTED** — TramAI controls selected boundaries but not the entire external runtime;
- **OBSERVED** — TramAI receives events/metadata but cannot guarantee prevention;
- **IMPORTED** — historical/external evidence only.

The dashboard, findings, evidence packs, and framework assessments must preserve this distinction.

### 3.14 Developer experience is part of governance quality

A policy nobody can understand locally will be bypassed in application code.

Developers need fast local answers to:

- why a provider was blocked;
- why a tool needs approval;
- which rule caused a classification;
- what would happen under a different policy/configuration;
- which governance contract a code change breaks.

The local developer workflow is therefore a governance control surface, not cosmetic UX.

---

## 4. Must-ship boundary amendments

The six existing P0 capabilities in the main roadmap remain authoritative.

Add the following architectural requirement to the P0 boundary:

7. **Cross-runtime governance contract** defining how non-TramAI-authored JVM workloads can be identified, evaluated, observed, and—where integration strength permits—constrained without duplicating the policy engine.

Add the following P0/P1 capability:

8. **Policy Simulation / Decision Preview** using the authoritative policy/selection evaluator with all external side effects disabled.

Add the following P1 capability:

9. **Governance Contract Testing** enabling deterministic CI assertions over classification, provider/model eligibility, tool approval, fallback, and other governance decisions.

These additions do not require shipping production-grade adapters for every JVM framework in 0.7.0. The P0 requirement is the architecture and contract that prevents TramAI from becoming impossible to integrate later.

---

# Phase 0 Amendment — Cross-Runtime Governance Contract

## Epic 0.2: External runtime governance boundary

**Priority:** P0 architecture contract  
**Goal:** Ensure TramAI can govern execution boundaries without requiring the workload to be authored by TramAI.

### 0.2.1 Core concept

Introduce an architecture-level SPI or equivalent contract between an external runtime and TramAI governance.

The exact API is intentionally not frozen in this roadmap, but the model should cover concepts equivalent to:

```kotlin
interface GovernedRuntimeAdapter {
    val runtimeId: String
    val enforcementMode: EnforcementMode

    fun describeWorkload(): GovernedWorkloadDescriptor

    suspend fun evaluate(
        intent: GovernedExecutionIntent
    ): GovernanceDecision

    suspend fun publishOutcome(
        outcome: GovernedExecutionOutcome
    )
}
```

This sketch is descriptive, not an API commitment.

### 0.2.2 Required execution-intent metadata

A governance evaluation should be able to receive a safe representation of:

```text
workload identity
workload version/config digest
deployment identity
environment
actor/service identity where available
operation/action type
declared data classification
classifier-derived metadata
requested model capability
provider/model candidates where known
requested tools/actions
network/egress target where applicable
resource/risk metadata
correlation/run id
policy/configuration reference
```

Raw prompts and tool payloads are not required merely to make the governance contract function.

### 0.2.3 Required decision representation

A governance decision should be able to represent:

```text
ALLOW
DENY
REQUIRE_APPROVAL
REQUIRE_TRANSFORMATION
NO_ELIGIBLE_ROUTE
QUARANTINED
SUSPENDED
```

with safe structured metadata such as:

```text
stable decision/reason code
policy version/config digest
matched rule references
effective classification
eligible candidates
rejected candidates + reason codes
required approval policy
required transformations/redactions
allowed tool/network scope
decision timestamp
decision id
```

Not every integration needs every field, but absence must be explicit rather than silently inferred.

### 0.2.4 Enforcement modes

Define an enum or equivalent:

```text
AUTHORITATIVE
INSTRUMENTED
OBSERVED
IMPORTED
```

#### AUTHORITATIVE

TramAI controls the execution boundary and a denial prevents the operation.

Examples:

- provider/model request routed through TramAI;
- tool invocation executed through TramAI tool governance;
- workflow step controlled by TramAI runtime.

Claims permitted:

- "blocked";
- "required approval";
- "policy prevented execution";
- technical control may be eligible for VERIFIED status if evidence requirements are met.

#### INSTRUMENTED

The external runtime calls TramAI at selected boundaries, but TramAI cannot prove there is no bypass path outside the adapter.

Claims permitted:

- "decision enforced at the instrumented boundary";
- "adapter reported governed execution".

Claims not permitted without stronger evidence:

- "all executions are guaranteed governed".

#### OBSERVED

TramAI receives telemetry or lifecycle information after/beside execution.

Claims permitted:

- "observed";
- "reported";
- "detected".

Claims not permitted:

- "prevented";
- "enforced".

#### IMPORTED

External historical evidence is ingested for review.

No runtime enforcement claim is permitted.

### 0.2.5 Adapter security requirements

An adapter is part of the trusted computing boundary when TramAI claims enforcement.

Therefore:

1. Adapter identity/version should be recorded where practical.
2. Workload identity must not be accepted from an untrusted client without authentication/registration rules.
3. Decision correlation IDs must be unambiguous.
4. An adapter must not be able to upgrade its own enforcement mode through client-provided metadata.
5. Unsupported decision types must fail closed where the configured integration claims authoritative enforcement.
6. Timeout semantics must be explicit: fail-open is never the silent default for sensitive/high-risk policy boundaries.
7. Replayed decision IDs must not accidentally authorize a different action.
8. Evidence must distinguish adapter-reported facts from TramAI-observed/enforced facts.

### 0.2.6 Initial adapter targets

0.7.0 does **not** require all of these to ship.

Candidates for post-contract adapters:

- Spring AI;
- Koog;
- LangChain4j;
- plain HTTP/custom JVM model clients;
- MCP tool execution boundaries;
- generic OpenAI-compatible provider call boundary.

The first production adapter should be selected by adoption value and ability to provide meaningful enforcement, not brand visibility.

### 0.2.7 Acceptance criteria

- `GovernedWorkload` remains framework-neutral.
- An external-runtime adapter can register a workload without pretending it is a TramAI-native workflow.
- Enforcement strength is represented explicitly.
- External-runtime decisions use the same core policy abstractions as native workloads.
- The dashboard can display native and external workloads without erasing enforcement differences.
- Evidence packs preserve integration/enforcement mode.
- A weak OBSERVED integration cannot satisfy an enforcement-required governance control.
- No adapter can bypass core authorization/policy by writing directly to control-plane projections.

---

# Phase 1 Amendment — Policy Simulation / Decision Preview

## Epic 1.2: Authoritative policy simulation

**Priority:** P0/P1  
**Goal:** Allow developers/operators to ask "what would TramAI decide?" without executing a provider call, tool, workflow action, approval side effect, or network request.

### 1.2.1 Core invariant

For equivalent inputs and authoritative state:

```text
simulate(input).decision == execute(input).preExecutionDecision
```

except for intentionally dynamic runtime facts documented as such, for example:

- provider health changes;
- budget consumption races;
- time-window policy changes;
- concurrent lifecycle changes;
- approval state created only by real execution.

The system should return which dynamic facts were sampled so differences are explainable.

### 1.2.2 Simulation input

A simulation request may specify:

- workload/version;
- environment;
- declared/effective classification input;
- desired capability;
- candidate providers/models;
- requested tool/action;
- risk level;
- hypothetical policy version/configuration;
- hypothetical provider health/cost metadata where authorized;
- actor identity/role context;
- network destination;
- fallback scenario;
- optional "as-of" configuration snapshot when supported.

### 1.2.3 Simulation output

Example:

```text
Simulation ID: sim-01J...
Workload: customer-refund-agent v17
Mode: NO_SIDE_EFFECTS

Effective classification:
  CONFIDENTIAL

Provider/model candidates:
  ✓ mistral-large @ eu-private
      eligible
      TOOL_CALLING supported
      CONFIDENTIAL permits EU_PRIVATE

  ✗ claude-sonnet @ global-cloud
      blocked: DATA_RESIDENCY_POLICY

  ✗ gpt-family @ global-cloud
      blocked: CLASSIFICATION_TRUST_ZONE

Requested tool:
  refund_payment
  risk: HIGH
  decision: REQUIRE_APPROVAL
  policy: payment-tools-v4

Final decision:
  ALLOW_MODEL_ROUTE
  REQUIRE_APPROVAL_BEFORE_TOOL

Side effects performed:
  none
```

### 1.2.4 Simulation must not

- call an external model;
- execute a tool;
- create a real approval request;
- mutate workflow state;
- consume a production budget unless an explicit reservation mode is designed later;
- change workload lifecycle state;
- emit evidence indistinguishable from actual execution;
- silently use a different policy evaluator.

### 1.2.5 Simulation evidence semantics

Simulation should be auditable when used for governance review, but its records must be clearly typed as `SIMULATION`.

Never show simulation as proof that an actual runtime control was enforced.

Useful metadata:

```text
simulation id
actor
workload/version
policy/config digest
requested scenario
decision result
reason codes
sampled dynamic facts
createdAt
```

### 1.2.6 Simulation API candidates

Headless API first:

```text
POST /control-plane/v1/simulations
GET  /control-plane/v1/simulations/{id}
```

or an equivalent typed service boundary.

Dashboard is a client, not the authority.

### 1.2.7 Dashboard decision-preview UX

Candidate interaction:

```text
Workload → Simulate policy

[Classification] CONFIDENTIAL
[Capability]     TOOL_CALLING
[Tool]           create_invoice
[Environment]    production

                    Run simulation

Result
────────────────────────────────
Eligible routes        2
Rejected routes        4
Approval               REQUIRED
Network                MANAGED_EGRESS_ONLY
Policy version         18
────────────────────────────────

Why?
  data-locality-v3
  finance-tools-v8
  production-egress-v2
```

### 1.2.8 Diff mode

A high-value extension is comparing two policy/configuration states:

```text
SIMULATE DIFF

Current policy v18        Candidate policy v19
------------------        --------------------
EU_PRIVATE allowed        EU_PRIVATE allowed
GLOBAL blocked            GLOBAL blocked
create_invoice approval   create_invoice approval
refund_payment denied     refund_payment approval
                          ^ governance behavior changed
```

This is especially valuable before policy deployment.

Diff mode may be P1 if basic simulation is P0/P1.

### 1.2.9 Acceptance criteria

- Simulation invokes the authoritative evaluator.
- Simulation causes zero external execution side effects.
- Stable reason codes match real execution decisions.
- Dynamic runtime facts affecting parity are surfaced.
- Simulation cannot satisfy evidence requiring actual enforcement.
- Simulation is callable without the dashboard.
- Privileged hypothetical configurations require appropriate authorization.
- Sensitive raw input is not persisted merely to support simulation.

---

# Phase 5 Amendment — Developer-Local Governance Experience

## Epic 5.2: Local governance debugger

**Priority:** P1, with P0 architectural compatibility  
**Goal:** Make TramAI governance understandable during development before a workload reaches an operational control plane.

### 5.2.1 Principle

The dashboard should not become a generic prompt playground.

Its local-development value should be a **governance debugger**.

### 5.2.2 Local questions to answer

A developer should be able to inspect:

- what workload TramAI discovered/registered;
- effective workload/config version;
- classification result and source;
- provider/model candidates;
- rejected candidate reasons;
- chosen route;
- tool permissions;
- required approvals;
- fallback constraints;
- network/egress constraints;
- policy/config versions;
- recent semantic execution trace;
- governance findings;
- simulation results.

### 5.2.3 Example local trace

```text
Request received
  ↓
Workload: invoice-assistant dev@4f91c2
  ↓
Classification: CONFIDENTIAL
  source: rule customer-record-fields
  ↓
Policy evaluation
  ↓
Provider candidates: 4
  ├─ local-llama       ELIGIBLE
  ├─ mistral-eu        ELIGIBLE
  ├─ global-provider-a DENIED / TRUST_ZONE
  └─ global-provider-b DENIED / TRUST_ZONE
  ↓
Selected: mistral-eu
  reason: eligible + capability + preference
  ↓
Tool request: create_invoice
  risk: HIGH
  ↓
Approval required
```

### 5.2.4 Local mode safety

Local mode must not silently weaken governance.

Rules:

- environment identity is explicit;
- dev-only policies cannot accidentally masquerade as production policies;
- `ALLOW_ALL` convenience modes, if they exist, must be noisy and non-production-safe;
- secrets/raw payloads remain protected according to configured reveal policy;
- sensitive reveal actions remain auditable where the control plane is persistent;
- developer bypasses are never the default.

### 5.2.5 Acceptance criteria

- A developer can understand a denied provider/tool decision without reading raw logs.
- Every displayed `why` is backed by structured reason/policy metadata.
- Simulation can be launched from workload detail.
- Local dashboard logic does not reimplement policy.
- Local behavior can be reproduced headlessly in tests/CLI/API.

---

# Supporting Track D — Governance Contract Testing

## Epic D.1: Deterministic governance test kit

**Priority:** P1  
**Goal:** Let teams prove governance invariants in CI without network calls, real models, or real tool side effects.

### D.1.1 Why this differs from generic AI testing

Generic AI framework testing often answers:

- did the agent visit this node?;
- did the mocked LLM return this response?;
- was this tool called?;
- did this graph route to this edge?

TramAI governance testing should answer:

- can CONFIDENTIAL data ever route to a forbidden trust zone?;
- can a fallback make a denied provider eligible?;
- can a HIGH-risk tool execute without approval?;
- can an OBSERVED adapter satisfy a control requiring enforcement?;
- does a new policy version expand egress unexpectedly?;
- can a quarantined workload start a new execution?;
- can a role approve an action it should not be allowed to approve?;
- does a classification rule accidentally downgrade sensitivity?;

### D.1.2 Candidate API shape

Descriptive example only:

```kotlin
governanceContract("finance-agent production") {
    workload("finance-agent", environment = "production")

    scenario("confidential data remains regional") {
        classification(CONFIDENTIAL)
        requires(TOOL_CALLING)

        assertEligibleTrustZones(EU_PRIVATE, LOCAL)
        assertDeniedTrustZone(GLOBAL_CLOUD)
    }

    scenario("payment requires oversight") {
        tool("execute_payment", risk = HIGH)
        assertDecision(REQUIRE_APPROVAL)
    }

    scenario("fallback cannot escape policy") {
        classification(RESTRICTED)
        primaryUnavailable()
        assertNoEligibleRouteOutside(LOCAL)
    }
}
```

Java-friendly equivalents should exist if/when the testing API becomes public.

### D.1.3 Assertion families

#### Classification

- effective classification equals/at-least expected level;
- explicit classification cannot be downgraded by weaker classifier source;
- matched rule ID is expected;
- protected fixture values are not persisted in evidence.

#### Provider/model selection

- provider eligible/ineligible;
- trust zone eligible/ineligible;
- reason code equals expected;
- preference cannot override policy;
- fallback candidate set remains constrained;
- required capabilities exclude incompatible models.

#### Tools

- tool exposure allowed/denied;
- risk level requires approval;
- network scope is constrained;
- tool unavailable to given workload/version;
- irreversible tool has stricter required control if configured.

#### Human oversight

- approval required;
- correct approver capability required;
- self-approval forbidden where separation of duties is configured;
- expired approval cannot authorize execution;
- denied approval blocks the action.

#### Workload lifecycle

- SUSPENDED cannot start new work;
- QUARANTINED denies configured boundaries;
- RETIRED cannot reactivate without explicit lifecycle policy;
- stale workload version cannot issue control commands.

#### Framework assessment

- required technical control becomes VERIFIED only with acceptable enforcement evidence;
- OBSERVED integration produces manual/gap status when enforcement is required;
- policy regression changes assessment result deterministically.

### D.1.4 Golden decision snapshots

Optional P1/P2 feature:

Persist a normalized decision snapshot:

```json
{
  "workload": "finance-agent",
  "scenario": "restricted-payment",
  "classification": "RESTRICTED",
  "decision": "REQUIRE_APPROVAL",
  "eligibleTrustZones": ["LOCAL"],
  "rejected": [
    {"zone": "EU_PRIVATE", "reason": "CLASSIFICATION_POLICY"},
    {"zone": "GLOBAL_CLOUD", "reason": "CLASSIFICATION_POLICY"}
  ],
  "policyVersion": "fixture-v7"
}
```

CI can review intentional governance changes as diffs rather than mysterious test failures.

Snapshots must avoid unstable timestamps/IDs and sensitive payloads.

### D.1.5 CI integration

Candidate Gradle flow:

```text
./gradlew governanceTest
```

Potential outputs:

- JUnit-compatible failures;
- machine-readable JSON report;
- optional SARIF mapping for governance findings;
- decision snapshot diff;
- policy/config version metadata.

SARIF is exploratory and should not block the initial test kit.

### D.1.6 Acceptance criteria

- Test kit runs without external model/provider access.
- Test scenarios use the production policy evaluator.
- Tests cannot accidentally execute real tools.
- Tests support deterministic provider/tool/approval/lifecycle assertions.
- A failing assertion displays the actual reason path, not just expected/actual enums.
- Test fixtures can declare model capabilities/provider trust zones explicitly.
- Test reports contain no raw sensitive fixture payload by default.

---

# Supporting Track E — Tool Effect and Compensation Semantics

## Epic E.1: Reversibility metadata

**Priority:** P2 / later than the 0.7.0 core  
**Goal:** Allow governance decisions to account for whether a tool's external effect can be reversed or compensated.

### E.1.1 Motivation

Checkpointing workflow state does not undo external reality.

Examples:

- an email was sent;
- a payment was initiated;
- a customer was deleted;
- a ticket was created;
- a deployment occurred;
- an external API mutation succeeded.

Governance should distinguish operations with different recovery characteristics.

### E.1.2 Candidate effect model

```text
READ_ONLY
REVERSIBLE
COMPENSATABLE
IRREVERSIBLE
UNKNOWN
```

Potential metadata:

```text
effect type
risk level
compensation operation reference
compensation authorization policy
compensation timeout/window
idempotency semantics
external transaction/reference id requirements
```

### E.1.3 Governance use

Effect semantics could later influence:

- approval requirements;
- risk findings;
- incident response;
- rollback UI;
- compensation workflow generation;
- evidence packs;
- control framework mappings.

Example deterministic finding:

```text
HIGH
Irreversible HIGH-risk tool is executable without human approval.
```

### E.1.4 Safety boundary

A "compensatable" action must never be presented as equivalent to a transaction rollback.

Compensation can fail and may require separate authorization.

### E.1.5 0.7.0 decision

Do not block 0.7.0 on compensation execution.

However, avoid tool-governance APIs that make future effect/reversibility metadata impossible to add cleanly.

---

## 5. Control-plane read-model amendments

The read model should be extensible to project:

- workload runtime/framework identity;
- enforcement mode;
- adapter identity/version where relevant;
- governance-boundary health;
- last successful governance handshake/evaluation;
- decision/simulation summaries;
- governance contract-test status where imported from CI;
- control/evidence confidence constrained by enforcement mode.

### 5.1 Workload detail additions

Add fields/views for:

```text
Runtime
  TramAI native / Spring AI / Koog / LangChain4j / Custom / Unknown

Governance integration
  AUTHORITATIVE / INSTRUMENTED / OBSERVED / IMPORTED

Coverage
  provider routing     AUTHORITATIVE
  tool execution       AUTHORITATIVE
  network egress       INSTRUMENTED
  external side loops  UNKNOWN

Last evaluated
  timestamp

Known gaps
  external runtime may perform provider calls outside governed adapter
```

The UI must prefer explicit limitation text over green status theatre.

### 5.2 Overview additions

Candidate cards/findings:

- workloads by enforcement mode;
- workloads with partial governance coverage;
- workloads observed but not enforceable;
- simulation regressions awaiting deployment;
- governance contract test failures imported from CI;
- external-runtime adapters out of date or unhealthy.

These are P1 unless required by an initial external adapter.

---

## 6. Security and threat-model amendments

Cross-runtime integration increases the attack surface. The roadmap should account for the following threats.

### 6.1 Spoofed workload identity

Threat:

An untrusted process claims to be `finance-agent` and receives policy/evidence treatment for a privileged workload.

Mitigations should support:

- authenticated service identity;
- deployment registration;
- workload-to-principal binding;
- signed/verified metadata where justified;
- rejection of unregistered identities in authoritative mode.

### 6.2 Adapter bypass

Threat:

The integrated framework calls TramAI for demo/test traffic but another path invokes providers/tools directly.

Mitigation:

- enforcement mode prevents false claims;
- architecture docs describe boundary coverage;
- network/provider/tool controls may be layered externally where needed;
- governance findings may flag known bypassable paths.

### 6.3 Decision replay

Threat:

An old `ALLOW` decision is reused for a different tool/input/workload version.

Mitigation:

Decision authorization must bind to a sufficiently specific execution intent or opaque server-side continuation token with expiration and one-time/appropriate replay semantics.

### 6.4 Fail-open integration

Threat:

When TramAI is unavailable, an adapter silently executes the sensitive action.

Mitigation:

- failure mode is explicit per boundary;
- sensitive/high-risk boundaries default to fail-closed when authoritative governance is required;
- fail-open configuration is a governance finding and must be visible/evidenced.

### 6.5 Simulation confused with authorization

Threat:

A client obtains an `ALLOW` from simulation and uses it as permission to execute.

Mitigation:

Simulation responses are never executable authorization tokens and use separate types/IDs/endpoints.

### 6.6 Evidence laundering

Threat:

Observed telemetry is represented as proof of enforcement.

Mitigation:

Evidence records carry provenance and enforcement mode; framework assessments reject insufficient evidence strength deterministically.

---

## 7. Governance framework / EU AI Act implications

The existing roadmap's evidence-over-certification boundary remains correct.

Cross-runtime governance strengthens it by making evidence quality explicit.

### 7.1 Evidence strength

A future generic evidence model should distinguish concepts similar to:

```text
ENFORCED_RUNTIME_EVIDENCE
INSTRUMENTED_BOUNDARY_EVIDENCE
OBSERVED_TELEMETRY
IMPORTED_EXTERNAL_EVIDENCE
MANUAL_ATTESTATION
```

This prevents a framework control from treating every event as equivalent proof.

### 7.2 Example

Requirement support: human oversight.

Case A:

```text
Tool: execute_payment
Integration: AUTHORITATIVE
Policy: approval REQUIRED
Evidence: approval state machine prevented execution until approval
Result: technical control can be VERIFIED
```

Case B:

```text
Tool: execute_payment
Integration: OBSERVED
Telemetry: adapter reported human approval
Result: MANUAL_EVIDENCE_REQUIRED or PARTIALLY_VERIFIED
```

The exact assessment status depends on framework mapping, but the enforcement distinction must survive end to end.

### 7.3 Regression detection

Policy simulation and governance tests can support pre-deployment readiness checks:

```text
Candidate configuration v19
  ↓
Governance contract suite
  ↓
Human oversight regression detected
  ↓
Candidate deployment blocked by CI policy owned by the organization
```

TramAI can provide the deterministic result without claiming that passing CI equals legal compliance.

---

## 8. Reference workflow amendment

Extend the primary 0.7.0 reference workflow with a pre-execution simulation and an external-runtime variant.

### 8.1 Native flow

```text
Developer changes provider/tool policy
        ↓
Governance contract tests run
        ↓
Policy simulation shows candidate routes
        ↓
No unexpected governance regression
        ↓
Deploy
        ↓
Sensitive document enters
        ↓
Classification
        ↓
Provider/model eligibility
        ↓
High-risk tool request
        ↓
Approval
        ↓
Execution
        ↓
Semantic trace + evidence
```

### 8.2 External-runtime flow

```text
Spring AI / Koog / LangChain4j / custom workload
        ↓
Registered as GovernedWorkload
        ↓
Governance adapter boundary
        ↓
Execution intent
        ↓
TramAI classification/policy/selection decision
        ↓
DENY / ALLOW / REQUIRE_APPROVAL
        ↓
Adapter enforces according to declared mode
        ↓
Outcome correlated back to TramAI
        ↓
Control-plane projection
        ↓
Evidence records include enforcement provenance
```

### 8.3 Demonstration acceptance criteria

At least one 0.7.x demonstration should eventually prove that TramAI governance is conceptually independent from agent authoring.

The demo does not require every external framework. One adapter is enough to validate the boundary.

---

## 9. Execution-order amendments

The main roadmap's dependency ordering remains valid, with these insertions.

### Milestone A — Architecture contract

Existing:

1. control-plane authority/read-model/command ADR;
2. control-plane module/API scaffold;
3. safe projection rules.

Add:

4. **External-runtime governance boundary ADR/SPI**;
5. **enforcement-mode and evidence-provenance model**.

These should land before public adapter APIs are committed.

### Milestone B — Selection and workload identity

After candidate eligibility and workload identity:

6. **Policy simulation core using authoritative selection/policy evaluator**;
7. stable decision/reason normalization suitable for execution, simulation, and tests.

### Milestone C — Projection layer

Add projection support for:

- enforcement mode;
- external runtime identity;
- simulation records/summaries where persisted;
- evidence provenance.

### Milestone D — Dashboard 2.0

Add:

- local governance debugger affordances;
- "why" views based on structured decisions;
- simulation/decision-preview UI;
- explicit governance coverage/enforcement-mode display.

### Milestone F — Expansion

Add:

- governance contract testing module;
- first external-runtime adapter proof;
- simulation diff mode if not already delivered.

### Deferred / post-0.7.0

- broad framework adapter matrix;
- tool compensation execution;
- external-runtime discovery across the enterprise;
- remote agent marketplace/inventory connectors.

---

## 10. Proposed module boundaries

Names are provisional.

```text
tramai-core
    │
    ├── policy/runtime contracts
    │
tramai-governance (or existing equivalent)
    │
    ├── authoritative evaluator
    ├── decisions/reasons
    ├── enforcement mode
    │
tramai-control-plane
    │
    ├── query model
    ├── simulations API
    ├── commands
    │
tramai-governance-testing
    │
    ├── deterministic fixtures
    ├── assertions
    ├── reports
    │
tramai-adapter-spi
    │
    └── external runtime boundary
         ├── spring-ai adapter (candidate)
         ├── koog adapter (candidate)
         └── langchain4j adapter (candidate)
```

Alternative packaging is acceptable if dependency direction remains clean.

### Dependency rules

- policy evaluator must not depend on dashboard;
- adapter SPI must not depend on Vue/control-plane UI;
- testing must exercise production evaluator code, not copy it;
- external runtime adapters depend inward on stable governance contracts;
- control-plane projections consume decisions/events but do not redefine them;
- simulation must not depend on provider/tool side-effect implementations.

---

## 11. API stability guidance

0.7.0 should be careful about prematurely stabilizing external adapter APIs.

Recommended approach:

1. Stabilize semantic models first:
   - workload identity;
   - execution intent;
   - decision;
   - reason codes;
   - enforcement mode;
   - evidence provenance.
2. Keep first adapter SPI experimental if needed.
3. Prove at least one native + one external path before declaring the SPI stable.
4. Add compatibility tests for adapters once the contract stabilizes.

The biggest mistake would be freezing a framework-shaped adapter API that does not generalize beyond the first integration.

---

## 12. TCK implications

The existing provider/persistence/runtime compatibility philosophy should extend to governance integrations.

### 12.1 Governance adapter TCK candidates

A conforming AUTHORITATIVE adapter should prove:

- workload identity is stable and attributable;
- DENY prevents execution at the declared boundary;
- REQUIRE_APPROVAL cannot execute before approval;
- stale/expired decisions cannot authorize new execution;
- correlation between decision and outcome is preserved;
- evidence mode is accurate;
- adapter failure obeys declared fail-open/fail-closed semantics;
- sensitive payloads are not emitted into generic telemetry;
- unsupported governance decisions fail safely.

### 12.2 Simulation parity tests

For deterministic fixtures:

```text
simulation decision == runtime pre-execution decision
```

must be part of the governance evaluator TCK.

### 12.3 Testing-module self-tests

The governance testing module should include mutation-style fixtures proving it actually catches:

- trust-zone expansion;
- approval removal;
- fallback escape;
- lifecycle bypass;
- weaker classification merge;
- role escalation.

---

## 13. Release-gate amendments

Add to Section 9 of the main roadmap.

### 13.1 Cross-runtime claim gate

- Every non-native workload declares enforcement/integration mode.
- UI/evidence language never upgrades OBSERVED into ENFORCED.
- External adapter identity is authenticated/registered according to the declared threat model for authoritative mode.

### 13.2 Simulation gates

- Simulation performs zero external side effects.
- Simulation and execution share the authoritative evaluator.
- Simulation results are type-distinct from executable authorization/evidence of enforcement.
- Deterministic parity tests are green.

### 13.3 Governance-test gates

If the testing module ships in 0.7.0:

- it runs offline;
- it cannot invoke real tools/providers by default;
- failures expose structured reason paths;
- production evaluator code is exercised directly.

### 13.4 Developer UX gate

For the reference workflow, a developer can answer "why was this denied/approved/routed?" from structured local/control-plane output without reverse-engineering logs or frontend policy code.

---

## 14. Success-metric amendments

Add product/architecture metrics:

| Metric | Target |
|---|---:|
| Simulation/runtime deterministic decision parity | 100% for declared deterministic fixtures |
| Simulation external side effects | 0 |
| External workloads with explicit enforcement mode | 100% |
| Evidence records that preserve enforcement provenance | 100% |
| Governance test assertions using production evaluator | 100% |
| OBSERVED integrations presented as AUTHORITATIVE | 0 |
| Generic framework-specific concepts leaked into core governance model | 0 known intentional violations |

Longer-term adoption metric:

> At least one non-TramAI-authored JVM workload can participate in TramAI governance without rewriting its agent/workflow into a TramAI-native abstraction.

This may be a 0.7.x proof rather than a hard 0.7.0 GA gate if implementation scope becomes excessive.

---

## 15. Explicit non-goal amendments

In addition to the existing non-goals, 0.7.0 does not aim to become:

- the most feature-rich JVM agent authoring framework;
- a planner framework arms race;
- a generic RAG platform;
- the framework with the largest provider integration count;
- a clone of another framework's graph DSL;
- a prompt playground as a primary product surface;
- a universal adapter for every AI framework in one release;
- a claim that wrapping an external runtime automatically makes all its execution governed;
- a distributed transaction engine capable of undoing arbitrary tool side effects.

---

## 16. Decision matrix: what to copy, adapt, or avoid

The framework landscape suggests the following product discipline.

| Market capability | TramAI action | Reason |
|---|---|---|
| Typed JVM APIs | Keep/strengthen | Native JVM ergonomics remains adoption-critical |
| Agent graph DSLs | Maintain only where TramAI runtime needs them | Crowded, low strategic moat |
| Provider breadth | Demand-driven | Integration breadth alone is commoditized |
| Structured output | Maintain | Foundational runtime capability |
| MCP/tool calling | Govern deeply | Tool boundary is strategically important |
| Generic tracing | Integrate, do not reinvent APM | Governance semantics are the value |
| Semantic governance timeline | Invest | Converts low-level events into explainable control evidence |
| Checkpointing | Maintain | Runtime reliability |
| Generic agent testing | Do not chase feature parity | Others can own graph/mock ergonomics |
| Governance contract testing | Invest | Distinctive and policy-aligned |
| Local developer UI | Adapt as governance debugger | High adoption leverage without becoming prompt playground |
| Runtime rollback | Learn from it | State rollback is useful but incomplete for external effects |
| Tool compensation semantics | Explore later | Strong fit with risk/oversight/evidence |
| Framework-specific agent orchestration | Avoid as core coupling | Weakens portability |
| Cross-runtime governance | Invest | Strategic category expansion |
| Policy simulation | Invest | Differentiated developer/operator capability |
| EU AI Act evidence mapping | Keep prominent | Reinforces evidence/control-plane positioning |

---

## 17. Architecture decision summary

After this amendment, the 0.7.0 architecture should satisfy the following statement:

> TramAI may execute a workload itself, or it may govern an execution boundary owned by another JVM AI runtime. In either case, authoritative TramAI governance semantics remain typed, deterministic, explainable, headless, testable, and evidence-producing. The control plane displays those semantics without becoming a second policy engine. Where TramAI cannot guarantee enforcement, it says so explicitly.

The desired long-term stack is:

```text
AI AUTHORING / ORCHESTRATION
────────────────────────────────────────
Spring AI | Koog | LangChain4j | Custom
TramAI-native workflows/services
────────────────────────────────────────
                  │
                  ▼
TRAMAI GOVERNANCE BOUNDARY
────────────────────────────────────────
identity
classification
policy
provider/model eligibility
tool/network authorization
human approval
lifecycle control
simulation
structured decisions/reasons
────────────────────────────────────────
                  │
                  ▼
EXECUTION
────────────────────────────────────────
Models | Providers | MCP | Tools | APIs
Local | EU/private | Global cloud
────────────────────────────────────────
                  │
                  ▼
TRAMAI CONTROL PLANE
────────────────────────────────────────
inventory
semantic trace
risk findings
operations
FinOps
incident reconstruction
evidence packs
framework assessments
EU AI Act readiness evidence
────────────────────────────────────────
```

This preserves the original 0.7.0 thesis while making it more durable against framework convergence:

> **The model proposes. TramAI decides. The control plane shows why.**

And adds the ecosystem corollary:

> **The framework may orchestrate. TramAI still governs the boundary.**
