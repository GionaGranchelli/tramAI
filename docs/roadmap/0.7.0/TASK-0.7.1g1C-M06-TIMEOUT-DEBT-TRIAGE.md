# TASK-0.7.1g1C — M06 TIMED_OUT Debt Triage

Diagnostic slice for the first tranche of M06 debt: the 43 TIMED_OUT identities that remain after
the g1B preflight. No production code, test, mutation authority, verifier, threshold or
classification was modified. This document is the only artifact.

## 1. Base and scope

| Item | Value |
|---|---|
| Task branch | `task/0.7.1g1c-m06-timeout-triage` |
| Exact base | `5c5de97e0c32d14a70b95dec5e9435266d1f57bb` (Epic tip; 40 characters, verified equal to `origin/epic/0.7.1-control-plane-authority`) |
| Working tree at start | clean (`git status --porcelain` empty) |
| Bounded question | Of the 43 unresolved TIMED_OUT M06 identities, which are genuine mutation/test failures and which are artifacts of PIT timeout or harness behaviour? |

### 1.1 Custody of the measurement evidence

The population used here was measured at `0f36ab8744c14ebbdb3b10964311d0416e012ab2` (campaigns A and B,
g1A). The g1B preflight proved that chain for `e38ce9f9` (§3.1 of the g1B document); this slice extends
it to its own base, and states the result for the whole range:

```
$ git diff --name-only e38ce9f9dbb3519947ffe3ea692932c934eb3f17 5c5de97e0c32d14a70b95dec5e9435266d1f57bb
docs/roadmap/0.7.0/TASK-0.7.1g1B-M06-EVOLUTION-PREFLIGHT.md

$ git diff --exit-code 0f36ab8744c14ebbdb3b10964311d0416e012ab2 5c5de97e0c32d14a70b95dec5e9435266d1f57bb \
    -- config/quality/test-quality.yml config/quality/mutation-baseline.json \
       config/quality/mutation-classifications.yml config/quality/mutation-evolution.yml \
       build-logic/src/main/kotlin/dev/tramai/build/quality
(no output, exit 0)

$ git diff --name-only 0f36ab87 5c5de97e | wc -l
9
$ git diff --name-only 0f36ab87 5c5de97e | grep -cE '^tramai-(engine|security|sovereign|core|structured)/'
0
```

The nine files, in full:

```
build-logic/src/main/kotlin/dev/tramai/build/docs/DocsContractVerifierTask.kt
build-logic/src/main/kotlin/dev/tramai/build/docs/PromotedReleaseVerifier.kt
build-logic/src/main/kotlin/dev/tramai/build/docs/VersionAlignmentVerifier.kt
build-logic/src/test/kotlin/dev/tramai/build/docs/TramaiDocsGuardsPluginTest.kt
build-logic/src/test/kotlin/dev/tramai/build/quality/CanonicalProbeFunctionalTest.kt
build-logic/src/test/kotlin/dev/tramai/build/quality/ResidualQualityVerifierTasksTest.kt
docs/roadmap/0.7.0/TASK-0.7.1g1A-MUTATION-POPULATION-DRIFT-AUDIT.md
docs/roadmap/0.7.0/TASK-0.7.1g1B-M06-EVOLUTION-PREFLIGHT.md
tramai-orchestration/src/test/kotlin/dev/tramai/orchestration/WorkflowMcpStepTest.kt
```

Across the whole chain from the measurement SHA to this base, **nine** files changed and **none of them is a
mutation-target input**: the analyzer, the mutation configuration, the authority and the classifications
are byte-identical, and no production or test source under a configured mutation target module changed.
The A/B populations are therefore valid evidence at `5c5de97e`, and no fresh 50-minute campaign was run
for this slice — a third campaign would repeat a measurement already demonstrated deterministic (A and B
are byte-identical, `sha256 06549e8a21e199b3f4c94fed359a3ae783e7796a29b27d6189c0dd261bc8a457`).

