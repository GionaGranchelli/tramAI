# TramAI 0.7.0 — Policy-Constrained Adaptive Routing and Selection

> **Status:** P0 routing-semantics refinement + bounded P1/architecture direction  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Refines `ROADMAP-0.7.0-RELEASE-CUT.md` P0.5, named trust zones/policy composition, Governance Vocabulary + Governance Facts, simulation, evidence, findings, and future FinOps/operational routing work.  
> **Scope:** Allow provider/model routing to consider availability, health, cost, latency, quality, capacity, quota, budget, and other typed operational/economic signals without ever allowing optimization or fallback to widen governance authority.

---

## 1. Decision

TramAI routing should distinguish three different questions:

```text
1. AUTHORIZED
   Where MAY this invocation execute?

2. VIABLE
   Which authorized candidates CAN execute now?

3. SELECTED
   Which viable candidate is PREFERRED for this invocation?
```

Canonical direction:

```text
policy + classification + trust + capability + registration
                         ↓
                   AUTHORIZED SET
                         ↓
availability + health + quota + capacity + runtime constraints
                         ↓
                    VIABLE SET
                         ↓
cost / latency / quality / locality / preference / budget strategy
                         ↓
                      SELECTED
                         ↓
                     EXECUTION
```

Core invariant:

> **Governance determines the permitted set. Runtime viability determines the possible set. Optimization determines the preferred candidate.**

Neither runtime viability nor optimization may make a governance-denied candidate executable.

---

## 2. Why this distinction matters

Routing concerns currently risk being collapsed into one generic concept of "policy" or "preference".

Those concerns have different authority:

```text
CONFIDENTIAL data not allowed in GLOBAL_CLOUD
    = governance constraint

provider health = DOWN
    = runtime viability fact

provider A is cheaper than provider B
    = optimization signal
```

They should not be treated as equivalent.

Example:

```text
LOCAL_MODEL
  governance: ALLOWED
  health: DOWN
  estimated cost: 0

EU_CLOUD
  governance: ALLOWED
  health: HEALTHY
  estimated cost: 0.01

GLOBAL_CLOUD
  governance: DENIED for CONFIDENTIAL
  health: HEALTHY
  estimated cost: 0.005
```

The result must be:

```text
GLOBAL_CLOUD -> excluded by governance
LOCAL_MODEL  -> authorized but not viable
EU_CLOUD     -> selected
```

The cheaper denied provider never becomes eligible merely because the preferred/authorized candidate is unavailable.

---

## 3. Canonical routing model

The existing roadmap invariant remains valid but becomes more explicit:

```text
authorized =
    policy
  ∩ classification
  ∩ trust/topology
  ∩ capability
  ∩ registration

viable =
    authorized
  ∩ availability
  ∩ health
  ∩ quota
  ∩ capacity
  ∩ runtime constraints

selected = selectionStrategy(viable)
```

For every invocation:

```text
selected ∈ viable
viable ⊆ authorized
```

Therefore:

```text
selected ∈ authorized
```

This must remain true for initial selection, retry, failover, and re-selection.

---

## 4. Hard constraints vs optimization objectives

Routing inputs must distinguish facts that exclude a candidate from facts that rank candidates.

Typical **hard constraints** include:

- data classification / residency restriction;
- named trust-zone restriction;
- missing required model capability;
- provider/model not registered;
- provider unavailable;
- circuit breaker open;
- exhausted quota where invocation cannot proceed;
- insufficient context window;
- hard budget ceiling;
- unsupported modality;
- explicit runtime quarantine or deny.

Typical **optimization objectives** include:

- lower estimated cost;
- lower expected latency;
- higher quality score;
- preferred local execution;
- preferred provider/model/deployment;
- lower current load;
- greater remaining capacity;
- better reliability history;
- lower energy/carbon impact where configured.

Some concepts can be either a constraint or an objective depending on the declared contract.

Examples:

```text
latency <= 2s
```

is a hard constraint, while:

```text
prefer lower latency
```

is an objective.

Likewise:

```text
quality >= REQUIRED_QUALITY
```

is a constraint, while:

```text
prefer highest quality
```

is an objective.

The API must not silently reinterpret one as the other.

---

## 5. Typed selection strategies

The initial architecture should favor explicit, explainable strategies over opaque optimization engines.

Candidate strategy concepts include:

