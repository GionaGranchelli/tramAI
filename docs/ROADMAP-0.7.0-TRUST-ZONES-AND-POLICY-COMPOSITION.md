# TramAI 0.7.0 — Trust Zones and Policy Composition

> **Status:** Draft roadmap architecture decision  
> **Target release:** TramAI 0.7.0  
> **Relationship:** Complements `ROADMAP-0.7.0.md`, especially policy-aware provider/model selection, Classification Pipeline 2.0, workload identity, simulation, and control-plane explainability  
> **Primary objective:** Keep TramAI simple for small deployments while allowing enterprises to model multiple local/cloud execution boundaries and compose restrictive governance policy without baking one organization's topology into the runtime.  

---

## 1. Executive Decision

TramAI 0.7.0 should keep the existing coarse provider trust categories:

```text
LOCAL
EU_CLOUD
GLOBAL_CLOUD
```

but stop treating those categories as sufficient identities for real enterprise execution boundaries.

0.7.0 should introduce the architectural direction of **named trust zones** and **restrictive policy composition**:

```text
provider deployment
       ↓
named trust zone
       ↓
coarse trust-zone category
       ↓
policy eligibility
       ↓
preference among eligible candidates
```

The release must preserve these principles:

1. **Topology, policy, and preference are separate concerns.**
2. **Provider deployment, not provider brand, owns the trust-zone association.**
3. **Lower-level policies narrow authority by default; they do not silently widen it.**
4. **`secure()` remains deny-by-default and does not guess a universal enterprise routing matrix.**
5. **Convenience presets/templates are explicit references, not hidden production policy.**
6. **The first 0.7.0 implementation should stay intentionally constrained and must not become a generic ABAC/rules-language project.**

---

## 2. Why the Current Three-Zone Model Is Not Enough

The existing model is useful for a simple deployment:

```text
ollama       → LOCAL
mistral-eu   → EU_CLOUD
openai       → GLOBAL_CLOUD
```

A realistic organization may instead have:

```text
local-amsterdam
local-frankfurt
finance-private-cluster
azure-openai-westeurope-prod
azure-openai-eastus-dev
bedrock-frankfurt
mistral-eu-saas
openai-global
```

Collapsing both `local-amsterdam` and `finance-private-cluster` to only `LOCAL` loses information that may be authoritative for policy.

Likewise, assigning a trust zone to the provider brand is wrong. The same provider technology may exist in several deployment boundaries:

```text
azure-openai-westeurope-prod → azure-eu-production
azure-openai-eastus-dev      → us-development-cloud
```

Same provider family; different governance boundary.

The coarse category is still useful, but it answers only:

> What broad class of execution boundary is this?

The named zone answers:

> Which actual organization-defined boundary is this workload about to use?

---

## 3. Target Conceptual Model

### 3.1 Trust-zone category

Keep a small stable category model such as:

```text
LOCAL
EU_CLOUD
GLOBAL_CLOUD
```

0.7.0 should not respond to enterprise variety by adding an ever-growing enum such as:

```text
PRIVATE_CLOUD
US_CLOUD
AIR_GAPPED
ON_PREM
EDGE
SOVEREIGN_CLOUD
...
```

That cannot represent every organization's topology and would couple TramAI's API to deployment taxonomy churn.

### 3.2 Named trust zone

Introduce the architectural concept of an organization-defined trust-zone identity:

```yaml
trust-zones:
  local-amsterdam:
    category: LOCAL

  local-frankfurt:
    category: LOCAL

  azure-eu-production:
    category: EU_CLOUD

  bedrock-frankfurt:
    category: EU_CLOUD

  openai-global:
    category: GLOBAL_CLOUD
```

The exact configuration/API shape is an implementation decision, but these semantics are required:

- zone ID is stable and organization-defined;
- zone category is explicit;
- provider deployments reference a named zone;
- policy may target named zones;
- category remains available for broad compatibility rules and conservative defaults;
- unknown/missing zone identity cannot silently inherit a permissive route.

### 3.3 Provider deployment identity

The routing unit should conceptually be a **provider deployment**, not only a provider vendor/type.

Example:

```yaml
providers:
  ollama-amsterdam:
    zone: local-amsterdam

  vllm-frankfurt:
    zone: local-frankfurt

  azure-openai-prod:
    zone: azure-eu-production

  bedrock-prod:
    zone: bedrock-frankfurt

  openai-global:
    zone: openai-global
```

This allows multiple instances of the same provider adapter without conflating their deployment trust boundaries.

---

## 4. Separate Topology, Policy, and Preference

### 4.1 Topology

Topology describes what execution boundaries exist and where provider deployments belong.