## 2. Phase A — the 43, reproduced exactly

### 2.1 The raw PIT reports, joined to canonical identities

The g1A campaign's raw PIT reports (`/tmp/tramai-071g1a-pit-A`, 14 `mutations.xml` files, one per
family/module) were re-read and every `<mutation>` element was recomputed into a canonical identity
(`SHA-256` over module, class, method, methodDescription, mutator, description, block, index — the schema
of `MutationIdentity.kt`) and matched against the fresh population:

| Check | Result |
|---|---|
| PIT mutation records parsed | 2544 |
| Fresh population rows | 2544 |
| Duplicate identities in the raw reports | 0 |
| Records whose recomputed identity did not match the population | 0 |
| Raw status disagreements between campaign A and campaign B | 0 |

The raw reports and the canonical population agree exactly, identity for identity, with no duplicates
and no status disagreement — including for every TIMED_OUT mutation.

Raw PIT status totals in campaign A:

| raw status | count |
|---|---|
| KILLED | 1667 |
| NO_COVERAGE | 330 |
| SURVIVED | 463 |
| TIMED_OUT | 84 |

### 2.2 Where the 84 timeouts sit

The campaign contains **84** TIMED_OUT mutations. The M06 set (candidate-only NON_KILLED) holds 46 of
them; three of those are category A, which leaves the **43** in scope for this slice. The rest are
already-authoritative timeouts that the base already carries:

| bucket | TIMED_OUT |
|---|---|
| candidate-only/A | 3 |
| candidate-only/B | 21 |
| candidate-only/C | 22 |
| shared | 38 |

Of the 84, 80 are `NegateConditionalsMutator`, 3 are `VoidMethodCallMutator` and 1 is
`ConditionalsBoundaryMutator`. Family concentration: `approval` 76 (65 on `:tramai-engine`, 11 on
`:tramai-security`), `retry` 5, `policy` 2, `routing` 1.

### 2.3 The exact 43

Exact identity set, sorted: sha256 `8fd6d1ef269497f40ac7195dc61537100892f3a63cf168efd3ceec330778a64a`.

By class#method, with raw PIT status (all TIMED_OUT) and the count:

| class#method | count |
|---|---|
| `ApprovalResumeCoordinator#authorizeResume` | 2 |
| `ApprovalResumeCoordinator#prepareResume` | 5 |
| `ApprovalResumeCoordinator#resume` | 8 |
| `ApprovalResumeCoordinator#revealAndValidateReplayPayload` | 1 |
| `ApprovalResumeCoordinator$resume$2#invokeSuspend` | 1 |
| `ApprovalSuspensionCoordinator#compensateStep` | 1 |
| `ApprovalSuspensionCoordinator#compensateSuspension` | 3 |
| `ApprovalSuspensionCoordinator#suspendToolExecution` | 6 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend` | 1 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend` | 1 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend` | 1 |
| `DefaultApprovalGateway#persistGoverned` | 3 |
| `DefaultApprovalGateway#persistUngoverned` | 3 |
| `DefaultApprovalGateway#requestApproval-Atj0Sqo` | 6 |
| `DefaultApprovalGateway#requireExistingAttributionMatches` | 1 |

Every one of the 43 is a `NegateConditionalsMutator` mutation and every one reports
`numberOfTestsRun=0`: no test in the unit completed
before PIT's minion was killed. That is the signature of a hang, not of a slow run.

## 3. Phase B — why they time out

### 3.1 The mutation points are Kotlin coroutine suspension checks

Every non-terminating mutant in scope sits on a source line that is a **suspend call site**. Kotlin
compiles each such call site as:

```
  137: invokespecial  prepareResume:(...)        // the suspend call at source line 65
  140: dup
  141: aload         12                          // the local holding COROUTINE_SUSPENDED
  143: if_acmpne     165                         // continue only if the call really returned
```

