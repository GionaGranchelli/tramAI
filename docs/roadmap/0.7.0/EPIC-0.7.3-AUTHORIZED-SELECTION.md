# Epic 0.7.3 — Explainable Authorized Provider/Model Selection

**Branch:** `epic/0.7.3-authorized-selection`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.2 HARD

## Executive decision

Make authorization, runtime viability, and selection separate typed stages so fallback, preference, availability, health, cost, or latency can never create governance authority.

## Core model

```text
authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration
viable     = authorized ∩ required runtime constraints
selected   = selectionStrategy(viable)
```

## Core invariants

```text
selected ∈ viable
viable ⊆ authorized
fallback/retry remains inside current governance authority
optimization signals can rank but cannot authorize
```

## Scope

- explicit candidate/deployment/model decision model;
- authorization reasons and runtime non-viability reasons where authoritative;
- selection/non-selection reason paths;
- constrained fallback/retry;
- decision/configuration identity/digest where required for evidence;
- safe historical decision evidence.

## Non-goals

- rich adaptive routing;
- machine-learned routing;
- FinOps optimization;
- broad cost/quality strategy productization.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.3a | Routing baseline audit | Map current registration/capability/policy/fallback stages and hidden coupling |
| 0.7.3b | Candidate decision types | Typed authorized/not-authorized/viability/selection states and stable reason families |
| 0.7.3c | Authorized-set derivation | Compute governance-authorized candidates from 0.7.2 contracts |
| 0.7.3d | Viability stage | Apply runtime constraints only after authorization |
| 0.7.3e | Selection/fallback fencing | Ensure selection/retry/fallback cannot escape viable authorized set |
| 0.7.3f | Decision identity/evidence | Persist enough context to explain historical selection safely |
| 0.7.3g | Adversarial/mutation/provider proof | Prove ineligible routes never become selected via fallback/preference |
| 0.7.3h | Integration/docs | Final API/architecture docs and Epic acceptance |

## Acceptance criteria

- Every selected candidate is viable and authorized.
- Policy-ineligible candidates cannot become selected through retry/fallback/preference.
- Rejected/non-selected candidates expose structured safe reasons.
- Historical evidence identifies the relevant workload/config/policy/routing context.

## Adversarial proof

Reject: selected candidate absent from authorized set; fallback that widens authority; unavailable candidate represented as governance denial; cost/latency preference that injects a candidate; reason text with no stable structured code.

## Mutation expectations

Kill set-membership/boundary mutations, fallback filter removal, authorization/viability stage swaps, and permissive defaults in candidate-state mapping.
