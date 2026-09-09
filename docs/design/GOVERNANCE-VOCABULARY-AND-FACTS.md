# TramAI 0.7.0 — Governance Vocabulary and Governance Facts

> **Status:** Architecture commitment + bounded P1 foundation  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md`, trust-zone/policy composition, tool invocation contracts, approval lifecycle, learning-trace privacy, cross-runtime governance, simulation, evidence, and runtime control  
> **Scope:** Give routing, tools, approvals, data handling, learning capture, egress, and other supported governance boundaries a shared typed semantic language without turning TramAI into a generic ontology, rules, ABAC, or knowledge-graph platform.

---

## 1. Decision

TramAI should establish a canonical, extensible **Governance Vocabulary** and represent each governed execution through typed **Governance Facts**.

Policies operate on that shared semantic model. TramAI remains responsible for deterministic constraint composition, final governance decisions, enforcement, evidence, and reconstruction.

Core direction:

```text
CANONICAL GOVERNANCE VOCABULARY
              ↓
       GOVERNANCE FACTS
              ↓
      GOVERNANCE POLICIES
              ↓
    EFFECTIVE CONSTRAINTS
              ↓
     GOVERNANCE DECISION
              ↓
         ENFORCEMENT
              ↓
          EVIDENCE
```

This is intentionally **not** a commitment to an ontology engine.

> **Custom policy should operate on an agreed governance worldview, not on isolated subsystem-specific semantics.**

> **We are giving existing TramAI components a common language; we are not replacing them with a semantic platform.**

---

## 2. Problem: semantic debt across governance domains

As TramAI expands, independently reasonable subsystems can accidentally invent overlapping concepts:

```text
Routing
  classification
  trust zone
  provider locality

Tools
  risk
  effect
  category

Approvals
  risk
  actor
  lifetime

Learning
  sensitivity
  retention
  export eligibility

Evidence
  classification
  risk
  purpose
```

Without a shared vocabulary, concepts such as:

```text
classification
sensitivity
privacyLevel
dataCategory
```

can drift toward similar meanings with different names, precedence, evidence, and enforcement semantics.

That is semantic debt.

The goal is not to make TramAI understand every business domain. The goal is to keep **TramAI governance concepts coherent across its own enforcement boundaries**.

---

## 3. Governance Vocabulary

The Governance Vocabulary defines concepts whose semantics are relevant to TramAI governance.

Initial dimensions should remain small and typed.

Conceptually:

```text
Governance Vocabulary
│
├── Data
│   ├── classification
│   ├── category
│   ├── residency
│   └── retention / learning eligibility where supported
│
├── Action
│   ├── category
│   ├── risk
│   └── effect
│
├── Execution
│   ├── trust zone
│   ├── provider deployment
│   ├── network/egress target
│   └── environment
│
├── Human Control
│   ├── approval class
│   └── actor/role facts
│
└── Purpose
```

Runtime commands/states such as:

```text
SUSPEND
RESUME
QUARANTINE
REPLAY
```

remain runtime semantics owned by the engine. They are not forced into one giant taxonomy merely because governance can constrain them.

---

## 4. Core vocabulary vs organization extensions

Interoperability requires a stable TramAI-owned core.

### 4.1 TramAI-owned core vocabulary

Examples may include:

```text
DataClassification
RiskLevel
ActionEffect
TrustZoneCategory
DecisionType
EnforcementMode
ApprovalDecision
```

The exact public type list is implementation work, but core semantics must remain stable enough that evidence from different TramAI deployments remains understandable.

Organizations must not redefine fundamental outcomes such as `ALLOW`, `DENY`, or `REQUIRE_APPROVAL` to mean unrelated things.

### 4.2 Organization extension vocabulary

Organizations need room for domain-specific governance facts that TramAI cannot standardize globally.

Examples:

```text
DataCategory
BusinessPurpose
ToolCategory
Department
CustomRole
NamedTrustZone
```

Illustrative organization extensions:

```text
DataCategory
├── CUSTOMER
│   ├── PII
│   └── FINANCIAL
└── INTERNAL

