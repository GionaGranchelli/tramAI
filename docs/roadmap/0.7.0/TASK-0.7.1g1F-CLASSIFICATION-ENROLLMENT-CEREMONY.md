# TASK-0.7.1g1F — Base Classification Enrollment Ceremony

Implementation of the missing authority operation g1E identified: the mutation ratchet rejects
candidate-side classification additions (M08/M09), while its documented complement — new
classifications "adjudicated on master during an enrollment ceremony and becoming part of the base" —
had **no implementation at all**.

This slice builds that ceremony as an authority primitive. It enrolls nothing.

## 1. Scope and custody

| Item | Value |
|---|---|
| Task branch | `task/0.7.1g1f-classification-enrollment-ceremony` |
| Exact base | `8c30d7db6ebd4b3476115bdea9db1b9ecdbca662` (Epic tip after #436; 40 characters, verified equal to `origin/epic/0.7.1-control-plane-authority` before branching) |
| Working tree at start | clean (`git status --porcelain` empty) |
| Primary change class | `build-logic` (verifier/authority mechanism) |
| Verdict | ceremony implemented and proven; **no classification enrolled** |

## 2. The problem this closes

`MutationRatchetVerifier` states the policy in its header: a PR may only *remove* classifications, never
add or re-author one; new ones are adjudicated on master during an enrollment ceremony. g1E proved the
ceremony did not exist as tooling: no Gradle task, workflow or script implements it, `AUTHORITY_EXCLUDED_IDENTITIES`
bypasses only M01, mainline pushes are verified against `github.event.before` (so a direct authority
commit faces the same rules), and the six existing `tool-limitation` records were created in `d5b19e35`
(2026-09-03) two days *before* `MutationRatchetVerifier` appeared in `0c02ff64` (2026-09-05).

The ratchet was therefore correct and unusable: an adjudicated survivor could not be enrolled by any
mechanism, at any trust level.

## 3. The trust model: temporal and base-derived, never mode-derived

An enrollment is an **authorization that a later transition consumes**, in two phases:

```
PHASE 1 — authorize                          PHASE 2 — consume (a LATER transition)
  base:      no authorization                  base:      exact authorization X
  candidate: + authorization X                 candidate: + exact classification X
             (no classification)                          - authorization X (consumed)
  -> PASS                                      -> PASS (M22 enrollment, M26 consumption)
```

The security property is exactly this asymmetry:

```
candidate authorization X  +  candidate classification X   (base has neither)  -> FAIL   (M23)
base      authorization X  +  candidate classification X   (identical payload) -> PASS   (M22)
```

A candidate can never create the authority it uses, because the authorization that permits enrollment
must already exist in the **PR base**. No privileged mode, label, actor, branch name, commit token,
environment, manual workflow bypass or hard-coded identity is involved: the ceremony adds no branch to the
verifier that the ratchet would not already trust.

The authorization binds the **complete classification record** — `id`, `classification`, `reason`,
`issue`, `targetPhase` — not the identity. Data-class equality over the whole payload makes "exact
authorization" literal, so a later transition cannot substitute a different classification type or rewrite
the approved rationale (M24).

## 4. Design

| Artifact | Role |
|---|---|
| `config/quality/mutation-classification-enrollments.yml` | The authorization ledger (schemaVersion `1`, `enrollments:` list). **Not created by this slice** — an absent file means "no authorizations", the most restrictive state. |
| `MutationClassificationEnrollments.kt` | `MutationClassificationEnrollment(classification)` and the ledger type. The payload *is* a `MutationClassification`, so no second semantic type and no payload drift. |
| `MutationClassificationEnrollmentLoader.kt` | Ledger parser. Absent → `NONE` (fail-closed). Present but malformed → hard `GradleException`. Duplicate ids → hard failure. |
| `MutationClassificationLoader.parseClassificationEntry` | Per-entry parsing/validation **shared** with the classifications loader, so the allowed vocabulary and field rules have one authority (`missing-test`, `equivalent-mutant`, `low-risk-implementation-detail`, `tool-limitation`, `known-design-ambiguity` — generic, nothing timeout-specific). |
| `MutationRatchetAuthority.enrollments` / `MutationRatchetCandidate.enrollments` | Base-side and candidate-side ledgers. Defaults to `NONE`, so every pre-existing construction keeps its exact meaning. |
| `MutationEnrollmentCeremony.kt` | M22-M27 plus the unchanged M08/M09 backstop. Split out of the verifier because keeping every rule inline exceeded the maintainability function-count limit; the verifier now delegates exactly where it used to dispatch added classifications. |
| `MutationRatchetAuthorityLoader` | Loads the base ledger from the **base SHA** (same temp-tree mechanism as every other authority file); a base predating the ledger simply has none. |

## 5. Discriminator matrix

Each row is a test in `MutationRatchetEnrollmentCeremonyTest` (31 tests, all passing).

| # | Scenario | Expected | Rule |
|---|---|---|---|
| 1 | base authorization X → exact classification X | PASS | M22 |
| 2 | base authorization X → classification X, authorization removed | PASS | M22 + M26 |
| 3 | candidate adds authorization X **and** classification X (base has neither) | FAIL | M23 (+ unchanged M08/M09 backstop) |
| 4 | no base authorization → classification added | FAIL | M08/M09 **unchanged** |
| 5 | classification type differs from authorization | FAIL | M24 |
| 6 | reason differs | FAIL | M24 |
| 7 | issue differs (and removed) | FAIL | M24 |
| 8 | targetPhase differs (and removed) | FAIL | M24 |
| 9 | authorization targets an identity absent from the base population | FAIL | M25 |
| 10 | authorization targets an identity absent from the base population **and** the candidate introduces it | FAIL | M25 **and M06** |
| 11 | authorization targets a KILLED identity | FAIL | M25 |
| 12 | authorization targets an already-classified identity | FAIL | M25 |
| 13 | duplicate authorization ids (in-memory ledger) | FAIL | M25 |
| 14 | duplicate authorization ids (ledger file) | FAIL | loader |
| 15 | unknown classification name in the ledger | FAIL | loader (shared vocabulary) |
| 16 | authorization removed without enrollment | FAIL | M27 |
| 17 | authorization retained after its classification was enrolled | FAIL | M27 (single-use) |
| 18 | enrolled classification retained byte-identically | PASS | M03 |
| 19 | enrolled classification rewritten later | FAIL | M03 |
| 20 | after enrollment, an unrelated fresh candidate classification | FAIL | M08 |
| 21 | BASE0 → BASE1 → BASE2 → CANDIDATE3 (below) | see below | full ceremony |
| 22 | retained authorization rewritten while unconsumed: classification / reason / issue / targetPhase | FAIL | M28 |
| 23 | retained authorization carried byte-identically (still pending) | PASS | pending |
| 24 | retained authorization whose target became KILLED | FAIL | M27 stale |
| 25 | retained authorization whose target disappeared | FAIL | M27 stale |
| 26 | authorization removed once its target became KILLED | PASS | cleanup, M04 governs |
| 27 | authorization removed once its target disappeared | PASS | cleanup, M21 governs |
| 28 | enrollment for a mutant this transition killed | FAIL | M29 |
| 29 | enrollment for a mutant this transition removed | FAIL | M29 |
| 30 | proposed authorization for a mutant already KILLED in the candidate | FAIL | M25 (candidate side) |
| 31 | every passing transition is usable as the next base | PASS | invariant (see §5.2) |

### 5.1 The mandatory transition proof

One test walks the complete sequence:

```
BASE0        X is NON_KILLED, no authorization, no classification
BASE1  <-    + authorization X for an existing base survivor, nothing enrolled        PASS
BASE2  <-    + exact classification X, authorization X consumed                       PASS
CAND3  <-    X retained byte-identically (M03)                        PASS for X
             unrelated NON_KILLED Y gets a candidate-side classification  FAIL for Y (M08)
```

The final assertion is the one that matters: the failure is attributed to **Y only** — `findingId ==
identityOf("y")` — and no diagnostic carries X's identity. Base-approved means allowed; candidate
self-approval still means rejected, in the same verifier run.

### 5.2 The invariant the review blockers violated

Every transition that passes must be usable as the next transition's base. The last test above
constructs each passing ceremony state — authorized-and-pending, consumed, and cleaned-up-after-death —
and re-verifies it as its own base/candidate pair, which is exactly the shape CI runs. A transition that
could poison the next base now fails here as well as in production.

## 6. M06 / M08 / M09 are untouched

- **M08/M09**: the unauthorized-addition path is byte-identical to the pre-ceremony implementation
  (`MutationEnrollmentCeremony.unauthorizedAddition`, messages preserved verbatim). Rows 4 and 20 above
  are the negative discriminators.
- **M06**: the new-survivor rule is unchanged (`outcomeRatchet`, `candidateIds - baseIds`, NON_KILLED →
  fail). Row 10 proves an authorization cannot smuggle a new NON_KILLED identity past it.
- **M01-M05, M07, M10-M21**: no code path or constant was altered. All 13 pre-existing `Mutation*Test`
  classes pass unchanged (169 tests including the new suite; 148 before it).
- No mutation population, baseline, raw-status mapping, canonical outcome, mutator, timeout, target family
  or evolution record was touched. `AUTHORITY_EXCLUDED_IDENTITIES` was not used.

## 7. Finding: the ceremony cannot yet enroll the g1E identities (and must not pretend to)

The 43 TIMED_OUT identities adjudicated `TOOL_LIMITATION_ELIGIBLE` in g1E are **not** eligible for
authorization today, and the gate says so rather than the reviewer having to notice:

| Evidence | Value |
|---|---|
| The 43 identities present in the committed base population (`config/quality/mutation-baseline.json`, 2384 rows) | **0 of 43** |
| Committed base NON_KILLED rows | 789, of which **766 are unclassified** |
| Rule that fires | **M25** — an authorization may only ratify an unresolved NON_KILLED survivor the base already carries |
| The complementary rule that blocks the alternative ordering | **M06** — a new NON_KILLED identity cannot enter the authority as a candidate, so the 43 cannot be admitted to the base population first |

So `g1G1 (authorize the 43)` **cannot run as planned**: its precondition is unmet, and the operation that
would satisfy it is exactly the population transition that g1B proved blocked by M06. The ceremony is
necessary but not sufficient; the 43 need the population-transition question resolved first.

What the ceremony *does* have today is a real, large customer base: **766** committed base NON_KILLED
identities are unresolved in the authority and could legitimately be adjudicated → authorized → enrolled.

## 8. Files changed

| File | Change |
|---|---|
| `build-logic/src/main/kotlin/dev/tramai/build/quality/MutationClassificationEnrollments.kt` | new — ledger types |
| `build-logic/src/main/kotlin/dev/tramai/build/quality/MutationClassificationEnrollmentLoader.kt` | new — ledger parser (fail-closed) |
| `build-logic/src/main/kotlin/dev/tramai/build/quality/MutationEnrollmentCeremony.kt` | new — M22-M29 + M08/M09 backstop |
| `build-logic/src/test/kotlin/dev/tramai/build/quality/MutationRatchetEnrollmentTestSupport.kt` | new — shared fixtures (abstract, no test methods) |
| `build-logic/src/test/kotlin/dev/tramai/build/quality/MutationRatchetEnrollmentCeremonyTest.kt` | new — 15 discriminators for M22-M26 + the transition proof |
| `build-logic/src/test/kotlin/dev/tramai/build/quality/MutationRatchetEnrollmentLifecycleTest.kt` | new — 12 discriminators for M27/M28/M29 + the next-base invariant |
| `build-logic/src/test/kotlin/dev/tramai/build/quality/MutationRatchetEnrollmentLedgerTest.kt` | new — 4 ledger-parsing discriminators |
| `MutationClassificationLoader.kt` | shared per-entry validation extracted (`requiredField`, `optionalField`, `requireAllowedClassification`); messages unchanged |
| `MutationRatchetAuthority.kt` | base + candidate ledgers; base ledger loaded from the base SHA |
| `MutationRatchetVerifier.kt` | delegates added-classification dispatch and the ceremony; header documents M22-M27; companion `private` → `internal` so the ceremony object can share its vocabulary |
| `VerificationDiagnostic.kt` | four new codes (M23/M24/M25/M27) |
| `VerifyArchitectureTask.kt` | exhaustive `DiagnosticCode` classification updated (compile-time decision preserved) |
| `MaintainabilityBaselinePlugin.kt` | both `verify` call sites wire the ledger |
| `docs/roadmap/0.7.0/TASK-0.7.1g1F-CLASSIFICATION-ENROLLMENT-CEREMONY.md` | this document |

## 9. The maintainability pin correction (moved out of this change)

Adding a `Mutation*Test` suite changes the `policy-maintainability` lane's exact count assertion, and
measuring it exposed **pre-existing drift**:

```
305  committed pin (what this branch inherited)
333  measured at this base through the lane's own 13 filters (0 failures)
364  measured with this change's 31 ceremony tests (0 failures)
```

The two measurements agree independently (333 + 31 = 364), and the lane's own filter list was extracted
from the workflow rather than approximated.

This workflow triggers **only for pull requests targeting `master`**, so epic-targeted PRs never exercised
the pin and it had been stale for the whole epic line. Review classified the correction as scope hygiene
rather than an authority defect, so this change no longer touches `.github/workflows/**`: the pin moves to
its own `ci-workflow` pull request, re-measured against the merged state. The other lanes' pins were **not**
re-measured and may carry the same drift, which that follow-up records too.

## 10. Verification

| Command | Result |
|---|---|
| `./gradlew :build-logic:test --tests '...MutationRatchetEnrollmentCeremonyTest'` | 31 tests, 0 failures |
| `./gradlew :build-logic:test --tests '...Mutation*Test'` | 179 tests, 0 failures (all pre-existing suites unchanged) |
| `policy-maintainability` lane filters (13, extracted from the workflow) | 364 tests, 0 failures (333 without this change's 31) |
| clean worktree at base `8c30d7db` — `CancellationWiringTest`, `TramaiDocsGuardsPluginTest` | both fail at base: pre-existing, attributed by rerun (§10.1) |
| `./gradlew :build-logic:test` (full module) | see §12 |
| `./gradlew spotlessCheck verifyStaticAnalysis` | PASSED — `Detekt baseline OK: base 4792 -> current 4792; 0 removed, 0 added`; new/unbaselined findings **0** |
| `./gradlew verifyMutationRatchet -PtramaiMutationBaseSha=8c30d7db…` | see §12 |
| `./gradlew verifyChangePolicy -PchangeClass=build-logic -PchangePolicyBase=8c30d7db…` | PASSED, 12 changed files, no violations |
| `./gradlew verifyPr -PchangePolicyBase=8c30d7db…` | see §12 |

No `@Suppress` was added anywhere and the detekt baseline was not edited: the seven findings the gate
initially reported (LongMethod ×2, MaxLineLength ×5, TooGenericExceptionCaught, ThrowsCount ×2,
TooManyFunctions, LongParameterList, UnusedPrivateProperty) were fixed by restructuring the code, which is
the repository's stated rule — config owns exceptions, the baseline is a ceiling (not an allowance budget).

### 10.1 Pre-existing failures in the full module run (attributed, not mine)

`:build-logic:test` (the whole module, not the lane above) reports failures in two families that predate
this change and are unreachable from it:

| Family | Evidence |
|---|---|
| `CancellationWiringTest > C1 cancellation authority remains exact base task()` | Fails identically in a clean worktree at base `8c30d7db` with no ceremony code present (3 tests, 1 failed). Needs its base-SHA property; not in the `policy-maintainability` lane. |
| `dev.tramai.build.docs.TramaiDocsGuardsPluginTest` (version-surface guards) | Fails in a clean worktree at base `8c30d7db` too (19 tests, 18 failed); in a full checkout the known deferred `verifyVersionAlignment` cases (4) are what remain. Belongs to the release lane, not `policy-maintainability`. |

Neither class is in the lane whose pin §9 measures, and none of the files this change touches is read by
them. Both were reproduced at the base commit, so the attribution is by rerun, not by inspection.

## 11. Non-claims and open decisions

- **No classification was enrolled, and no g1E identity was authorized.** The ledger file does not exist in
  this slice. §7 explains why the 43 cannot be authorized yet.
- The ceremony does not decide *what* may be adjudicated; it decides *how* an adjudicated decision becomes
  base authority. Adjudication standards remain where g1E found them (repository precedent).
- Whether an authorization should be cancellable before consumption is deliberately **not** implemented:
  M27 fails closed on removal without enrollment rather than inventing a silent cancellation path.
- The other maintainability-lane pins (§9) may be stale; only `policy-maintainability` was measured.
- The separation of the workflow pin from the build-logic change is a judgement call disclosed here:
  `verifyChangePolicy` accepts the combination, and AGENTS.md rule 5 concerns production source. If review
  prefers strict separation, the workflow line can move to its own `ci-workflow` PR without touching code.


## 13. Review round 1 corrections

Review of `d3ea942c` (CHANGES REQUESTED) found two authority holes the 21 tests did not cover. Both were
real, both were in the transition state machine, and both are fixed here rather than by weakening a rule.

**Blocker 1 — a retained authorization could be rewritten before consumption.** `consumption()` compared
the base and candidate ledgers only by identity, so an unconsumed authorization could be silently
re-authored (`tool-limitation`/`A` → `equivalent-mutant`/`B`) in an intermediate transition, and the next
PR could legitimately consume the rewritten approval. Fixed by **M28**: a retained authorization must be
byte-identical to the record the base authorized. It must be consumed exactly, or left unchanged.

**Blocker 2 — the lifecycle could create invalid base authority.** Two manifestations:

- *2A*: a transition could enroll a classification for a mutant it had just reported KILLED (or removed),
  because target validity was only ever checked against the **base** population. The resulting candidate
  authority is one its own next verification rejects (`baseClassificationIntegrity` requires NON_KILLED).
  Fixed by **M29**: every active candidate classification — new or retained — must point at a candidate
  mutant that is present and NON_KILLED.
- *2B*: when a target died before consumption, the authorization was **undeletable** — retaining it left
  an M25-invalid base record, removing it tripped M27. Fixed by terminal semantics: a dead or disappeared
  target makes removal legal (cleanup, like M04), while retaining or enrolling it fails.

M25's candidate-side half also now applies to *proposed* authorizations, so a transition cannot propose an
authorization for a mutant it is killing in the same commit.

The reviewer's full matrix (12 cases) is covered by tests 22-31 above, including the strong invariant test.
One bug was caught by these tests during implementation: M28 initially reported
`MUTATION_RATCHET_ENROLLMENT_ORPHANED` instead of `MUTATION_RATCHET_ENROLLMENT_MISMATCH`; the rule was
corrected, not the test.

**Scope hygiene:** the `.github/workflows/maintainability-baseline.yml` pin correction was removed from
this change and is being landed separately as a `ci-workflow` change (see §9).

## 12. Outcome

Filled in with the final gate results and head custody at push time (see the PR body for the same set).

```
CEREMONY IMPLEMENTED — M22-M27 PROVEN, M06/M08/M09 UNCHANGED, NOTHING ENROLLED
```