`NegateConditionalsMutator` negates exactly that `if_acmpne`. The state machine then continues when the
call actually suspended (and returns the sentinel when it did not), so the continuation is never resumed
and the awaiting test never completes. Non-termination is not a side effect here — it is the mutation's
behaviour.

Attribution was done against the compiled bytecode of this base (`javap -p -c -l`), by tracking which
local receives `IntrinsicsKt.getCOROUTINE_SUSPENDED` and locating the comparison that reads it, then
mapping the comparison offset through the `LineNumberTable`:

| Result | Count |
|---|---|
| in-scope mutation points directly attributed to a `getCOROUTINE_SUSPENDED` comparison | 39 |
| in-scope points in synthetic suspend-lambda classes, each containing exactly one sentinel reference (`$resume$2`, `$compensateSuspension$2$1/2/3`) | 4 |
| **total** | **43** |

The comparison that implements the check is `if_acmpne` in all 54 attributed sites across these classes.

### 3.2 It is not a timeout-budget problem

PIT's per-unit budget is `timeoutConst + timeoutFactor × baseline unit time`. Both values are set in the
single authority for the canonical init script, `MutationProbeInitScript.kt:32-33`
(`TIMEOUT_CONST_MILLIS = 4_000L`, `TIMEOUT_FACTOR = 1.25`) and applied at `:112-113`; `test-quality.yml`
carries no timeout keys, and this slice touched neither. Measured against that:

| Measurement | Value |
|---|---|
| covering test classes for the timed-out classes | 11 |
| their total test time, freshly measured (`./gradlew :tramai-engine:test --tests "dev.tramai.engine.approval.*" --rerun-tasks`) | 0.7 s total; slowest class 0.20 s |
| PIT's own baseline diagnostic on the approval unit runs | `0 tests took longer than 2000 ms` |
| derived per-unit timeout for those units | ≈ 4.0 s + 1.25 × (well under 1 s) ≈ **4.3 s** |

A mutant killed at ~4.3 s against a sub-second baseline is not running slowly; it is not terminating. The
budget is generous by roughly an order of magnitude, so `HARNESS_BUDGET` is not a valid explanation for
this cluster, and no timeout setting needs loosening.

### 3.3 It is deterministic, not scheduling

Two independent campaigns produced byte-identical populations, and the 43 identities have identical raw
statuses in both (`A_vs_B_status_mismatches = 0`). The timeout follows the mutation identity, not the
machine's mood. `NONDETERMINISTIC_EXECUTION` is not a valid explanation here.

### 3.4 Negating a sentinel check usually hangs; NegateConditionals elsewhere usually does not

Restricting the population to the sites attributed above sharpens the picture. On the **54 attributed
sentinel sites**, the `NegateConditionals` mutants distribute as:

| status | count |
|---|---|
| TIMED_OUT | 51 (every one with `numberOfTestsRun=0`) |
| KILLED | 3 |
| NO_COVERAGE | 3 |
| **total** | **57** |

All 54 sites carry at least one such mutant. The rest of the population behaves the opposite way: the
**1107** `NegateConditionals` mutants that do **not** sit on an attributed sentinel site are 925 KILLED,
103 SURVIVED, 29 TIMED_OUT and 50 NO_COVERAGE.

An earlier version of this document said "most negations of a suspension check are killed" on the strength
of the population-wide 1164 figure. That claim did not follow from that denominator and is **wrong**: 51 of
57 (89%) of the sentinel-site negations hang. The three KILLED sentinel-site mutants do matter, though —
detection is not impossible in principle, which is why §5 keeps "bound the test" alive as a hypothesis to
be measured rather than assuming the opposite.

The off-site bucket means **not on one of the 54 sentinel sites attributed in this slice** — it is not a
claim that those mutants are proven not to be sentinel sites. The attribution pass covered only the
inspected classes, so the 1107 residual mutants may include further sentinel-check sites in classes this
slice never opened.