Example:

```text
local-amsterdam       LOCAL
local-frankfurt       LOCAL
azure-eu-production   EU_CLOUD
bedrock-frankfurt     EU_CLOUD
openai-global         GLOBAL_CLOUD
```

Topology does **not** itself grant permission.

### 4.2 Policy

Policy determines which candidate deployments remain eligible for a workload/invocation.

Example:

```text
PUBLIC:
  local-amsterdam
  local-frankfurt
  azure-eu-production
  bedrock-frankfurt
  openai-global

CONFIDENTIAL:
  local-amsterdam
  local-frankfurt
  azure-eu-production

RESTRICTED:
  local-frankfurt
```

### 4.3 Preference

Preference chooses among candidates that already survived policy and runtime eligibility checks.

Examples:

```text
lowest cost
lowest latency
preferred model
preferred provider deployment
capacity/load
quality score
```

Preference must never expand eligibility.

The existing roadmap invariant therefore remains authoritative:

```text
eligible = policy ∩ classification ∩ capability ∩ registration ∩ runtime constraints
selected = preference(eligible)
```

---

## 5. Default Policy Decision

### 5.1 `secure()`

`PolicyConfiguration.secure()` / sovereign secure defaults should continue to mean:

> **Deny by default; require explicit authority for production execution.**

TramAI must not ship a supposedly universal production policy such as:

```text
PUBLIC       → all zones
INTERNAL     → local + EU cloud
CONFIDENTIAL → local + EU cloud
RESTRICTED   → local only
```

That may be appropriate for one organization and wrong for another.

The framework cannot know whether a company's `CONFIDENTIAL` data is allowed in public EU cloud, private cloud only, on-prem only, or nowhere in AI systems.

### 5.2 `preview()` / development convenience

Developer/demo presets may remain ergonomic, but must be clearly distinguishable from production security policy.

They must not be presented as recommended enterprise governance.

### 5.3 Reference policy templates

0.7.0 may provide explicit examples/templates such as:

```text
local-only
hybrid-enterprise
eu-sovereign
```

but these are **reference configurations**, not implicit defaults.

A reference policy must materialize into an inspectable effective configuration before it can be treated as authority.

---

## 6. Policy Composition

Enterprise deployments need several governance scopes without forcing application teams to duplicate organization policy.

The target conceptual model is:

```text
organization policy
        ∩
environment policy
        ∩
workload policy
        ↓
effective policy
```

### 6.1 Restrictive composition

The default composition rule should be intersection/narrowing.

Example:

```text
Organization:
  allowed zones = {A, B, C}

Production environment:
  allowed zones = {A, B}

Finance workload:
  allowed zones = {B, D}

Effective:
  {B}
```

A workload-level policy cannot silently use `D` merely because it requested it; the organization and environment scopes did not grant it.

### 6.2 No implicit widening

If organization policy states:

```text
RESTRICTED → local-frankfurt only
```

then a workload policy requesting:

```text
RESTRICTED → openai-global
```

must not override the organization restriction.

Widening authority, if TramAI ever supports it, must require a separate explicit administrative mechanism with its own authorization, evidence, and audit semantics. It is not part of this 0.7.0 slice.

### 6.3 Avoid inheritance/override semantics

0.7.0 should not introduce object-oriented policy inheritance such as:

```text
FinancePolicy extends CorporatePolicy
```

with ambiguous `override` behavior.

Restrictive composition is easier to explain, test, simulate, and audit.

---

## 7. Explainability Requirements

The control plane must be able to distinguish why a candidate was accepted or rejected at each scope.

Example:

```text
azure-eu-production
  organization policy: ALLOWED
  production policy:   ALLOWED
  workload policy:     DENIED
  final eligibility:   DENIED

local-frankfurt
  organization policy: ALLOWED
  production policy:   ALLOWED
  workload policy:     ALLOWED
  capability:          SUPPORTED
  health:              AVAILABLE
  final eligibility:   ELIGIBLE
```

This reason path should be reused by:

- runtime routing evidence;
- policy simulation / decision preview;
- governance contract tests;
- Dashboard 2.0;
- incident reconstruction;
- auditor-safe evidence where appropriate.

The dashboard must not recompute effective policy independently.

---

## 8. Interaction with Document Metadata Classification

The document-metadata roadmap slice should feed directly into this model.

Example:

```text
document metadata label = SECRET
        ↓
organization mapping = RESTRICTED
        ↓
effective classification = RESTRICTED
        ↓
provider deployments discovered
        ↓
named-zone policy evaluation
        ↓
only local-frankfurt remains eligible
        ↓
preference selects model/provider inside local-frankfurt
```