BusinessPurpose
├── CUSTOMER_SUPPORT
├── FRAUD_ANALYSIS
└── PAYMENT_PROCESSING
```

Extensions enrich governance facts; they do not replace TramAI's core semantics or silently widen core authority.

---

## 5. Governance Facts

`GovernanceFacts` is the runtime semantic representation of what TramAI knows for a governed decision.

The exact public API is not frozen.

Conceptually:

```kotlin
data class GovernanceFacts(
    val data: DataFacts,
    val action: ActionFacts,
    val execution: ExecutionFacts,
    val actor: ActorFacts,
    val workload: WorkloadFacts,
    val purpose: Purpose?,
    val extensions: GovernanceExtensions,
)
```

The important design property is not this exact shape. It is that routing, tools, approvals, learning, egress, simulation, testing, and evidence should not each create incompatible representations of the same governance-relevant fact.

Example:

```text
data.classification = CONFIDENTIAL
action.effect = IRREVERSIBLE
action.risk = HIGH
execution.zone = EU_CLOUD
actor.role = FINANCE_OPERATOR
purpose = PAYMENT_PROCESSING
```

Policies consume these facts.

---

## 6. Facts are not model assertions by default

A governance fact must preserve its authority/provenance.

Potential sources include:

```text
APPLICATION_DECLARED
TRAMAI_DERIVED
CLASSIFIER_DERIVED
POLICY_DERIVED
PROVIDER_REPORTED
ADAPTER_REPORTED
MODEL_REPORTED
OPERATOR_DECLARED
```

Exact source names are implementation work.

A model saying:

```text
risk = HIGH
```

must not automatically have the same authority as a trusted application amount threshold or authoritative organization policy.

Likewise:

```text
model output: REQUEST_HUMAN_APPROVAL
```

is not the same fact as:

```text
runtime approval state: PENDING
```

The evidence model must preserve that distinction.

---

## 7. Typed relationships, not arbitrary ontology graphs

Some relationships are directly useful because they correspond to governance decisions TramAI already needs to make.

Examples:

```text
ToolDescriptor
  category
  risk
  effect

ProviderDeployment
  trustZone

GovernedData
  classification
  category

LearningTrace
  sourceClassification
  purpose

ApprovalAuthority
  authorizes exact ToolInvocation / governed action
```

These are ordinary typed relationships and descriptors.

0.7.0 does not need a generic relation store or graph traversal engine to represent them.

---

## 8. Semantic-depth boundary

The roadmap intentionally limits semantic complexity.

### Level 0 — explicit values

Supported foundation:

```text
risk = HIGH
effect = IRREVERSIBLE
classification = CONFIDENTIAL
zone = EU_CLOUD
```

### Level 1 — controlled taxonomy

Supported architectural direction:

```text
CUSTOMER_PII is PERSONAL_DATA
PAYMENT_TOOL is FINANCIAL_TOOL
```

Taxonomy exists inside the governance vocabulary where it prevents duplicated concepts and improves policy portability.

### Level 2 — explicit bounded derivations

Possible later capability requiring separate design refinement:

```text
CUSTOMER_FINANCIAL → classification CONFIDENTIAL
FINANCIAL_ACTION + HIGH_VALUE → require finance approval
```

If introduced, derivation must have explicit precedence, provenance, conflict handling, versioning, explainability, and bounded evaluation semantics.

### Level 3 — general semantic graph reasoning

Explicitly outside TramAI core:

```text
arbitrary relation traversal
multi-hop inference
general rule chaining
open-ended graph reasoning
```

0.7.0 commits to Levels 0–1 only.

Level 2 is not part of the initial contract and must not be smuggled in merely to reduce configuration.

Level 3 is an explicit non-goal.

> **For governance systems, explicitness is often a feature.**

---

## 9. Policy model: constraints before final decision

Organization-specific policy should not each manufacture final governance semantics independently.

Preferred conceptual flow:

```text
GovernanceFacts
       ↓
policy contributors
       ↓
GovernanceConstraintSet(s)
       ↓
TramAI authoritative composition
       ↓
GovernanceDecision
```

This preserves the existing rule:

```text
organization ∩ environment ∩ workload = effective policy
```

across more than routing.

For example:

```text
Organization policy:
  payment > 50k → DENY

Environment policy:
  production payment > 5k → REQUIRE_APPROVAL

Workload policy:
  payment > 1k → REQUIRE_APPROVAL

Effective result for 60k:
  DENY
