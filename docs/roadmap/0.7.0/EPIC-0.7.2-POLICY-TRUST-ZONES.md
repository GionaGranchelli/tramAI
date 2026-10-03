# Epic 0.7.2 — Classification, Trust Zones & Restrictive Policy

**Branch:** `epic/0.7.2-policy-trust-zones`  
**Status:** ✅ Complete — restrictive-decision core (see "Delivered" below)  
**Dependencies:** 0.7.1 SOFT

## Executive decision

Resolve classification, concrete provider-deployment trust topology, effective policy, and the provider-bound data-release decision before a model invocation can cross a trust boundary. Authorization answers whether a provider may be used; the provider-input release boundary separately answers what exact representation of the governed input that provider may receive. The governance decision path must remain deterministic and side-effect-free so later execution, external-runtime integration, simulation, and testing can reuse one meaning rather than invent parallel policy engines.

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
- a side-effect-free deterministic evaluation boundary for classification/policy/release meaning;
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
same authoritative facts + same effective policy => same governance decision
policy evaluation itself performs no provider/tool/network side effects
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
| 0.7.2e | Restrictive policy composition | Deterministic org/environment/workload intersection with no widening path; provider-input release obligations derive from the same effective policy authority; evaluation remains side-effect-free |
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
- Equivalent authoritative facts under the same policy/configuration produce the same deterministic decision for the supported profile.
- Decision evaluation can be exercised with provider/tool/network traps and produces no external side effects.

## Adversarial proof

Reject implementations that classify after exposure, downgrade an explicit strong class, infer EU/local trust from vendor name, union policies instead of intersecting them, treat unknown as allow, invoke the provider before the release/minimization decision, mutate canonical input in place, reuse an OpenAI-authorized/minimized projection after fallback to a different provider deployment, expose raw matched values in evidence, pass raw input through when required DLP/minimization fails, or make policy evaluation itself depend on an external provider/tool/network side effect.

## Mutation expectations

High-value mutations: intersection→union, deny→allow default, comparison weakening, classification ordering bypass, explicit-class precedence removal, provider-release checkpoint bypass, release outcome `DENY`→`ALLOW_RAW`, minimization failure→pass-through, selected-deployment identity ignored during projection derivation, and canonical-input aliasing that allows in-place mutation.

## Delivered — 0.7.2 complete (restrictive-decision core)

**Epic head at completion:** `655e607a14118634d5f302aa9ad8024540153128`

The candidate table above was re-scoped during implementation, so its letters do
not all line up with the slices that were actually built. The mapping below is
authoritative for what shipped.

| merged as | slice | what it established | evidence |
|---|---|---|---|
| #485 | 0.7.2a | workload → classification → trust zone, deterministic and fail-closed; an explicit stronger classification is never downgraded | `WorkloadGovernanceResolverTest` (15) |
| #487 | 0.7.2b | a provider *deployment* carries exactly one named trust zone; deployment identity is distinct from provider brand | `ProviderDeploymentTest` (11) |
| #488 | 0.7.2c | restrictive zone compatibility — an explicitly listed ordered pair allows, everything else denies, including the same zone | `TrustZonePolicyTest` (8) |
| #489 | 0.7.2d | the provider-input release decision — released only when the zone policy allows **and** the classification's allow-list contains the provider zone | `ProviderInputReleaseTest` (7) |
| #490 | 0.7.2e | restrictive composition — organization ∩ environment ∩ workload, per classification; a narrower scope cannot widen authority | `EffectiveRoutingPolicyTest` (10) |
| #491 | review findings | the Copilot findings raised against the slices above | `TASK-0.7.2-REVIEW-FINDINGS.md` |

Verification at the epic head, measured rather than assumed:

| gate | result |
|---|---|
| `spotlessCheck` | rc=0 |
| `verifyStaticAnalysis` | rc=0 — Detekt baseline 4792 → 4792, no growth |
| `verify060Architecture` | PASS 10/10 (delta measured against the pre-merge epic tip) |
| `verifyChangePolicy` | PASSED — 11 changed files, class `runtime-behaviour`, no violations |
| the 0.7.2 suites | 56 tests, 0 failing (incl. `ExecutionSecurityContextTest` 5/5) |

The chain, end to end: classify → resolve trust → describe deployment trust →
restrict compatibility → compose restrictions → authorize data release.

## Acceptance-criteria audit

**Met.** Concrete provider deployments rather than brands carry trust-zone
assignment. Lower scope cannot authorize something denied above it. Missing
required classification or topology cannot silently become permissive within the
new boundary: an unresolvable classification refuses, an unknown zone name
resolves to nothing, an unlisted pair denies, an unclassified release withholds.
Equivalent authoritative facts produce the same deterministic decision.
Evaluation is side-effect-free — none of the five types performs I/O.

**Vacuously true — true only because nothing invokes a provider yet.**

- "No provider invocation occurs before the selected deployment has an explicit
  provider-input data-release outcome." Nothing invokes a provider, and the
  release boundary is not wired into any invocation path.
- "No policy-required request reaches an ineligible provider before classification
  resolves." The resolver exists; the orchestration ordering guard does not.
- "Decision evaluation can be exercised with provider/tool/network traps." The
  evaluation is pure, but no trap test exists.

**Not met — features this epic deliberately did not build, or criterion text that
presumes them.** Provider-input minimization/redaction and the provider-bound
projection (canonical vs derived, recomputation when fallback changes the
deployment); safe typed evidence and stable reason metadata for decisions
(decisions are `Boolean` by explicit scope instruction); "decisions expose safe
stable reasons usable by 0.7.3/0.7.4".

One pre-existing seam is recorded rather than fixed here:
`DefaultPolicyEngine.evaluateProviderRouting` returns `null` to mean
*inconclusive*, which falls back to legacy checks. That is permissive-by-fallback
in existing code. The 0.7.2 boundary does not route through it.

## Deliberately deferred — 0.7.2f/g/h

Not started, and not required by the delivered contract. Each carries the
condition under which it should be revived:

- **f — missing/unknown/failure semantics.** Largely satisfied already (see the
  audit above). The residual is the `evaluateProviderRouting` fallback seam.
  *Revive when* a slice routes a decision through that engine check.
- **g — reason/evidence model.** *Revive when* a caller must distinguish denial
  causes. Boolean decisions were an explicit scope instruction, not an oversight.
- **h — adversarial/TCK/mutation proof.** Each invariant already has focused
  adversarial tests (no downgrade, no brand trust, no widening, no pre-release
  invocation, no fail-open path). *Revive when* selection and invocation land,
  because then "no pre-release invocation" stops being vacuous and becomes
  testable against a real caller.

**Why not now.** Every unmet criterion above becomes *enforceable* only once
something selects a deployment and invokes a provider — which is 0.7.3's subject,
and 0.7.3 depends on this epic HARD. A guard against invoking a provider that
nothing invokes cannot be tested against reality. Building it here would ship
unverifiable code and would let planned subtasks justify themselves after the
epic's own objective was met.
