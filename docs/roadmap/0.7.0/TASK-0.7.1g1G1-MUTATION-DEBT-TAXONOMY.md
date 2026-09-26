# TASK-0.7.1g1G1 — Mutation-Debt Taxonomy and Remediation Boundary (g1G1 consolidated diagnosis)

**Status:** Recorded authority for the 0.7.1 candidate-only mutation-debt cohort
**Scope:** the 195 candidate-only `NON_KILLED` mutation identities measured at campaign A (`06936629`)
**Supersedes:** the working decompositions quoted in the g1G1a/g1G1b slice records (11 genuine + 18 genuine; "29 remediation identities"; "120 glue")
**Does not authorize:** any enrollment, classification write, M06 change, or gate exemption. See §8.

## 1. Method

Every row was classified from the **mutated instruction and the measured execution facts**, not from a filename or a description filter. A description-signature filter was used first and was **wrong in both directions** (§6.1), which is why the procedure below is the recorded one.

Per identity:

1. **Mutated instruction** — `javap -p -c -l` on the compiled class, attributing each instruction to its source line via the `LineNumberTable`. Traps that cost real time: `javap` prints `line <n>: <start_pc>` (line first), a single line can carry **several** entries, and a `{line: offset}` map built last-write-wins silently hides the entry you need. Inlined standard-library bodies also carry their own file's line numbers into the caller's class — `ApprovalRunAttribution.kt:174` is not in that 169-line file; it is the inlined `Iterable.filter` fast path.
2. **Execution fact** — `numberOfTestsRun` per row from the family `mutations.xml` (`an XML ATTRIBUTE on <mutation>`, with `block`/`index` nested inside `<blocks>`/`<indexes>`).
3. **Internal control** — for a branch-guard question, compare the guard's line with the line below it: the branch-header line shows `numberOfTestsRun > 0` (an existing test covers the guard) while the sentinel line shows `0`. That contrast is what proves a "coverage gap" is a capability limit rather than a missing test.
4. **Observability** — read the enclosing source (catch ordering, branch structure, the called helper's body) and decide whether the mutated behaviour can change an observable contract at all.

Reproduction recipes: §7.

## 2. Three distinct concepts (do not blur them)

| Concept | Question | Where it is answered |
|---|---|---|
| **Mutation classification** | Is this mutant meaningful, equivalent, glue, or a compiler artefact? | §3, §4 |
| **Remediation eligibility** | Would a test killing it assert a real contract? | §5 |
| **Remediation reachability** | Can that remediation be made inside the permitted change class **at this repository state**? | §6 |

The two coordinator identities in §5 are the case that forces the distinction. Both are *real weak assertions* and both are *eligible*, but their *reachability* differed: `ApprovalSuspensionCoordinator:117` could be asserted from an isolated test file that exercises the renewed-requirement validation without reopening the drifted test files (**closed by #446**), while `ApprovalResumeCoordinator:102` needs the full resume saga and stays **deferred**.

## 3. Determined taxonomy

| Bucket | Count | Meaning |
|---|---|---|
| Coroutine/compiler glue | 124 | Testing them asserts nothing about the contract (instruction-level evidence in §3.1) |
| Weak-assertion debt | 10 | Real, observable, test-killable — **8 closed by #444, 1 closed by #446, 1 deferred** (§5) |
| Equivalent mutants | 4 | The mutation cannot change any observable outcome |
| Compiler guards | 2 | Java-platform null guards whose removal changes only a compiler-generated message |
| Closed by #442 | 9 | 8 `NO_COVERAGE` validator rows + `validateMetadata:163` |
| **Determined** | **149** | 195 − 46 undetermined |
| Undetermined (§4) | 46 | `TIMED_OUT` — measurement-capability limited, **not** a settled category |
| **Cohort total** | **195** | |

The 8 `#444` closures are the **closed half of the weak-assertion bucket**, not a sibling bucket: counting them a second time is how 149 determined identities silently become 157.

### 3.1 Glue — 124 identities, with the instruction-level evidence

Membership is established by the mutated instruction, never by the row's description:

- **16 rows: the mutated line carries a coroutine/compiler marker** — `getCOROUTINE_SUSPENDED`, `KotlinNothingValueException`, or `ResultKt::throwOnFailure`. Examples: `ApprovalResumeCoordinator:119`, `:144`, `ApprovalResumeCoordinator$resume$2#invokeSuspend:85`, `ApprovalSuspensionCoordinator:286`, `:299`, `:293`–`:295` (three `compensateSuspension` lambdas), `DefaultApprovalGateway:201`, `:222`, `:244`, and the two sentinel checks `ApprovalSuspensionCoordinator:280`, `:282`.
- **8 rows: an `areturn` on a `Unit`-returning suspend function's return or early-return** — the value is discarded by the caller, so replacing it with `null` is unobservable by construction. `DefaultApprovalGateway:205` (`if (governedIdentity == null) return`), `:219`, `:234`, `:265`; `ApprovalSuspensionCoordinator:284` (×2), `:297`, `:307`.
- **2 rows: suspend-call sentinel checks in the approval coordinator** — `ApprovalResumeCoordinator:151`, `:155`. Each is an `if_acmpne` against `COROUTINE_SUSPENDED` immediately after `invokevirtual denyAndCancel(…, Continuation): Object` / `cancelForNestedApproval(…)`. The callees are declared `Nothing` and always throw, so the comparison runs only if the call **returns** — which needs a genuinely suspending collaborator, not a synchronous fake. The internal control confirms it: the branch-header lines `150`/`154` report `numberOfTestsRun = 1` (the existing deny and nested tests do cover the guards), while `151`/`155` report `0`.
- **98 rows: the canonical glue pair** at a suspend function's declaration line — one `NullReturnVals` ("replaced return value with null for `<Class>::<method>`") plus one `VoidMethodCall` ("removed call to `kotlin/ResultKt::throwOnFailure`") per suspension point, in matching `block` pairs.

**What glue means here:** the mutant is not killable by asserting a contract, because the mutated code is state-machine bookkeeping. Whether a suspension-driving harness could execute it at all is a separate, unanswered capability question (§4).

### 3.2 Equivalents — 4 identities

- **`ApprovalResumeCoordinator:108` and `ApprovalSuspensionCoordinator:305`** — a removed `CancellationKt::rethrowIfCancellation` call in a `catch (e: Exception)` clause that **follows** a `catch (cancelled: CancellationException)` clause. The helper's entire body is `if (this is CancellationException) throw this`, so at that call site the receiver is a non-cancellation **by construction** and the guard is provably false. Two consequences worth recording: (a) the call is vestigial defensive code — it does not walk the cause chain, so a *wrapped* cancellation is not rethrown either, which makes "nest a cancellation inside the exception" an unwinnable test design; (b) deleting the call would kill the mutant but would be a production change made for a mutation score, which is the wrong direction.
- **`ApprovalRunAttribution.kt:174` ×2** — negations of the inlined `Iterable.filter` fast path (`is Collection<*> && isEmpty()`) reached from `decodeApprovalAttribution:94`. Equivalent because the existing suite already pins every input class: `ApprovalRunAttributionTest:30` (round-trip equals `Governed`), `:53` (legacy stays un-attributed), `:62` (every partial reserved set is corruption). Any negation that changed the outcome for those inputs could not have survived those assertions.

### 3.3 Compiler guards — 2 identities

`ApprovalSuspensionCoordinator:160` (`checkNotNull` on `expiresAt`) and `:176` (`checkNotNullExpressionValue` on `clock.instant()`). Both are compiler-inserted guards on Java-platform values. With the guard removed and a null flowing, the enclosing constructor's `checkNotNullParameter` still throws **before any state is created**, so the original and the mutant fail fast at the same point and differ only in a Kotlin-compiler-generated message. Pinning that message as contract is not a legitimate assertion, so **no message-contract tests exist for these two** and none should be added.

## 4. Undetermined — 46 identities

The 46 `TIMED_OUT` rows are **measurement-capability limited, not a settled semantic category**. They are `NegateConditionals` mutants with `numberOfTestsRun = 0` (42 attributed to the coroutine sentinel comparison, 4 to synthetic `invokeSuspend` continuations). What is known: no test in the unit completed before the minion was killed, and the tranche has **0 KILLED** siblings at the same site class — i.e. no positive control exists for the bounded-test hypothesis, which is why g1E recorded `CONTROL_MECHANISM_NOT_TRANSFERABLE`.

A genuinely suspending harness and a bounded covering test that converts a hang into a failure were the first two hypotheses, and they have now been **tested once, negative** (§4.1). Still untested: a different scheduler, and a future PIT mutation-selection or timeout policy. Measured harness cost, kept for that evaluation: the scoped two-family probe executed **675 tests, 9.12 per mutation**, so per-mutant execution amplification remains a live hypothesis for this tranche alongside mutant semantics and test-harness budget. Therefore:

> These 46 are **undetermined**. They are not "non-actionable mutants" and must not be reclassified as glue or equivalent by inference from their neighbour rows.

Nothing in this record changes their count, and no remediation is planned for them in g1G1. Their status is now undetermined **by attempt as well as by assumption**: one representative was pushed with every capability the first two hypotheses named, and it did not move.

### 4.1 Measured attempt on one representative — capability negative

**Subject** — the identity `4245a1aa59c91bc5…`: `DefaultApprovalGateway.kt:105`, block 35, index 243, `NegateConditionalsMutator` (`negated conditional`), on the `approvalStore.get(...)` call inside `requestApproval` (`requestApproval-Atj0Sqo`). Chosen by census rather than by guess: the 46 rows live in exactly three classes, and `DefaultApprovalGateway.requestApproval` holds six of them with the cheapest harness, its first store call being an injected `ApprovalStore` method — so the suspension is genuinely injectable.

**Procedure** (throwaway worktree at the Epic head; no production, PIT-policy, classification, baseline or authority change):

- a delegating store (`ApprovalStore by delegate`) overrides only `get`, incrementing a counter and genuinely suspending via `delay(1)` before delegating;
- one test drives the public entry point across that suspension and asserts the counter reached 1, so the suspending body provably ran and the state-machine resume path behind line 105 is exercised rather than short-circuited;
- the call is wrapped in `withTimeout(2_000)` inside `runBlocking` — below PIT's 4s `timeoutConst` — so a mishandled resume should surface as a deterministic test failure rather than a harness timeout;
- scoped diagnostic campaign over that class only: 79 mutants, 4m11s, provenance confirmed by a name-only diff against the PR head.

**Result**

- positive control passes: the unmutated implementation completes normally (13 tests, 0 failures, the new test green in 18 ms, returning `ApprovalRequestResult.Suspended`);
- the target identity **`TIMED_OUT` → `TIMED_OUT`**, with PIT recording `status="TIMED_OUT"`, `numberOfTestsRun="0"` and an empty `killingTest`;
- the class's `TIMED_OUT` census is unchanged (13 → 13); **0** `KILLED → non-KILLED` regressions; identity sets identical (79/79 shared, 0 new);
- incidental movement only: two rows on the method's declaration line (73) moved `NO_COVERAGE → KILLED` (`NullReturnVals`) and `NO_COVERAGE → SURVIVED` (`VoidMethodCall`) — driving the entry point adds coverage there. Movement on the 124 glue rows is interesting evidence but was never this experiment's success criterion.

**Interpretation** — a test-level suspension harness does not overcome PIT's behaviour for this sentinel class. The operative fact is `numberOfTestsRun = 0`: under the mutant no test completes, so the mutation is recorded `TIMED_OUT` and never credited as killed, even when the suspending path is proven reachable and the calling test is locally bounded.

**Scope limit, stated explicitly** — this is one representative in one class, not all 46. It converts the first two hypotheses from *untested* to *tested once, negative*; it does not license reclassifying the remaining rows, and it does not touch the still-untested hypotheses (a different scheduler, a future PIT selection/timeout policy), which stay open.

## 5. Weak-assertion debt — 10 identities, and where they went

All ten were `SURVIVED` with `numberOfTestsRun > 0` and a provably observable contract. Nine are closed — eight by #444, one by #446 — and one is **deferred**.

| Identity | Contract asserted | Status |
|---|---|---|
| `GovernedApprovalStore.kt:56` b7.i25, b2.i12, b5.i22 | `(attribution as? Governed)?.identity ?: return` — every negation skips the binding comparison, so an approval whose attribution names another run is accepted (fail-open) | **closed by #444** |
| `RuntimeEvidenceAttribution.kt:24` b0.i4 | the five-key allowlist; Kotlin reads a same-object property as a direct field load, so the consumer is `RuntimeEvidenceContractValidator:133` (`allowedKeys = familyAllowedKeys + metadataKeys`) — an empty vocabulary rejects the identity tuple as unknown keys | **closed by #444** |
| `RuntimeEvidenceContractValidator.kt:135` b12.i51 | the removed `RuntimeEvidenceAttribution.validate(...)` call — a partial tuple is corruption, not legacy | **closed by #444** |
| `RuntimeEvidenceContractValidator.kt:170` b75.i270, `:201` b116.i459 | `approvalVersion >= 0` and `attempt >= 0`; `attempt = 0` is the **normal first attempt** | **closed by #444** |
| `GovernedContinuationContinuity.kt:35` b4.i29 | the run id in the continuity rejection; a message reading `null` describes no run | **closed by #444** |
| `ApprovalSuspensionCoordinator.kt:117` b43.i148 | `timeoutMillis > 0` — zero is an approval already expired when persisted | **closed by #446** |
| `ApprovalResumeCoordinator.kt:102` b76.i533 | the structured-parse uncertain reason must name the failure type; a constant `unknown` erases it from emitted evidence | **deferred** (§6) |

Measured at the #444 head (`eb691e19`): the 8 admissible identities `SURVIVED → KILLED`, **0** `KILLED → non-KILLED` regressions across 1047 baseline-KILLED rows in scope, **0** identity churn, and exactly **2** `KILLED → SURVIVED` flips versus the ten-test version — the two then-blocked identities, confirming the removed tests account for exactly two kills.

Measured for #446 at its head (`3fda224e`, scoped approval-family campaign, 918 mutants, 18m25s): `ApprovalSuspensionCoordinator.kt:117` b43.i148 — the `ConditionalsBoundaryMutator` (`> 0` → `>= 0`) — `SURVIVED → KILLED`, with **0** `KILLED → non-KILLED` regressions across 512 baseline-KILLED rows in scope, **0** identity churn, and exactly one flip (512 → **513** killed, 137 → **136** survived). The two `NegateConditionals` rows at the same site were already `KILLED`, so the **inverted boundary** was the specific survivor — which is why that contract is asserted from both sides: zero rejected, positive accepted, and each side fails when the boundary inverts.

For the one deferred identity, the precise statement is: **classification** real weak assertion · **remediation eligibility** yes · **remediation reachability in g1G1** no. It is not a tool limitation and not merely "still SURVIVED".

## 6. The change-class boundary (why one identity is deferred)

`ApprovalSuspensionCoordinatorTest.kt` and `ApprovalResumeCoordinatorTest.kt` carry **dormant formatter drift** (unsorted imports, non-canonical wrapping). The spotless ratchet is **PR-scoped** — a file that is not part of the PR's change set is not checked, which is why the drift has survived — so touching either file puts the whole file through the formatter. Measured in both directions:

- **Not reformatted**: `spotlessKotlinCheck` fails on the same two files.
- **Reformatted**: `spotlessKotlinCheck` passes, and the detekt baseline no longer matches: **22 non-baselined findings** in that state.

The mechanism is **not** a one-to-one re-anchor, and it is worth stating precisely because the intuitive account is wrong. `.editorconfig` deliberately leaves KtLint's line-length rule **off** ("ktlint_official leaves it off"), while detekt's `MaxLineLength` is **active at 120**. Canonical formatting therefore moves lines across detekt's threshold in *both* directions at once. Measured on the two files (base `92b306eb` → canonical form):

| | before | after |
|---|---|---|
| Lines over 120 characters (raw length) | 40 | 18 |
| Baseline `MaxLineLength` entries for these files | 38 | 28 |
| Constructs no longer over 120 (formatter wrapped them) | — | 32 |
| Findings on newly joined single-line statements | — | 22 |

So the repair is a **baseline recomposition**, not a re-anchor: net **−10** entries for these two files (4792 → 4782 repository-wide) — 32 legacy suppressions genuinely removed, 22 added on statements KtLint canonicalises as single lines. It belongs to its own slice and its own change class (`baseline-migration`), and it needs an explicit per-entry mapping, because a slice that both reformats and adds assertions destroys the one property that made #444 strong: **mutation movement attributable purely to assertion-strengthening tests**.

Measured hazard from the same run: `verifyStaticAnalysis` printed `Detekt baseline OK: base 4792 -> current 4792; 0 removed, 0 added` **while reporting 22 non-baselined findings**. The gate's removed/added counters compare *cardinality*, not identity, so equal counts can still fail. Treat "0 removed, 0 added" as a count statement only, and always pair it with detekt's own non-baselined finding list.

```
formatter/baseline normalization (own PR, baseline-migration)
  → prove spotlessCheck + verifyStaticAnalysis stable with no changes to findings' cardinality
  → add the resume test (own PR, runtime-behaviour)
  → scoped PIT measurement
```

**Outcome in g1G1: this recomposition was measured and rejected** as unjustified for a single mutant. `ApprovalSuspensionCoordinator:117` never needed the drifted files — the renewed-requirement validation reads only the request, the policy decision and the arguments digester, touching no gate, store or suspension state — so an isolated test file with the real `Sha256ToolArgumentsDigester`, a matching digest, and never-reached stand-ins closed it (**#446**). What remains blocked is one identity: `ApprovalResumeCoordinator:102`, whose scenario needs the full resume saga and therefore one of the drifted files. The normalization stays available as repository hygiene if those legacy files are reopened for their own sake — never as a mutation-score prerequisite.

### 6.1 The description filter that produced the earlier wrong counts

The first partition classified a row as glue if its description matched `replaced return value with null` or `removed call to kotlin/ResultKt::throwOnFailure`. That rule is:

- **too narrow** — it missed the two sentinel-negation rows (`ApprovalResumeCoordinator:151`, `:155`) and the two sentinel checks `ApprovalSuspensionCoordinator:280`, `:282`, all glue by instruction;
- **unverified** — it accepted 8 rows (`areturn` on `Unit` returns) as glue on the strength of the description alone, without checking the instruction.

Net effect: "11 genuine `NO_COVERAGE` + 18 genuine `SURVIVED` = 29 remediation identities" and "120 glue". The instruction-verified figures are **10 weak-assertion identities total** and **124 glue**. The corrected arithmetic, which is the reason this record exists:

```
195 cohort
├─ 124 glue                      (determined)
├─  46 TIMED_OUT                 (UNDETERMINED — measurement capability)
├─  10 weak assertion            (determined)
│    ├─ 8 closed by #444
│    ├─ 1 closed by #446
│    └─ 1 deferred (baseline-migration reachability)
├─   4 equivalent                (determined)
├─   2 compiler guard            (determined)
└─   9 closed by #442
= 195                                 (149 determined · 46 undetermined)
```

The 8 `#444` closures are the already-closed half of the 10 weak assertions, not a separate bucket — writing them twice is how "195" silently becomes 203.

## 7. Reproduction recipes

**Instruction attribution**

```
javap -p -c -l <module>/build/classes/kotlin/main/<FQCN>.class
# per method: read the LineNumberTable as (line, start_pc) pairs, keep EVERY entry per line,
# then attribute each instruction to the entry with the largest start_pc <= its offset.
# Glue markers: getCOROUTINE_SUSPENDED, KotlinNothingValueException, ResultKt::throwOnFailure.
```

**Internal control from the family report** — `numberOfTestsRun` (attribute) per line: the branch-header line `> 0`, the sentinel line below it `= 0`.

**Scoped family measurement** — in a **throwaway worktree**, narrow `mutation.targetFamilies` in `config/quality/test-quality.yml` to the families under test, commit, then run `generateCriticalMutationBaseline`. The aggregate lands at `<worktree>/config/quality/mutation-baseline.json` with per-row identity and status. Two traps: the **committed** population file is checked out in that worktree before the run, so verify `measuredCommit` equals the probe head and that the row count matches the narrowed scope before reading it; and never let the narrowed config leave the throwaway worktree.

**Comparisons** — join on the **full canonical identity**, never on `(file, line, block, index)`: that tuple collides across mutators and produced a false "`ApprovalSuspensionCoordinator:117` was already KILLED" during this work. Identity in the partition summary is a 16-character prefix of the population identity, so prefix-join deliberately.

## 8. What this record does not authorize

- **No enrollment.** The 4 equivalents, the 2 blocked weak assertions, the 2 compiler guards and the 124 glue rows are **not** to be written into `config/quality/mutation-classifications.yml`. The g1F finding stands: a candidate-only identity cannot be authorized by a candidate, so classification of any of these requires the M22–M29 ceremony against a governing base — and the g1G0 wall (0 of 43 adjudicated identities in the committed population; admission floor is the whole 195-row cohort) is unchanged.
- **No new tests for glue, equivalents or compiler guards**, and no message-contract tests for the two compiler guards.
- **No reclassification of the 46** `TIMED_OUT` rows as a settled category (§4).
- **No gate change of any kind** — no baseline edit, no deviation ceiling, no workflow change, no exemption. The §6 migration is a future slice's work under its own change class, not a licence taken here.
- **The one deferred identity stays `SURVIVED`** in every measurement until §6's prerequisite lands.
