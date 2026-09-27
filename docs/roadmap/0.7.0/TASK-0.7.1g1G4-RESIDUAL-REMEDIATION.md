# TASK-0.7.1g1G4 — Residual Mutation Remediation and Final Adjudication

**Status:** in progress — Phases A–C landed; Phase D measured and recorded in §5
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

## 5.1 What the remaining 116 need (grouped by production scenario, not by identity)

| Remaining | Class | Methods (identities) | Required harness |
|---|---|---|---|
| 12 `NullReturnVals`, 15 `VoidMethodCall` | `DefaultApprovalGateway` | `requestApproval` (5+5), `persistUngoverned` (3+4), `persistGoverned` (3+4), `requireExistingAttributionMatches` (1+2) | the pattern of §3.2 applied to the remaining seams: suspending `ApprovalGatewayRequestFactory.createRequest`, `SuspendedInvocationStore.create`, `ApprovalContinuationStore.create`, and the governed stores (`createGovernedApproval`, `attributionOf`, `createGoverned`) |
| 22 `NullReturnVals`, 26 `VoidMethodCall`, 2 `NegateConditionals` | `ApprovalResumeCoordinator` | `resume` (8+9), `prepareResume` (5+6), `authorizeResume` (4+5 +2 NC), `executeClaimedResume` (3+3), `revealAndValidateReplayPayload` (1+1), `$resume$2.invokeSuspend` (1+2) | drive `resume()` with genuinely suspending/failing `ApprovalContinuationStore`, `SuspendedInvocationStore`, `ReplayAuthorizationService`, `ContinuationClaimService` and `ClaimedResumeExecutor` |
| 18 `NullReturnVals`, 19 `VoidMethodCall`, 2 `NegateConditionals` | `ApprovalSuspensionCoordinator` | `suspendToolExecution` (6+6), `compensateSuspension` (3+4 + 3×2 lambda pairs), `persistSuspendedInvocation` (2+2 +2 NC), `compensateStep` (1+3) | tool-suspension and compensation pipelines with a suspending collaborator; the `compensateSuspension$2$*` lambdas need the compensation path itself to suspend |
| 4 `NegateConditionals` | both coordinators | `authorizeResume` ×2, `persistSuspendedInvocation` ×2 | per-site analysis only; the two `Nothing`-callee sentinel cases (`:151`, `:155`) may be structurally unreachable rather than uncovered — do not fold into either coroutine harness |


## 6. What this slice does not settle

- 116 of the 118 identities have no durable test in this commit (see §5.1 for the per-scenario grouping). Each remaining site needs the harness that
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