Classification determines which policy constraints apply; trust-zone topology describes where candidates execute; preference acts only after those constraints are satisfied.

---

## 9. Minimal 0.7.0 Scope

0.7.0 does not need a universal enterprise policy language.

The minimum credible implementation should prove:

1. Multiple named zones may share the same coarse category.
2. Multiple deployments of the same provider technology may belong to different named zones.
3. Routing policy can target named zones without losing the coarse category compatibility model.
4. Organization/environment/workload constraints compose restrictively.
5. A lower-level scope cannot silently widen an upper-level denial.
6. Policy evaluation exposes stable reason codes / reason paths for composition decisions.
7. Preference/cost/latency never expands the policy-eligible candidate set.
8. Missing/unknown zone configuration fails closed in strict/sovereign mode.
9. Existing simple `LOCAL` / `EU_CLOUD` / `GLOBAL_CLOUD` use cases remain straightforward and migratable.

Recommended implementation order:

```text
1. Named zone identity + category
2. Provider-deployment → named-zone mapping
3. Named-zone eligibility rules
4. Restrictive policy composition
5. Decision/reason model integration
6. Simulation + governance TCK coverage
7. Dashboard/control-plane projection
```

---

## 10. Deliberate Non-Goals for This Slice

Do not make these 0.7.0 blockers:

- generic ABAC expression language;
- arbitrary boolean policy DSL;
- every jurisdiction/region/operator represented as a first-class enum;
- automatic cloud-account discovery;
- infrastructure firewall/NetworkPolicy enforcement;
- cross-organization policy federation;
- policy exception workflow that can widen authority;
- generic IAM replacement;
- generic Kubernetes/cloud topology inventory.

The architecture should leave room for later trust-zone attributes such as region, jurisdiction, environment, operator, or certification metadata, but 0.7.0 should not require a generic selector engine.

---

## 11. Security Invariants

The following should be treated as architectural invariants:

```text
selected candidate ∈ effective eligible set
```

```text
preference(eligible) ⊆ eligible
```

```text
effective allowed zones ⊆ organization allowed zones
```

```text
effective allowed zones ⊆ environment allowed zones
```

```text
effective allowed zones ⊆ workload allowed zones
```

where a scope is omitted, it contributes no additional widening authority.

Additionally:

- unknown named zones are not treated as trusted;
- provider brand alone never implies trust location;
- a provider deployment cannot route without an authoritative zone association in strict/sovereign mode;
- fallback is evaluated under the same effective policy and cannot escape the named-zone restriction;
- simulation must use the same composition and eligibility evaluator as execution;
- policy evidence must identify the effective policy/configuration version used for the decision.

---

## 12. Testing Direction

Governance contract tests should include at least:

```text
same provider adapter, EU deployment allowed, US deployment denied
```

```text
two LOCAL zones, RESTRICTED workload permitted in only one
```

```text
organization denies zone X, workload requests X → still denied
```

```text
organization allows {A,B}, environment allows {B,C}, workload allows {B,D} → effective {B}
```

```text
fallback provider in denied named zone → fallback rejected
```

```text
cheapest candidate is policy-denied → cannot be selected
```

```text
missing named-zone mapping in sovereign strict mode → fail closed before provider invocation
```

Mutation tests should prove that removing any composition/intersection guard makes at least one discriminator fail.

---

## 13. Product Positioning

This model allows TramAI to serve both ends of the market without two governance architectures.

### Small deployment

```text
ollama → local
openai → global
```

Configuration remains simple.

### Enterprise deployment

```text
multiple local clusters
multiple cloud accounts/regions
multiple provider deployments
organization policy
environment policy
workload policy
```

The same policy engine and candidate-selection model scale to the larger topology.

The product principle is:

> **Categories provide portable defaults. Named zones model the organization's real trust boundaries. Policy decides eligibility. Preference only chooses among what policy already allows.**

---

## 14. 0.7.0 Decision Summary

0.7.0 should therefore lock the following architectural direction before the control-plane APIs and Dashboard 2.0 make the current three-zone topology difficult to evolve:

- retain `LOCAL`, `EU_CLOUD`, `GLOBAL_CLOUD` as coarse categories;
- add the concept of organization-defined named trust zones;
- associate trust with concrete provider deployments, not provider brands;
- separate topology, policy, and preference;
- keep production secure defaults deny-by-default;
- use explicit reference policy templates rather than a universal enterprise policy;
- compose organization/environment/workload policy by restrictive intersection;
- expose the effective reason path through simulation, telemetry, and control-plane APIs;
- defer generic ABAC/rule-language ambitions until there is demonstrated need.