```

A lower scope cannot turn an upper-scope denial into permission.

---

## 10. Custom policy extension direction

A future typed policy SPI may conceptually resemble:

```kotlin
interface GovernancePolicy {
    fun evaluate(
        facts: GovernanceFacts,
    ): GovernanceConstraintSet
}
```

This syntax is illustrative only and must not freeze the API.

The strategic separation is:

> **Organization code defines organization-specific constraints. TramAI owns deterministic composition, final decision semantics, enforcement, reason paths, evidence, and replay compatibility.**

This extension model should be applicable across supported governance boundaries rather than only provider routing.

Candidate domains include:

```text
provider/model eligibility
tool invocation / obligation
approval requirement and lifetime
data handling
learning capture/export
network egress
runtime-control authorization
```

The first implementation does not need every domain.

---

## 11. Cross-domain examples

### 11.1 Routing

```text
data.classification = RESTRICTED
execution.candidateZone = GLOBAL_CLOUD
→ DENY candidate
```

### 11.2 Tool obligation

```text
action.category = PAYMENT
action.amount = 18_400
action.risk = HIGH
→ REQUIRED_NAMED(schedule-payment)
```

The obligation remains separate from authorization; required tools still pass through policy, approval, validation, and exactly-once protections.

### 11.3 Approval

```text
action.effect = IRREVERSIBLE
action.risk = HIGH
→ REQUIRE_APPROVAL
→ bounded execution-authority lifetime
```

### 11.4 Learning capture

```text
data.classification = RESTRICTED
purpose = MODEL_TRAINING
→ raw learning capture/export denied
```

### 11.5 Egress

```text
data.classification = CONFIDENTIAL
execution.egressTarget = arbitrary-global-endpoint
→ DENY
```

### 11.6 Runtime control

```text
workload.state = QUARANTINED
actor.role = DEVELOPER
action = RESUME
→ DENY
```

The runtime state/action remains an engine concept; governance facts make the control request evaluable without redefining runtime lifecycle semantics as ontology categories.

---

## 12. Governance relevance litmus test

A concept belongs in TramAI's governance vocabulary only when its semantics can materially affect a supported:

```text
governance decision
constraint
enforcement boundary
evidence claim
```

Practical question:

> **Can this fact change an execution, routing, approval, disclosure, learning, runtime-control, or evidence decision?**

If yes, it is a candidate governance fact.

If no, keep it in application/domain logic.

For example, TramAI should not attempt to model:

```text
Invoice belongsTo PurchaseOrder
PurchaseOrder originatesFrom Vendor
Vendor hasLegalEntity
```

unless the application maps a business property into a governance-relevant fact such as:

```text
businessPurpose = APPROVED_VENDOR_PAYMENT
action.risk = HIGH
data.classification = CONFIDENTIAL
```

This prevents a canonical governance vocabulary from becoming a canonical business ontology.

---

## 13. Evidence and explainability

Governance facts used by authoritative decisions should be explainable safely.

Evidence may need to preserve:

```text
fact key/type
normalized value
source/provenance
source identity/version where relevant
confidence only where semantically meaningful
policy scope
policy/configuration version
constraint produced
reason code
```

Raw sensitive values remain minimized/redacted according to evidence policy.

The control plane should be able to explain:

```text
Fact:
  data.classification = CONFIDENTIAL
  source = APPLICATION_DECLARED

Fact:
  action.risk = HIGH
  source = ORGANIZATION_THRESHOLD_POLICY

Organization constraint:
  REQUIRE_APPROVAL

Workload constraint:
  ALLOW

Effective decision:
  REQUIRE_APPROVAL