41 of the 43 in-scope lines carry the timeout as the *only* mutation on that line; the remaining 2 lines
also carry one killed sibling negation, which shows those lines' ordinary conditionals are detectable.

The 43 in-scope identities themselves distribute 39 on the 54 attributed sites and 4 on the synthetic
suspend-lambda sites, so the attribution covers the entire scope.

## 4. Phase C — cause classification (investigation result, not authority)

These buckets are diagnostic labels for this report. They are **not** mutation classifications, they do
not enter `mutation-classifications.yml`, and they grant no exemption from M06.

| bucket | count | evidence |
|---|---|---|
| `PATHOLOGICAL_MUTANT` | **43 / 43** | the negated conditional is the coroutine suspension sentinel check; negation makes resumption impossible, and `numberOfTestsRun=0` shows the unit never completed a single test |
| `HARNESS_BUDGET` | 0 | covering tests run in 0.7 s against a ~4.3 s budget |
| `TEST_HANG_OR_DEADLOCK` | 0 | no test-side hang was observed on its own; the hang follows the mutant, and disappears with it |
| `NONDETERMINISTIC_EXECUTION` | 0 | A and B agree identity-for-identity |
| `REAL_MISSING_PROTECTION` | 0 identified | no ordinary assertion gap was found: the mutant is not "uncovered", it is undetectable *by completion* — the code under test never returns, so no assertion of any strength can run. Whether bounded **liveness** protection is materially missing is a different question, and §4.1 does not settle it. |

The honest summary: **this is one mechanical cause, not 43 defects** — a property of the intersection of
Kotlin coroutines, PIT's `NegateConditionals` mutator and the JVM. The 43 in scope share this
coroutine-sentinel failure mode. Whether equivalent sentinel negations elsewhere also time out depends on
their covering tests and is **not** established by this slice.

### 4.1 What this classification does not establish

`REAL_MISSING_PROTECTION = 0` means **no ordinary assertion gap was identified**. It is not a semantic
zero and it should not be frozen as one. The mutants are pathological at the compiler-generated suspension
check — that much is proven — but if a bounded test can detect the resulting non-termination, then that
test also establishes a legitimate liveness property of the approval workflow, and the *absence* of such a
test is a real gap of a different kind. Stating that as a missing protection would be premature; stating it
as zero would be worse. It is measured in the next slice (g1D), not here.

## 5. Phase D — how the authority should represent this (decision, not implementation)

The scope of this slice was triage. No fix is applied, deliberately, and the reason is not caution: the
triage result changes *what the fix would be*, and three of the four available levers are forbidden or
weakening.

| Option | Assessment |
|---|---|
| Kill them with stronger tests | The only non-weakening lever. A test that is bounded in wall-clock time fails when the mutant stops terminating, and a failing test is a **kill** in PIT's eyes — the identity leaves M06 honestly and the suite gains a real safety property. Not implemented here: the covering tests currently have no wall-clock bound, and whether a bound converts this specific hang into a kill (rather than a leaked thread that still outlives the minion) is a *measurement*, not a deduction. It needs a demonstration before it is written into tests. |
| Classify them as approved survivors | Requires 43 authority-side approval records adjudicated on master. It would also misdescribe the observation: these mutants are not "accepted risk", they are undetectable-by-completion. Expensive and semantically wrong. |
| Change the canonical mapping (e.g. treat TIMED_OUT as KILLED) | **Weakening, and out of scope.** A timeout is not evidence of detection: a hung or overloaded test would also produce it. This slice did not touch it and recommends against it. |
| Remove the mutation points from the target set | **Narrowing the measurement, and forbidden** by the task's own constraints (`add/remove mutation target families`, target-class narrowing is an M14/M15 failure). |

### 5.1 What the next slice must demonstrate before any test change

