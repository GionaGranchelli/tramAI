# Task 0.7.3e — Selection/Fallback Fencing

**Epic:** 0.7.3 — Explainable Authorized Provider/Model Selection
**Requires:** 0.7.3c (authorized set), 0.7.3d (viability stage) — both merged
**Change class:** `runtime-behaviour`

## Required result

Selection, retry and fallback cannot escape the viable authorized set. The
invocation path chooses only from candidates that are both authorized and
viable, and a failed attempt's fallback to the next route cannot introduce a
candidate that governance never authorized.

Epic invariant this slice makes true:

```text
selected ∈ viable
viable     ⊆ authorized
fallback/retry remains inside current governance authority
```

## What is already true (verified, not assumed)

- `CandidateAuthorization.authorizedSet(...)` returns `AuthorizedCandidates`,
  a value class with an `internal constructor`; it is the only type
  `CandidateViability` accepts (`CandidateViability.viableCandidates(authorized)`
  returns the `Viable` subset).
- No consumer outside `tramai-security` can construct `AuthorizedCandidates`,
  so authority cannot be minted downstream — it can only be received.
- `tramai-engine` already declares `implementation(project(":tramai-security"))`,
  so the engine can consume the boundary without a new dependency direction.

## The gap this slice closes

`ProviderExecutionCoordinator.execute` iterates a candidate list from the
routing plan:

```kotlin
val candidates = routingPlan.resolveCandidates(request.operation.operation)
for ((index, route) in candidates.withIndex()) { ... transition(..., next, ...) }
```

Grep across the repository shows every reference to `CandidateViability`,
`AuthorizedCandidates`, `CandidateViabilityDecision` and `CandidateAuthorization`
lives inside `tramai-security` and its tests. **No engine code consumes the
boundary.** 0.7.3c states the same thing from its own side: *"Nothing consumes
this boundary yet. Wiring it into an invocation path belongs with 0.7.3e, where
selection and fallback are fenced."*

The existing `ProviderFallbackGate` fences a *transition* at policy level (it
raises `PolicyViolationException`), but neither it nor `resolveCandidates`
consults the authorized/viable set. A candidate that governance never
authorized is reachable by falling through the loop.

## Design decision

The fence is a **consumption point**, not a new decision model. Because
`AuthorizedCandidates` cannot be constructed outside `tramai-security`, the
authority that the engine routes over must be handed to it:

- the viable set enters the invocation path as a supplied input;
- the coordinator resolves candidates through that set, and the raw registered
  candidate list is never iterated for execution;
- an empty viable set means no provider may be used, not that the caller should
  look elsewhere (the same rule 0.7.3c already applies to an empty authorized set).

No ranking, no scoring, no preference logic, and no second decision model: this
slice moves authority across the boundary, it does not add selection policy.

## Boundary of this slice

Not here, and not implied: ranking/scoring/cost/latency strategy, health
scoring, adaptive routing, decision identity/evidence persistence (0.7.3f),
the XR1 external-runtime proof, or any I/O.

## Invariants to prove, each with a test that fails if violated

| condition | shape of the test |
|---|---|
| a selected candidate is always viable | the executed route is a member of the viable set |
| a non-authorized candidate is unreachable by fallback | first candidate fails, the non-authorized second is never attempted |
| a non-viable authorized candidate is unreachable by fallback | same shape, refusal `AVAILABILITY` |
| ordering does not widen authority | reversing viable order cannot add a candidate |
| an empty viable set selects nothing | the call fails with no provider attempted |
| retry does not escape authority | every retried route is a member of the viable set |

## Mutation expectations

Kill set-membership and boundary mutations on the viable set, removal of the
viability filter on the fallback path, and any permissive default that lets an
absent viable set fall back to the registered candidate list.

## Verification (to be recorded on completion)

`:tramai-engine:test`, `:tramai-security:test`, `spotlessCheck`,
`verifyStaticAnalysis` (Detekt baseline must not grow), `verify060Architecture`,
`verifyChangePolicy -PchangeClass=runtime-behaviour` against the epic base, plus
a red/green proof that each new fence test fails when the fence is removed.
