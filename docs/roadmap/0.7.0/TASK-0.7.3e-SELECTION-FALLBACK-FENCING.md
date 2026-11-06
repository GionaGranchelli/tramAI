# Task 0.7.3e — Selection/Fallback Fencing

**Epic:** 0.7.3 — Explainable Authorized Provider/Model Selection
**Requires:** 0.7.3c (authorized set), 0.7.3d (viability), 0.7.3d1 (authorization completeness)
**Change class:** `runtime-behaviour`

## Required result

Selection, retry, fallback, preference and ranking may choose among viable candidates, but may
never introduce one. The epic's final stage is enforced and adversarially proven:

```text
selected ∈ viable
viable     ⊆ authorized
fallback/retry remains inside current governance authority
optimization signals can rank but cannot authorize
```

## What exists now

- `CandidateAuthorization` → `AuthorizedCandidates` (module-internal constructor).
- `CandidateViability` → **`ViableCandidates`** (module-internal constructor, new here). It is the
  only producer, so a selectable candidate is always one authorization permitted and viability
  found usable.
- `CandidateSelection` → `CandidateSelectionDecision`, choosing zero or one candidate from a
  `ViableCandidates` and checking the strategy's answer against it.

`ViableCandidates` narrows only: `without(candidate)` removes an attempted candidate for retry and
can never add one, so a retry derives its candidates from the original envelope instead of asking
routing again. A configured fallback route is not authority.

## Selection decision vocabulary

```text
CandidateSelectionDecision.Selected(candidate)
CandidateSelectionDecision.NoSelection(reason)   reason ∈ SelectionRefusal

SelectionRefusal.NO_VIABLE_CANDIDATES          the envelope is empty
SelectionRefusal.STRATEGY_DECLINED             the strategy answered with none
SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET   the strategy answered from outside the envelope
```

Exactly three reasons, each with a producer in this slice. A nullable return is deliberately not
used: it cannot distinguish "nothing was viable" from "the strategy declined" from "the strategy
tried to escape". A strategy's answer is caller-supplied input, so the membership check lives in
the boundary rather than being documented as a strategy's obligation.

Ordering is separated from membership: `orderedBy(preference)` orders and can never add;
`CandidateSelectionStrategy` receives the viable set and decides, so cost/latency/preference are
expressible as ordering without becoming authority.

## Existing routing compatibility

`ProviderRoutingPlan` was **not changed**. Its classification, unchanged by this slice:

| fact | where it lives |
|---|---|
| configuration | `ProviderRoutingPlan.Builder.model/fallbackModel/fallbackProvider/defaultProvider` |
| registration snapshot | `ProviderRoutingPlan.providers` (consumed by authorization, 0.7.3d1) |
| discovery | `resolveCandidates(operation)` / `resolve(operation)` |
| ordering | `routes[modelId] = [primary] + fallbacks` |
| execution | `ProviderExecutionCoordinator` iterating those routes |

The preferred chain is `routing → candidate facts → authorization → viability → selection`, rather
than making the routing plan itself a governance authority.

## Invariants proven

Each with a test that fails when the code is neutralised: selected ∈ viable; an empty envelope
selects nothing and never consults a strategy; a single viable candidate is the only selectable
one; a strategy cannot inject an outside candidate; a configured fallback that is unauthorized,
or authorized but non-viable, cannot be selected, while one inside the envelope may be; retry stays
inside the original envelope; a preference may reorder but not add; ordering does not change
membership; duplicates do not widen the universe; a non-viable candidate is never reclassified as
governance-denied; selection performs no invocation; selection does not recompute authorization or
viability.

## Boundary of this slice

`ProviderExecutionCoordinator` does **not** yet consume `ViableCandidates`: wiring the envelope into
the execution path is a separate integration, and this slice deliberately stops at the decision
boundary. Until that lands, the fence is proven on the decision boundary, not on the invocation
path.

**This is a hard obligation on the integration task, not a note.** Epic 0.7.3 must not be declared
complete while `ProviderExecutionCoordinator` can still resolve and execute routing or fallback
independently of this viable-selection boundary. Whoever lands that integration must show the
execution path selecting only from `ViableCandidates` — the decision fence being proven here does
not by itself constrain execution. (Epic task 0.7.3h, Integration/docs.)

Not here either: provider invocation, circuit-breaker behaviour beyond consuming produced
viability facts, adaptive/ML routing, cost or latency productization, weighted-ranking frameworks,
decision evidence/persistence, digests, audit projections, retry scheduling, provider health
models, new registry abstractions, a generic policy engine, or a second routing graph.