The family-scoped machinery already exists and should be reused rather than duplicated:
`MutationProbeInitScript` renders the canonical PIT init script and selects exactly one family through
`gradle.startParameter.projectProperties['tramaiMutationFamily']` (`:67`), refusing an unknown or missing
family (`:68-70`), applying that family's `targetClasses`/`targetTests` (`:105-106`) and registering
`canonicalMutationProbe` over its `pitest` tasks (`:121`). That is how the canonical campaign was rendered
family-by-family, and one family's run is minutes, not fifty.

So the gap is narrower than "there is no scoped capability":

1. **Add a per-class selection path through the existing renderer** — a way to isolate e.g.
   `ApprovalResumeCoordinator` without running the broad canonical ceremony, without touching analyzer
   semantics (target classes, mutators, `timeoutConst`, `timeoutFactor` all stay as they are). This is
   `build-logic` class and must not ride a documentation or debt PR.
2. **Start from the three KILLED sentinel-site mutants, not from a timeout.** They are the control group:
   §3.4 established that 3 of 57 mutants on attributed sentinel sites are already killed while 51 time out.
   Why do those three die — do their covering tests impose a deadline, use a different scheduler, exercise
   a non-suspending path, or fail on another observable effect? Deriving that difference is what can produce
   the right test shape, instead of introducing a timeout blind and discovering afterwards what it hides.
3. **Reproduce one known TIMED_OUT identity unchanged** under the per-class path, then **add the bounded
   liveness test** the control group implies and observe whether PIT records KILLED — or TIMED_OUT with a
   leaked thread, in which case the bound hides the hang and must be rejected.
4. **Then repeat for determinism**, and only then generalise to the other 42.
5. **Then re-measure the canonical population** to quantify the actual debt reduction.

I am not recommending an accelerated version of this, and if the bounded test still yields TIMED_OUT the
experiment stops there rather than mass-editing tests. The user-facing risk of guessing wrong is a test
suite that appears to detect non-termination while actually leaking threads into the next test.

## 6. Remaining M06 debt, stated exactly

| Component | Count | Status after this slice |
|---|---|---|
| TIMED_OUT (categories B+C) | 43 | investigated and explained in this document; **still NON_KILLED, still M06 debt** |
| SURVIVED | 36 | untouched; needs stronger tests |
| NO_COVERAGE | 79 | untouched; needs reachability adjudication |
| **total** | **158** | unchanged |

The M06 population is unchanged by this slice: 195 candidate-only NON_KILLED identities, of which 158
remain under the most permissive reading of category A. Explaining a debt is not paying it, and this
document claims no reduction.

## 7. Explicit non-claims

- No production code, test, build-logic, workflow, threshold, family, baseline, evolution record or
  classification was modified. The only artifact is this document.
- No fresh mutation campaign was run. The A/B populations are reused under the custody proof in §1.1.
- No mutant was killed, classified, exempted or removed. The 43 remain M06 failures.
- The `PATHOLOGICAL_MUTANT` label is a diagnostic bucket in this report, not a mutation classification,
  and it must not be lifted into `mutation-classifications.yml`.
- The claim "negating the suspension sentinel check causes non-termination" is supported by direct
  bytecode attribution (43/43) plus PIT's own observation, not by a controlled single-mutant experiment.
- The derived ~4.3 s PIT budget is **derived** from the committed `TIMEOUT_CONST_MILLIS` /
  `TIMEOUT_FACTOR` (`MutationProbeInitScript.kt:32-33`) and the measured baseline, not read from PIT's
  verbose log (verbose logging is disabled in campaign runs).
- I did not verify that a bounded test converts any of these timeouts into a kill; §5.1 says so and
  proposes the measurement rather than assuming its outcome.
- The other 41 timeouts in the campaign (38 shared, and the 3 category-A ones) were not investigated and
  are not characterised by this document.

## 8. Commands run

