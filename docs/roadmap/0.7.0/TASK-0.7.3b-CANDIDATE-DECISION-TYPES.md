# TASK 0.7.3b — Candidate Decision Types

**Epic:** [EPIC-0.7.3-AUTHORIZED-SELECTION.md](EPIC-0.7.3-AUTHORIZED-SELECTION.md)  
**Branch:** `task/0.7.3b-candidate-decision-types` → `epic/0.7.3-authorized-selection`  
**Status:** Implemented (stacked on 0.7.3c until that slice is merged)  
**Depends on:** 0.7.3c authorized-set derivation

## What this slice establishes

```text
candidate + governance facts
            ↓
      AUTHORIZED | NOT_AUTHORIZED(reason)
```

One invariant: **a refusal is a typed outcome with a nameable cause, never the
absence of a truth value.**

## Contract

```kotlin
sealed interface CandidateAuthorizationDecision {
    data object Authorized : CandidateAuthorizationDecision
    data class NotAuthorized(val reason: AuthorizationRefusal) : CandidateAuthorizationDecision
}

enum class AuthorizationRefusal {
    IDENTITY_DEPLOYMENT_MISMATCH,
    ZONE_PAIR_NOT_ALLOWED,
    CLASSIFICATION_ZONE_NOT_PERMITTED,
}

sealed interface CandidateViabilityDecision { Viable; NotViable(reason: ViabilityRefusal) }
enum class ViabilityRefusal { CAPABILITY, AVAILABILITY, HEALTH }
```

> **Superseded in part by 0.7.3d:** two of the three families declared here were
> removed. `HEALTH` had no producer — nothing in the repository reports provider
> health. `CAPABILITY` was in the wrong stage: the epic places capability in
> authorization (`authorized = … ∩ capability ∩ registration`), not in runtime
> viability. This slice's record stands as what 0.7.3b declared; the
> authoritative vocabulary is in the source.

`CandidateAuthorization` now exposes:

```kotlin
fun decisionFor(candidate, workloadZone, classification): CandidateAuthorizationDecision
fun authorizedSet(candidates, workloadZone, classification): Set<ProviderCandidate>   // unchanged
```

## The migration, and what was removed

`CandidateAuthorization.authorizes(candidate, workloadZone, classification): Boolean`
is **removed**, not deprecated. Keeping a Boolean beside the typed answer would
preserve exactly the ambiguity this slice exists to remove: a caller could still
branch on a truth value that cannot say why, and three-valued reasoning
(authorized / denied / not-yet-decided) would remain unrepresentable.

The public dump confirms it: `authorizes (` occurs **0** times, `decisionFor`
once. The api-migration entry declares the removal with
`migration: "Consumer action required only for the 0.7.3c preview API"` — nothing
in the repository consumed it, because 0.7.3c was never merged to release.

`ProviderInputRelease.releases(...)` is **kept**: it is a predicate at the release
boundary, not an authorization answer, and removing it would be a break unrelated
to this slice. It is now *defined as* "no refusal", so the Boolean view and the
typed view cannot disagree.

## Where the refusal reasons come from

Refusals are produced by `ProviderInputRelease.refusalFor(...)`, mapped
member-for-member onto `AuthorizationRefusal` in an exhaustive `when`. Adding a
member to either family fails compilation until it is mapped, so the two cannot
drift apart silently.

`refusalFor` walks the same two authorities as `releases`, in a documented order
(the pair authority first, then the classification rule). That order is part of
the contract and is asserted by tests, so a candidate failing two restrictions
still has exactly one deterministic answer.

**Deliberate coarseness, recorded:** a classification with *no rule* and a rule
that *omits the zone* produce the same refusal. The configured rule matrix does
not record which of the two an operator intended, so distinguishing them would be
a claim the facts do not support. Upgrade path: have the release boundary report a
richer outcome if an operator ever needs that distinction — that is a change to
the configuration vocabulary, not to this decision model.

## The viability vocabulary, and why it has no producer

`CandidateViabilityDecision` and `ViabilityRefusal` are declared so that the
authorization stage and the viability stage cannot be collapsed into one enum
later, and so a future refusal can say which stage refused it.

They are deliberately **not** evaluated here. This is structural, not a
convention: `CandidateAuthorization` returns `CandidateAuthorizationDecision`,
which has no viability member, and no source file outside the declaration and its
test references the vocabulary at all.

Cost and latency are deliberately absent from `ViabilityRefusal`. They are
selection signals: they may rank candidates but may never remove one from the
viable set, and admitting them here would let an optimization signal subtract
governance authority.

## Boundary of this slice

Not here: viability evaluation, provider health, availability, ranking,
selection, fallback, retries, invocation, scoring, and any generic decision
framework. Nothing consumes the decision yet.

## Verification

| check | result |
|---|---|
| `:tramai-security:test --tests '*CandidateAuthorizationTest'` | 20 tests, 0 failing |
| governance suites (`tramai-security`) | 73 tests, 0 failing |
| `spotlessCheck` | rc=0 |
| `verifyStaticAnalysis` | rc=0 — Detekt baseline 4792 → 4792, 0 added |
| `verify060Architecture` | PASSED — report `10/10` |
| `verifyChangePolicy` | PASSED — 6 changed files, `runtime-behaviour` |
| api migration chain | 18 entries; declared `fromSha256` == computed pre-change hash == the previous entry's `toSha256`; declared `toSha256` == the dump's actual hash |
| Boolean entry point gone | `authorizes (` absent from `tramai-security.api` (0 occurrences) |
| viability has no producer | vocabulary referenced only in its own declaration and test |

### One real defect this slice's gates caught

`verifyStaticAnalysis` refused the first version of the new consistency test with
`NestedBlockDepth` — three nested `forEach` plus an `if`. Fixed at the source by
flattening the iteration and moving the expectation into an independent oracle
helper. No suppression, no baseline entry, and the baseline is unchanged at 4792.

## Acceptance conditions, each with its evidence

| condition | evidence |
|---|---|
| 1. every previously authorized candidate is AUTHORIZED | the 0.7.3c fixtures and verdicts are reused; `an explicitly authorized candidate is AUTHORIZED` |
| 2. every previously denied candidate is NOT_AUTHORIZED | same fixtures; the five refusal tests assert the exact typed outcome |
| 3. a refusal exposes a stable reason family | `every refusal carries a member of the stable reason family`, `the reason family has exactly the three restrictions this boundary consults` |
| 4. no Boolean ambiguity remains at the boundary | `authorizes()` removed; the dump shows 0 occurrences of it and `decisionFor` in its place |
| 5. no viability decision is performed | the boundary's return type cannot express viability, and the vocabulary has no call site in source |
| 6. exact-head tests and repository gates green | the table above |

## Not run

- `./gradlew verifyPr`: known-red locally with 15 pre-existing `build-logic`
  failures plus the canonical-probe task, reproduced at the pristine base and
  green in CI. See the 0.7.3c task record for the full list and the reproduction.
- `verification of the boundary by a consumer`: nothing consumes it yet, by design.