```

This is more useful than a generic statement that "a rule matched."

---

## 14. Simulation, testing, and replay

The same fact model should feed:

```text
runtime evaluation
policy simulation
governance contract tests
forensic reconstruction
policy replay where supported
```

A simulator must not invent a second semantic representation of classification, risk, tool effect, purpose, or trust zone.

Replay-relevant facts require stable identity/version/provenance where semantic drift would change decisions.

---

## 15. 0.7.0 bounded P1 foundation

The first implementation should stay deliberately small.

### P1 foundation

1. Define the canonical governance-vocabulary boundary.
2. Identify a minimal stable core set of existing TramAI concepts rather than inventing duplicates.
3. Define a typed `GovernanceFacts`/equivalent representation.
4. Preserve fact provenance/authority.
5. Distinguish TramAI core vocabulary from organization extension vocabulary.
6. Define restrictive constraint-composition semantics independent of one policy domain.
7. Prove the model in at least two or three real governance boundaries, preferably:
   - routing/provider eligibility;
   - tool obligation/governance;
   - approval decisions.
8. Project enough fact/constraint evidence for simulation, testing, and reconstruction.
9. Preserve backward compatibility for existing specialized policy APIs while migration/evolution is deliberate.

This is an architectural convergence effort, not a requirement to rewrite every policy subsystem in one release.

---

## 16. Mandatory design refinement before API freeze

The public extension model is intentionally **not frozen by this roadmap**.

Before stable API freeze, a focused design review must decide at least:

```text
Which concepts are truly core?
Which facts are optional vs required per governance boundary?
How is fact provenance represented?
How are unknown/missing facts handled?
How do organization extensions avoid collisions?
Can extensions narrow core semantics but never redefine/widen them?
What exactly does restrictive composition mean across ALLOW / DENY / REQUIRE_APPROVAL / REQUIRE_TRANSFORMATION?
How are conflicts represented and explained?
How are fact/vocabulary versions recorded for replay?
How do specialized routing/tool/approval APIs migrate or adapt?
What is the compatibility contract for Kotlin and Java users?
What facts may be exposed in audit/evidence without leaking sensitive data?
```

Core refinement gate:

> **Do not freeze a general custom-governance SPI until vocabulary ownership, fact provenance, extension boundaries, restrictive composition, evidence, and compatibility semantics are reviewed together.**

---

## 17. Security and governance invariants

1. Missing or unknown governance facts never silently create authority where strict policy requires them.
2. Model-reported facts do not silently become authoritative application/policy facts.
3. Organization extensions cannot redefine TramAI core decision semantics.
4. Lower policy scopes cannot silently widen higher-scope constraints.
5. Extension vocabulary cannot bypass existing tool, approval, routing, privacy, or runtime authority boundaries.
6. Preference/optimization remains downstream of eligibility and cannot reinterpret governance facts to widen authority.
7. Facts used for authoritative decisions carry enough provenance/version identity for explanation and supported replay.
8. Evidence projection does not duplicate sensitive raw facts unnecessarily.
9. Runtime/control-plane clients do not independently recompute derived governance meaning.
10. General multi-hop semantic inference is not introduced implicitly through convenience APIs.

---

## 18. Testing direction

Required discriminator categories should include:

```text
same canonical fact → same policy meaning across routing/tool/approval consumers
```

```text
organization DENY + workload ALLOW → effective DENY
```

```text
model reports HIGH but trusted application fact says LOW
→ authority/provenance semantics are explicit and deterministic
```

```text
unknown required classification in strict mode
→ no permissive fallback
```

```text
organization extension category present
→ may influence configured policy
→ cannot redefine core DENY semantics
```

```text
same fact set in simulation and execution
→ same pre-execution decision where dynamic state is equivalent
```

```text
reconstruction shows fact provenance + produced constraints
→ performs zero side effects
```

Mutation/adversarial tests should prove that removing provenance, restrictive-composition, or unknown-fact guards is detected.

---

## 19. Explicit non-goals

This capability does **not** require:

- RDF;
- OWL;
- SPARQL;
- a graph database;
- a generic knowledge graph;
- arbitrary relation traversal;
- general multi-hop inference;
- general rule chaining;
- a generic ontology editor;
- a business-domain ontology;
- a universal ABAC/IAM language;
- string-expression policy rules;
- arbitrary boolean policy DSLs;
- automatic inference merely to reduce explicit configuration;
- replacement of application domain models with TramAI concepts;
- rewriting every existing policy subsystem in 0.7.0.

Controlled Level-2 derivations, if ever justified, require a separate design decision and are not implied by this roadmap.

---

## 20. Priority

This is an **architecture commitment with a bounded P1 foundation**.

0.7.0 should prevent routing, tools, approvals, learning, egress, and runtime governance from hardening into incompatible semantic islands.

It does not need to finish a universal custom-policy framework before release.

If the shared-fact foundation slips to first 0.7.x, 0.7.0 implementations should still avoid introducing conflicting public meanings for the same governance concepts.

---

## 21. Product principle

> **TramAI defines a canonical, extensible Governance Vocabulary and converts governed execution into typed Governance Facts. Organization policies operate on that shared semantic model; TramAI remains responsible for deterministic composition, enforcement, evidence, and replay integrity.**

And the boundary remains:

> **Model the governance world, not the business world.**
