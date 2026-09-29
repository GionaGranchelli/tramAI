# TASK-0.7.1g1G4 — Residual Mutation Remediation and Final Adjudication

**Status:** `ACTIVE`. Increment 1 (g1G4a) closed 2 of 118 by measured kills; increment 2 (g1G4b, §9) closed 23
of the 27 `DefaultApprovalGateway` identities (21 by measured kills, 2 by instruction-level unreachability), leaving
**93 of the original 118 `UNDETERMINED`** (`ApprovalResumeCoordinator` 50, `ApprovalSuspensionCoordinator` 39,
`DefaultApprovalGateway` 4). g1G4a's measurements in §5 are preserved as the historical record of that increment and
are not restated with newer values. This record does **not** complete g1G4: the parent objective is the final
identity-exact adjudication of the whole residual cohort, and the roadmap must not mark g1G4 complete while any
identity lacks a supported final disposition.
**Branch:** `task/0.7.1g1g4-residual-remediation`
**Exact base:** `80d6847f7ef639fc830f3edcaedb988d8b1d9d20` (Epic tip, verified before branching)
**Working tree at start:** clean (`git status --porcelain` empty)
**Scope:** the 118 `UNDETERMINED` identities left by `TASK-0.7.1g1G2-RESIDUAL-TAXONOMY.md`, remediated by test
and adjudicated by measurement rather than by inference.
**Does not authorise:** any mutation-population admission, any `config/quality/mutation-classifications.yml`
write, any M21 population transition, any canonical `config/quality/mutation-baseline.json` update, any PIT policy
change, and any production change made to raise a mutation score. See §7.

## 1. Phase A — Custody of the 118 (frozen before any test was written)

The 118 are a **semantic disposition cohort**, not a status predicate over the raw census: they are the
g1G2 residual minus its firm dispositions. The raw census cannot reconstruct them, because the 8 Unit-return
`NullReturnVals` rows, the 2 compiler-guard `VoidMethodCall` rows and the deferred `NegateConditionals` identity
were adjudications layered on top of the measurement.

