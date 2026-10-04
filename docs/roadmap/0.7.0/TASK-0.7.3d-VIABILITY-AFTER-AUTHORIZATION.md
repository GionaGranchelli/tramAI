# TASK-0.7.3d — Viability After Authorization

- **Status:** ✅ Delivered — `task/0.7.3d-viability-after-authorization`, base `epic/0.7.3-authorized-selection`
- **Depends on:** 0.7.3c (authorized set) and 0.7.3b (typed decision model), both merged
- **Epic:** [EPIC-0.7.3-AUTHORIZED-SELECTION.md](./EPIC-0.7.3-AUTHORIZED-SELECTION.md)
- **Related decisions:** [ADR-019](../../adr/adr-019.md), [ADR-020](../../adr/adr-020.md)

## Goal

Apply runtime constraints **after** authorization, without letting the two stages
merge or letting one widen the other.

```text
authorization:  may TramAI use this candidate?      governance
viability:      can TramAI use it right now?        operational
```

## The invariant

> A candidate must be AUTHORIZED before viability is evaluated.

Enforced **structurally**, not by convention. Viability accepts `AuthorizedCandidates`,
a value whose constructor is module-internal, so `CandidateAuthorization` is the only
thing that can produce one:

```kotlin
@JvmInline
value class AuthorizedCandidates internal constructor(val candidates: Set<ProviderCandidate>)
```

Consequences that follow from the type rather than from discipline:

- there is no API that takes a bare `ProviderCandidate`, so viability cannot be asked
  about a candidate that authorization refused;
- `viable ⊆ authorized` cannot be broken by a caller;
- `decisions()` builds its keys **from** the authorized set, so every key is authorized
  by construction.

It is proven, not asserted: `viability is never evaluated for a candidate authorization
refused` records every call to the constraint function and asserts the refused candidate
was never passed to it — then asserts it never appears as a `NotViable` key either,
because "not viable" would be the wrong answer to a question that must not be asked.

## What was built

| Element | File | Notes |
| --- | --- | --- |
| `AuthorizedCandidates` | `CandidateAuthorization.kt` | module-internal constructor; minted only by the authorization boundary |
| `CandidateAuthorization.authorizedCandidates(...)` | `CandidateAuthorization.kt` | one implementation, two views with `authorizedSet` |
| `CandidateViability` | `CandidateViability.kt` | `decisions()` and `viableCandidates()`; no ranking, no precedence |
| `ViabilityRefusal` | `CandidateAuthorization.kt` | `CAPABILITY`, `AVAILABILITY` — `HEALTH` **removed** |

Runtime constraints are supplied as a `(ProviderCandidate) -> ViabilityRefusal?` function
rather than modelled here, because the repository already owns the facts. This boundary must
not become a second source of them.

## Producers: this is why HEALTH was removed

ADR-020 allows vocabulary to precede its producer, on the condition that the first producer
either **proves the model or exposes something genuinely missing**. This slice is that first
producer, and the repository can report two of the three families 0.7.3b declared:

| Family | Producer that already exists |
| --- | --- |
| `CAPABILITY` | the provider contract's `supportsCapability(ProviderCapability)`, `VISION` / `STREAMING`, and `StreamCapable` |
| `AVAILABILITY` | `ProviderCircuitBreaker`'s `CircuitBreakerAdmission.Rejected` — the deployment is blocked right now |
| ~~`HEALTH`~~ | **none.** Nothing in the repository reports provider health; the worker/actuator health indicators are unrelated |

`HEALTH` was therefore a hypothetical state rather than an unexpressed one, and is removed
rather than kept. Cost and latency remain absent for a different reason: they are selection
signals, and admitting them would let an optimization signal remove governance authority.

## Not built (deliberately)

No ranking, scoring, preference, selection, fallback, retry, or invocation. No I/O. No
constraint engine, no constraint interface, no registry — the constraints arrive as one
function. No second source of provider facts. `viableCandidates` returning empty means
nothing may be used *now*; it never means the caller may reach outside the authorized set.

## Verification

| Command | Result |
| --- | --- |
| `:tramai-security:test --tests 'dev.tramai.security.governance.*'` | **84 tests, 0 failing** (7 suites; `CandidateViabilityTest` 11 new) |
| `spotlessCheck` | rc=0 |
| `verifyStaticAnalysis` | rc=0 — Detekt baseline 4792 → 4792, 0 added, 0 removed |
| `verify060Architecture` (base pinned) | **PASS 10/10** |
| `verifyChangePolicy` (base pinned) | PASSED — 7 changed files, `runtime-behaviour`, no violations |
| api migration chain | 19 entries; written `fromSha256` == computed pre-change hash == previous entry's `toSha256`; `toSha256` == the dump's hash |

### Two traps hit and fixed in this slice

1. **`verify060Architecture` was red, then stale.** The first failure was real: the
   provider-TCK enrollment scanner is a *text* heuristic — it takes the text from a
   `class`/`object` keyword to the next `{` **anywhere in the file** and flags anything whose
   "supertype section" contains the literal `ModelProvider`. Declarative KDoc documenting the
   real producer was read as an implemented interface, so the gate failed naming innocent
   declarations. Fixed in the slice by rewording the comments (no behaviour change). The
   scanner defect itself is **reported separately** — a gate never ships with what it guards.
2. **The gate then re-printed the old verdict.** `architectureContractEnrollmentTest` reads
   sources off disk, so Gradle inputs do not include the files it inspects: the task stayed
   `UP-TO-DATE` and the report was regenerated with the *previous* verdict. Deleting the
   report did not help — the stored result lives in the task's own output. It took
   `--rerun-tasks` on that task to get a real measurement (PASS 10/10). Rule: after editing
   a scanned source, force the task that owns the verdict; check the report's mtime against
   your run before trusting it.

## Remaining risk

- The constraint function is supplied by the caller, so a caller that supplies `{ null }`
  reports everything authorized as viable. Viability has no authority to *widen* anything, so
  this cannot create governance authority — but it does mean "viable" is only as good as the
  facts the caller wires in. The engine-side wiring is the next slice's job, not this one's.
- No consumer calls this boundary yet. Its tests are the guarantee until one does.
