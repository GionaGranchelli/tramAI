# Epic 0.7.2 — Classification, Trust Zones & Restrictive Policy

**Branch:** `epic/0.7.2-policy-trust-zones`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.1 SOFT

## Executive decision

Resolve classification, concrete provider-deployment trust topology, effective policy, and the provider-bound data-release decision before a model invocation can cross a trust boundary. Authorization answers whether a provider may be used; the provider-input release boundary separately answers what exact representation of the governed input that provider may receive.

## Scope

- integrate classification before provider/model eligibility;
- preserve explicit stronger classification over weaker signals;
- fail closed for unknown/missing classification where policy requires it;
- provider-deployment identity distinct from provider brand;
- named organization-defined trust zones plus portable `LOCAL`, `EU_CLOUD`, `GLOBAL_CLOUD` categories;
- restrictive organization/environment/workload policy composition;
- mandatory governed data-release checkpoint immediately before provider invocation;
- distinguish authoritative/canonical execution input from the provider-bound projection derived for one selected deployment;
- support at least `DENY`, `ALLOW_RAW`, and `ALLOW_WITH_MINIMIZATION` provider-input release outcomes, or semantically equivalent typed outcomes chosen during implementation;
- apply required provider-input minimization/redaction without mutating authoritative/canonical workload state;
- recompute the provider-bound projection for fallback/retry when the concrete provider deployment changes rather than reusing a projection authorized for another trust boundary;
- fail closed before invocation when required input minimization/inspection cannot be completed;
- emit safe typed evidence for provider-input release/minimization without persisting raw matched sensitive values;
- stable safe reason paths for denial/constraint decisions;
- typed evidence sufficient for downstream projection/reconstruction.

## Non-goals

- rich classification UX/ML classification;
- Governance Vocabulary/Facts public foundation;
- broad policy simulation/authoring;
- policy DSL redesign;
- a generalized enterprise DLP product spanning every application, tool, telemetry, storage, and learning boundary;
- enterprise DLP-vendor integrations, vault-backed tokenization, or organization-wide discovery/classification products;
- redesigning existing model-output/tool-result DLP unless compatibility work is required to preserve one coherent boundary model.

The 0.7 mandatory slice is the missing **provider-request** release/minimization boundary. Later releases may generalize the same source → destination trust-boundary semantics to tool invocation, observability, learning capture, and external DLP integrations without inventing a second policy engine.

## Core invariants

```text
classification required by policy happens before provider exposure
explicit stronger classification is not silently downgraded
provider brand != proof of trust/residency/locality
organization ∩ environment ∩ workload = effective policy
lower scope cannot widen higher-level denial

provider invocation requires an explicit data-release outcome
providerBoundInput = projection(canonicalInput, selectedDeployment, effectivePolicy)
providerBoundInput transformation never mutates canonicalInput
fallback to a different deployment => derive a new providerBoundInput
required minimization/inspection failure => no provider invocation
safe evidence never contains raw matched sensitive values
```

The provider-input release decision is distinct from provider authorization. A provider may be authorized for a workload while policy still requires minimization of particular data before that concrete invocation.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.2a | Baseline classification/policy/data-boundary audit | Characterize existing classifiers, routing policy, provider metadata, trust assumptions, current output/tool-result DLP, and the missing provider-request release boundary |
| 0.7.2b | Provider deployment + named zone contract | Introduce/normalize deployment identity and named-zone/category association |
| 0.7.2c | Classification-before-exposure integration | Move/guard orchestration ordering so required classification precedes eligibility/exposure |
| 0.7.2d | Provider-input data-release/minimization contract | Define canonical input vs provider-bound projection, typed release outcomes, fail-closed transformation semantics, provider-specific recomputation, and safe evidence |
| 0.7.2e | Restrictive policy composition | Deterministic org/environment/workload intersection with no widening path; provider-input release obligations derive from the same effective policy authority |
| 0.7.2f | Missing/unknown/failure semantics | Fail closed where classification/topology or required provider-input minimization/inspection is unavailable |
| 0.7.2g | Reason/evidence model | Stable safe reasons for classification/trust/policy/data-release outcomes without raw sensitive values |
| 0.7.2h | Adversarial/TCK/mutation proof | Prove ordering, no downgrade, no brand trust, no widening, no pre-release invocation, no cross-provider projection reuse, and no fail-open sanitizer path |
| 0.7.2i | Integration/docs | Final cross-module docs/evidence and Epic gate |

## Acceptance criteria

- No policy-required request reaches an ineligible provider before classification resolves.
- No provider invocation occurs before the selected deployment has an explicit provider-input data-release outcome.
- When policy requires minimization, only the transformed provider-bound projection crosses the provider boundary.
- Canonical/authoritative workload input remains unchanged by provider-specific minimization.
- Changing provider deployment through fallback/retry causes a fresh release/minimization decision and projection for that deployment.
- Required minimization/inspection failure cannot silently fall through to raw provider invocation.
- Data-release evidence exposes stable rule/reason metadata while excluding raw matched sensitive values.
- Concrete provider deployments, not brands, carry trust-zone assignment.
- Lower scope cannot authorize something denied above it.
- Missing required classification/topology cannot silently become permissive.
- Decisions expose safe stable reasons usable by 0.7.3/0.7.4.

## Adversarial proof

Reject implementations that classify after exposure, downgrade an explicit strong class, infer EU/local trust from vendor name, union policies instead of intersecting them, treat unknown as allow, invoke the provider before the release/minimization decision, mutate canonical input in place, reuse an OpenAI-authorized/minimized projection after fallback to a different provider deployment, expose raw matched values in evidence, or pass raw input through when required DLP/minimization fails.

## Mutation expectations

High-value mutations: intersection→union, deny→allow default, comparison weakening, classification ordering bypass, explicit-class precedence removal, provider-release checkpoint bypass, release outcome `DENY`→`ALLOW_RAW`, minimization failure→pass-through, selected-deployment identity ignored during projection derivation, and canonical-input aliasing that allows in-place mutation.
