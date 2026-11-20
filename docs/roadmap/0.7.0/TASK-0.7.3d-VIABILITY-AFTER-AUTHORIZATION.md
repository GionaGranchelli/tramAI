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
a value whose constructor is module-internal, so only code inside `tramai-security` can
produce one — in practice `CandidateAuthorization`, its only caller:

```kotlin
@JvmInline
value class AuthorizedCandidates internal constructor(internal val candidates: Set<ProviderCandidate>)
```

Consequences that follow from the type rather than from discipline:

- no consumer outside this module can construct an `AuthorizedCandidates`, so a refused
  candidate cannot be smuggled in as an authorized one;
- `candidates` is `internal`, so it is not part of the published API and cannot be reached,
  cast, or mutated from outside the module;
- `decisions()` builds its keys **from** the authorized set, so every key is authorized
  by construction.

**Scope of the claim, stated precisely:** inside the module this is a plain wrapper over a
`Set`, which Kotlin models as read-only rather than immutable, so module-internal code could in
principle cast a returned set and mutate it. No authority-token framework is added to prevent
that — it would cost more than the risk it removes. The guarantee is scoped to the module
boundary, which is where the obligation lives.

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
| `ViabilityRefusal` | `CandidateAuthorization.kt` | `AVAILABILITY` only — `HEALTH` and `CAPABILITY` **removed** |

Runtime constraints are supplied as a `(ProviderCandidate) -> ViabilityRefusal?` function
rather than modelled here, because the repository already owns the facts. This boundary must
not become a second source of them.

**The evaluator is required — there is deliberately no default.** A permissive
`= { null }` default would make `CandidateViability()` legal, marking every authorized candidate
viable without consulting a single runtime fact: fail-open on a governance boundary, and a direct
contradiction of the rule that an unestablished constraint is not evidence of availability. The
API dump is the evidence — the constructor takes the evaluator, and no no-arg constructor exists.

## Why HEALTH and CAPABILITY were removed

Both were declared in 0.7.3b, before any producer existed, and both were removed here for
different reasons:

| Family | Why it is gone |
| --- | --- |
| `HEALTH` | **No producer.** Nothing in the repository reports provider health; the worker/actuator indicators are unrelated. ADR-020's condition: the first producer must prove the model or expose a genuine gap. |
| `CAPABILITY` | **Wrong stage.** The epic fixes `authorized = policy ∩ classification ∩ trust ∩ capability ∩ registration` and `viable = authorized ∩ required runtime constraints`. A candidate that cannot perform a required capability is not temporarily unusable — it is not an eligible authorized candidate. Wiring capability into authorization is a separate, smaller correction. |

`AVAILABILITY` remains because it has a real producer and belongs to this stage:
`ProviderCircuitBreaker`'s `CircuitBreakerAdmission.Rejected` means the deployment is blocked
right now. Cost and latency stay absent for a third reason: they are selection signals, and
admitting them would let an optimization signal remove governance authority.

## Precedent: ADR-020's staging condition was satisfied, then enforced

ADR-020 allows vocabulary to precede its producer, on the condition that the first producer
either **proves the model or exposes something genuinely missing**. This slice is that first
producer, and it exercised the condition against two of the three declared families rather than
treating the vocabulary as settled — which is exactly what the condition is for.

## Not built (deliberately)

No ranking, scoring, preference, selection, fallback, retry, or invocation. No I/O. No
constraint engine, no constraint interface, no registry — the constraints arrive as one
function. No second source of provider facts. `viableCandidates` returning empty means
nothing may be used *now*; it never means the caller may reach outside the authorized set.

## Verification

| Command | Result |
| --- | --- |
| `:tramai-security:test --tests 'dev.tramai.security.governance.*'` | **83 tests, 0 failing** (7 suites; `CandidateViabilityTest` 10 — one capability test was deleted with its family) |
| `spotlessCheck` | rc=0 |
| `verifyStaticAnalysis` | rc=0 — Detekt baseline 4792 → 4792, 0 added, 0 removed |
| `:architectureContractEnrollmentTest --rerun-tasks` + `verify060Architecture` (base pinned) | **PASS 10/10**, report confirmed written during the run |
| `verifyChangePolicy` (base pinned) | PASSED — 8 changed files, `runtime-behaviour`, no violations |
| api migration chain | 19 entries; `fromSha256` == predecessor (same module) `toSha256`; `toSha256` == the dump's hash |

### Review revision: three findings, all accepted

The owner reviewed the first head and asked for three corrections. All three were correct.

| # | Finding | Fix | Evidence |
| --- | --- | --- | --- |
| 🔴 1 | `= { null }` made `CandidateViability()` legal — fail-open: every authorized candidate became `Viable` with no runtime fact consulted, contradicting this slice's own rule | Default removed; the evaluator is required | API dump: `public fun <init>(Lkotlin/jvm/functions/Function1;)V` is the only constructor; no no-arg construction is expressible, in Kotlin or Java |
| 🔴 2 | `CAPABILITY` belongs to authorization per the epic, not to runtime viability | `CAPABILITY` removed from `ViabilityRefusal`; capability wiring into `CandidateAuthorization` deferred to its own correction | `ViabilityRefusal` has one member (`AVAILABILITY`) and the dump has 0 occurrences of `CAPABILITY`; the word survives only in two comments recording the removal |
| 🟠 3 | `internal constructor` is module-wide (not `CandidateAuthorization`-only), and `val candidates` was publicly exposed, so `viable ⊆ authorized cannot be broken by a caller` was false | Property is now `internal`; both claims scoped to the module boundary; the module-internal residual recorded rather than hidden | API dump: `getCandidates` has 0 occurrences |

The `HEALTH` removal was confirmed as correct and stands.

### Third trap: the migration predecessor when amending a PR

`api-architecture` then failed this slice: the entry declared `fromSha256 = 779528d3…` (this PR's
*own* earlier dump) where the verifier demanded `e3cefefc…` (the epic tip's). On a fresh branch
those coincide; on an **amended** PR they do not, because HEAD already contains your first
iteration. The rule: `fromSha256` is the last **same-module** predecessor entry's `toSha256`, never
`HEAD:<dump>` — and the value the failure message names is the one to use.

### The first two traps: a text scanner and a stale verdict

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