```text
LOWEST_COST
LOWEST_LATENCY
HIGHEST_QUALITY
PREFER_LOCAL
PREFER_PROVIDER
PREFER_DEPLOYMENT
HIGHEST_AVAILABLE_CAPACITY
MOST_RELIABLE
```

The exact public API is not frozen by this roadmap.

A strategy should operate only on the already viable candidate set.

A useful initial composition model is lexicographic/ordered selection rather than arbitrary weighted scoring.

Example:

```text
1. governance eligible
2. healthy and available
3. TOOL_CALLING supported
4. quality >= required threshold
5. choose lowest estimated cost
6. tie-break by expected latency
```

This is easier to simulate, test, explain, and reconstruct than:

```text
score = cost*0.42 + latency*0.31 + quality*0.27
```

General weighted multi-objective optimization is therefore **not required for the initial 0.7.0 slice**.

---

## 6. Availability, health, capacity, and quota

Runtime viability should be based on typed facts with explicit source and freshness semantics.

Candidate operational facts may include:

```text
availability
health
circuit-breaker state
rate-limit/quota state
available capacity/current load
queue depth
context-window availability
runtime quarantine state
```

Important distinction:

```text
unhealthy != governance denied
```

and:

```text
governance allowed != currently executable
```

The control plane/evidence model should preserve both.

Stale or unknown runtime facts must have explicit semantics. Sensitive profiles must not silently treat unknown availability/capacity state as proof that a candidate is viable where doing so would violate configured fail-closed requirements.

---

## 7. Cost and budget

Cost should normally be an optimization signal, but budget policy can create hard constraints.

Example:

```text
estimated cost = optimization fact
monthly workload budget exhausted = hard constraint
```

Conceptually:

```text
candidate A
  authorized = true
  viable = true
  estimated cost = 0.012

candidate B
  authorized = true
  viable = true
  estimated cost = 0.006

strategy = LOWEST_COST

selected = B
```

But:

```text
candidate B
  estimated cost = 0.006
  GLOBAL_CLOUD = governance denied
```

must remain excluded.

0.7.0 does not need sophisticated dynamic FinOps optimization. It should preserve typed cost/budget facts and the separation between budget constraints and cost preference.

---

## 8. Quality and capability

Capability and quality must not be conflated.

Example:

```text
TOOL_CALLING supported
```

is capability eligibility.

A historical/evaluated quality measure such as:

```text
qualityTier = HIGH
```

may instead be a constraint or ranking signal.

Quality signals must identify their authority/source where used for consequential routing. Model self-description is not authoritative quality evidence by default.

The architecture should allow future workload-specific quality requirements without forcing a universal scoring model into TramAI core.

---

## 9. Failover is re-routing, not policy escape

Fallback/failover must be modeled as another routing decision under the same effective governance constraints.

Required flow:

```text
invocation
    ↓
authorized set
    ↓
viable set
    ↓
select A
    ↓
A fails / rate-limited / becomes unavailable
    ↓
refresh relevant runtime facts
    ↓
re-evaluate viability under SAME governance authority
    ↓
select B from remaining viable authorized candidates
```

Forbidden flow:

```text
A failed
    ↓
use any configured fallback even if governance-denied
```

Core invariant:

```text
fallbackCandidate ∈ currentAuthorizedSet
```

A retry/failover must not reuse stale eligibility if a governance-relevant fact changed and the configured contract requires re-evaluation.

---

## 10. Interaction with Governance Vocabulary + Governance Facts

The shared governance-facts architecture should prevent routing from inventing a parallel semantic universe.

A useful conceptual split is:

```text
GovernanceFacts
  classification
  trust zone
  actor/workload constraints
  required capability

OperationalFacts
  availability
  health
  capacity
  quota
  reliability
  expected latency

EconomicFacts
  estimated cost
  remaining budget
  budget ceiling
```

This split is descriptive, not a frozen class hierarchy.

All authoritative/relevant facts should preserve provenance and freshness where necessary for explainability and replay.

Organization extensions may add routing-relevant facts, but they must not redefine core governance meanings or allow a selection strategy to widen authorization.

---

## 11. Explainability and evidence

The routing decision should be explainable by stage.

Example:

