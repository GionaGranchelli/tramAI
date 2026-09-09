# Epic 0.7.2 — Classification, Trust Zones & Restrictive Policy

**Branch:** `epic/0.7.2-policy-trust-zones`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.1 SOFT

## Executive decision

Resolve classification, concrete provider-deployment trust topology, and effective policy before provider/model exposure can become authorized.

## Scope

- integrate classification before provider/model eligibility;
- preserve explicit stronger classification over weaker signals;
- fail closed for unknown/missing classification where policy requires it;
- provider-deployment identity distinct from provider brand;
- named organization-defined trust zones plus portable `LOCAL`, `EU_CLOUD`, `GLOBAL_CLOUD` categories;
- restrictive organization/environment/workload policy composition;
- stable safe reason paths for denial/constraint decisions;
- typed evidence sufficient for downstream projection/reconstruction.

## Non-goals

- rich classification UX/ML classification;
- Governance Vocabulary/Facts public foundation;
- broad policy simulation/authoring;
- policy DSL redesign.

## Core invariants

```text
classification required by policy happens before provider exposure
explicit stronger classification is not silently downgraded
provider brand != proof of trust/residency/locality
organization ∩ environment ∩ workload = effective policy
lower scope cannot widen higher-level denial
```

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.2a | Baseline classification/policy audit | Characterize existing classifiers, routing policy, provider metadata and trust assumptions |
| 0.7.2b | Provider deployment + named zone contract | Introduce/normalize deployment identity and named-zone/category association |
| 0.7.2c | Classification-before-exposure integration | Move/guard orchestration ordering so required classification precedes eligibility/exposure |
| 0.7.2d | Restrictive policy composition | Deterministic org/environment/workload intersection with no widening path |
| 0.7.2e | Missing/unknown semantics | Fail-closed behavior where classification/topology is required |
| 0.7.2f | Reason/evidence model | Stable safe reasons for classification/trust/policy outcomes |
| 0.7.2g | Adversarial/TCK/mutation proof | Prove ordering, no downgrade, no brand trust, no widening |
| 0.7.2h | Integration/docs | Final cross-module docs/evidence and Epic gate |

## Acceptance criteria

- No policy-required request reaches an ineligible provider before classification resolves.
- Concrete provider deployments, not brands, carry trust-zone assignment.
- Lower scope cannot authorize something denied above it.
- Missing required classification/topology cannot silently become permissive.
- Decisions expose safe stable reasons usable by 0.7.3/0.7.4.

## Adversarial proof

Reject implementations that classify after exposure, downgrade an explicit strong class, infer EU/local trust from vendor name, union policies instead of intersecting them, or treat unknown as allow.

## Mutation expectations

High-value mutations: intersection→union, deny→allow default, comparison weakening, classification ordering bypass, explicit-class precedence removal.