```
git fetch origin && git rev-parse origin/epic/0.7.1-control-plane-authority   # == 5c5de97e...
git status --porcelain                                                     # empty
git checkout -B task/0.7.1g1c-m06-timeout-triage 5c5de97e0c32d14a70b95dec5e9435266d1f57bb
git diff --exit-code 0f36ab87 5c5de97e -- \
    config/quality/test-quality.yml config/quality/mutation-baseline.json \
    config/quality/mutation-classifications.yml config/quality/mutation-evolution.yml \
    build-logic/src/main/kotlin/dev/tramai/build/quality                     # exit 0, no output
python3 /tmp/tramai-071g1c-phaseab.py     # raw PIT XML inventory + PIT timing diagnostics
python3 /tmp/tramai-071g1c-joina.py       # identity join: raw reports <-> canonical population
./gradlew :tramai-engine:compileKotlin    # for bytecode inspection
CP=tramai-engine/build/classes/kotlin/main
for c in ApprovalResumeCoordinator ApprovalSuspensionCoordinator DefaultApprovalGateway; do
    javap -p -c -l -classpath $CP dev.tramai.engine.approval.$c
done
for c in 'ApprovalResumeCoordinator$resume$2' 'ApprovalSuspensionCoordinator$compensateSuspension$2$1' \
         'ApprovalSuspensionCoordinator$compensateSuspension$2$2' 'ApprovalSuspensionCoordinator$compensateSuspension$2$3'; do
    javap -p -c -classpath $CP "dev.tramai.engine.approval.$c"
done
python3 /tmp/tramai-071g1c-phaseb.py      # sentinel attribution via LineNumberTable
./gradlew :tramai-engine:test --tests "dev.tramai.engine.approval.*" --rerun-tasks   # 0.7s, 11 classes
```

Analysis scripts live in `/tmp` and are not committed; the JSON they produced is
`/tmp/tramai-071g1c-{phaseA,phaseB,phase-ab}.json`.

## 9. Working-tree proof

### 9.1 Before the task commit

```
$ git rev-parse HEAD
5c5de97e0c32d14a70b95dec5e9435266d1f57bb          # the base, unmoved
$ git status --porcelain                           # empty at task start
$ git status --porcelain                           # after writing this document, before committing:
?? docs/roadmap/0.7.0/TASK-0.7.1g1C-M06-TIMEOUT-DEBT-TRIAGE.md
```

### 9.2 After the task commit

Commands, and the values that hold for this document's own commit:

```
$ git rev-parse HEAD^
5c5de97e0c32d14a70b95dec5e9435266d1f57bb          # the task commit's parent is the base
$ git diff --name-only HEAD^ HEAD
docs/roadmap/0.7.0/TASK-0.7.1g1C-M06-TIMEOUT-DEBT-TRIAGE.md
$ git diff --name-only 5c5de97e HEAD | wc -l
1                                                  # exactly one file changed relative to base
$ git status --porcelain                           # empty
```

The literal head SHA is deliberately **not** hardcoded here: it is the SHA of the commit that contains
this paragraph, so any literal value would be falsified by the act of committing it — the same
temporal-impossibility trap that an earlier revision of this section fell into by showing `HEAD == base`
and "the task head commit" in one transcript. The invariant that matters is checkable regardless:
`HEAD^ == 5c5de97e`, one changed file against base, clean status.

## 10. Outcome

```
TRIAGED — 43 / 43 TIMED_OUT IDENTITIES EXPLAINED, NONE KILLED, NO CODE CHANGED
```

The 43 are one mechanical cause: negation of Kotlin's coroutine suspension sentinel check, deterministic,
with a timeout budget an order of magnitude larger than the covering tests need. None of them is an
ordinary missing-assertion problem, so none can be closed by writing more assertions of the usual kind;
and none of them justifies loosening a timeout or changing M06. The cheapest honest route to closing them
is a bounded covering test, which must be demonstrated on one cluster before it is generalised — a
separate slice (g1D), reusing the existing family-scoped renderer with a per-class selection path, not
part of this document.

M06 is untouched. Authority evolution remains blocked until this debt is genuinely resolved.
