# TASK-0.7.1g1G2 — Residual Mutation Taxonomy and Adjudication

**Status:** Recorded adjudication for the residual candidate-only mutation population at the post-0.7.1g1G1 frozen head
**Frozen baseline:** Epic `epic/0.7.1-control-plane-authority` at `c568b9c7` (post-#447)
**Predecessor:** [TASK-0.7.1g1G1](TASK-0.7.1g1G1-MUTATION-DEBT-TAXONOMY.md) — the cohort and its buckets
**Does not authorize:** enrollment, classification writes, gate changes, or opportunistic kills. See §6.

This slice is **measurement and adjudication only**. No production, test, PIT-policy, classification, baseline or authority change was made; no kill was attempted. Its purpose is to give every residual identity exactly one disposition before the g1G0 admission ceremony, without improving the mutation score opportunistically.

## 1. Method

1. **Recover the cohort identity-exactly.** The g1G0 partition buckets (`no_coverage` 109, `survived_with_tests` 40, `timed_out` 46) are joined to the campaign-A population on the canonical mutation identity prefix: **195 / 195 identities recovered**, none lost, none duplicated.
2. **Apply the measured closures.** An identity counts as closed only if a *newer measurement* reports it `KILLED` — not because a PR claims it: the `#442` scoped re-measurement (evidence family), the `#444` re-measurement (approval + evidence at `eb691e19`), and the `#446` re-measurement (approval at `3fda224e`). Result: **18 closed**, **177 residual**.
3. **Re-census at the frozen head** (approval family only, full family configuration otherwise unchanged; 918 mutants, 18m10s, throwaway worktree `a47a759f`). Approval is a complete scope here, because **all 177 residual identities are in the approval family**.
4. **Group by mechanism, not by appearance.** Grouping uses the mutation operator, the site's compiled instructions (`javap`, line-attributed), and the source line's shape. Line-level attribution is imperfect where PIT's line tables shift an instruction to a neighbouring line; where that happened the group is named for what is *measurable* (see G3) rather than for what was guessed.

## 2. Accounting

```
cohort (candidate-only NON_KILLED at campaign A)   195
  closed by measured slices                         18   (9 #442 + 8 #444 + 1 #446)
  residual                                         177
      NO_COVERAGE                                  101
      SURVIVED                                      30
      TIMED_OUT                                     46
```

Dispositions — every residual identity appears exactly once:

```
TOTAL_RESIDUAL = 177
  KILLABLE              0
  EQUIVALENT           12
  UNREACHABLE           0
  TOOLING_LIMITATION   46
  DEFERRED_STRUCTURAL   1
  UNDETERMINED        118
  ---------------------------------
  sum                 177     accounted 177/177
```

Integrity: **lost identities 0 · duplicate identities 0 · unexplained movement 0.**

Measurement reality is unchanged by this record — the re-census is identical to the measurement that the closures were recorded against:

- census rows 918 / previous 918, identity sets identical (census-only 0, previous-only 0)
- **status movement between the two measurements: 0**
- family totals identical: `KILLED` 513, `NO_COVERAGE` 193, `SURVIVED` 136, `TIMED_OUT` 76
- of the 177 residual identities, **177 are still `NON_KILLED` in the census and 0 became `KILLED`**
- of the 18 closed, none reverted; 13 are outside the approval scope (they are evidence-family validator rows) and 5 are `KILLED` inside it

Adjudication therefore changes *classification*, not measurement.

## 3. Mechanism groups (numeric view)

| Group | Mechanism | Operator | Count | Statuses |
|---|---|---|---|---|
| G1 | `NULL_RETURN_OTHER` — return value replaced by null, site is not a suspension point | `NullReturnVals` | 49 | 38 `NO_COVERAGE`, 11 `SURVIVED` |
| G2 | `SUSPENSION_POINT_RETHROW` — removed `ResultKt::throwOnFailure` at a suspension point | `VoidMethodCall` | 43 | 38 `NO_COVERAGE`, 5 `SURVIVED` |
| G3 | negated conditional on a coroutine sentinel comparison (all `TIMED_OUT`) | `NegateConditionals` | 46 | 46 `TIMED_OUT` |
| G4 | `OTHER_VOID_CALL` — removed call that is not at a suspension point | `VoidMethodCall` | 20 | 11 `NO_COVERAGE`, 9 `SURVIVED` |
| G5 | `NULL_RETURN_AT_SUSPENSION_POINT` | `NullReturnVals` | 12 | 12 `NO_COVERAGE` |
| G6 | negated conditional on a source-level branch, not timing out | `NegateConditionals` | 5 | 2 `SURVIVED`, 2 `NO_COVERAGE`, 1 deferred identity |
| G7 | inlined standard-library `Iterable.filter` fast path | `NegateConditionals` | 2 | 2 `SURVIVED` |
| | | **total** | **177** | |

G3 is defined by the *measurable* fact that every `TIMED_OUT` row in the residual is a `NegateConditionals` row and no other row times out (46 = 17 sites whose line carries the sentinel compare + 29 attributed one line off). It is **not** an instruction-exact split: the sub-tag is line-attribution dependent, and the disposition below does not depend on it.

Locations: `ApprovalResumeCoordinator` 71, `ApprovalSuspensionCoordinator` 58, `DefaultApprovalGateway` 46, `ApprovalRunAttribution.kt:174` 2.

## 4. Dispositions with evidence

**G3 — 46 identities — `TOOLING_LIMITATION`.**
Evidence: every one is a `NegateConditionals` row at a suspend-call site and every one reports `TIMED_OUT`, i.e. no test completes (measured for the representative as `numberOfTestsRun="0"` with an empty `killingTest`, §4.1 of the predecessor record). The predecessor's bounded experiment — a genuinely suspending collaborator, a proven suspension, and a local bound below PIT's `timeoutConst` — left its representative in the same bucket.
Basis for generalising to the block: the operator, the site class and the status are identical across all 46, and PIT's behaviour here is a property of the operator applied to a compiler-generated sentinel comparison rather than of any particular test. This is a **structural generalisation with one representative demonstration**, not 46 independent demonstrations.
Further experimentation required: no for this disposition; yes only if a second representative is wanted. Failure modes excluded: the row is not merely uncovered and not merely weakly asserted — both were provided and neither moved it.

**G7 — 2 identities — `EQUIVALENT`.**
`ApprovalRunAttribution.kt:174` ×2 are negations inside the inlined `Iterable.filter` fast path (`is Collection && isEmpty()`) reached from `decodeApprovalAttribution:94`. The existing suite pins every input class (`ApprovalRunAttributionTest:30/53/62`: governed round-trip, legacy un-attributed, every partial reserved set is corruption), so any negation that changed behaviour for those inputs could not have survived those assertions.
Further experimentation required: no.

**Unit-return `areturn` subset — 8 identities — `EQUIVALENT`.**
`DefaultApprovalGateway:205`, `:219`, `:234`, `:265` and `ApprovalSuspensionCoordinator:284` (×2), `:297`, `:307`. The mutated instruction is the `areturn` of a `Unit`-returning suspend function's return or early return; the value is discarded by the caller, so replacing it with `null` is unobservable by construction.
Further experimentation required: no.

**Compiler guards — 2 identities — `EQUIVALENT`.**
`ApprovalSuspensionCoordinator:160` (`checkNotNull` on `expiresAt`) and `:176` (`checkNotNullExpressionValue` on `clock.instant()`). Both are compiler-inserted guards on Java-platform values; with the guard removed and a null flowing, the enclosing `checkNotNullParameter` still throws before any state is created, so original and mutant fail fast at the same point and differ only in a compiler-generated message. That message is not part of the contract, which is why no message-contract test exists or should be added.
Further experimentation required: no. Recorded nuance: this is equivalence *for contract-observable behaviour*, not message-level identity.

**G1 — 49 identities — `UNDETERMINED`.**
`NullReturnVals` at non-suspension sites. A replaced return value is observable exactly when the caller consumes it; that has not been established per site. The 8 identities above are the proven-unobservable subset inside this family; the remainder require per-site reading of the enclosing function's contract and its callers.
Required evidence: per-site result-usage analysis; one representative experiment would bound the family.

**G2 — 43 identities — `UNDETERMINED`.**
Removed `ResultKt::throwOnFailure` at suspension points. Removing an exception rethrow is observable when the suspend call can return a `Failure` and the caller observes the difference; whether that path is reachable for each site has not been established.
Required evidence: reachability of a failing suspend call per site, then the family experiment.

**G4 — 20 identities — `UNDETERMINED`, of which 9 are `KILLABLE` candidates.**
Removed calls that are not at suspension points are *source-level* calls, so the mutation removes real behaviour. The 9 `SURVIVED` rows in this group are the strongest `KILLABLE` candidates in the residual: they have covering tests that do not observe the missing call.
Required evidence: per-site reading of what the removed call guarantees; this is where a missing assertion would most plausibly be found, and finding one is **not** authorised by this record (§6).

**G5 — 12 identities — `UNDETERMINED`.** Same question as G1 at suspension points.

**G6 — 5 identities — 1 `DEFERRED_STRUCTURAL`, 4 `UNDETERMINED`.**
`ApprovalResumeCoordinator.kt:102` b76.i533 stays **`DEFERRED_STRUCTURAL`** exactly as the predecessor record left it: the structured-parse uncertain reason must name the failure type, and treating it requires the drifted-file formatter/baseline decision that was deliberately rejected for this tranche. It is not folded into this slice and its disposition is unchanged.
The other four are negated source-level conditionals that do not time out; they are `KILLABLE` candidates pending per-site analysis.

## 5. Why `KILLABLE` and `UNREACHABLE` are empty

Not because they cannot apply, but because nothing in hand establishes them:

- `KILLABLE` requires a *concrete* missing behavioural assertion. Group G4 has nine plausible candidates; naming one is an act of diagnosis that leads directly to a test, and this slice is explicitly not a remediation slice.
- `UNREACHABLE` requires an argument that production structure makes the mutated path unreachable under valid inputs. The residual's sites are reachable in principle; what is missing is observability evidence, which is a different claim.

Recording them as zero with the reason is stronger than assigning either to fill the cell.

## 6. What this record does not authorise

- **No enrollment.** Nothing here is written into `config/quality/mutation-classifications.yml`. Classification of candidate-only identities still requires the M22–M29 ceremony against a governing base, and the g1G0 wall is unchanged.
- **No kills.** No test was added and no survivor was fixed. If a later slice acts on a `KILLABLE` candidate it must preserve identity census, regression accounting, provenance, and a positive control.
- **No reclassification of the 118 `UNDETERMINED` rows by inference** from their neighbours, their family, or the one demonstrated `TOOLING_LIMITATION` representative.
- **No gate change** of any kind, and no deviation-ceiling change.
- **`ApprovalResumeCoordinator:102` stays deferred** until the taxonomy produces evidence specific to it.

## 7. Reproduction

1. Recover the cohort by joining `config/quality/mutation-baseline.json`-derived campaigns and the g1G0 partition buckets on the canonical identity (16-character prefix join; never join on `(file, line, block, index)` — that tuple collides across mutators).
2. Treat an identity as closed only if a newer measurement says `KILLED`.
3. Re-census in a throwaway worktree: narrow `mutation.targetFamilies` to the family owning the residual (all 177 are `approval`), commit the narrowing, run `generateCriticalMutationBaseline`, then confirm `measuredCommit` equals the probe commit and prove provenance with `git diff --name-only <frozen-head> <probe-commit>` — it must list only the throwaway config file.
4. Group with `javap -p -c -l` per site: a suspend call is an instruction whose descriptor ends `Continuation;)Ljava/lang/Object;`; a sentinel comparison is a line carrying `getCOROUTINE_SUSPENDED`, `if_acmpne` or `if_acmpeq`. Line attribution shifts instructions to neighbouring lines — verify a representative before trusting any per-line split.
5. Re-derive the accounting equation and assert `lost = 0`, `duplicate = 0`, `unexplained movement = 0` before any admission step.
