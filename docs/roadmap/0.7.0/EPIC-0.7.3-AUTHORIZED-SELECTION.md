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
- reusable governance-decision identity/envelope semantics that bind workload/run/policy/authority/reasons without becoming provider-selection-specific;
- safe historical decision evidence.

## Non-goals

- rich adaptive routing;
- machine-learned routing;
- FinOps optimization;
- broad cost/quality strategy productization;
- productionized external-runtime adapter SDKs or a broad framework-integration matrix.

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.3a | Routing baseline audit | Map current registration/capability/policy/fallback stages and hidden coupling |
| 0.7.3b | Candidate decision types | Typed authorized/not-authorized/viability/selection states and stable reason families |
| 0.7.3c | Authorized-set derivation | Compute governance-authorized candidates from 0.7.2 contracts |
| 0.7.3d | Viability stage | Apply runtime constraints only after authorization |
| 0.7.3e | Selection/fallback fencing | Ensure selection/retry/fallback cannot escape viable authorized set |
| 0.7.3f | Decision identity/evidence | Persist enough workload/run/policy/decision/authority context to explain historical selection safely and to support the XR1 external-runtime authority proof without inventing a second decision model |
| 0.7.3g | Adversarial/mutation/provider proof | Prove ineligible routes never become selected via fallback/preference |
| 0.7.3h | Integration/docs | Final API/architecture docs and Epic acceptance. **Hard obligation:** the execution path must select only from the viable set — `ProviderExecutionCoordinator` currently resolves and executes routing/fallback independently of the 0.7.3e selection boundary, and this Epic cannot be declared complete while that remains true. |

## Acceptance criteria

- Every selected candidate is viable and authorized.
- Policy-ineligible candidates cannot become selected through retry/fallback/preference.
- Rejected/non-selected candidates expose structured safe reasons.
- Historical evidence identifies the relevant workload/config/policy/routing context.
- Decision identity and structured reasons are not coupled to TramAI owning the workflow runtime or to provider selection as the only future decision family.

### Evidence status (0.7.3h reconciliation)

The hard obligation on 0.7.3h — *the execution path must select only from the viable set* — is now met:
`ProviderExecutionCoordinator` and `StreamingExecutionCoordinator` both route through the
`GovernedProviderEnvelope` boundary, which is entered whenever a governed routing topology exists, and
an availability exclusion that execution advances past is answered by the continuation policy rather
than by walking configured order. A governed execution cannot opt out of that boundary: forcing one
down the legacy branch is killed by *"a configured next route outside the envelope is never invoked"*.

| Criterion | Evidence |
| --- | --- |
| Every selected candidate is viable and authorized | selection reads only `ViableCandidates`, which is derived from the authorized set; mutants M4, M5 and M8 (route substitution, configured-order execution, strategy ignoring the eligible set) are all killed on this boundary |
| Ineligible candidates cannot be selected via retry/fallback/preference | pre-open and late-open circuit exclusions consult the continuation policy exactly once and narrow with `remaining.without(chosen)`; M6/M7 (re-widening to the envelope snapshot) are killed by non-termination, which is itself the proof that monotonic narrowing is load-bearing |
| Rejected/non-selected candidates expose structured safe reasons | `SelectionRefusal` / `ProviderFallbackReason.CIRCUIT_BREAKER_OPEN` / `FallbackDenied(CIRCUIT_OPEN_ONLY)`; the three "nothing to execute" reasons (governance refused, all candidates circuit-open, no candidate qualified) stay distinguishable, asserted in the governed execution-path suite |
| Historical evidence identifies workload/config/policy/routing context | 0.7.3f decision identity: `GovernedRunIdentity` is transported per execution and the config topology is the explicit `ProviderRoutingConfiguration`; absence fails closed rather than being inferred |
| Decision identity/reasons are not coupled to owning the runtime or to provider selection alone | structural: the envelope is a fact carrier over the shared selection/viability types, and no new reason or policy abstraction was introduced for 0.7.3h |

**Gap against "Mutation expectations" above.** "Kill set-membership/boundary mutations, fallback
filter removal" is covered (M4–M8). "Authorization/viability stage swaps" and "permissive defaults in
candidate-state mapping" are **not** covered by the M1–M9 set: no mutant inverts the
authorization/viability order and none relaxes a candidate-state mapping default. Until those two
mutants exist and are killed, this Epic's mutation expectation is partially, not fully, satisfied.


## Post-Epic release checkpoint

After 0.7.3 decision semantics are available, run [XR1 — External Runtime Authority Proof](CHECKPOINT-0.7-XR1-EXTERNAL-RUNTIME-AUTHORITY-PROOF.md) using Spring AI as the reference non-TramAI runtime. XR1 must complete before 0.7.4 evidence/projection contracts are considered frozen.

XR1 is a release architecture proof, not an expansion of this Epic into framework-adapter productization.

## Adversarial proof

Reject: selected candidate absent from authorized set; fallback that widens authority; unavailable candidate represented as governance denial; cost/latency preference that injects a candidate; reason text with no stable structured code.

## Mutation expectations

Kill set-membership/boundary mutations, fallback filter removal, authorization/viability stage swaps, and permissive defaults in candidate-state mapping.
