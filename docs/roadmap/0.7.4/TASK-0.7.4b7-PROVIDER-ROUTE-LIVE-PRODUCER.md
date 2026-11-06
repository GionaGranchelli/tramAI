# TASK-0.7.4b7 — Wire a live `provider.route` producer (follow-up to G4)

Status: not started. Follow-up to [0.7.4b6 / G4](0.7.4b6-G4-PROVIDER-ROUTING-EVIDENCE.md), which declared
`provider.route` as a supported family **without** a live producer.
Scope class: `runtime-behaviour` (engine internals) plus whatever capture surface the design review selects.
**Not part of 0.7.4b, and not a prerequisite for the 0.7.4b schema freeze.**

## Goal

Make real routing decisions produce `provider.route` records through the existing exporter and validator, without
creating a second routing authority and without letting evidence delivery influence routing.

## Where the facts become true

Selection, fallback and terminal refusal already happen at identifiable points. Emit there, never from final outcomes:

| Fact | Emit at | Carries today |
|---|---|---|
| `SELECTED` (governed) | `ProviderExecutionCoordinator.kt:216` — after `CandidateSelection.Selected` and circuit-breaker admission, before dispatch | route (`ResolvedProviderRoute`), correlation id, security context, run identity on the request |
| `SELECTED` (legacy) | `ProviderExecutionCoordinator.kt:266` — same gate, pre-0.7.3h path | as above |
| `FALLBACK` | `GovernedCandidateWalk.transition` (`ProviderExecutionCoordinator.kt:569-592`), reached from `:205`, `:446`, `:507`; legacy `:260`, `:276` | `ProviderFallbackTransition` — previous/next provider and model, reason, correlation id, security context. **No run identity.** |
| `FALLBACK` (streaming) | `StreamingRouteAttempts.kt:176`, `:261`, `:288` | same carrier |
| `BLOCKED` | `GovernedCandidateWalk.exhausted` (`ProviderExecutionCoordinator.kt:541-567`); legacy tail `:285-288` | only a collapsed `ProviderException` — per-cause classification must be produced here, not recovered downstream |

## Blockers to resolve in the design step

1. **No capture surface exists.** No runtime-evidence collector/sink/registry exists in any `src/main`, and
   `RuntimeEvidenceBundleWriter` is invoked only by tests and the example path. Decide between an engine-owned bounded
   sink (with an explicit delivery policy) and an application-supplied source the engine fills. Documented decision
   required before code.
2. **Internal-only seams.** `ProviderRouteGate` / `ProviderFallbackGate` / `ProviderResolutionGate` are `internal`.
   Reaching them from an application needs public API on a module that ships an `.api` dump → API-compatibility and
   binary-compatibility review, and it must not be bundled with an analyzer or baseline change.
3. **Evidence-eligibility is already decided.** `tramai.route.selected` is `evidenceEligible = false`
   (`RuntimeEventCatalogue.kt:312-326`). Flipping that flag, or reconstructing decisions from attempt events, both
   require an owner decision: the task must not silently reinterpret the catalogue.
4. **`IS_FALLBACK` is not usable.** `routeIndex > 0` is index-derived. Any producer must ignore it.
5. **Delivery policy inside a fail-closed path.** An observer that throws inside the walk lands in the retry/fallback
   logic. Evidence delivery must not change routing; the failure policy has to be stated, not inferred.
6. **Run identity is missing where fallback is announced.** `ProviderFallbackTransition` carries correlation but not
   `GovernedRunIdentity`; `workflowRunId` is the canonical run identifier, so the carrier needs it (or the producer
   needs an equivalent source) before FALLBACK records can be attributed.
7. **Three execution paths.** Governed sync, legacy sync and streaming. Either cover them or state the uncovered ones
   in the PR, with the same precision G4 used.

## Required tests

- Real-machinery discriminators, not fixture-only: drive `ProviderExecutionCoordinator` through selection, fallback and
  exhaustion and assert the emitted records; a test that only constructs `ProviderRouteDecisionEvidenceSource` proves
  nothing about production.
- Removal discriminator: the emission removed → the test fails.
- Ordering discriminator: emission moved before the authoritative decision (e.g. before authorization or before
  circuit-breaker admission) → the test fails.
- Invalid-transition discriminator: emission triggered on a non-transition (configured-but-unused route, retry of the
  same route, cancellation, post-dispatch failure) → the test fails.
- Availability: evidence capture disabled or the sink unavailable → existing behaviour unchanged under the established
  contract.
- Every emitted record must pass `RuntimeEvidenceContractValidator` and be writable by `RuntimeEvidenceBundleWriter`.

## Acceptance

- At least one execution path emits correctly scoped, validated records end to end; uncovered paths stated explicitly.
- No change to routing decisions, ordering, retry, fallback, circuit-breaker or governance behaviour.
- `runtime-evidence.v1`, `source.component = "provider-router"`, the three decision kinds, the six allowlisted reason
  codes, digest semantics and bundle-verifier compatibility all preserved.

## Non-goals

A second routing authority, a replacement planner, a new adapter API, new selection heuristics, new trust-zone
semantics, a dashboard, a schema version change, execution-completion attestation, event-origin authentication, or the
0.7.4b schema freeze.
