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

Measured test commit `4d772e81`. The shipped head differs from it only by test-harness formatting (line wrapping,
shortened double names, two fixture knobs moved to fields) with the same 22 tests and the same assertions, and a nested
measurement at the shipped head failed to compile before that was corrected. **Certified at the shipped head.** The measurement was re-run at the shipped head (`36c50804`, narrowing
`bfd061af`, 5 m 10 s): the identity set is identical (161/161), the cohort reproduces exactly - 39
`NO_COVERAGE -> KILLED`, 1 `SURVIVED -> KILLED`, 7 `NO_COVERAGE -> SURVIVED`, 3 `SURVIVED -> SURVIVED` - and there is
**zero status drift** against the earlier measured head, 0 regressions and 0 new `TIMED_OUT`. An intermediate head
(`c606501c`) did not compile, which the nested measurement caught before it was certified. Throwaway worktree at
that commit with one committed narrowing (measurement commit `f01d296e`,
not part of this branch); command `./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks`;
**4 m 50 s**, 161 mutants, **identity set identical to the control (161/161 shared, 0 lost, 0 new)**. Control: the same
narrowing at base `6d798f80`, 5 m 25 s, 782 tests.

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