```text
Selected
--------
mistral-eu-prod

Authorization
-------------
classification: CONFIDENTIAL
zone: EU_PRODUCTION
policy: ALLOWED
capability: TOOL_CALLING supported

Runtime viability
-----------------
health: HEALTHY
capacity: AVAILABLE
quota: AVAILABLE

Selection
---------
strategy: LOWEST_COST
estimated cost: 0.008
expected latency: 420 ms

Other candidates
----------------
ollama-local
  AUTHORIZED
  NOT_VIABLE: REQUIRED_CAPABILITY_MISSING

azure-eu
  AUTHORIZED
  VIABLE
  NOT_SELECTED: HIGHER_ESTIMATED_COST

openai-global
  NOT_AUTHORIZED: CLASSIFICATION_TRUST_ZONE_DENIED
```

Stable reason families should distinguish at least:

```text
NOT_AUTHORIZED
NOT_VIABLE
NOT_SELECTED
```

with typed reason details rather than one generic "provider rejected" string.

The dashboard must display authoritative stage/reason data rather than re-score candidates itself.

---

## 12. Simulation and governance testing

Policy simulation should be able to separate governance from routing optimization.

Useful scenarios include:

```text
what if local provider is unavailable?
what if EU provider reaches quota?
what if estimated cost doubles?
what if latency threshold becomes 1s?
what if workload budget is exhausted?
what if a cheaper GLOBAL_CLOUD provider exists but classification forbids it?
```

Simulation must not use different selection semantics than production.

Governance/routing contract tests should prove:

1. cheapest governance-denied candidate is never selected;
2. lowest-latency governance-denied candidate is never selected;
3. unavailable authorized candidate is not selected when a viable authorized candidate exists;
4. failure of selected candidate re-routes only within the authorized set;
5. hard budget ceiling excludes an otherwise viable candidate;
6. cost preference ranks but does not itself authorize;
7. latency as a hard constraint behaves differently from latency as an objective;
8. stable reasons distinguish denied, non-viable, and viable-but-not-selected candidates;
9. selection is deterministic when the strategy and sampled facts are deterministic;
10. simulation and runtime produce the same selection for equivalent sampled facts.

Mutation tests should kill removal of the authorized-set boundary around viability, selection, and fallback.

---

## 13. Initial 0.7.0 scope

The bounded implementation should avoid turning TramAI into a general optimizer.

The minimum useful direction is:

1. formalize `AUTHORIZED -> VIABLE -> SELECTED` semantics;
2. preserve existing governance eligibility as the outer non-widening boundary;
3. distinguish hard runtime constraints from ranking objectives;
4. support a small set of typed, explainable selection strategies;
5. represent availability/health and cost signals where reliable data exists;
6. keep failover/retry inside the same governance boundary;
7. expose stage-specific reason/evidence;
8. reuse production semantics in simulation/testing.

Availability breadth, live provider metrics, sophisticated cost accounting, quality benchmarking, capacity prediction, and multi-objective optimization may continue into 0.7.x/later.

---

## 14. Explicit non-goals

Do not make the following 0.7.0 blockers:

- generic mathematical optimization framework;
- arbitrary weighted scoring language;
- machine-learned routing policy;
- provider-price web crawler;
- global cost oracle;
- predictive autoscaling;
- universal quality benchmark/scoring system;
- generic service-mesh/load-balancer replacement;
- infrastructure health-monitoring platform;
- cross-cloud capacity scheduler;
- optimization that can override governance denial.

---

## 15. Security and correctness invariants

Treat these as architectural invariants:

```text
selected ∈ viable
viable ⊆ authorized
fallback ∈ authorized
```

```text
optimizationCanExpandAuthorization = false
runtimeAvailabilityCanExpandAuthorization = false
costCanOverrideGovernanceDeny = false
latencyCanOverrideGovernanceDeny = false
qualityCanOverrideGovernanceDeny = false
fallbackCanOverrideGovernanceDeny = false
```

Additionally:

- missing selection metadata must not be interpreted as authorization;
- a candidate excluded for governance reasons must remain distinguishable from one excluded for runtime reasons;
- selection strategy identity/configuration should be reconstructable when it materially affects a decision;
- dynamic facts used for selection should carry sampled time/freshness where necessary;
- retry/failover cannot silently switch to a candidate whose required capability or trust-zone constraints are not satisfied;
- dashboard/operator preference cannot directly inject a provider outside the authorized set;
- cost/latency/quality telemetry must not become a covert path for sensitive prompt/content capture.

---

## 16. Product statement

> **TramAI first decides where an AI workload is allowed to run, then where it can run now, then which permitted option is best.**

This allows sovereignty, security, availability, reliability, performance, and economics to coexist without weakening governance.