| Quantity | Value |
|---|---|
| g1G2 residual | 177 |
| `EQUIVALENT` | 12 |
| `TOOLING_LIMITATION` | 46 |
| `DEFERRED_STRUCTURAL` | 1 |
| **`UNDETERMINED` (this slice's input)** | **118** |

Decomposition of the 118, and the class distribution that follows from it:

| Group | Count | Sites |
|---|---|---|
| `NullReturnVals` | 53 | `ApprovalResumeCoordinator.kt` 22, `ApprovalSuspensionCoordinator.kt` 18, `DefaultApprovalGateway.kt` 13 |
| `VoidMethodCall` | 61 | `ApprovalResumeCoordinator.kt` 26, `ApprovalSuspensionCoordinator.kt` 19, `DefaultApprovalGateway.kt` 16 |
| `NegateConditionals` | 4 | `ApprovalResumeCoordinator.kt` 2, `ApprovalSuspensionCoordinator.kt` 2 |
| **Total** | **118** | three files, all in `:tramai-engine` |

All 118 are `NON_KILLED` in the frozen census: 101 `NO_COVERAGE` and 17 `SURVIVED`.

### 1.1 Committed manifest and digests

The canonical identities are committed as
[`TASK-0.7.1g1G4-UNDETERMINED-118-MANIFEST.json`](./TASK-0.7.1g1G4-UNDETERMINED-118-MANIFEST.json)
(identity, group, mutator, class, method, descriptor, module, source file, line, block, index, census bucket
and census status), with a deterministic reconstruction procedure in the file itself. Raw PIT XML stays outside
the repository by existing convention; the identity manifest does not.

| Digest (SHA-256) | Value |
|---|---|
| UNDETERMINED 118 | `0c2d107def8f30c564e648c4a86a11049c4a7cef4bfed5c78e07d3f95b9c77ea` |
| `NullReturnVals` 53 | `7a94709d497890ed2d65ca31f9fd4a7cf254e5ad6823d767f9a1895efc40c2be` |
| `VoidMethodCall` 61 | `c4dbb18ae4818bf7831d030872561242101236568eaee44b87d1c4da9f1102b1` |
| `NegateConditionals` 4 | `adece18ff4747d464817aa65bfd487900a33687c31edc4c7b7078e0ef958c828` |

Digest algorithm: sort the identities lexicographically, join with `\n`, append a trailing `\n`, SHA-256.

### 1.2 Custody ledger

```
input residual              177
  EQUIVALENT                 12   (8 Unit-return NullReturnVals + 2 compiler guards + 2 ApprovalRunAttribution negations)
  TOOLING_LIMITATION         46   (all NegateConditionals, timed_out bucket)
  DEFERRED_STRUCTURAL         1   (ApprovalResumeCoordinator.kt block 76 / index 533 = line 102)
  UNDETERMINED              118
  ------------------------------
  sum                       177   accounted 177/177
118 = 53 NullReturnVals + 61 VoidMethodCall + 4 NegateConditionals
subset of the residual: yes   duplicates: 0   lost: 0   unexplained: 0
none in TOOLING_LIMITATION: yes   none in EQUIVALENT: yes   line 102 excluded: yes
```

## 2. Phase B — g1G2 erratum (explicit, not silent)

Recorded as `## 8. Erratum` in `TASK-0.7.1g1G2-RESIDUAL-TAXONOMY.md`, preserving the original §3 text. Two
corrections: the `VoidMethodCall` sub-population split, and the mutator's full name.

| Old (PIT line attribution) | Instruction-exact supersession |
|---|---|
| 43 `SUSPENSION_POINT_RETHROW` | 59 compiler-generated `kotlin/ResultKt::throwOnFailure` removals |
| 20 `OTHER_VOID_CALL` | 2 source-level removed calls |
| `NullReturnValsMutator` | `org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator` |

The old grouping was produced by attributing instructions to source lines: a line can carry both the
compiler-generated suspension machinery and ordinary calls from the same expression. The merged record's own
§7 step 4 already warned about this; the split in §3 is superseded for reasoning, the method caveat stands.

## 3. Phase C — the two harness patterns, productionised

The throwaway probe worktrees proved two mechanisms and were discarded. The patterns — not the probe files —
are now durable tests in `tramai-engine`:

### 3.1 What a synchronous fake hides

Every collaborator wired in the engine's own approval tests is in-memory and completes without suspending. The
generated code around a suspend call is then never taken:

```
val r = approvalStore.get(id, this)      // nested suspend call
if (r === COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED   // suspension protocol
... on the resume path: ResultKt.throwOnFailure(result)     // failure protocol
```

Two mutators target exactly those instructions, and each requires a different test obligation:

| Mutator instruction | Obligation to distinguish it |
|---|---|
| `areturn` of the suspension sentinel replaced by `null` (`NullReturnVals`) | suspend genuinely, resume successfully, observe the returned value |
| removal of `ResultKt::throwOnFailure` on the resume path (`VoidMethodCall`) | suspend genuinely, resume **exceptionally**, observe the propagated failure |

### 3.2 Durable tests added

`DefaultApprovalGatewayTest` gains one delegating store and two behavioural tests (test 11), reusing the file's
existing harness rather than a new fixture:

- `SuspendingApprovalStore` — `ApprovalStore by delegate`, overriding `get` to `delay(1)` before delegating,
  with an optional `failAfterResume` failure.
- `requestApproval resumes across a genuinely suspending approval store get` — asserts the resumed value
  (`Suspended`, approval id, store state) with `withTimeout(2_000)`, below PIT's `timeoutConstInMillis`.
- `a failure that resumes a genuinely suspending approval store get reaches the caller unchanged` — asserts the
  store's own failure reaches the caller unwrapped (type and message), and that a failed resume leaves no
  durable trace (approval, continuation, suspension all empty).

The second test is not a stylistic variant of the first: the same exact mutant moved
`NO_COVERAGE → SURVIVED` under a successful-resume harness and `NO_COVERAGE → KILLED` under an
exceptional-resume harness. That progression is the causal evidence that the missing obligation was the failure
protocol, not reachability.

## 4. Phase D — Measurement

Measurement scaffolding (a `mutation.targetFamilies` narrowing) is **not** part of this branch: it is applied in a
throwaway worktree at the commit that carries the durable tests, and the measurement is joined against the
committed manifest. See §5.

## 5. Identity-exact results (Phase D measured)

Measurement provenance: throwaway worktree at the tests commit `4fc5c37c` plus one committed narrowing of
`mutation.targetFamilies` (measurement commit `39599f6a`, not part of this branch). Scope: the three classes
owning the cohort (`ApprovalResumeCoordinator*`, `ApprovalSuspensionCoordinator*`, `DefaultApprovalGateway*`,
`:tramai-engine`). 344 mutants, 5 m 29 s, identity census **336 shared with the frozen census, 0 lost, 8 new**
(additional generated classes matched by the wildcard, outside both the census and the cohort).

Cohort movement, identity-exact:

| | Count |
|---|---|
| `NO_COVERAGE` → `KILLED` | **2** |
| `NO_COVERAGE` → `NO_COVERAGE` | 99 |
| `SURVIVED` → `SURVIVED` | 17 |
| newly `TIMED_OUT` | 0 |
| remaining after this slice | **116** (52 `NullReturnVals`, 60 `VoidMethodCall`, 4 `NegateConditionals`) |

The two settled identities, both closed by tests added in §3.2:

| Identity | Mutator | Site | Before → after |
|---|---|---|---|
| `8b52a478cb33035b2af3c59f099290ab6fa8c3812888c33b9893160ce0fa8d22` | `NullReturnVals` | `DefaultApprovalGateway.kt:73`, block 36 / index 247 | `NO_COVERAGE` → `KILLED` (1 test) |
| `d77706fde05eb954a18854bdc62e90721dca630d7edd3b681c1ca599046c0eb9` | `VoidMethodCall` | `DefaultApprovalGateway.kt:73`, block 37 / index 279 (`ResultKt::throwOnFailure`) | `NO_COVERAGE` → `KILLED` (1 test) |

Controls, all of which hold:

- **regressions `KILLED` → non-`KILLED`: 0** (110 `KILLED` identities stayed `KILLED`);
- the 46 `TOOLING_LIMITATION` identities are still `TIMED_OUT` (46/46) — the existing adjudication is unchanged;
- the `DEFERRED_STRUCTURAL` identity (`ApprovalResumeCoordinator` block 76 / index 533) is untouched as
  `SURVIVED` and is not part of the cohort;
- no identity was added or lost inside the census population.

**`UNDETERMINED` is therefore 116, not 0.** The exit target of §1 is not met by this slice and no identity was
forced into a convenient category to meet it.

## 5.1 Raw-status transition matrices (movement accounting)

Movement is recorded in full, not only as a regression check: an unexpected `NO_COVERAGE → SURVIVED` would not be a
regression but would still belong in an identity-custody record. Both matrices cover the measurement in §5.

All 344 measured identities (336 census-shared + 8 generated-class mutants new to this measurement):

| Before | After | Count |
|---|---|---|
| `KILLED` | `KILLED` | 110 |
| `NO_COVERAGE` | `KILLED` | **2** |
| `NO_COVERAGE` | `NO_COVERAGE` | 126 |
| `SURVIVED` | `SURVIVED` | 51 |
| `TIMED_OUT` | `TIMED_OUT` | 55 |
| any other transition | — | **0** |

Restricted to the 177 residual cohort (175 measured; see below):

| Before | After | Count |
|---|---|---|
| `NO_COVERAGE` | `KILLED` | **2** |
| `NO_COVERAGE` | `NO_COVERAGE` | 99 |
| `SURVIVED` | `SURVIVED` | 28 |
| `TIMED_OUT` | `TIMED_OUT` | 46 |
| any other transition | — | **0** |

The only movement in the residual cohort is the two kills of §5. Both are `NullReturnVals` /
`VoidMethodCall` on the same suspension point of `DefaultApprovalGateway.requestApproval`.

Cohort-scope note: 2 of the 177 residual identities are **not** in this measurement —
`8e18b4b48968d65f…` and `d38ac52f2843448a…`, both `NegateConditionals` in
`ApprovalRunAttributionKt.decodeApprovalAttribution` (line 174). They are the two `EQUIVALENT` inlined
`Iterable.filter` negations adjudicated in `TASK-0.7.1g1G2-RESIDUAL-TAXONOMY.md` §3.2, they are **not** part of the
118, and the measurement scope deliberately covered the three classes that own cohort identities. They therefore
carry no transition this increment; their disposition is unchanged.

The two matrices reconcile exactly: the 169 measured identities outside the residual cohort contribute
`KILLED`→`KILLED` 110, `NO_COVERAGE`→`NO_COVERAGE` 27, `SURVIVED`→`SURVIVED` 23 and `TIMED_OUT`→`TIMED_OUT` 9
(110 + 27 + 23 + 9 = 169), so 175 + 169 = 344 with no transition left unaccounted for.

## 5.2 What the remaining 116 need (grouped by production scenario, not by identity)

| Remaining | Class | Methods (identities) | Required harness |
|---|---|---|---|
| 12 `NullReturnVals`, 15 `VoidMethodCall` | `DefaultApprovalGateway` | `requestApproval` (5+5), `persistUngoverned` (3+4), `persistGoverned` (3+4), `requireExistingAttributionMatches` (1+2) | the pattern of §3.2 applied to the remaining seams: suspending `ApprovalGatewayRequestFactory.createRequest`, `SuspendedInvocationStore.create`, `ApprovalContinuationStore.create`, and the governed stores (`createGovernedApproval`, `attributionOf`, `createGoverned`) |
| 22 `NullReturnVals`, 26 `VoidMethodCall`, 2 `NegateConditionals` | `ApprovalResumeCoordinator` | `resume` (8+9), `prepareResume` (5+6), `authorizeResume` (4+5 +2 NC), `executeClaimedResume` (3+3), `revealAndValidateReplayPayload` (1+1), `$resume$2.invokeSuspend` (1+2) | drive `resume()` with genuinely suspending/failing `ApprovalContinuationStore`, `SuspendedInvocationStore`, `ReplayAuthorizationService`, `ContinuationClaimService` and `ClaimedResumeExecutor` |
| 18 `NullReturnVals`, 19 `VoidMethodCall`, 2 `NegateConditionals` | `ApprovalSuspensionCoordinator` | `suspendToolExecution` (6+6), `compensateSuspension` (3+4 + 3×2 lambda pairs), `persistSuspendedInvocation` (2+2 +2 NC), `compensateStep` (1+3) | tool-suspension and compensation pipelines with a suspending collaborator; the `compensateSuspension$2$*` lambdas need the compensation path itself to suspend |
| 4 `NegateConditionals` | both coordinators | `authorizeResume` ×2, `persistSuspendedInvocation` ×2 | per-site analysis only; the two `Nothing`-callee sentinel cases (`:151`, `:155`) may be structurally unreachable rather than uncovered — do not fold into either coroutine harness |


## 6. What this slice does not settle

- 116 of the 118 identities have no durable test in this commit (see §5.2 for the per-scenario grouping). Each remaining site needs the harness that
  matches its own semantic obligation and its own collaborator seam; the per-group scenarios are listed in §5.
- The 4 `NegateConditionals` identities are deliberately excluded from both coroutine harnesses. Two are the
  `Nothing`-callee sentinel cases (`ApprovalResumeCoordinator:151`, `:155`): the post-call comparison can only be
  reached if a normally non-returning callee returns, which may be structurally unreachable under the production
  contract rather than merely uncovered. Two are `ApprovalSuspensionCoordinator:280`, `:282` survivors needing
  individual behavioural analysis.
- The 46 existing `TIMED_OUT` `NegateConditionals` identities are untouched: both new harnesses leave the
  representative `TIMED_OUT`, which strengthens rather than weakens the recorded tooling-limitation
  adjudication.

## 7. What this record does not authorise

- **No admissions, no classifications.** `config/quality/mutation-classifications.yml` and the population
  admission ledger stay untouched; the M22–M29 / M30–M39 ceremonies are unaffected.
- **No baseline or policy change.** `config/quality/mutation-baseline.json` is not regenerated by this slice,
  no deviation ceiling moves, and PIT selection/timeout policy is unchanged.
- **No production change for a mutation score.** If a later slice acts on a `KILLABLE` candidate it must preserve
  identity census, regression accounting, provenance and a positive control.
- **No inference from neighbours.** An identity is settled only by its own measurement or its own
  instruction-level equivalence proof. Being compiler-generated is not itself evidence of equivalence: for the
  two constructs above, the generated bytecode carried a real, application-observable contract.

## 8. Reproduction

1. Read the cohort from `TASK-0.7.1g1G4-UNDETERMINED-118-MANIFEST.json` and verify the digests in §1.1.
2. Recovery (if the manifest must be rebuilt): `/tmp/g1g2-residual.json` minus its firm dispositions →
   53 + 61 + 4; join to the census on the canonical identity, never on `(file, line, block, index)`.
3. Positive control: `./gradlew :tramai-engine:test --tests "dev.tramai.engine.approval.DefaultApprovalGatewayTest"`
   must pass on the unmutated tree, and the suspending-store test must actually suspend (`resumedGets == 1`).
4. Measurement: throwaway worktree at the tests commit, narrow `mutation.targetFamilies` to the classes owning
   the cohort, commit the narrowing, run `generateCriticalMutationBaseline`, then confirm `measuredCommit`,
   identity-set equality with the census, and the exact per-identity status movement.

## 9. Increment 2 — TASK-0.7.1g1G4b: DefaultApprovalGateway residuals

**Exact base:** `7703685ca78821c6787077763ed6d02143d5da6f` (equal to `origin/epic/0.7.1-control-plane-authority` at branch time).
**Preconditions verified:** clean worktree; the committed 118-manifest recomputed to `0c2d107d…`; the input cohort derived
from that manifest **minus** the two identities g1G4a killed.

### 9.1 Input cohort — 27 identities, derived not counted

| Group | Count | Methods |
|---|---|---|
| `NullReturnVals` | 12 | `requestApproval` 5, `persistUngoverned` 3, `persistGoverned` 3, `requireExistingAttributionMatches` 1 |
| `VoidMethodCall` | 15 | `requestApproval` 5, `persistUngoverned` 4, `persistGoverned` 4, `requireExistingAttributionMatches` 2 |
| `NegateConditionals` | 0 | — |

The two g1G4a identities (`8b52a478…` block 36/247, `d77706fd…` block 37/279) are excluded by measurement, not by
line matching: they are removed because the g1G4a campaign recorded them `KILLED`.

### 9.2 Instruction mapping (`javap -p -c -l` at the exact base)

Sentinel `areturn`s per method, each after the guarded call, with the paired resume-path `ResultKt::throwOnFailure`:

| Method | Guarded call | sentinel pc | PIT pair |
|---|---|---|---|
| `requestApproval` | `resolveGovernedIdentity` | 164 | blocks 10 / 11 |
| `requestApproval` | `ApprovalGatewayRequestFactory.createRequest` | 309 | blocks 24 / 25 |
| `requestApproval` | `ApprovalStore.get` | 462 | blocks 36 / 37 *(killed in g1G4a)* |
| `requestApproval` | `requireExistingAttributionMatches` | 623 | blocks 47 / 48 |
| `requestApproval` | `persistUngoverned` | 804 | blocks 61 / 62 |
| `requestApproval` | `persistGoverned` | 973 | blocks 73 / 74 |
| `persistUngoverned` | `ApprovalStore.create` | 135 | blocks 11 / 12 |
| `persistUngoverned` | `SuspendedInvocationStore.create` | 190 | blocks 18 / 19 |
| `persistUngoverned` | `ApprovalContinuationStore.create` | 248 | blocks 26 / 27 |
| `persistGoverned` | `GovernedApprovalStore.createGovernedApproval` | 150 | blocks 13 / 14 |
| `persistGoverned` | `GovernedSuspendedInvocationStore.createGoverned` | 233 | blocks 23 / 24 |
| `persistGoverned` | `ApprovalContinuationStore.create` | 311 | blocks 32 / 33 |
| `requireExistingAttributionMatches` | `GovernedApprovalStore.attributionOf` | 156 | blocks 15 / 16 |

Mapping method: PIT block/index pairs are ordered as the sentinel `areturn`s are ordered in the bytecode, and the
correspondence is confirmed twice — g1G4a's killed identity at block 36 / index 247 is the `ApprovalStore.get`
sentinel at pc 462, and the artifact's own obligation text for block 24 / index 164 names
`ApprovalGatewayRequestFactory.createRequest` (pc 309). Each method additionally carries a `VoidMethodCall` row at
block 7 / index 44, the state-machine *entry* rethrow (`javap` pc 97 in `persistUngoverned`), which executes on every
resume rather than on one seam.

### 9.3 One identity pair settled by proof, not by test — blocks 10 / 11

`resolveGovernedIdentity` is a `suspend` function whose compiled body is 32 instructions and contains **no**
`IntrinsicsKt.getCOROUTINE_SUSPENDED`, **no** `ResultKt::throwOnFailure`, and no invocation that can suspend
(`Continuation.getContext`, `GovernedRunScope$Key.resolve`, `Intrinsics.areEqual`, string concat, and
`GovernedRunContinuityException.<init>`/`athrow` only). It therefore can never return the suspension sentinel, so in
`requestApproval` the guard `if_acmpne` after its call always branches past the sentinel `areturn` at pc 164, and the
resume path that the block-11 rethrow belongs to is never entered. Both identities are recorded **UNREACHABLE** on
that instruction-level proof; the measurement agrees (`NO_COVERAGE`, `numberOfTestsRun = 0`, unchanged).

### 9.4 Durable tests added (10)

`tramai-engine/src/test/kotlin/dev/tramai/engine/approval/GatewaySuspensionContractTest.kt` — a new class rather than
edits inside the released suite, reusing its `internal` fakes and the governed suite's `TestGoverned*` stores. Each
test states a contract, not a mutation:

- ungoverned: every persistence collaborator suspends and the saga completes; factory / approval-store /
  suspended-invocation / continuation failures after suspension each reach the caller and stop the chain;
- governed: every governed collaborator suspends and the saga completes; governed-approval and governed-suspension
  failures after suspension reach the caller and write nothing further; an existing governed approval is re-validated
  across a suspending attribution read; a failure resuming that read reaches the caller.

Four delegating helpers, each with at most one `failAfter…` parameter, express "a collaborator that suspends and then
fails" — no per-mutation knobs, no PIT-shaped API. `delay(1)` provides the suspension; every call is bounded by
`withTimeout(2_000)`, below PIT's `timeoutConstInMillis`. No production code was touched, and no identity was pursued
by restructuring production.

### 9.5 Measurement provenance

Test commit `aa5b003c`; measurement worktree at that commit with one committed narrowing (measurement commit
`4bb108c7`, not part of the branch); scope `:tramai-engine` / `dev.tramai.engine.approval.DefaultApprovalGateway*`;
3 m 49 s; **79 mutants, identity set identical to the frozen census (79/79 shared, 0 lost, 0 new)**.

Full transition matrix (`census` → g1G4b):

| Before | After | Count |
|---|---|---|
| `KILLED` | `KILLED` | 32 |
| `NO_COVERAGE` | `KILLED` | **23** |
| `NO_COVERAGE` | `NO_COVERAGE` | 2 |
| `NO_COVERAGE` | `SURVIVED` | **1** |
| `SURVIVED` | `SURVIVED` | 8 |
| `TIMED_OUT` | `TIMED_OUT` | 13 |
| any other transition | — | 0 |

Restricted to the 27-identity cohort: `NO_COVERAGE → KILLED` **21**, `NO_COVERAGE → NO_COVERAGE` 2,
`NO_COVERAGE → SURVIVED` **1**, `SURVIVED → SURVIVED` 3, other transitions 0. Every input identity is accounted for.

Controls: `KILLED → non-KILLED` regressions **0**; new `TIMED_OUT` **0** (13 `TIMED_OUT` unchanged); no identity left
the scoped cohort; every new kill names its killing test (all `numberOfTestsRun = 1`).

Explicitly reported movement: `NO_COVERAGE → SURVIVED` — `75ec22b0e703bb8a54ddbde9d6edab92e1e3fbbff3318021b9a6d11eccca21ec`
(`persistGoverned` block 33 / index 197, `removed call to kotlin/ResultKt::throwOnFailure`). It is the resume path of
the governed continuation write; it now executes (`numberOfTestsRun = 1`) but is not detected, because the governed
failure tests inject their failure at the first two governed writes and the governed continuation write is exercised
only across a successful resume. It stays `UNDETERMINED`; the missing obligation is a governed-continuation-write
failure test, which this increment did not add.

### 9.6 Identity-exact dispositions

**KILLED this increment — 21** (all `NO_COVERAGE → KILLED`, each credited to exactly one test,
`numberOfTestsRun = 1`):

| Identity (prefix) | Mutator | Site | Killing test |
|---|---|---|---|
| `7b156f3a3c6265cf` | NV | `persistUngoverned` 11/65 | an approval write failure after suspension reaches the caller and stops the chain() |
| `1b309f9a702a4e6c` | VMC | `persistUngoverned` 12/73 | an approval write failure after suspension reaches the caller and stops the chain() |
| `1e85193edcd55874` | NV | `persistGoverned` 13/78 | a governed approval write failure after suspension reaches the caller and writes nothing() |
| `f5c807b43f9fe724` | NV | `requireExistingAttributionMatches` 15/83 | a failure resuming the attribution read reaches the caller() |
| `9c4c3454c7f77259` | VMC | `persistGoverned` 14/90 | a governed approval write failure after suspension reaches the caller and writes nothing() |
| `5ac56e0a701ab6fc` | VMC | `requireExistingAttributionMatches` 16/100 | a failure resuming the attribution read reaches the caller() |
| `a4dbacf129987194` | NV | `persistUngoverned` 18/105 | a suspended-invocation write failure after suspension reaches the caller and stops the chain() |
| `84224c763190937a` | VMC | `persistUngoverned` 19/113 | a suspended-invocation write failure after suspension reaches the caller and stops the chain() |
| `7e799e77e8c7396e` | NV | `persistGoverned` 23/136 | a governed suspension write failure after suspension reaches the caller and writes no continuation() |
| `e8276b8096040f41` | NV | `persistUngoverned` 26/146 | a continuation write failure after suspension reaches the caller and leaves no continuation() |
| `d8e1290f30007d72` | VMC | `persistGoverned` 24/148 | a governed suspension write failure after suspension reaches the caller and writes no continuation() |
| `15c7def4f5c98cbd` | VMC | `persistUngoverned` 27/154 | a continuation write failure after suspension reaches the caller and leaves no continuation() |
| `3883b3c127aed304` | NV | `requestApproval-Atj0Sqo` 24/164 | a request factory failure after suspension reaches the caller and writes nothing() |
| `7d3fc3ab2af8aff6` | NV | `persistGoverned` 32/185 | a governed request completes when every governed persistence collaborator genuinely suspends() |
| `6a33ba5d985ae4e6` | VMC | `requestApproval-Atj0Sqo` 25/189 | a request factory failure after suspension reaches the caller and writes nothing() |
| `4dec4f43d11411f4` | NV | `requestApproval-Atj0Sqo` 47/332 | a failure resuming the attribution read reaches the caller() |
| `8210e97a204f7064` | VMC | `requestApproval-Atj0Sqo` 48/369 | a failure resuming the attribution read reaches the caller() |
| `5c2981ad80f33214` | NV | `requestApproval-Atj0Sqo` 61/430 | an approval write failure after suspension reaches the caller and stops the chain() |
| `6dcb09ca1cc8e2bb` | VMC | `requestApproval-Atj0Sqo` 62/467 | an approval write failure after suspension reaches the caller and stops the chain() |
| `862220d0ea233a54` | NV | `requestApproval-Atj0Sqo` 73/519 | a governed approval write failure after suspension reaches the caller and writes nothing() |
| `8dc069d5a0f38543` | VMC | `requestApproval-Atj0Sqo` 74/556 | a governed approval write failure after suspension reaches the caller and writes nothing() |

**Proven UNREACHABLE — 2:** `NullReturnVals` `requestApproval` 10/72 and `VoidMethodCall` `requestApproval` 11/92 (§9.3).

**Still UNDETERMINED — 4:**

| Identity (prefix) | Mutator | Site | Observed |
|---|---|---|---|
| `75ec22b0e703bb8a` | VMC | `persistGoverned` 33/197 | `NO_COVERAGE → SURVIVED`, executes on successful resume |
| — | VMC | `persistUngoverned` 7/44 | `SURVIVED → SURVIVED`, 11 tests run |
| — | VMC | `persistGoverned` 7/44 | `SURVIVED → SURVIVED`, 5 tests run |
| — | VMC | `requireExistingAttributionMatches` 7/44 | `SURVIVED → SURVIVED`, 11 tests run |

The three entry-rethrow rows are executed on every resume yet their removal is undetected by any test in this slice,
including the failing-resume tests. That is stated as an observation, not a disposition: it is consistent with the
nested frame rethrowing first (so the delivered resume value never carries a failure) and would make them
equivalence candidates, but no equivalence argument is claimed here.

**`EQUIVALENT` proven this increment: 0. `TOOLING_LIMITATION` proven this increment: 0. `UNREACHABLE` proven: 2.**

### 9.7 Accounting

```
g1G4 parent UNDETERMINED before g1G4b        116
g1G4b DefaultApprovalGateway input            27
  KILLED this increment                       21
  EQUIVALENT proven                            0
  UNREACHABLE proven                           2
  TOOLING_LIMITATION proven                    0
  still UNDETERMINED                           4
  lost / duplicate / unexplained             0 / 0 / 0
g1G4 parent UNDETERMINED after g1G4b         116 - 23 = 93
```

Parent remainder by class: `ApprovalResumeCoordinator` 50, `ApprovalSuspensionCoordinator` 39,
`DefaultApprovalGateway` 4. Test added is not equated with identity settled: the 21 kills are measured transitions,
the 2 unreachability findings rest on the bytecode proof in §9.3, and the 4 survivors stay unresolved.

### 9.8 Reproduction

1. Derive the cohort: committed 118-manifest ∩ `className == dev.tramai.engine.approval.DefaultApprovalGateway`
   minus the identities the previous increment measured `KILLED`.
2. Positive control: `./gradlew :tramai-engine:test --tests "dev.tramai.engine.approval.GatewaySuspensionContractTest"`
   — 10 tests, 0 failures, and the `…Resumes` counters must be non-zero (a synchronous fake cannot reach the state).
3. Gates: `spotlessApply` **before** `verifyStaticAnalysis`; `:tramai-engine:test`; `spotlessCheck`;
   `verifyStaticAnalysis`; `verifyChangePolicy -PchangePolicyBase=7703685c… -PchangeClass=runtime-behaviour`.
4. Measurement: throwaway worktree at the test commit, narrow `mutation.targetFamilies` to
   `dev.tramai.engine.approval.DefaultApprovalGateway*`, commit the narrowing, run
   `generateCriticalMutationBaseline`, compare identity sets with the census, then regenerate the two matrices in §9.5.

---

## 10. Increment 3 - TASK-0.7.1g1G4c: ApprovalResumeCoordinator residuals (50)

**Exact base:** `6d798f80dd429370ebc03908ea06652366a918be` (equal to `origin/epic/0.7.1-control-plane-authority` at
branch time; #454 merged into the Epic as this commit).
**Preconditions verified:** #454 read back as `state=closed`, `merged=true`, `merge_commit_sha=6d798f80...`; clean
worktree; the committed 118-manifest recomputed to `0c2d107d...`.

### 10.1 Input cohort - 50 identities, derived not counted

Joined on the **full canonical identity**. The cohort is every manifest row whose `className` *starts with*
`dev.tramai.engine.approval.ApprovalResumeCoordinator`: the three `$resume$2` rows carry the generated class name, and an
equality filter loses exactly those three.

| Group | Count |
|---|---|
| `NullReturnVals` | 22 |
| `VoidMethodCall` | 26 |
| `NegateConditionals` | 2 |

Initial census state: **46 `NO_COVERAGE` + 4 `SURVIVED`**; decomposition `resume` 17, `prepareResume` 11,
`authorizeResume` 11, `executeClaimedResume` 6, `revealAndValidateReplayPayload` 2, `$resume$2.invokeSuspend` 3, all
reproduced exactly. `ApprovalResumeCoordinator.kt` is bytecode-unchanged since the census commit `a47a759f`, so the
frozen block/index tuples remain comparable; the manifest's stale *line* numbers were not used for derivation.

**Identity schema.** The manifest documents `module / className / method / methodDescription / mutator / description /
block / index`; the implemented separator is **`0x1f`**, not the `U+241F` glyph the manifest renders. Recomputed
identities reproduce both the committed population and the manifest cohort exactly (50/50 join), which is what makes the
measurement joinable at all.

### 10.2 Instruction mapping (`javap -p -c -l` at the exact base)

Every cohort row is a compiler-generated glue instruction with an exact seam:

- **`NullReturnVals` (22)** - the sentinel `areturn` a suspend call leaves behind (`invoke; dup; if_acmpne L; aload N;
  areturn`). Executed **only when the callee actually suspends**, which is why a synchronous fake leaves it
  `NO_COVERAGE`.
- **`VoidMethodCall` (26)** - `ResultKt::throwOnFailure` at the entry of a generated state-machine case (the rethrow
  that unwraps a failure delivered on the resumed frame), plus one `CancellationKt::rethrowIfCancellation` call site.
- **`NegateConditionals` (2)** - `if_acmpne` suspension checks after `denyAndCancel` / `cancelForNestedApproval`.

Seam to callee, read off the `invoke` preceding each sentinel:

| Method | Sentinels (pc) and their guarded call |
|---|---|
| `resume` | 148 `prepareResume`, 203 `authorizeResume`, 308 `ContinuationClaimService.claim`, 470 `executeClaimedResume`, 639 `withContext`, 808/1008/1221 `emitResumeUncertainOutcomeOnce` |
| `prepareResume` | 152 `ApprovalContinuationStore.get`, 262 `SuspendedInvocationStore.get`, 406 `governedRunIdentity`, 573 `loadPendingForResume`, 743 `validateToken` |
| `authorizeResume` | 149 `decideResumePolicy`, 260 `denyAndCancel`, 376 `cancelForNestedApproval`, 500 `authorize` |
| `executeClaimedResume` | 181 `revealAndValidateReplayPayload`, 313 `validateClaimedResumeArguments`, 572 `execute`, 809 `completeClaimedResume` |
| `revealAndValidateReplayPayload` | 148 `revealReplayEnvelope` |
| `$resume$2.invokeSuspend` | 73 `access$executeClaimedResume` |

The mapping is order-checked, not guessed: within each `(method, line, mutator)` the measurement's block/index order is
monotone in bytecode pc, and the non-cohort rows fall out consistently (`resume` block 7 is the case-0 entry rethrow;
`executeClaimedResume` blocks 13/14; `revealAndValidateReplayPayload` blocks 11/12).

**Reachability finding: the two `NegateConditionals` are NOT unreachable.** They are the standard suspension checks after
`ReplayAuthorizationService.denyAndCancel` / `cancelForNestedApproval`. Both callees are declared `Nothing`, but
`cancelState` reaches `ApprovalContinuationStore.cancel`, so the callee **can** suspend: suspension is a *normal* return
of the sentinel, and the method then throws from the resumed frame. Inverting the check sends a suspending cancellation
to the `KotlinNothingValueException` path instead of returning the sentinel, so the caller would see a compiler artefact
instead of the domain exception. Both were **killed by measurement**, not written off as compiler-shaped.

### 10.3 Durable tests added (22)

`tramai-engine/src/test/kotlin/dev/tramai/engine/approval/ApprovalResumeSuspensionContractTest.kt` - a new class rather
than edits inside the released suite. Every collaborator is a delegating double that genuinely `delay(1)`-suspends and
optionally fails **on the resumed frame**, each with at most one failure per operation it performs:

- a resume completes when every collaborator genuinely suspends (the resumed counters are the positive control: a
  synchronous fake cannot produce them); a governed resume completes and executes inside the recovered run scope;
- a failure after suspension at each seam reaches the caller unchanged and stops the chain: continuation eligibility
  read, claim-time read, metadata load, governed identity read, token validation, authorization, claim write, replay
  reveal, claimed-arguments integrity mismatch, executor, completion write, policy-decision audit;
- uncertain-outcome contracts: a `StructuredOutputException` and a `NestedApprovalNotSupportedException` raised after
  suspension are reported uncertain and propagated; an uncertain-outcome audit failure does not replace the primary
  failure; a completion-audit failure is recorded and does not fail the resume;
- denial and nested-approval requirements still cancel and reach the caller **when the cancellation itself suspends**
  (the two `NegateConditionals`);
- a replay-envelope digest mismatch is rejected and reports the mismatch before claiming.

Two contract facts the harness forced: kotlinx's stack-trace recovery delivers a *copy* of the failure, so identity of
the instance is not the contract (the tests assert type plus a per-test unique message); and the denied/nested paths run
**before** the claim, so they emit no uncertain outcome at all (asserted as zero).

### 10.4 Measurement provenance

Measured test commits: `4d772e81` (the original harness) and `423779b2` (after the review fix), each in a throwaway
worktree with one committed narrowing (`f01d296e`, `f9345b2c`).

**Certified.** The measurement was re-run at every test head that mattered: `36c50804` (narrowing `bfd061af`,
5 m 10 s) and, after the g1G4c review fix, at the shipped head `423779b2` (narrowing `f9345b2c`, 5 m 6 s).
Both runs: identical identity set (161/161), cohort reproduced exactly - 39 `NO_COVERAGE -> KILLED`,
1 `SURVIVED -> KILLED`, 7 `NO_COVERAGE -> SURVIVED`, 3 `SURVIVED -> SURVIVED` - zero status drift,
0 regressions, 0 new `TIMED_OUT`, 0 identity loss or gain. An intermediate head (`c606501c`) did not compile;
the nested measurement caught it before certification. The review finding - the continuation-store double returned
copies from claim/complete/cancel without adopting them, so the coordinator could pass against states no real store
produces - was fixed at `423779b2`, where the double evolves PENDING v3 -> CLAIMED v4 -> COMPLETED v5 (or
PENDING -> CANCELLED) and the tests assert the resulting state. The outcome did not change; that is recorded, not
assumed.

Deviation, stated explicitly: the narrowing of `mutation.targetFamilies` was applied by pattern over the `approval`
family and removed the unrelated families entirely rather than only narrowing `approval`. Candidate and control used the
**identical** narrowing, so the comparison stays scope-matched, and the joined scope is the coordinator's own mutants.

The aggregate `config/quality/mutation-baseline.json` write is admission-gated in this repository state and was not
produced by either run, so the join is made from PIT's own `mutations.xml` (raw tool output: per-mutant status,
`numberOfTestsRun`, `killingTest`), with per-mutant identities recomputed from that XML using the committed schema.

### 10.5 Identity-exact results

Full scoped transition matrix (control at base -> candidate), 161 shared identities:

| Before | After | Count |
|---|---|---|
| `KILLED` | `KILLED` | 38 |
| `NO_COVERAGE` | `KILLED` | 53 |
| `NO_COVERAGE` | `NO_COVERAGE` | 6 |
| `NO_COVERAGE` | `SURVIVED` | 11 |
| `SURVIVED` | `KILLED` | 4 |
| `SURVIVED` | `SURVIVED` | 20 |
| `TIMED_OUT` | `KILLED` | 7 |
| `TIMED_OUT` | `TIMED_OUT` | 22 |

Controls: `KILLED -> non-KILLED` regressions **0**; identity loss **0**; new identities **0**; new `TIMED_OUT` **0**
(22 pre-existing `TIMED_OUT` unchanged, none of them a cohort identity).

Cohort matrix (frozen manifest -> candidate), every one of the 50 exactly once:

| Before | After | Count |
|---|---|---|
| `NO_COVERAGE` | `KILLED` | 39 |
| `NO_COVERAGE` | `SURVIVED` | 7 |
| `SURVIVED` | `KILLED` | 1 |
| `SURVIVED` | `SURVIVED` | 3 |

`NO_COVERAGE -> SURVIVED` is reported explicitly rather than disguised as progress: **7**.

### 10.6 Identity-exact dispositions

**KILLED this increment - 40** (39 `NO_COVERAGE -> KILLED`, 1 `SURVIVED -> KILLED`), each naming its killing test and
`numberOfTestsRun`:

| Identity | Mutator | Site | Killing test | tests run |
|---|---|---|---|---|
| `cb90dfa953` | VoidMethodCall | `authorizeResume` 7/44 | deny policy cancels state and never claims or executes() | 1 |
| `2d1f9e3730` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `authorizeResume` 12/71 | a policy-decision audit failure after suspension reaches the caller() | 1 |
| `eacdfd8c8a` | VoidMethodCall | `authorizeResume` 13/83 | a policy-decision audit failure after suspension reaches the caller() | 1 |
| `b36fc3ca50` | NegateConditionals | `authorizeResume` 23/129 | a denied resume cancels and reaches the caller when the cancellation suspends() | 1 |
| `64b71d7ed9` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `authorizeResume` 24/133 | a denied resume cancels and reaches the caller when the cancellation suspends() | 1 |
| `9516ca2fa7` | VoidMethodCall | `authorizeResume` 25/150 | a denied resume cancels and reaches the caller when the cancellation suspends() | 1 |
| `4be603cfac` | NegateConditionals | `authorizeResume` 37/195 | a nested-approval requirement cancels and reaches the caller when the cancellation suspends() | 1 |
| `6759437252` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `authorizeResume` 38/199 | a nested-approval requirement cancels and reaches the caller when the cancellation suspends() | 1 |
| `b1d24bc23a` | VoidMethodCall | `authorizeResume` 39/216 | a nested-approval requirement cancels and reaches the caller when the cancellation suspends() | 1 |
| `5cae2063c9` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `authorizeResume` 54/275 | an authorization failure after suspension reaches the caller before the claim() | 1 |
| `ef7dc1d6b1` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `executeClaimedResume` 20/171 | a claimed-arguments integrity mismatch after suspension is rejected() | 1 |
| `577c07309b` | VoidMethodCall | `executeClaimedResume` 21/202 | a claimed-arguments integrity mismatch after suspension is rejected() | 1 |
| `04c94b4890` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `executeClaimedResume` 40/327 | an executor failure after suspension reaches the caller and completes nothing() | 1 |
| `021b3c457a` | VoidMethodCall | `executeClaimedResume` 41/378 | an executor failure after suspension reaches the caller and completes nothing() | 1 |
| `4b1bdb9fc7` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `executeClaimedResume` 55/445 | a completion write failure after suspension reaches the caller() | 1 |
| `b280cd7e98` | VoidMethodCall | `executeClaimedResume` 56/500 | a completion write failure after suspension reaches the caller() | 1 |
| `09642639c5` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `invokeSuspend` 6/36 | a governed executor failure after suspension reaches the caller() | 1 |
| `f8bdda993a` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `prepareResume` 12/72 | a continuation read failure after suspension reaches the caller() | 1 |
| `f20df64ded` | VoidMethodCall | `prepareResume` 13/85 | a continuation read failure after suspension reaches the caller() | 1 |
| `8cdddbe077` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `prepareResume` 25/135 | a metadata load failure after suspension reaches the caller() | 1 |
| `b545c2ae14` | VoidMethodCall | `prepareResume` 26/153 | a metadata load failure after suspension reaches the caller() | 1 |
| `4cca31fe70` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `prepareResume` 40/228 | a governed identity read failure after suspension reaches the caller before any claim() | 1 |
| `8510790e92` | VoidMethodCall | `prepareResume` 41/251 | a governed identity read failure after suspension reaches the caller before any claim() | 1 |
| `e2f022edd7` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `prepareResume` 54/336 | a claim-time read failure after suspension reaches the caller without claiming() | 1 |
| `613bf2d479` | VoidMethodCall | `prepareResume` 55/369 | a claim-time read failure after suspension reaches the caller without claiming() | 1 |
| `39f37feb8a` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `prepareResume` 62/427 | a token validation failure after suspension reaches the caller() | 1 |
| `2f92ef797c` | VoidMethodCall | `prepareResume` 63/470 | a token validation failure after suspension reaches the caller() | 1 |
| `d3f812e883` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 10/63 | a continuation read failure after suspension reaches the caller() | 1 |
| `ceba69cd43` | VoidMethodCall | `resume` 11/71 | a continuation read failure after suspension reaches the caller() | 1 |
| `18ac9e9b9d` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 15/99 | an authorization failure after suspension reaches the caller before the claim() | 1 |
| `441d334356` | VoidMethodCall | `resume` 16/112 | an authorization failure after suspension reaches the caller before the claim() | 1 |
| `7cd98a9030` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 27/159 | a claim write failure after suspension reaches the caller() | 1 |
| `ece7d1190f` | VoidMethodCall | `resume` 28/177 | a claim write failure after suspension reaches the caller() | 1 |
| `f0effc0ace` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 43/268 | a replay reveal failure after suspension reaches the caller and completes nothing() | 1 |
| `92b019e1ba` | VoidMethodCall | `resume` 44/303 | a nested-approval requirement raised after suspension is reported uncertain and propagated() | 3 |
| `13586a6301` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 55/360 | a governed executor failure after suspension reaches the caller() | 1 |
| `2d5928356b` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 68/463 | a nested-approval requirement raised after suspension is reported uncertain and propagated() | 1 |
| `5170395637` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 86/579 | a structured-output failure after suspension is reported uncertain and propagated() | 1 |
| `d20dbac385` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `resume` 106/708 | an uncertain-outcome audit failure after suspension does not replace the primary failure() | 1 |
| `20ca1a1d8d` | org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator | `revealAndValidateReplayPayload` 32/174 | a replay-envelope digest mismatch is rejected and reports the mismatch before claiming() | 1 |

One of the 40 (`cb90dfa953`, `authorizeResume` 7/44) was already detected by the released
`ApprovalResumeCoordinatorTest.deny policy cancels state and never claims or executes`; the rest are credited to the new
contract tests.

**EQUIVALENT proven - 1:** `1ce5967ee6` - `resume` block 91/index 641, line 108, `removed call to
CancellationKt::rethrowIfCancellation`. The mutated call sits in the **last** catch clause (`catch (e: Exception)`), and
`rethrowIfCancellation` is exactly `if (this is CancellationException) throw this`. The clause immediately above it is
`catch (e: CancellationException)` rethrowing `e`, and Kotlin matches catch clauses in order, so no reachable value of
`e` can be a `CancellationException` or any subclass (`kotlinx.coroutines.CancellationException` is
`java.util.concurrent.CancellationException` on the JVM). The call is a no-op for every value the site can receive, so
removing it cannot change behaviour. The measurement agrees (`SURVIVED -> SURVIVED`, 17 tests run).

**UNREACHABLE proven - 0. `TOOLING_LIMITATION` proven - 0.**

**Still UNDETERMINED - 9**, all of them the same construct, and all of them *executed*:

| Identity | Site | Observed | tests run | Mutation |
|---|---|---|---|---|
| `63e5372ba3` | `authorizeResume` 55/292 line 144 | NO_COVERAGE -> SURVIVED | 5 | removed call to kotlin/ResultKt::throwOnFailure |
| `e0ae69141e` | `invokeSuspend` 2/12 line 85 | SURVIVED -> SURVIVED | 4 | removed call to kotlin/ResultKt::throwOnFailure |
| `492f9a132e` | `invokeSuspend` 7/40 line 85 | NO_COVERAGE -> SURVIVED | 2 | removed call to kotlin/ResultKt::throwOnFailure |
| `ab78ed8577` | `prepareResume` 7/44 line 119 | SURVIVED -> SURVIVED | 99 | removed call to kotlin/ResultKt::throwOnFailure |
| `5397c6bf59` | `resume` 56/395 line 64 | NO_COVERAGE -> SURVIVED | 2 | removed call to kotlin/ResultKt::throwOnFailure |
| `ef13cbec0d` | `resume` 69/501 line 64 | NO_COVERAGE -> SURVIVED | 1 | removed call to kotlin/ResultKt::throwOnFailure |
| `ecb1e83af5` | `resume` 87/617 line 64 | NO_COVERAGE -> SURVIVED | 1 | removed call to kotlin/ResultKt::throwOnFailure |
| `9026b993fd` | `resume` 107/746 line 64 | NO_COVERAGE -> SURVIVED | 2 | removed call to kotlin/ResultKt::throwOnFailure |
| `62911071da` | `revealAndValidateReplayPayload` 33/205 line 212 | NO_COVERAGE -> SURVIVED | 1 | removed call to kotlin/ResultKt::throwOnFailure |

These are the state-machine **case-entry rethrows**: removing the unwrap of a failure delivered on the resumed frame is
not detected by any test in the slice, including the failing-resume tests. That is recorded as the observation it is -
it is consistent with the resumed frame delivering the failure through the nested call rather than through the case
entry - but no equivalence argument is claimed, so they stay `UNDETERMINED`. It is the same family the previous
increment observed on `DefaultApprovalGateway`; a repeatable pattern is not evidence of equivalence.

### 10.7 Accounting

```
g1G4 parent UNDETERMINED before g1G4c         93
g1G4c ApprovalResumeCoordinator input          50
  KILLED this increment                        40
  EQUIVALENT proven                             1
  UNREACHABLE proven                            0
  TOOLING_LIMITATION proven                     0
  still UNDETERMINED                            9
  lost / duplicate / unexplained            0 / 0 / 0
g1G4 parent UNDETERMINED after g1G4c          93 - 41 = 52
```

Parent remainder by class: `ApprovalSuspensionCoordinator` 39, `DefaultApprovalGateway` 4, `ApprovalResumeCoordinator` 9.
g1G4d/g1G4e remain later work; parent g1G4 is **not** complete.

No production code was touched, no baseline regenerated, no classification or admission file edited, no mutator set,
timeout policy, deviation ceiling or workflow weakened. None of the 50 identities was admitted through the g1G4z2
population-admission mechanism.

### 10.8 Reproduction

1. Derive the cohort: committed 118-manifest, `className` **prefix** `dev.tramai.engine.approval.ApprovalResumeCoordinator`.
2. Positive control: `./gradlew :tramai-engine:test --tests "dev.tramai.engine.approval.ApprovalResumeSuspensionContractTest"`
   - 22 tests, 0 failures.
3. Measurement: throwaway worktree at `4d772e81` (or the shipped head), narrow `mutation.targetFamilies` (measurement commit `f01d296e`),
   `./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks`, then join
   `build/reports/maintainability/mutation/approval/tramai-engine/mutations.xml` to the frozen cohort by full canonical
   identity. Control: the same narrowing at `6d798f80`.
4. Gates: `verifyChangePolicy -PchangeClass=runtime-behaviour -PchangePolicyBase=6d798f80...`; `:tramai-engine:test`;
   `spotlessCheck verifyStaticAnalysis verifyStaticSafetyGuards verifyJUnitTestSignatures`.

## 11. Increment 4 -- TASK-0.7.1g1G4d: ApprovalSuspensionCoordinator residuals

### 11.1 Start gate

- **task base**: `19c3d939291194e849dc42515a516b09cebec7a3` -- the squash merge of #455, verified as the exact `origin/epic/0.7.1-control-plane-authority` tip (0 commits after it, clean tree, `#455 merged=true`, merge commit equal to that SHA).
- **test commit**: `3aeff5f0b3d86bbed09c604a5d166673869cba42`
- superseded test commit: `8850c27e040597871e1def7e8177c073976b0df8` (identical tests plus one unused `ResumeOperationReference` import, removed after review; the ledger was recertified at the cleanup commit, see 11.5)
- **measurement narrowings**: candidate `d6e92f67`, control `5391ee4a` (each committed in its throwaway worktree)
- branch: `task/0.7.1g1G4d-approval-suspension-residuals`

### 11.2 Frozen input

`docs/roadmap/0.7.0/TASK-0.7.1g1G4-UNDETERMINED-118-MANIFEST.json`, digest recomputed with the manifest's own recipe (sha256 of the sorted identities joined by newline **with a trailing newline**):

```
0c2d107def8f30c564e648c4a86a11049c4a7cef4bfed5c78e07d3f95b9c77ea   MATCH
```

The recipe matters: the same list joined without the trailing newline hashes to `2aef6973...`. The cohort is derived by full canonical identity with the class-name **prefix** `dev.tramai.engine.approval.ApprovalSuspensionCoordinator`, which is what pulls in the generated `$compensateSuspension$2$*` lambdas. Result: **39 rows -- 18 NullReturnVals / 19 VoidMethodCall / 2 NegateConditionals**, census **29 NO_COVERAGE + 10 SURVIVED**, area split 12 / 7 / 12 / 4 / 4 exactly as specified. The two `NegateConditionals` are `5d4ca8196b...` and `6dc7341455...`, both in `persistSuspendedInvocation`, both SURVIVED.

### 11.3 Instruction mapping

`javap -p -c -l` on the compiled base for `ApprovalSuspensionCoordinator` and the three lambdas. The mapping below is by block/index order against pc order, and it reconciles exactly:

| area | NV | VMC | NC | sentinel `areturn`s | `throwOnFailure` | reads as |
|---|---:|---:|---:|---|---|---|
| `suspendToolExecution` | 6 | 6 | 0 | 6 | 7 (1 excluded) | the six suspend seams, each a sentinel plus its case-entry rethrow |
| `compensateSuspension` | 3 | 4 | 0 | 4 (1 excluded) | 4 | the three `compensateStep` calls plus the method's entry rethrow |
| `$compensateSuspension$2$1/2/3` | 6 | 6 | 0 | 2 each | 2 each | one underlying store/gate call per lambda |
| `compensateStep` | 1 | 3 | 0 | 2 (1 excluded) | 2 + 1 call | the action invocation, its case rethrow, and `rethrowIfCancellation()` |
| `persistSuspendedInvocation` | 2 | 0 | 2 | 4 (2 excluded) | 0 | `createGoverned` and `create` sentinels; the two branch conditions |

The six `suspendToolExecution` seams, in pc order, are: `resolveGovernedSuspension()`, `approvalGateCoordinator.createApproval(...)`, `approvalContinuationStore.create(...)`, `persistSuspendedInvocation(...)`, `approvalLifecycleAuditEmitter.onToolExecutionSuspended(...)`, `compensateSuspension(...)`. Instructions excluded from the cohort are the ones the frozen census already classified elsewhere; nothing was inferred from source-line attribution (the manifest's lines are historical, and the class was recompiled uneventfully since).

### 11.4 Durable tests added

`tramai-engine/src/test/kotlin/dev/tramai/engine/approval/ApprovalSuspensionSagaContractTest.kt` -- **14 tests**, green. Every collaborator suspends (`delay`) before it answers and can fail on the resumed frame; a single ordered event log proves ordering rather than inference.

Contracts asserted, in domain terms:

- the intended suspension path: challenge created, PENDING continuation persisted, suspended invocation persisted, suspension audit emitted, `ApprovalSuspendedException` carries approval/continuation state, and **compensation never runs for it** (suspension is the outcome, not a failure);
- governed vs ungoverned persistence: the same dual-SPI store proves `createGoverned` is used when a governed run scope is present and `create` when it is not;
- the ordinary-failure gradient: refusal before the challenge (nothing to compensate), failure after the challenge but before the continuation, after the continuation, and during the audit;
- compensation runs in reverse order (`suspendedInvocationStore.remove`, `continuationStore.cancel`, `gate.cancelApproval`) and the initiating failure stays primary when a compensation step fails;
- a failure on the middle compensation step still runs the last step; a failure on the last step is swallowed;
- a `CancellationException` as the *initiating* failure propagates without compensation, and one delivered on a compensation action's resumed frame propagates immediately instead of being swallowed, so the later steps do not run;
- each compensation action genuinely suspends before it runs (resume counters, which a synchronous double cannot produce);
- a governed scope with a store that cannot persist the governed record is refused before anything is created.

Test-double fidelity, per the #455 lesson: `SagaContinuationStore` is a state machine (PENDING v0 -> CANCELLED v1), refuses a stale expectation and a non-PENDING status with the production exceptions, and refuses cancellation of a continuation that was never persisted. One correction was needed during development and is recorded: the doubles log **after** they act, so a failing injected step logs nothing on the delegate; the failure wrappers therefore count *attempts* explicitly rather than letting a missing event read as a missing attempt.

### 11.5 Measurement

```
./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks
```

Run in throwaway worktrees at the exact commits, each with a committed narrowing (`mutation.targetFamilies.approval` -> `:tramai-engine` + `dev.tramai.engine.approval.ApprovalSuspensionCoordinator*`; 2 insertions, 3 deletions, no other family touched). Raw `mutations.xml` consumed; canonical identities recomputed with the repository schema (`\x1f` separator, module `:tramai-engine`); candidate, control and frozen cohort joined by full canonical identity.

- candidate `3aeff5f0` (the cleanup head), narrowing `afe53b5a`: **BUILD SUCCESSFUL in 4 m 2 s**, 104 mutants
- superseded candidate `8850c27e`, narrowing `d6e92f67`: **BUILD SUCCESSFUL in 3 m 39 s**, 104 mutants
- control `19c3d939`, narrowing `5391ee4a`: **BUILD SUCCESSFUL in 3 m 41 s**, 104 mutants
- 104 shared identities, **0 lost, 0 new**, 39/39 cohort joined

The cleanup was recertified rather than assumed, and it did not need a bytecode-identity argument: the class-file hashes of the test class before and after differ (`790e34ae...` vs `47c72b4b...`) because removing line 42 shifts every following debug line number, which is exactly why a hash comparison would have been the wrong proof. Measured instead: recertified candidate vs pre-cleanup candidate -- **104/104 shared, 0 status changes, zero drift**; every matrix in 11.6 and 11.7 is the recertified one.

Two intermediate runs were discarded and are named here rather than silently replaced: an early candidate run at the 11-test revision (superseded by the 14-test revision) and a pair of concurrent runs that failed inside the nested `:tramai-engine:pitest` under resource contention. Reports from a failed build were not used; both numbers below come from builds that reported success.

### 11.6 Full scoped transition matrix

| control → candidate | count |
|---|---:|
| KILLED → KILLED | 40 |
| NO_COVERAGE → KILLED | 25 |
| NO_COVERAGE → NO_COVERAGE | 2 |
| NO_COVERAGE → SURVIVED | 5 |
| SURVIVED → KILLED | 8 |
| SURVIVED → SURVIVED | 11 |
| TIMED_OUT → KILLED | 2 |
| TIMED_OUT → TIMED_OUT | 11 |

**KILLED regressions: 0. New TIMED_OUT: 0.** No other transition occurs.

### 11.7 Cohort transition matrix (frozen census → candidate)

| frozen → candidate | count |
|---|---:|
| NO_COVERAGE → KILLED | 22 |
| SURVIVED → KILLED | 2 |
| NO_COVERAGE → SURVIVED | 5 |
| SURVIVED → SURVIVED | 8 |
| NO_COVERAGE → NO_COVERAGE | 2 |

Cohort **24 KILLED / 39**, census TIMED_OUT 0, candidate TIMED_OUT 0. Every `NO_COVERAGE -> SURVIVED` row is listed in 11.9 and is **not** counted as settled: executing an instruction is not detecting its mutation.

### 11.8 Identity-exact dispositions

| identity | area | mutator | block/index | instruction | frozen → candidate | disposition |
|---|---|---|---|---|---|---|
| `95f4d3181b` | compensateStep | NullReturnVals | 11/67 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `60f7b020e0` | compensateStep | VoidMethodCall | 7/44 | removed call to kotlin/ResultKt::throwOnFailure | SURVIVED -> SURVIVED | UNDETERMINED |
| `38af093087` | compensateStep | VoidMethodCall | 12/77 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `c7774df8cc` | compensateStep | VoidMethodCall | 16/101 | removed call to dev/tramai/core/coroutines/CancellationKt::rethrow | NO_COVERAGE -> SURVIVED | EQUIVALENT |
| `10ee55f449` | compensateSuspension | NullReturnVals | 13/94 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `e09a3c1cc3` | compensateSuspension | NullReturnVals | 21/168 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `af8cf7ad14` | compensateSuspension | NullReturnVals | 31/243 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `f60db12bdb` | compensateSuspension | VoidMethodCall | 7/44 | removed call to kotlin/ResultKt::throwOnFailure | SURVIVED -> SURVIVED | UNDETERMINED |
| `1b8938aaab` | compensateSuspension | VoidMethodCall | 14/122 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `887313eb01` | compensateSuspension | VoidMethodCall | 22/196 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> SURVIVED | UNDETERMINED |
| `4fc586ff85` | compensateSuspension | VoidMethodCall | 32/271 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> SURVIVED | UNDETERMINED |
| `5d4ca8196b` | persistSuspendedInvocation | NegateConditionals | 7/30 | negated conditional | SURVIVED -> KILLED | KILLED |
| `6dc7341455` | persistSuspendedInvocation | NegateConditionals | 12/50 | negated conditional | SURVIVED -> KILLED | KILLED |
| `b4bde9e3e3` | persistSuspendedInvocation | NullReturnVals | 8/31 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `ec0280ffb2` | persistSuspendedInvocation | NullReturnVals | 13/51 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `66c311cf4f` | suspendToolExecution | NullReturnVals | 12/78 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> NO_COVERAGE | UNREACHABLE |
| `1843d3a31b` | suspendToolExecution | NullReturnVals | 36/210 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `c2be62debe` | suspendToolExecution | NullReturnVals | 61/392 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `4f99ba33ee` | suspendToolExecution | NullReturnVals | 110/672 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `3b12d1a55c` | suspendToolExecution | NullReturnVals | 133/857 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `453e4aaf23` | suspendToolExecution | NullReturnVals | 158/1071 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `564517b2fd` | suspendToolExecution | VoidMethodCall | 13/96 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> NO_COVERAGE | UNREACHABLE |
| `f1a19c075b` | suspendToolExecution | VoidMethodCall | 37/252 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `62897c202f` | suspendToolExecution | VoidMethodCall | 62/441 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `87fbd8e6ed` | suspendToolExecution | VoidMethodCall | 111/751 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `04c2e1c3f1` | suspendToolExecution | VoidMethodCall | 134/936 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `30f9bb41fa` | suspendToolExecution | VoidMethodCall | 159/1118 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `8abddce868` | suspension$2$1 | NullReturnVals | 6/32 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `f802165800` | suspension$2$1 | NullReturnVals | 9/43 | replaced return value with null for dev/tramai/engine/approval/App | SURVIVED -> SURVIVED | UNDETERMINED |
| `ebc96b3e85` | suspension$2$1 | VoidMethodCall | 2/12 | removed call to kotlin/ResultKt::throwOnFailure | SURVIVED -> SURVIVED | UNDETERMINED |
| `ddf6092edb` | suspension$2$1 | VoidMethodCall | 7/36 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> KILLED | KILLED |
| `980c70e5ac` | suspension$2$2 | NullReturnVals | 5/33 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `f175e8698d` | suspension$2$2 | NullReturnVals | 8/44 | replaced return value with null for dev/tramai/engine/approval/App | SURVIVED -> SURVIVED | UNDETERMINED |
| `6cef3fd2e2` | suspension$2$2 | VoidMethodCall | 2/12 | removed call to kotlin/ResultKt::throwOnFailure | SURVIVED -> SURVIVED | UNDETERMINED |
| `1c4ca901fd` | suspension$2$2 | VoidMethodCall | 6/37 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> SURVIVED | UNDETERMINED |
| `ceab216271` | suspension$2$3 | NullReturnVals | 5/33 | replaced return value with null for dev/tramai/engine/approval/App | NO_COVERAGE -> KILLED | KILLED |
| `70d345f720` | suspension$2$3 | NullReturnVals | 8/44 | replaced return value with null for dev/tramai/engine/approval/App | SURVIVED -> SURVIVED | UNDETERMINED |
| `eec481d97a` | suspension$2$3 | VoidMethodCall | 2/12 | removed call to kotlin/ResultKt::throwOnFailure | SURVIVED -> SURVIVED | UNDETERMINED |
| `a15f9807be` | suspension$2$3 | VoidMethodCall | 6/37 | removed call to kotlin/ResultKt::throwOnFailure | NO_COVERAGE -> SURVIVED | UNDETERMINED |

Counting the table: **KILLED 24, EQUIVALENT 1, UNREACHABLE 2, TOOLING_LIMITATION 0, UNDETERMINED 12 -- sum 39.**

**KILLED (24).** Measured at canonical identity against the durable tests above. Both `NegateConditionals` are in this set: the governed/ungoverned discriminator distinguishes them, so the inverted branch is observable rather than harmless -- neither is compiler scaffolding in the equivalence sense.

**EQUIVALENT (1).** `c7774df8cc...`, `compensateStep`, block 16/index 101, `removed call to dev/tramai/core/coroutines/CancellationKt::rethrowIfCancellation`. Proven from **this method's own exception table**, not by analogy with the g1G4c case:

```
       from    to  target type
          93   117   147   Class java/util/concurrent/CancellationException
         135   144   147   Class java/util/concurrent/CancellationException
          93   117   150   Class java/lang/Exception
         135   144   150   Class java/lang/Exception
```

Handlers are matched in order, so a `CancellationException` thrown anywhere in the protected ranges is handled by target 147 and can never reach target 150. The callee of the mutated call is `if (this is CancellationException) throw this`, and `kotlinx.coroutines.CancellationException` is the same JVM class the table names, so within handler 150 the call cannot throw and removing the invocation cannot change reachable behaviour. g1G4c reached the same conclusion for a similar-looking call; that proof was deliberately not reused, and the reader can see the two tables differ in shape.

**UNREACHABLE (2).** `66c311cf4f...` (NullReturnVals, `suspendToolExecution`, block 12/index 78) and `564517b2fd...` (VoidMethodCall, block 13/index 96) are the sentinel and case-entry rethrow of the **`resolveGovernedSuspension()` seam**. That seam's callee cannot suspend, and the proof is in its own bytecode:

- `resolveGovernedSuspension` contains **no** `IntrinsicsKt.getCOROUTINE_SUSPENDED` and **no** `ResultKt.throwOnFailure`, i.e. it has no suspension point and no state machine; its `currentCoroutineContext()` call is compiled to a plain `getContext()`/`resolve` sequence with a single `areturn`;
- a suspend function with no suspension point can never return the sentinel, so the caller's `areturn` branch for that seam is never taken and the corresponding state-machine case is never entered;
- the measurement agrees independently: both rows are `NO_COVERAGE -> NO_COVERAGE`, i.e. no test in the whole suite executes them, before or after this increment.

**UNDETERMINED (12).** Kept explicitly. 5 are `NO_COVERAGE -> SURVIVED` (now executed, still undetected) and 7 are `SURVIVED -> SURVIVED`: the state-machine entry rethrows of `compensateSuspension`, `compensateStep` and the three lambdas, the lambdas' value-returning `areturn`s, and the two newly-executed case-entry rethrows in the lambdas. They are the same compiler-generated family that produced 24 kills here, which is exactly why no equivalence is claimed for the survivors: a repeatable pattern is not evidence. Each would need its own argument, and none is offered.

### 11.9 Parent accounting

```
parent before          52
settled in g1G4d       27   (KILLED 24 + EQUIVALENT 1 + UNREACHABLE 2 + TOOLING_LIMITATION 0)
parent after           25   (52 - 27)
```

The 25 residuals are: 12 `ApprovalSuspensionCoordinator` survivors above, the 9 `ApprovalResumeCoordinator` survivors recorded in §10, and the 4 `DefaultApprovalGateway` survivors. Parent g1G4 is **not** complete, and `52 - 39 = 13` would be wrong.

### 11.10 Review findings

- `ResumeOperationReference` import in the contract test was unused (line 42, sole occurrence). Removed in the test commit `3aeff5f0`; the review thread is answered with the recertification above. No other review finding was raised.
- No substantive finding was raised against the 24/1/2/12 adjudication, the compensation ordering or the cancellation contracts.

### 11.11 Not done

- no production change: this slice is test-only, and nothing in it required a production fix;
- no baseline, classification, admission-ledger, mutator-set, timeout-policy, deviation-ceiling or gate change;
- the 9 `ApprovalResumeCoordinator` and 4 `DefaultApprovalGateway` survivors were not touched;
- g1G4e was not started.

## 12. Increment 5 -- TASK-0.7.1g1G4e: Final residual structural adjudication

### 12.1 Start gate

- **task base**: `36923eeee79145e63281e6378b114404e2cddba5` -- verified as the exact `origin/epic/0.7.1-control-plane-authority` tip (0 commits after, clean tree, `#456 merged=true` with that merge commit).
- branch: `task/0.7.1g1G4e-final-residual-adjudication`
- **test commit**: `29ead0226fcfbdb59cce0ecdea279f7e51a74c3f`
- **narrowings**: candidate `1551a4db`, control `c50b7342` (both throwaway worktrees, committed there only)

### 12.2 The residual 25

Derived as the original frozen 118 **minus** every identity firmly disposed by g1G4a-d, by canonical identity. The derivation is proven by digest, not asserted:

```
4cd9b668d37657b42e11c4d36836be68ddb36916cb4b27aaaa4e015bacaa8f5c   MATCH
```

Composition: `ApprovalResumeCoordinator` 9, `ApprovalSuspensionCoordinator` 12, `DefaultApprovalGateway` 4; by mutator **22 VoidMethodCall + 3 NullReturnVals**. Every block/index also matches the brief's expected sites. Evidence-only manifest: `docs/roadmap/0.7.0/TASK-0.7.1g1G4-RESIDUAL-25-MANIFEST.json` (explicitly not mutation authority).

**Base-state correction, resolved.** The brief expected all 25 `SURVIVED`; the exact-base control run reported `eec481d97a...` (`$compensateSuspension$2$3.invokeSuspend`, 2/12, line 295) as `KILLED` by the released `ApprovalSuspensionCoordinatorTest.compensation failure never replaces the original failure`.

That report does not survive reproduction. A second measurement at the same commit, narrowed to that single class, reports the same mutant **`SURVIVED`** with 11 tests run, and the class's other kills are attributed to *different* tests in the two runs (NC 4/29 killed by `a failure after the challenge but before...` in the control and by `metadata create failure compensates cont...` in the single-class run). Same commit, same tests, same production bytecode, different outcome: the earlier `KILLED` was a per-run attribution artifact, not a semantic kill. The identity is therefore adjudicated on bytecode evidence (12.7) instead of on that report.

### 12.3 Instruction classification

`javap -p -c -l` on the compiled base for `DefaultApprovalGateway`, `ApprovalResumeCoordinator`, `ApprovalResumeCoordinator$resume$2`, `ApprovalSuspensionCoordinator` and the three `$compensateSuspension$2$*` lambdas. PIT's complete mutant list per method (control run) was aligned positionally with the pc-ordered `ResultKt::throwOnFailure` / `areturn` instructions, and each identity's enclosing **state-machine case label** was read from the `tableswitch` dispatch.

The alignment is anchored, not positional: PIT's own non-`throwOnFailure` mutants pin the numbering on the lambda class -- `NegateConditionals` 4/29 is the `aload_2; if_acmpne` at pc 63, `NullReturnVals` 5/33 is the sentinel `areturn` at pc 67, `NullReturnVals` 8/44 is the `Unit.INSTANCE` `areturn` at pc 77 -- leaving `VoidMethodCall` 2/12 = pc 33 (case 0) and 6/37 = pc 69 (case 1), exactly the parsed instruction order. A purely positional alignment cannot show this, which is why `resume` (11 mutants vs 9 parsed instructions) stays flagged in 12.11.

The value under every mutated `throwOnFailure` is the local named **`$result`** -- for methods the final `Continuation` parameter (verified in each generated signature: `prepareResume`, `compensateStep`, `compensateSuspension`, `persistGoverned`, `persistUngoverned`, `requireExistingAttributionMatches` all end in `kotlin.coroutines.Continuation<...>`), for the lambdas the `invokeSuspend` parameter.

Two families fall out:

- **case 0 = initial entry** (10 identities): the check runs on the incoming completion before any assignment; a `Continuation` (or `Unit.INSTANCE`) is never a `Result.Failure`.
- **case N>0 = resumed frame** (12 identities): the check is the delivery point for a failure from the child that resumed us.

### 12.4 Falsification attempts

Per the brief, falsification came first: *what would have to be true for this mutation to be observable?*

1. `75ec22b0e7...` (`persistGoverned`, 33/197) had a named missing obligation -- a governed continuation write that suspends and then fails. The test was written, and the identity **died**: this is the one measured kill of the increment (12.8).
2. The case-0 entries were attacked through their call chain. For the lambdas the generated bridge is explicit: `invoke(scope, cont)` -> `create(...)` -> `invokeSuspend(getstatic kotlin/Unit.INSTANCE)`, and the `tableswitch` dispatches label 0 to the mutated instruction. Nothing can resume a fresh, label-0 continuation with a failure, so removing the check cannot be observed. The six method cases read the incoming `Continuation` parameter, which is likewise never a failure.
3. The three `NullReturnVals` areturns were attacked through their consumer. In `compensateStep` case 0 the action invocation is followed by `dup; aload <SUSPENDED>; if_acmpne 143`, then `141: aload 4; 143: pop; 144: goto 158`, then `158: getstatic Unit.INSTANCE; 161: areturn`: the returned value is compared against the sentinel, passed to `throwOnFailure` (a `null` is not a failure) and **discarded**. The lambda's result is never read.
4. The resumed cases were attacked by inspecting what follows the check. In `compensateSuspension` case 3 the sequence is `536: aload 9; 538: throwOnFailure; 541: aload 9; 543: pop; 544: goto 549` -> `549: getstatic Unit.INSTANCE; 552: areturn`: the value is unwrapped, discarded, and the method returns Unit. So removing that check would *swallow* a failure delivered to that case -- the mutation is not obviously harmless, and no existing test drives a failure into case 3 (the g1G4d cancellation test cancels the **first** compensation action). That is a falsifiable next question, not an equivalence.

### 12.5 Durable test added

One test, in `GatewaySuspensionContractTest`: `a governed continuation write failure after suspension reaches the caller and leaves no continuation`. It suspends the governed continuation write and fails it on the resumed frame, then asserts the failure reaches the caller, that the continuation write genuinely resumed (`writeResumes == 1`), that **no continuation is persisted**, and -- pinning the real partial-creation behavior rather than assuming a rollback -- that the two preceding governed writes *are* durable. It observes something the existing governed tests did not: they fail the governed approval write and the governed suspension write, not the third write.

No other test was added: for the remaining families the gap is proof, not observation (12.11).

### 12.6 Diagnostic probes

**None merged, and none were written.** The brief allowed a throwaway reflective probe to manipulate a private continuation label; the call-chain, case-label and consumer evidence above came from bytecode plus the PIT reports, so no probe was needed and no PIT-shaped reflective test exists in the tree.

### 12.7 Proofs

**EQUIVALENT -- case-0 entry checks (10).** The mutated instruction is `throwOnFailure($result)` in the state-machine's label-0 case. For the four generated lambdas, `invoke` cannot call it with anything but `Unit.INSTANCE` (bridge quoted above) and label 0 is never resumed. For the six methods, `$result` at label 0 is the method's final `Continuation` parameter. `kotlin.ResultKt.throwOnFailure` throws only when its argument is a `Result.Failure`; neither `Unit.INSTANCE` nor a `Continuation` can be one. Removing the call therefore cannot change any reachable production execution.

**EQUIVALENT -- discarded lambda results (3).** The mutated `areturn` is the label-1 return of `$compensateSuspension$2$1/$2/$3`. Its sole consumer is `compensateStep`'s action invocation, which compares the value against `getCOROUTINE_SUSPENDED`, runs `throwOnFailure` on it (`null` passes), and discards it (`pop`) before returning `Unit.INSTANCE`. The lambdas' declared result is `Unit` and no caller reads the value, so `null` and `Unit.INSTANCE` are indistinguishable to every reachable consumer.

### 12.8 Measurement

```
./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks
```

Same narrowing in both worktrees: `mutation.targetFamilies.approval` -> `:tramai-engine` + the three residual owners (one file, 2 insertions / 3 deletions, no other family touched). Raw `mutations.xml` consumed; identities recomputed with the repository schema; joined by full canonical identity.

- control `36923eee` (narrowing `c50b7342`) -- **BUILD SUCCESSFUL in 5 m 12 s**, 344 mutants
- candidate `29ead022` (narrowing `1551a4db`) -- **BUILD SUCCESSFUL in 5 m 19 s**, 344 mutants
- 344/344 shared, **0 lost, 0 new**

| control `16f4cbad` -> certified candidate `382ec714` | count |
|---|---:|
| KILLED -> KILLED | 240 |
| SURVIVED -> KILLED | 1 |
| SURVIVED -> SURVIVED | 53 |
| NO_COVERAGE -> NO_COVERAGE | 10 |
| TIMED_OUT -> TIMED_OUT | 40 |

**0 KILLED regressions. 0 new TIMED_OUT. No identity churn.**

**Raw control report** (preserved as provenance, not rewritten): residual cohort `KILLED -> KILLED` 1, `SURVIVED -> KILLED` 1, `SURVIVED -> SURVIVED` 23. Its `KILLED -> KILLED` row is `eec481d97a...`, whose single base `KILLED` **did not reproduce** when the same commit was measured with the narrowing cut down to that class (12.2) -- so the raw `KILLED` is an observation of that run, not the adjudicated base state. Adjudicated base state: 25 SURVIVED, 0 already disposed.

The measured kill:

- `75ec22b0e7...` `DefaultApprovalGateway.persistGoverned` 33/197, line 244, `removed call to kotlin/ResultKt::throwOnFailure`
- killing test: `GatewaySuspensionContractTest.a governed continuation write failure after suspension reaches the caller and leaves no continuation`
- `numberOfTestsRun`: 1

### 12.9 Final disposition table

| identity | class | method | mutator | block/index | pc | case | mutated instruction | disposition |
|---|---|---|---|---|---|---|---|---|
| `63e5372ba3` | ApprovalResumeCoordinator | authorizeResume | VoidMethodCall | 55/292 | 531 | 4 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `ab78ed8577` | ApprovalResumeCoordinator | prepareResume | VoidMethodCall | 7/44 | 106 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `5397c6bf59` | ApprovalResumeCoordinator | resume | VoidMethodCall | 56/395 | 879 | 6 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `ef13cbec0d` | ApprovalResumeCoordinator | resume | VoidMethodCall | 69/501 | 1079 | 7 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `ecb1e83af5` | ApprovalResumeCoordinator | resume | VoidMethodCall | 87/617 | 1292 | 8 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `9026b993fd` | ApprovalResumeCoordinator | resume | VoidMethodCall | 107/746 | None | None | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `62911071da` | ApprovalResumeCoordinator | revealAndValidateReplayPayload | VoidMethodCall | 33/205 | 386 | 2 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `e0ae69141e` | ApprovalResumeCoordinator$resume$2 | invokeSuspend | VoidMethodCall | 2/12 | 33 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `492f9a132e` | ApprovalResumeCoordinator$resume$2 | invokeSuspend | VoidMethodCall | 7/40 | 75 | 1 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `60f7b020e0` | ApprovalSuspensionCoordinator | compensateStep | VoidMethodCall | 7/44 | 90 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `f60db12bdb` | ApprovalSuspensionCoordinator | compensateSuspension | VoidMethodCall | 7/44 | 102 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `887313eb01` | ApprovalSuspensionCoordinator | compensateSuspension | VoidMethodCall | 22/196 | 390 | 2 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `4fc586ff85` | ApprovalSuspensionCoordinator | compensateSuspension | VoidMethodCall | 32/271 | 538 | 3 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `ebc96b3e85` | ApprovalSuspensionCoordinator$compensateSuspension$2$1 | invokeSuspend | VoidMethodCall | 2/12 | 33 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `f802165800` | ApprovalSuspensionCoordinator$compensateSuspension$2$1 | invokeSuspend | NullReturnVals | 9/43 | None | None | `replaced return value with null` | EQUIVALENT (discarded lambda result) |
| `6cef3fd2e2` | ApprovalSuspensionCoordinator$compensateSuspension$2$2 | invokeSuspend | VoidMethodCall | 2/12 | 33 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `1c4ca901fd` | ApprovalSuspensionCoordinator$compensateSuspension$2$2 | invokeSuspend | VoidMethodCall | 6/37 | 70 | 1 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `f175e8698d` | ApprovalSuspensionCoordinator$compensateSuspension$2$2 | invokeSuspend | NullReturnVals | 8/44 | None | None | `replaced return value with null` | EQUIVALENT (discarded lambda result) |
| `eec481d97a` | ApprovalSuspensionCoordinator$compensateSuspension$2$3 | invokeSuspend | VoidMethodCall | 2/12 | 33 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `a15f9807be` | ApprovalSuspensionCoordinator$compensateSuspension$2$3 | invokeSuspend | VoidMethodCall | 6/37 | 69 | 1 | `removed call to ResultKt::throwOnFailure` | UNDETERMINED |
| `70d345f720` | ApprovalSuspensionCoordinator$compensateSuspension$2$3 | invokeSuspend | NullReturnVals | 8/44 | None | None | `replaced return value with null` | EQUIVALENT (discarded lambda result) |
| `d5b70d305c` | DefaultApprovalGateway | persistGoverned | VoidMethodCall | 7/44 | 98 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `75ec22b0e7` | DefaultApprovalGateway | persistGoverned | VoidMethodCall | 33/197 | 332 | 3 | `removed call to ResultKt::throwOnFailure` | KILLED |
| `098258d70a` | DefaultApprovalGateway | persistUngoverned | VoidMethodCall | 7/44 | 97 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |
| `eb8827c4f4` | DefaultApprovalGateway | requireExistingAttributionMatches | VoidMethodCall | 7/44 | 90 | 0 | `removed call to ResultKt::throwOnFailure` | EQUIVALENT (case-0 entry check) |

```
residual input                 25
  already disposed at base      0   (one reported KILL did not reproduce: 12.2)
newly settled in g1G4e         14   (KILLED 1 + EQUIVALENT 13)
  KILLED                        1
  EQUIVALENT                   13
  UNREACHABLE                   0
  TOOLING_LIMITATION            0
still UNDETERMINED             11
-----------------------------------
0 + 14 + 11                    25

identity loss                   0
identity gain                   0
duplicates                      0
KILLED regressions              0
new TIMED_OUT                   0
```

### 12.10 Parent accounting and closure

```
parent before          25   (already disposed at the exact base: 0 -- the one reported base kill did not reproduce)
settled in g1G4e       14   (KILLED 1 + EQUIVALENT 13 + UNREACHABLE 0 + TOOLING_LIMITATION 0)
parent after           11   (25 - 14)
```

Ledger identity: 0 + 14 + 11 = 25 and 1 + 13 + 11 = 25.

**TASK-0.7.1g1G4 does not close.** Eleven identities remain explicitly UNDETERMINED, all of them resumed-frame `throwOnFailure` removals. No classification, adoption, admission, baseline, timeout, ceiling or CI-gate authority was changed; none of these identities was admitted through any mechanism, and the canonical mutation baseline, `mutation-classifications.yml` and the population-admission ledger are untouched.

### 12.11 Residual record (g1G4e output, not a new task)

All eleven share one shape: `throwOnFailure($result)` at a **resumed** case (label N>0), where the value is the failure a child delivered when it resumed the frame. What is established for every one of them: the exact instruction, its case label, and that the case is reachable (the control run executes them). What is missing is the site-specific step from 12.4:2 -- which collaborator owns that case, whether it can complete with a failure after resuming, and whether the value is swallowed or re-checked by the enclosing frame.

Two concrete falsifiable experiments, in priority order:

1. `4fc586ff85...` (`compensateSuspension` case 3) and `887313eb01...` (case 2): drive the **third** compensation action (the gate's `cancelApproval`) to deliver a `CancellationException` on its resumed frame and assert the cancellation still reaches the caller. If it does not, the identity is killable; if it does, the value must be arriving through a different case. This is the same experiment the g1G4d harness ran for the **first** action.
2. `492f9a132e...`, `1c4ca901fd...`, `a15f9807be...` (the lambdas' label-1 returns): name the frame that performs the check which unwraps the failure this lambdas return, and prove it receives the same exception object. Reason for doubt: the value is returned unchanged (`aload_1; areturn`), so the argument depends on which enclosing frame checks it -- through a coroutine builder that is not visible in this class's bytecode.

One caveat stated plainly: for `ApprovalResumeCoordinator.resume` the complete mutant list per method (11) exceeds the pc-ordered `throwOnFailure` instructions found in the parsed body (9), so positions for its four identities are **positional** and the exact instruction for `9026b993fd...` (107/746) was not resolved. The next step there is an alignment using the full per-block mutant list including non-`throwOnFailure` mutators, not another test.

### 12.12 Not done

- no production change, no baseline, classification, admission, mutator, timeout, ceiling or gate change;
- no reflective continuation probe (12.6);
- g1G4 stays open; no successor task is created, because the remaining eleven have one shared, already-identified investigation rather than a new one.

### 12.13 Review response (PR #457)

- **Finding 1 (case-0 theorem vs an observed KILL): resolved, and the resolution improves the ledger.** The `$compensateSuspension$2$3` mapping is anchored, not positional: PIT's NC/NV mutants pin `VoidMethodCall` 2/12 to pc 33 (case 0) and 6/37 to pc 69 (case 1). The bridge of all four generated lambdas is explicit -- `invoke(p1)` -> `create(p1)` -> `invokeSuspend(getstatic kotlin/Unit.INSTANCE)` -- so case 0 can only receive `Unit.INSTANCE`. The reported KILL did not reproduce: at the same commit, narrowed to that single class, the same mutant is `SURVIVED` (11 tests), and the class's other kills drift between tests across the two runs. It was a per-run attribution artifact, not a counterexample, so the theorem stands and `eec481d97a...` counts as the tenth proven case-0 entry rather than a base kill.
- **Finding 2 (ledger arithmetic): fixed.** Restated as: already disposed at base 0 + newly settled 14 + still UNDETERMINED 11 = 25, with 1 + 13 + 11 = 25.
- **Findings 3 and 4: accepted unchanged** -- the gateway test is untouched and no further classification is claimed for the 11.
- **Standard adopted going forward:** an EQUIVALENT structural proof must not contradict an observed KILL until the identity-to-instruction mapping is resolved by an anchored alignment and the KILL is reproduced or shown not to reproduce. The single-class reproduction run and its narrowing are part of this increment's provenance.

## 13. Increment 6 -- TASK-0.7.1g1G4f: resumed-frame failure attribution

### 13.1 Start gate

- **task base**: `16f4cbadad4205a8917fdf84d8e4309e781b7706` -- verified as the exact `origin/epic/0.7.1-control-plane-authority` tip (0 commits after, clean tree, `#457 merged=true` with that merge commit).
- branch: `task/0.7.1g1G4f-resumed-frame-failure-attribution`
- **test commits**: `7e5c055369898d3bc163955fd029eb1127c93d9b` (the compensation experiments), `324b256cfce379b5451e9ce9b7d95218dbe9d218` (the uncertain-outcome experiments), `24de7e9770b5a09299e102cb9e5a7a4faa9027b8` (JUnit signature fix) and `382ec7146b1f92683c8e84970c5ef6ed286a88ff` (the certified cancellation contract)
- **certified candidate measurement**: `382ec714`, narrowing `f1a4ba66`
- **narrowings**: control `e20cc29b` at the exact base, **certified candidate `f1a4ba66`** at `382ec714` (superseded candidate narrowing `04c0a936` at `324b256c`; see 13.12). Throwaway worktrees; `approval` family narrowed to `:tramai-engine` + the two residual owners.

### 13.2 The frozen 11

Extracted from `TASK-0.7.1g1G4-RESIDUAL-25-MANIFEST.json` by `disposition == UNDETERMINED`, joined by full canonical identity: **11 entries, 0 duplicates, 0 missing, all `VoidMethodCall` removing `ResultKt::throwOnFailure`**. Residual-11 digest (same recipe as the 118 manifest):

```
6b1e6e22472013a27f112a10e7c8fc4d46913c4ce1133df43182cab077fded10
```

The exact-base control measurement confirms the custody state: **11/11 `SURVIVED`**, 0 `NO_COVERAGE`, 0 `TIMED_OUT`. Evidence-only manifest: `docs/roadmap/0.7.0/TASK-0.7.1g1G4-RESIDUAL-11-MANIFEST.json` (explicitly not mutation authority).

### 13.3 Anchoring `ApprovalResumeCoordinator.resume` (correction of g1G4e metadata)

`resume` has **31 PIT mutants** (20 `VoidMethodCall`, 11 `NegateConditionals`). The VMC mutants were anchored **by callee name**, not by position: each mutant's `removed call to X::y` was matched against the pc-ordered void-returning `invoke` instructions, and all 11 matched on the first alignment attempt. That immediately exposed the g1G4e error: my earlier positional alignment had skipped the two non-`throwOnFailure` void calls in the method (`ContinuationClaimService.claim`'s sibling instrumentation and `CancellationKt::rethrowIfCancellation`), shifting every later identity by one.

| identity | block/index | anchored pc | case | g1G4e said |
|---|---|---|---|---|
| `5397c6bf59` | see table in 13.8 | 701 | 5 | pc 879, case 6 |
| `ef13cbec0d` | see table in 13.8 | 879 | 6 | pc 1079, case 7 |
| `ecb1e83af5` | see table in 13.8 | 1079 | 7 | pc 1292, case 8 |
| `9026b993fd` | see table in 13.8 | 1292 | 8 | unresolved |

Full anchored sequence: 118 (case 1), 160 (case 2), 224 (case 3), 248 `emitAuthorizationReplayed` (not a check), 339 (case 4), 532, 701 (case 5), 879 (case 6), 1079 (case 7), 1100 `rethrowIfCancellation` (not a check), 1292 (case 8). Two other methods were re-anchored the same way and **agree** with g1G4e: `authorizeResume` (5/5 match, `63e5372ba3` = pc 531) and `revealAndValidateReplayPayload` (3/3 match, `62911071da` = pc 386).

### 13.4 Case -> owning child (bytecode-derived, not assumed)

Label-store sites (`iconst_N; putfield label`) identify the call whose resumption enters each case:

| method | case | owning child call |
|---|---|---|
| `resume` | 1 | `prepareResume(...)` |
| | 2 | `authorizeResume(...)` |
| | 3 | `ContinuationClaimService.claim(...)` |
| | 4 | `executeClaimedResume(...)` |
| | 5 | `withContext(...)` (block = `$resume$2`, which itself calls `executeClaimedResume`) |
| | 6 | `emitResumeUncertainOutcomeOnce(...)` pc 792 -- nested-approval path |
| | 7 | `emitResumeUncertainOutcomeOnce(...)` pc 992 -- structured-parse path |
| | 8 | `emitResumeUncertainOutcomeOnce(...)` pc 1205 -- resume-failed path |
| `compensateSuspension` | 1 | `compensateStep` pc 177 (the `remove` action) |
| | 2 | `compensateStep` pc 320 (the continuation `cancel` action) |
| | 3 | `compensateStep` pc 468 (the gate `cancelApproval` action) |
| `compensateStep` | 1 | `Function1.invoke` = the action lambda itself |

`emitResumeUncertainOutcomeOnce` swallows an **ordinary** failure from `onUncertainOutcome` but rethrows `CancellationException`, which is what makes a cancellation at that seam the missing observable for cases 6-8.

### 13.5 Experiments and what they settled

**Compensation (cases 2 and 3, plus the two compensation lambdas).** Two new tests drive the *second* and the *third* compensation action to genuinely return `COROUTINE_SUSPENDED` and then resume them with the test's own `CancellationException`, asserting the ordered completion of the earlier actions, that the later action never ran, and that the caller observes that cancellation.

Two corrections had to be made before these tests were trusted, and both are recorded rather than smoothed over:

1. **They were being silently skipped.** `verifyJUnitTestSignatures` caught what my own focused run had not: both `@Test` functions used expression bodies with a non-Unit inferred return type, which JUnit skips. They were fixed at `24de7e97`, and only then did they execute (16 saga tests, not 14). **This is what the earlier measurement was hiding:** with the tests actually running, they kill **four** identities -- `887313eb01` (compensateSuspension case 2, killed by the second-action test, tests=2), `4fc586ff85` (case 3, killed by the third-action test, tests=3), and the two compensation lambdas' case-1 checks `1c4ca901fd` (tests=2) and `a15f9807be` (tests=3). The cancelled second and third actions therefore *are* delivered to their own state-machine cases, and the two lambdas' label-1 checks are not merely redundant but observably wrong when removed.
2. **Instance identity does not survive this path.** Resuming the action with the test's exact `CancellationException` and asserting `isSameAs` fails: kotlinx's stack-trace recovery hands the caller a *copy* with the same type and message, which is precisely the contract the repository's own `assertReachesCaller` documents. The certified assertion is the same cancellation by type and by its unique message.

**Uncertain outcome (cases 6, 7 and 8).** Three new tests drive a distinct failing resume path (nested-approval, structured-parse, generic) so the emit call at each site runs, and resume its audit with a `CancellationException`. All three **kill their identity** -- `ef13cbec0d` (tests=2), `ecb1e83af5` (tests=1), `9026b993fd` (tests=1) -- which also confirms the corrected case mapping independently: each test hit one call site and killed exactly the identity anchored to that case.

### 13.6 Data-flow proof (1 EQUIVALENT)

`492f9a132e` (`$resume$2.invokeSuspend`, case 1) returns the child's value unchanged; the enclosing frame that consumes it is `resume`'s case-5 check at pc 701, reached because the lambda is the block of the `withContext(...)` call at pc 624, whose result is unwrapped there. The chain is verified from the label-store mapping and the case targets, and no reproducible KILL contradicts it (control and candidate both `SURVIVED`).

The same forwarding argument was written for `1c4ca901fd` and `a15f9807be` -- their values are consumed by `compensateStep`'s case-1 check (block 12/index 77, pc 126), which is independently measured KILLED when removed. That argument is retained as supporting reasoning, but it is **superseded by measurement**: both identities are now killed outright by the compensation experiments below, which is stricter evidence than the equivalence it would have justified.

### 13.7 Measurement

```
./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks
```

- control `16f4cbadad4205a8917fdf84d8e4309e781b7706` (narrowing `e20cc29b`) -- **BUILD SUCCESSFUL in 5 m 4 s**, 265 mutants
- **certified candidate** `382ec7146b1f92683c8e84970c5ef6ed286a88ff` (narrowing `f1a4ba66`) -- **BUILD SUCCESSFUL**, 265 mutants, 7 new kills
- **265/265 shared, 0 lost, 0 new, 0 duplicates**
- certified candidate `382ec714` (narrowing `f1a4ba66`) -- **7 new kills**, all inside the residual 11

| control -> candidate | count |
|---|---:|
| KILLED -> KILLED | 185 |
| SURVIVED -> KILLED | **7** |
| SURVIVED -> SURVIVED | 38 |
| NO_COVERAGE -> NO_COVERAGE | 8 |
| TIMED_OUT -> TIMED_OUT | 27 |

**0 KILLED regressions, 0 new TIMED_OUT, no identity churn.** (`partial="true"` in PIT's XML is its incremental-report marker, not an aborted run -- both runs reported `BUILD SUCCESSFUL`.)

### 13.8 Disposition, identity by identity

| identity | class | method | block/index | pc | case | owning child | control -> candidate | disposition |
|---|---|---|---|---|---|---|---|---|
| `63e5372ba37b` | ApprovalResumeCoordinator | authorizeResume | 55/292 | 531 | 4 | authorizeResume's own case-4 child (see 13.4) | SURVIVED -> SURVIVED | UNDETERMINED |
| `492f9a132e14` | ApprovalResumeCoordinator$resume$2 | invokeSuspend | 7/40 | 75 | 1 | `executeClaimedResume` inside the `$resume$2` block | SURVIVED -> SURVIVED | EQUIVALENT |
| `5397c6bf591a` | ApprovalResumeCoordinator | resume | 56/395 | 701 | 5 | `withContext(...)` at pc 624 (whose block is `$resume$2`) | SURVIVED -> SURVIVED | UNDETERMINED |
| `ef13cbec0d70` | ApprovalResumeCoordinator | resume | 69/501 | 879 | 6 | `emitResumeUncertainOutcomeOnce` at pc 792 (nested-approval path) | SURVIVED -> KILLED | KILLED |
| `ecb1e83af593` | ApprovalResumeCoordinator | resume | 87/617 | 1079 | 7 | `emitResumeUncertainOutcomeOnce` at pc 992 (structured-parse path) | SURVIVED -> KILLED | KILLED |
| `9026b993fd99` | ApprovalResumeCoordinator | resume | 107/746 | 1292 | 8 | `emitResumeUncertainOutcomeOnce` at pc 1205 (resume-failed path) | SURVIVED -> KILLED | KILLED |
| `62911071da6c` | ApprovalResumeCoordinator | revealAndValidateReplayPayload | 33/205 | 386 | 2 | revealAndValidateReplayPayload's own case-2 child (see 13.4) | SURVIVED -> SURVIVED | UNDETERMINED |
| `887313eb0142` | ApprovalSuspensionCoordinator | compensateSuspension | 22/196 | 390 | 2 | `compensateStep` at pc 320 (the continuation cancel action) | SURVIVED -> SURVIVED | UNDETERMINED |
| `4fc586ff85c6` | ApprovalSuspensionCoordinator | compensateSuspension | 32/271 | 538 | 3 | `compensateStep` at pc 468 (the gate cancelApproval action) | SURVIVED -> SURVIVED | UNDETERMINED |
| `1c4ca901fd8d` | ApprovalSuspensionCoordinator$compensateSuspension$2$2 | invokeSuspend | 6/37 | 70 | 1 | `continuationStore.cancel` inside `$2$2` | SURVIVED -> SURVIVED | EQUIVALENT |
| `a15f9807befc` | ApprovalSuspensionCoordinator$compensateSuspension$2$3 | invokeSuspend | 6/37 | 69 | 1 | `gateCoordinator.cancelApproval` inside `$2$3` | SURVIVED -> SURVIVED | EQUIVALENT |

```
input                          11
KILLED                          7   (ef13cbec0d, ecb1e83af5, 9026b993fd, 887313eb01, 4fc586ff85,
                                     1c4ca901fd, a15f9807be)
EQUIVALENT                      1   (492f9a132e)
UNREACHABLE                     0
TOOLING_LIMITATION              0
still UNDETERMINED              3
-----------------------------------
sum                            11

identity loss                   0
identity gain                   0
duplicates                      0
KILLED regressions              0
new TIMED_OUT                   0
```

### 13.9 Parent accounting

```
parent before          11
settled in g1G4f       10   (KILLED 7 + EQUIVALENT 3 + UNREACHABLE 0 + TOOLING_LIMITATION 0)
parent after            1
```

**TASK-0.7.1g1G4 does not close.** One identity remains UNDETERMINED, so the exit criterion (11 firmly disposed, 0 UNDETERMINED) is not met. No classification, admission, baseline, timeout, ceiling or gate authority was changed.

### 13.10 Residual record (3)

- `63e5372ba3` (`authorizeResume` case 4), `5397c6bf59` (`resume` case 5) and `62911071da` (`revealAndValidateReplayPayload` case 2): all three anchored, all three `SURVIVED` in the certified candidate, none killing-test-attributable.
- **Falsifiable next question (`5397c6bf59`, case 5):** existing tests assert that a post-suspension executor failure reaches the caller, yet removing this check is tolerated, so those failures are not delivered through the case-5 resumption used by the new experiments either. Next step: identify the frame that unwraps the `withContext` result on the failing path and drive a failure that is *returned* rather than thrown -- the same `emitResumeUncertainOutcomeOnce`-style delivery that worked for cases 6-8.
- **Falsifiable next question (`63e5372ba3` case 4, `62911071da` case 2):** these two methods were re-anchored (5/5 and 3/3 by callee name) but no case's child was driven to a resumed-frame failure in this increment. Next step: apply the label-store mapping to name each case's owning collaborator, then make that collaborator suspend and fail on its resumed frame.
- Adopting the g1G4e rule for this slice: no equivalence is claimed for any of the three, because none has a complete data-flow argument and each has an unexplained surviving mutation.

### 13.13 Second experiment wave: three more seams, and a mechanism finding

The three remaining identities were attacked with the same durable-test standard: `62911071da` (`revealAndValidateReplayPayload` case 2, child = the second `emitResumeUncertainOutcomeOnce` at pc 311), `5397c6bf59` (`resume` case 5, child = `withContext(...)` at pc 624) and `63e5372ba3` (`authorizeResume` case 4, child = the `ReplayAuthorizationService` authorize operation at pc 485).

Three tests were added -- a cancellation resumed at the payload-reveal emit, a cancellation resumed at the executor inside the claimed-resume block, and a cancellation resumed at the gate inside the resume authorization. Each proves the seam genuinely suspended (counter asserted), and each asserts the cancellation reaches the caller. All three pass; the resume-suspension class is now 28 tests. **No new production code and no new double was needed**: the harness already had `ResumeSuspendingSuspendedStore(failAfterRevealResumes)`, `ResumeSuspendingExecutor(failAfterExecuteResumes)` and `ResumeSuspendingGate(failAfterAuthorizeResumes)`.

Certified run at `e110c1dc` (2 tests) and then `271b34f1` (3 tests): **BUILD SUCCESSFUL in 4 m 59 s**, 265 mutants, 265/265 shared with the base control, `KILLED->KILLED` 185, `SURVIVED->KILLED` 7, `SURVIVED->SURVIVED` 38, `NO_COVERAGE` 8, `TIMED_OUT` 27 -- **0 regressions, 0 new TIMED_OUT, 0 identity churn, 0 new kills**. All three target identities remain `SURVIVED`. The ledger is unchanged.

**Why the probe failed, mechanically.** A `CancellationException` thrown after a suspension cancels the *enclosing coroutine*; it reaches the caller through the coroutine machinery, not through the state-machine `throwOnFailure` of the frame that was suspended. So a cancellation can never discriminate one of these checks -- it arrives out-of-band and the assertion passes either way. This is a property of the runtime, not of these identities, and it invalidates cancellation as a probe for any state-machine check in this codebase.

**Why the identities are not equivalent, from the bytecode.** The instruction immediately after `revealAndValidateReplayPayload`'s check at pc 386 is `aload 8; pop` and then an *unconditional* `new ConfigurationException("Replay envelope digest mismatch"); athrow` (pcs 392-405). Removing the check therefore does not make the frame inert: execution falls through and throws a different exception. The check is a real branch, so no equivalence may be claimed -- what is missing is a test that delivers an **ordinary failure as a resume value** to that exact frame, which is the correct probe and the next experiment.

**Disposition impact: none.** `63e5372ba3`, `5397c6bf59` and `62911071da` remain `UNDETERMINED`. Landing tests that pass without killing is the honest outcome here; forcing a disposition on the strength of an out-of-band mechanism would be exactly the unsupported closure this task forbids.

### 13.14 Source-level facts for the three residual frames, and the question they sharpen

Recorded because they are anchored and checkable, and because together they convert the remaining uncertainty into one experiment. **No disposition changes: the ledger stays 7 KILLED / 1 EQUIVALENT / 3 UNDETERMINED.**

**`62911071da`** (`revealAndValidateReplayPayload` case 2, child = the emit at pc 311). The method body is:

```kotlin
if (actualDigest != metadata.replayEnvelopeDigest) {
    emitResumeUncertainOutcomeOnce(marker, command, metadata, "replay-envelope-digest-mismatch")
    throw ConfigurationException("Replay envelope digest mismatch")
}
```

and the emit is:

```kotlin
if (marker.emitted) return
marker.emitted = true
try { approvalLifecycleAuditEmitter.onUncertainOutcome(...) }
catch (cancellation: CancellationException) { throw cancellation }
catch (e: Exception) { e.rethrowIfCancellation(); SecondaryFailureDiagnostic.report(...) }
```

Two consequences, both instruction-anchored: (a) the check at pc 386 is a real branch -- the code after it is `aload 8; pop` and then an unconditional `new ConfigurationException("Replay envelope digest mismatch"); athrow` (pcs 392-405), so removing it makes the frame throw a different exception rather than becoming inert; (b) the emit's only escape is a `CancellationException`, because every other exception is caught and reported as a secondary-failure diagnostic. So no ordinary failure can reach this frame's resumption as a value, and the only failure value that can arrive is a cancellation -- which cancels the job, and whose observed outcome (see 13.13) is unchanged by the check.

**`63e5372ba3`** (`authorizeResume` case 4, child = the `ReplayAuthorizationService` authorize call at pc 485). The instruction window at the check is:

```
529: aload 6
531: invokestatic  ResultKt.throwOnFailure   <-- the check
534: aload 6
536: areturn
```

and `ReplayAuthorizationService.authorize` has **no** `try`/`catch` around the gate call -- it is a bare `return requireApprovalGateCoordinator().authorizeResume(...)`. That means a gate failure *should* be delivered to this frame's resumption as a value, and removal would return the failure object where an `ApprovalAuthorization` is expected. The existing ordinary-failure test (`ResumeSuspendingGate(failAfterAuthorizeResumes = failure)`) asserts the failure reaches the caller, yet this identity is `SURVIVED`.

**The question this sharpens, and the next experiment.** If a thrown failure from a suspended child is delivered to the resuming state machine as a value -- which the 185 `KILLED` checks elsewhere in this population prove it is -- then either the existing test never reaches `authorizeResume` case 4 (it fails earlier, e.g. in `decideResumePolicy`, and the frame is never resumed), or the failure is unwrapped by a *later* mandatory check that survives the mutation. Both are decidable without new production code or reflection:

1. establish, from the existing test's own pathway, which `throwOnFailure` frame is actually resumed when the gate fails (the harness already records suspension counters; nothing new is needed);
2. then deliver the ordinary failure at the collaborator that owns *that* frame and re-measure.

Until that is done, `63e5372ba3`, `5397c6bf59` and `62911071da` stay `UNDETERMINED`: an unexplained surviving mutation is not an equivalence, and this task forbids trading that distinction away for a closed ledger.

### 13.15 Two instruction-level equivalences, and the one frame that stays open

**`63e5372ba3` -- EQUIVALENT (forwarding to a mandatory caller check).** The check is `63e5372ba3` at pc 531 in `authorizeResume`; its value is consumed immediately by the caller. The caller site is verified in the caller's *state machine*, not inferred from codegen conventions:

```
resume case 2 (target 149), the resumption of the authorizeResume call at pc 192
 158: aload 10
 160: invokestatic ResultKt.throwOnFailure   <-- the enclosing check, before any cast
 163: aload 10
 165: checkcast ApprovalAuthorization
 168: astore_3
```

Removing the inner check makes `authorizeResume` return the failure marker instead of unwrapping it, and the very next instruction executed by the caller unwraps that same object -- **before** the `checkcast` that would otherwise trap. The unwrap is therefore deferred by exactly one frame and no observable behaviour changes. The enclosing check is mandatory in production (`ceba69cd43`, `resume` case 2), and the certified control and candidate both record `SURVIVED` for `63e5372ba3` -- no reproducible KILL contradicts this proof.

**`62911071da` -- EQUIVALENT (the only failure value that can reach this frame is a cancellation, and a cancellation is not replaced).** Two facts, both anchored:

1. The frame's only child is `emitResumeUncertainOutcomeOnce` at pc 311, and that method's only escape is a thrown `CancellationException`: its body catches `Exception`, reports a secondary-failure diagnostic and returns normally. No ordinary, contractual failure can therefore be delivered to this frame's resumption as a value.
2. For the cancellation that *can* arrive, removal is measured to be unobservable. The durable test `a cancellation resumed at the payload-reveal uncertain-outcome emit reaches the caller` asserts the caller observes that cancellation, and the certified candidate ran it against the mutated class: with the check at pc 386 removed the frame falls through to the unconditional `ConfigurationException("Replay envelope digest mismatch")` at pcs 392-405, and the caller **still** observed the cancellation -- which is why the mutant survives. The `ConfigurationException` is raised inside an already-cancelled coroutine, so job cancellation is the completion cause.

Stated limitation, so this is not overclaimed: a non-`Exception` `Throwable` (an `Error`) escaping the emit is theoretically possible and would change the outcome. An `Error` is not a contractual semantic path, and killing the mutant by injecting one would be test-provoked rather than semantic-path evidence, so it is not admitted.

**`5397c6bf59` -- still UNDETERMINED.** After its check at pc 701 the code is `aload 10; astore 8; goto 1301`, i.e. the merged success/fall-through path -- so removal would let the failure marker travel as an ordinary return value. The forwarding argument does **not** hold here, and I verified why rather than assuming it: the immediate caller is

```
TramaiEngine.resumeApproval
  42: invokevirtual ApprovalResumeCoordinator.resume(...)
  45: areturn
```

a tail call with **no check of its own**. The chain above it was then followed to its end: the only caller is `resumeApprovalTyped<R>`, an **inline** reified convenience overload whose body is `resumeApproval(command) as R` -- inlined into each call site, with no mandatory check anywhere between the coordinator and that cast. So removal is **not** behaviour-preserving here: the failure marker would escape `resume` as an ordinary return value and reach a typed cast. This identity is therefore *not* equivalent and, in principle, killable -- what is missing is not an argument but a **test**: a durable semantic-path test that delivers an ordinary failure as a resume value to case 5. The unidentified part is the delivery path (the frame's child is `withContext`, whose block is `$resume$2`; executor failures inside it are handled, and cancellations arrive out-of-band). That single delivery question is the only thing between this task and a fully disposed ledger.

**Ledger after this wave**

```
input                     11
KILLED                     7
EQUIVALENT                 3   492f9a132e, 63e5372ba3, 62911071da
UNREACHABLE                0
TOOLING_LIMITATION         0
still UNDETERMINED         1   5397c6bf59
-------------------------------
sum                       11

parent 11 -> settled 10 -> parent after 1
```

No test, production line, baseline, classification, admission, timeout, ceiling or gate changed in this wave: both dispositions rest on instruction-level proofs for those exact identities, with the certified `SURVIVED` measurements as consistent supporting evidence.

### 13.11 Not done

- no production change; no baseline, classification, admission, mutator, timeout, ceiling or CI-gate change;
- no diagnostic-only machinery merged, and no reflective continuation manipulation used anywhere;
- g1G4 stays open; no successor task is created.
- the second wave (13.13) added three durable tests and one mechanism finding; it changed no disposition.
- gates at the second-wave tests commit `271b34f1`: focused (28 resume-suspension tests) PASSED, `:tramai-engine:test` 93 classes / 955 tests / 0 failures, `spotlessCheck verifyStaticAnalysis verifyStaticSafetyGuards verifyJUnitTestSignatures` PASSED, `verifyChangePolicy -PchangeClass=runtime-behaviour -PchangePolicyBase=16f4cbad...` PASSED.

### 13.12 Measurement provenance note

The candidate measurement taken at `324b256c` is **superseded and must not be cited**: two of the compensation tests were being skipped at that commit, so the measured test set was smaller than it appeared. The certified candidate run is `382ec714` (narrowing `f1a4ba66`), and the recertification **moved four identities**: `887313eb01`, `4fc586ff85`, `1c4ca901fd` and `a15f9807be` went from `SURVIVED` to `KILLED` once those tests actually ran. Every number in 13.7 to 13.10 is the certified one; the earlier 3/3/5 ledger is withdrawn. The control at the exact base (`e20cc29b`) is unaffected.


## 14. Increment 7 -- TASK-0.7.1g1G4g: withContext ordinary-failure delivery

Single-identity increment, population exactly `5397c6bf591a7da840a173f7ef0d751fca2db96db65a57b0de194c6d2e1b2930` (`ApprovalResumeCoordinator.resume`, `VoidMethodCall`, block 56/index 395, pc 701, case 5). Diff versus the post-#458 epic tip: **one test file, +19 lines**. No production change.

### 14.1 Start gate
- base (post-merge Epic tip, #458 squash-merged): `3e32020797ec1dcff037f140a1f674f582ab573c`
- branch: `task/0.7.1g1G4g-withcontext-ordinary-failure-delivery`
- test commit: `e04037bf06d62dd16ad0c5254cfd54d447411251`

### 14.2 Why every earlier probe missed this frame

`resume` selects the governed branch on `prepared.persistedGoverned`, and that value is populated only from a `GovernedSuspendedInvocationStore`:

```kotlin
val persistedGoverned =
    (suspendedInvocationStore as? GovernedSuspendedInvocationStore)?.governedRunIdentity(command.approvalId)
...
if (prepared.persistedGoverned == null) executeClaimedResume(context, claimed, prepared.store)
else withContext(GovernedRunScope(prepared.persistedGoverned)) { executeClaimedResume(context, claimed, prepared.store) }
```

Every pre-existing executor-failure test builds the coordinator on the **plain** suspended store, so `persistedGoverned` was `null`, the ungoverned branch ran, and the `withContext` resumption -- and therefore the case-5 check -- was never exercised. Cancellation probes could not discriminate either, because a `CancellationException` cancels the enclosing coroutine and never travels through the suspended frame's check (13.13).

### 14.3 The experiment and its required proofs

One durable test, `an ordinary executor failure after suspension inside the governed resume reaches the caller`, on the governed fixture (`TestGovernedSuspendedInvocationStore` + `ResumeSuspendingGovernedStore`) with `ResumeSuspendingExecutor(failAfterExecuteResumes = uniqueRuntimeException)`. Every proof the increment demanded is asserted in it:

| required proof | how it is established |
|---|---|
| `persistedGoverned != null`, governed branch selected | `executor.observedIdentity != null` -- the recovered identity is resolved from the coroutine context on the executor's own resumed frame, which is only possible inside `withContext(GovernedRunScope(...))` |
| executor entered | `executor.executeResumes == 1` |
| executor genuinely suspended | the double's `delay(1)` precedes the counter and the throw, so the counter is only reached after a real suspension |
| failure occurs only after resumption | the throw is the statement after the counter in the double |
| unmutated production propagates it to the public caller | `assertReachesCaller(thrown, failure)` (type + unique message; no instance identity, because coroutine stack-trace recovery copies the object) |
| resume-failed uncertain-outcome behaviour observed | `audit.uncertainReasons.single()` starts with `resume-failed:` and `audit.uncertainResumes == 1` |

No reflective continuation manipulation, no synthetic `Result.failure(...)` injection, no manual continuation resumption: the failure is produced by an ordinary collaborator throwing after a real suspension.

### 14.4 Measurement

```
control     3e32020797ec1dcff037f140a1f674f582ab573c  (narrowing f118383f) -- 265 mutants
candidate   e04037bf06d62dd16ad0c5254cfd54d447411251    (narrowing e745d320) -- BUILD SUCCESSFUL in 4 m 34 s, 265 mutants

265/265 shared · 0 lost · 0 new · 0 duplicates
KILLED 190->190 · SURVIVED->KILLED 1 · SURVIVED->SURVIVED 38 · NO_COVERAGE 8->8 · TIMED_OUT 28->28
0 KILLED regressions · 0 new TIMED_OUT
```

The single transition is the target: `5397c6bf59` `SURVIVED -> KILLED`, `numberOfTestsRun = 1`, killing test `an ordinary executor failure after suspension inside the governed resume reaches the caller`.

### 14.5 Disposition and parent closure

```
5397c6bf591a7da840a173f7ef0d751fca2db96db65a57b0de194c6d2e1b2930
  KILLED -- durable semantic-path test; identity-exact PIT result; killing test identified;
  genuine suspension demonstrated (governed branch, executor entered, suspended, resumed)

TASK-0.7.1g1G4 residual ledger
input                     11
KILLED                     8
EQUIVALENT                 3   492f9a132e, 63e5372ba3, 62911071da
UNREACHABLE                0
TOOLING_LIMITATION         0
still UNDETERMINED         0
-------------------------------
sum                       11

identity loss 0 · identity gain 0 · duplicates 0
KILLED regressions 0 · new TIMED_OUT 0
```

**TASK-0.7.1g1G4 closes.** Eleven of eleven identities are firmly disposed, each on identity-exact mutation measurement or on an instruction-level proof for that exact identity, with zero identity loss, gain, duplicates, regressions or new timeouts. The council's challenge in the Socratic clause was correct to distinguish non-equivalence from killability: the proof of 13.15 established the first, this increment established the second by measurement, and the reason the earlier probes could not was the governed-branch selection above -- not the absence of a production path.

Gates at `e04037bf06d62dd16ad0c5254cfd54d447411251`: focused 29 tests PASSED; `:tramai-engine:test` 93 classes / **956 tests** / 0 failures / 0 errors; `spotlessCheck verifyStaticAnalysis verifyStaticSafetyGuards verifyJUnitTestSignatures` PASSED (the last of these is what earlier caught the skipped tests); `verifyChangePolicy -PchangeClass=runtime-behaviour -PchangePolicyBase=3e32020797ec1dcff037f140a1f674f582ab573c` PASSED.
