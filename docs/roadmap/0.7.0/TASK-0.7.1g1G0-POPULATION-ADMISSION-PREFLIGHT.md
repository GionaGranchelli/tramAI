# TASK-0.7.1g1G0 — Candidate-Only Population Admission Semantics Preflight

Evidence-only preflight. No population enrollment, no authority file modified, M06 not weakened, no build
logic changed.

Bounded question: **can TramAI safely support an explicit base-authoritative admission ceremony for
candidate-only NON_KILLED mutation identities without weakening M06, and what exact transition would move
the g1E identities into the committed population?**

## 1. Scope and custody

| Item | Value |
|---|---|
| Task branch | `task/0.7.1g1g0-population-admission-preflight` |
| Exact base | `0693662969f7459e7106d3d708b300139820b43a` (== `origin/epic/0.7.1-control-plane-authority` at task start; 40 characters verified) |
| Worktree at start | clean (`git status --porcelain` empty) |
| Deliverable | this document only |
| Change class | `documentation` |

No `config/quality/**` file, no build logic, no workflow, no test and no production source is modified.

## 2. Phase A — the population transition, refreshed at the current head

### 2.1 What the previous evidence was, and why it may not be current

g1A/g1B established, measured at `0f36ab8744c14ebbdb3b10964311d0416e012ab2` (2026-09-24):

```
committed 2384   fresh 2544   shared 2195   base-only 189   candidate-only 349
candidate-only KILLED 154      candidate-only NON_KILLED 195 (TIMED_OUT 46 / NO_COVERAGE 109 / SURVIVED 40)
```

Those counts are treated as historical. The committed population itself last changed on 2026-09-04
(`488f28e8`, `measuredCommit 5856530e…`); the drift became *visible* on 2026-09-24 with #429.

### 2.2 Measurement campaigns

| Campaign | Head | Command | Result |
|---|---|---|---|
| g1A-A | `0f36ab87` | `./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks` (detached worktree) | 2544 rows, BUILD SUCCESSFUL |
| g1A-B | `0f36ab87` | same, independent run | 2544 rows, BUILD SUCCESSFUL |
| **g1G0-A** | `06936629…` | same canonical command, detached worktree `/tmp/tramai-071g1g0-a` | **2544 rows**, BUILD SUCCESSFUL, 50m14s, artifact sha256 `5091cf86e42288e1e4c16fd15a2ff065850b83e4693dd009ddec4bc52071407e` |

Independent-repeatability evidence from the g1A pair (identical canonical settings):

| Comparison | Result |
|---|---|
| identity sets identical | **yes** |
| rows differing in status/outcome/family/module | **0** |
| analyzer block identical | **yes** |
| canonical projection hash | identical (`d7b6af9ca4d18e65b19a537d89490226…`) |

### 2.3 Analyzer semantics equality

| Fact | At head | Committed authority |
|---|---|---|
| PIT Gradle plugin / engine | `1.19.0` / `1.22.1` | identical |
| mutators | 11 (CONDITIONALS_BOUNDARY, INCREMENTS, INVERT_NEGS, MATH, NEGATE_CONDITIONALS, TRUE_RETURNS, FALSE_RETURNS, PRIMITIVE_RETURNS, EMPTY_RETURNS, NULL_RETURNS, VOID_METHOD_CALLS) | identical |
| `timeoutConst` / `timeoutFactor` | `4000` / `1.25` | identical |
| identity schema | `2` — SHA-256 over `module␟className␟method␟methodDescription␟mutator␟description␟block␟index` | identical |
| family/module scope | 7 families over `:tramai-engine`, `:tramai-security`, `:tramai-sovereign`, `:tramai-core`, `:tramai-structured` | unchanged |

Config hashes at head: `test-quality.yml` `dff61c7775472ca9ed6e460bce28d740…`,
`mutation-baseline.json` `07f595225666e0b0cbd8914db9881d9b…`,
`mutation-classifications.yml` `2440fd5be443b1073b98d9461b21a646…`,
`mutation-evolution.yml` `23808e191d627b13c27c4adc87cf3fd2…`.

### 2.4 Custody of the range since the old measurement

`git diff --name-only 0f36ab87..06936629` contains **no** mutation-target source
(`tramai-{engine,security,sovereign,core,structured}/src/**`), **no** analyzer-semantics file
(`MutationProbeInitScript`, `MutationIdentity`, `MutationPopulationAggregator`, `MutationReportParser`) and
**no** quality configuration. Exactly one non-target test changed
(`tramai-orchestration/src/test/.../WorkflowMcpStepTest.kt`); `:tramai-orchestration` is not a mutation
family module and appears in no `targetTests`, so it cannot move a measurement.

### 2.5 Refreshed counts and set hashes

Three independent measurements now exist, taken at **two different heads**. Comparison is row-for-row over the
complete persisted row (status, outcome, family, module, class, method, method description, mutator,
description, block, index):

| Comparison | Result |
|---|---|
| g1G0-A (`06936629…`) vs g1A-A (`0f36ab87`) | identity sets identical; **0 rows differing** |
| g1G0-A vs g1A-B | identity sets identical; **0 rows differing** |
| `analyzer` block, g1G0-A vs g1A-A | byte-equal |
| g1A-A vs g1A-B | identical (0 differing) |

⇒ **the population is stable.** Three campaigns, two heads, zero differences — including one campaign taken
after the head moved by 10 merges. The `POPULATION_NOT_STABLE_ENOUGH_FOR_ADMISSION_DESIGN` stop condition is
**not** triggered, and a fourth campaign would measure the same rows. (Decision recorded: a fresh campaign B
was **not** run for this reason — three-way agreement with one measurement at a *different* head is stronger
evidence of stability than a repeat at the same head.)

The measurement's own totals cross-check against PIT's campaign summary exactly:

| Raw status | Population | PIT summary |
|---|---|---|
| KILLED | 1667 | 1667 |
| NO_COVERAGE | 330 | 330 |
| SURVIVED | 463 | 463 |
| TIMED_OUT | 84 | 84 |
| total | 2544 | 2544 |

Family counts likewise: approval 918, evidence 808, policy 209, structuredOutput 382, retry 155, routing 15,
tools 57. `measuredCommit` recorded in the artifact = `0693662969f7459e7106d3d708b300139820b43a`.

**The current transition** (g1G0-A vs the committed population):

| Quantity | Value |
|---|---|
| committed population | 2384 |
| fresh population | 2544 |
| shared | 2195 |
| base-only (disappeared) | 189 |
| candidate-only | 349 |
| candidate-only KILLED | 154 |
| candidate-only NON_KILLED | 195 |
| **shared with a changed status/outcome** | **2** — see §4.2 |

Candidate-only NON_KILLED raw-status distribution: `SURVIVED` 40, `TIMED_OUT` 46, `NO_COVERAGE` 109.

These equal the g1B figures exactly, and the g1E 43 are 43/43 present with identical rows. ⇒
**`CURRENT_POPULATION_CHANGED — REPARTITION_REQUIRED` is not triggered**: the g1B/g1C/g1E partition remains
current, now *re-measured* rather than inherited.

### 2.6 Integrity of the refreshed population

| Check | Result |
|---|---|
| identity self-consistency (recomputed SHA-256 over the `␟`-joined schema-v2 fields) | 0 inconsistencies / 2544 |
| duplicate identities | 0 |
| `outcome`/`status` disagreement (`status == KILLED` ⟺ `outcome == KILLED`) | 0 |
| rows with `status == KILLED` and `outcome != KILLED` | 0 |
| all 43 g1E identities present | 43/43 |
| rows differing from the g1A measurement | 0 |

## 3. Phase B — the 43 g1E identities in the fresh population

Measured fresh in this slice against `06936629…`; nothing here is inferred from the g1C/g1E artifacts.

| Check | Result |
|---|---|
| present in the current fresh measurement | **43/43** |
| present in the committed population | **0/43** |
| persisted row identical to the g1A measurement (all fields) | 43/43 |
| persisted row identical to the g1E adjudication artifact | 43/43 |
| raw status | `TIMED_OUT` 43/43 |
| canonical outcome | `NON_KILLED` 43/43 |
| family / module / mutator | `approval` / `:tramai-engine` / `NegateConditionalsMutator` 43/43 |

Conclusion unchanged at the current head: **0 of the 43 are base-population members**, so the g1F ceremony's
M25 refuses to authorize them, and M06 blocks admitting them first. g1G1 ("authorize the 43") remains
unexecutable — it is not the next slice, whatever happens to the ceremony.

Precision note on the cohort boundary: the candidate-only `TIMED_OUT` bucket is **46**, and the g1E 43 are a
strict subset of it. The 3 additional identities are `ApprovalResumeCoordinator.executeClaimedResume` with
`NegateConditionalsMutator` — the same class/method/mutator shape as the adjudicated 43 (the "category A"
three in g1C). Any admission set that covers the 43 covers them too, or they fail M06 in the same transition.

## 4. Phase C — is a partial "43 only" population transition possible?

**No. The impossibility is structural, not incidental.** Three independent mechanisms forbid it:

1. **The evolution proof binds the complete population.** In `RECORDED_EVOLUTION` mode `verifyMutationRatchet`
   runs its own fresh full canonical measurement (`MaintainabilityBaselinePlugin.kt:840-853`,
   `persistCommittedBaseline = false`) and mints a proof only when
   `MutationPopulationEvolutionProof.exactComparison(fresh, candidate)` is diagnostic-free.
   `rowDifferences` iterates `fresh.keys union candidate.keys` and fails on any row "absent from committed
   candidate" as well as any status/outcome/family/module difference. A 43-row committed file is missing
   every other fresh identity → diagnostics → `proof == null` → M21 fails with *"recorded evolution requires
   a proof from an exact fresh canonical measurement"* (`MutationRatchetVerifier.kt:935-941`).
2. **M06 consults nothing** (`MutationRatchetVerifier.kt:312-323`): no classification, no authorization, no
   ledger. Any unadmitted candidate-only survivor in the committed population fails, whatever the author
   intended to add.
3. **Scope may not narrow.** M14/M15 reject a candidate that drops a family, module, target class or target
   test, so a partial file cannot masquerade as scope reduction.

⇒ **The hypothesis holds: a legitimate fresh baseline transition necessarily carries the complete measured
population**, so every candidate-only NON_KILLED identity in that measurement must be resolved or explicitly
admitted. There is no legal subset.

### 4.1 The minimum viable admission set

| Category | Count (g1A) | Requirement to commit the population |
|---|---|---|
| candidate-only KILLED | 154 | none — M07 passes new kills |
| candidate-only NON_KILLED | **195** | **every one must be admitted** (or killed by new tests) |
| base-only (disappeared) | 189 | one M21 record each, bound to `fromBaseSha` |

"Admit the 43" therefore has a floor of **195 admissions, not 43**, and only 43 of those carry any
adjudication today.

### 4.2 Changed rows: no new primitive is required (correction to an earlier draft)

Two identities are present in **both** populations but with a different persisted row:

| Identity | Target | Committed (base) | Fresh |
|---|---|---|---|
| `20b3d3021a…` | `PolicyEnforcementHelper.evaluate` [NullReturnValsMutator], policy, `:tramai-engine` | `NO_COVERAGE` / NON_KILLED | `KILLED` / KILLED |
| `49f02d5ba7…` | `PolicyEnforcementHelper.evaluate` [VoidMethodCallMutator], policy, `:tramai-engine` | `NO_COVERAGE` / NON_KILLED | `SURVIVED` / NON_KILLED |

An earlier draft of this record concluded from these two rows that a third authority primitive
("changed-row resolution") is required. **That conclusion was wrong**, and the error was one of arrow
direction:

| Comparison | Where it lives | What it checks |
|---|---|---|
| **fresh measurement vs candidate committed population** | `MutationPopulationEvolutionProof.exactComparison` → `rowDifferences`, called from exactly one place (`MutationRatchetVerifier.kt:739`) | per-row `status`, `outcome`, `family`, `module`; identities missing from either side; family/module topology; analyzer identity |
| **base population vs candidate population** | shared-identity loop, `MutationRatchetVerifier.kt:288-311` | `M01` regression (`base.outcome == KILLED && candidate.outcome == NON_KILLED`) and `M14` family re-homing — **nothing else** |

`rowDifferences` never runs base→candidate. Its messages talk about raw and canonical status, which is what
made the misreading tempting, but the arrow is fresh→candidate. A legitimate transition commits the exact
fresh row, so `fresh == candidate` holds by construction and the proof is minted.

Both rows are therefore absorbed by semantics that already exist:

| Transition | Rule | Verdict |
|---|---|---|
| base KILLED → candidate NON_KILLED | M01 | FAIL (regression) |
| base NON_KILLED → candidate KILLED | none | PASS — monotonic improvement needs no authority |
| base NON_KILLED raw status A → candidate NON_KILLED raw status B | none | PASS — commit the exact fresh row |
| candidate-only NON_KILLED | M06 | FAIL unless killed or admitted |
| base-only identity | M21 | FAIL without an evolution record |

Neither row carries a classification (`config/quality/mutation-classifications.yml` has no entry for
either), so M08/M09 cannot interact with the change. **No changed-row primitive and no third precondition:**
the first of the two rows is an improvement the ratchet already permits for free.

## 5. Phase D — M21's trust properties, reconstructed

| Property | Implementation |
|---|---|
| **Invocation authority** | property `tramaiMutationEvolution`: absent/`forbid` → FORBID, unknown → throw. CI gives a PR `recorded-evolution` only with the `mutation-population-evolution` label (`ci.yml:220`); a master push only when the diff changed **both** `mutation-baseline.json` and `mutation-evolution.yml` (`ci.yml:232-237`). An ordinary push can never enter a ~50-minute campaign. |
| **Exact fresh-measurement proof** | `MutationPopulationEvolutionProof(projectionHash)`; the hash covers every row as `identity\|status\|outcome\|family\|module`, the family→modules topology and the analyzer block (`MutationRatchetVerifier.kt:840-860`). Missing proof → fail; proof not matching the committed candidate → fail. |
| **Per-identity records** | `MutationEvolutionRecord(id, fromBaseSha, reason, issue?, targetPhase?, authorizedAt?, authorizedBy?)`. |
| **Exact record set** | every record must name a base identity disappearing in this transition (extra records fail); every disappearance must be named (else fail). |
| **Base binding** | every record's `fromBaseSha` must equal the transition base SHA — records cannot be replayed from an older base. |
| **Default FORBID** | without authority every base-only identity fails; absence is never read as improvement. |

`config/quality/mutation-evolution.yml` is `records: []`: the disappearance ceremony has **never been
used**, so no part of this design may lean on precedent.

### 5.1 Which M21 properties transfer to *appearing* identities, and which do not

Additions and removals are **not** symmetric, and treating them as symmetric is the main design trap here.

| Property | Transfers? | Why |
|---|---|---|
| Invocation authority (property + label) | **yes** | same trust shape: an explicit, non-default invocation with a human decision behind it |
| Exact-set bookkeeping | **yes**, inverted | disappearances: record-set must equal the removed set. Appearances: authorization-set must equal the *admitted* subset; every other appearing identity must still fail M06. The set is no longer equal to the whole delta — it is a *subset* — which is precisely why it needs its own rule rather than a reused one |
| Per-identity payload | **no — must be larger** | an M21 record names an identity plus a reason. An admission must freeze the **complete persisted row** (§6.4), because for an appearance the *value* is what M06 judges, while for a disappearance only the absence matters |
| `fromBaseSha` binding | **yes** | prevents replay from an older base |
| Fresh-measurement proof | **yes, and it becomes the binding constraint** | the proof is computed over the whole population, so it simultaneously (a) proves the measurement is fresh and (b) forces the transition to commit everything measured |
| Default FORBID | **yes** | an appearing NON_KILLED identity without authorization must keep failing M06 |
| "Absence is not improvement" reasoning | **no analogue** | removals are dangerous because absence may be an artifact (narrowed target, aborted campaign). Appearances are dangerous because they are *real* unverified survivors: the risk is not measurement error but an untested behaviour |
| Symmetry assumption | **rejected** | the failure modes, the payload size and the set semantics all differ. An admission rule is new authority, not a mirror of M21 |

## 6. Phase E — exact-row preauthorization model (on paper; not implemented)

Two transitions, by analogy with M22-M29 — same temporal trust boundary (the authorization must already
exist in the PR base), different payload.

```
P1 — AUTHORIZE                                 P2 — CONSUME (a LATER transition)
  base:      X absent from committed pop         base:      exact authorization X present
             no authorization X                   candidate: fresh measurement contains X
  candidate: baseline unchanged                            committed population := exact fresh measurement
             proposes authorization X                     X's candidate row == the authorized row
  -> adjudication moment = merge of P1                     authorization X consumed
                                                 -> X admissible; every unauthorised NON_KILLED still fails M06
```

### 6.1 Minimum payload, field by field (each field justified by the attack it prevents)

| Field | Attack / ambiguity it prevents |
|---|---|
| canonical identity (64-hex, schema v2) | identity substitution; also forbids descriptor/source-line similarity being used as authority — the identity is the only key |
| complete persisted row (`status`, `outcome`, `family`, `module`) | authorizing an identity and then admitting a *different* row: status laundering (a `TIMED_OUT`/`NO_COVERAGE` admitted as if it were `KILLED`), outcome substitution, re-homing into another family, module substitution under the same class name. Exactly the hole M28 closed for classifications |
| raw status | silent drift of the diagnostic value between authorization and admission |
| canonical outcome | admitting under an outcome the authorizer never saw — and the outcome is what M06 actually judges |
| analyzer semantics (plugin, engine, mutators, `timeoutConst`, `timeoutFactor`) | "authorize now, measure with different settings later": M16-M18 forbid analyzer drift, so an authorization valid under one analyzer must not be consumed under another |
| source base SHA (`fromBaseSha`) | replay of an authorization minted for an older base; mirrors M21's binding for removals |
| fresh-measurement digest (population projection hash) | authorizing row X against measurement M and admitting X from a *different* measurement M′; also binds the neighbours, so the transition's other identities are the measured ones |
| `reason`, `issue`, `targetPhase` | re-authoring the rationale or the tracked issue after the decision; the M24/M28 lesson |
| `authorizedBy`, `authorizedAt` | **audit only — no enforcement value.** Recording them is fine; letting them carry trust would make identity/actor a bypass |

Minimum enforceable payload: everything above **except** `authorizedBy`/`authorizedAt`.

Non-negotiable security property: **a candidate may never create the authorization it uses.** The ledger is
read at the base SHA; a candidate-added authorization plus an admission in the same transition must FAIL
(the M23 analogue).

### 6.2 One thing the model must *not* become

The digest binding creates a temptation: mint the digest from the *fresh* measurement so that a partial
population could present a matching digest. That would be a bypass: it would let a candidate define its own
measurement. The digest must be bound to the **canonical fresh measurement produced by the verifier's own
campaign**, and the committed candidate population must equal that measurement — which is exactly what the
M21 proof already enforces.

## 7. Phase F — adversarial discriminator matrix

The minimum negative/positive set an implementation would require. "Existing" means a rule that already
covers the case today and must keep covering it.

| # | Scenario | Expected | Rule |
|---|---|---|---|
| 1 | base-authorized exact appearing row, row identical, consumed | PASS | new (M30) |
| 2 | same-transition authorization + population addition | FAIL | new (M31 analogue of M23) |
| 3 | no base authorization + new NON_KILLED identity | FAIL | **existing M06, unchanged** |
| 4 | authorization by identity only, row changed (status/outcome/family/module) | FAIL | new (M32 analogue of M24) |
| 5 | raw status changed from the authorized value | FAIL (or a new authorization) | new |
| 6 | canonical outcome changed | FAIL (re-adjudication required) | new |
| 7 | authorized row absent from the fresh measurement | FAIL — never admitted, never silently satisfied | new |
| 8 | authorized X **plus** unauthorized NON_KILLED Y in the same measurement | **FAIL for Y** | new + **existing M06** |
| 9 | new KILLED identity | PASS | existing M07 |
| 10 | base-only disappearance without an M21 record | FAIL | existing M21 |
| 11 | valid M21 removal + valid admission in the same exact transition | PASS (composable) — both sets are checked against the same committed population and the same proof | existing M21 + new |
| 12 | candidate baseline ≠ the exact fresh measurement | FAIL | existing M21 proof |
| 13 | authorization retained after consumption | FAIL | new (M26/M27 analogue) |
| 14 | authorization rewritten before consumption | FAIL | new (M28 analogue) |
| 15 | authorized identity becomes KILLED before consumption | authorization becomes stale and cleanable; admission must NOT be forced | new (M27 terminal-state analogue) |
| 16 | every passing population transition must itself be usable as the next base | PASS by construction | the invariant test from g1F, reused |

**The single most important negative: authorized X + unauthorized NON_KILLED Y → FAIL for Y.** An admission
ceremony must never become a general M06 bypass. This is the discriminator that separates "explicit
authority for one adjudicated identity" from "turn M06 off for this transition".

## 8. Phase G — the three options, traced

### 8.1 Option B — committed population remains authoritative forever

Not closure. It is a **latent publish blocker**, and this is checkable rather than rhetorical:

```
publish.yml:173  →  verify060MaintainabilityRelease
                 →  wireArchitectureAndMaintainabilityAuthorities()
                 →  :verifyReleaseMutation
                 →  runs a FRESH full PIT measurement, verifies it as candidate against the
                    committed population as base, with evolution defaulting to FORBID
```

`verifyReleaseMutation` therefore fires exactly the two rules that the drift violates: M21 for the 189
base-only identities and M06 for the 195 candidate-only survivors. Consequences:

| Consequence | Finding |
|---|---|
| fresh release mutation verification | fails deterministically while the committed population is stale — the publish path cannot go green |
| current code vs stale population | the authority stops describing the code; 154 measured KILLs exist in the measurement and are unrecorded in the authority |
| future M06/M21 comparisons | every future regeneration faces the same 195-identity wall, larger if tests are added |
| can g1 close? | not by declaring closure: the gate is wired into the release path |
| can the 43 ever reach classification enrollment? | **no** — M25 requires base-population membership, which B never provides |
| genuine closure or documented drift? | documented drift with a hard downstream gate — the worst of both |

Latency detail (stated precisely): the last `publish.yml` run was 2026-09-09, *before* the drift became
visible on 2026-09-24 with #429. The blocker has therefore not fired yet; it fires at the next publish.

### 8.2 Option C — wholesale population replacement

Wholesale replacement does not remove the authority question, it *is* the authority question:

| Question | Answer |
|---|---|
| does an exact fresh measurement solve anything by itself? | no — it is the *precondition*: the proof is what makes the committed population admissible evidence at all |
| every candidate-only NON_KILLED identity | must be admitted or killed; 195 of them today, and M06 has no exemption |
| every base-only identity | needs an M21 record (189), each bound to `fromBaseSha` |
| bypass or recreate? | recreates, at maximum blast radius: one transition carrying 2544 rows, 189 records and 195 admissions |
| blast radius vs A | identical in *content*, worse in *reviewability*: A can be staged (authorize, then consume), whereas C is one indivisible authority change with no intermediate state to review |

### 8.3 Option A — base-preauthorized appearing-identity admission

| Question | Answer |
|---|---|
| what it buys | the only mechanism by which an adjudicated survivor becomes base-authoritative WITHOUT being killed; without it, an unkillable survivor (tool limitation) can never be recorded, and the population can never be re-synchronised |
| what authority it creates | a new, *narrower-than-M06* admission path: base-minted, exact-row, single-use. It is new authority — it must be argued as a change to M06's rejection path, not smuggled as "just another ledger" |
| minimum evidence standard | the field table in §6.1 is the payload floor; the *adjudication* standard is the one g1E reconstructed for tool limitations (identity + exact instruction/row + construct + determinism + harness independence + semantic protection), which is what makes the authorization content-reviewed rather than typo-reviewed |
| does the whole NON_KILLED cohort need authorization? | **yes, unless killed.** The transition carries the whole measurement, so each candidate-only survivor must either be admitted or be KILLED by the time the measurement is taken (§4.1) |
| can the 43 be handled independently? | **no** — only as part of a complete fresh-population transition (Phase C) |
| how does it compose with M21? | cleanly: M21 handles removals, the admission rule handles appearing NON_KILLED identities, both are checked in the same transition against the same exact measurement and the same proof (discriminator 11) |
| is classification enrollment still separate? | yes, and the order is forced: identities must be admitted to the base population first (g1G2-style), *then* their classifications can be enrolled through the existing M22-M29 ceremony, which requires base-population membership (M25) |

### 8.4 The shortcut this preflight refuses

Writing tests that kill the survivors is not a shortcut around the authority question — it is the *legitimate*
answer for the subset that can be killed. A survivor that a new test kills becomes `KILLED` in the next
measurement and passes M07 automatically: no admission, no authorization, no M06 change. That reduces the
irreducible admission set to the survivors that genuinely cannot be killed.

## 9. Phase H — the whole candidate-only NON_KILLED cohort

All 195 identities, not just the 43. Partitioned from data, not from status: every identity was matched to
its raw PIT record (195/195 matched, 14 `mutations.xml` files), giving `numberOfTestsRun` — the counts below
are therefore about *what the tests do at the mutation point*, which is the only thing that distinguishes a
coverage hole from an unkillable construct. Cross-referenced against the g1B cohort.

| Bucket | n | Evidence | Reading |
|---|---|---|---|
| `TIMED_OUT` | **46** | 100% `NegateConditionalsMutator`; `numberOfTestsRun = 0` for all 46; 3 classes hold 42 | tool-limitation-shaped: the hang signature (PIT never completes a test, so it reports 0) |
| `NO_COVERAGE` | **109** | `numberOfTestsRun = 0` for all 109; `VoidMethodCallMutator` 49 + `NullReturnValsMutator` 50 + `NegateConditionalsMutator` 10; **93 of 109 in 3 approval classes** | real coverage gaps — no test reaches the mutation point at all |
| `SURVIVED` | **40** | `numberOfTestsRun` 1–80 (15 rows at exactly 4); `VoidMethodCall` 15, `NullReturnVals` 11, `NegateConditionals` 9, `ConditionalsBoundary` 4 | tests execute but do not detect: weak assertions **or** equivalent/redundant mutants — not separable without per-identity analysis |

Concentration is the headline: the three approval classes (`ApprovalResumeCoordinator` 67,
`DefaultApprovalGateway` 46, `ApprovalSuspensionCoordinator` 44) hold **157 of the 195** identities across all
three buckets. This is one module's test surface, not a project-wide phenomenon.

| Requested bucket | Count | Basis |
|---|---|---|
| already adjudicated / classifiable | **43** | the g1E adjudication; all 43 in the `TIMED_OUT` bucket |
| likely real test gaps | **109** | `NO_COVERAGE`, `numberOfTestsRun = 0` — nothing reaches the mutant |
| likely tool limitation | **46** | `TIMED_OUT`, `NegateConditionals`, `numberOfTestsRun = 0`, coroutine sentinel cluster — the g1C/g1D shape; 43 already adjudicated, **3 more** same class/method/mutator (`executeClaimedResume`) awaiting adjudication |
| stable but unresolved | **40** | `SURVIVED` with tests running — genuinely ambiguous between "equivalent mutant" and "assertion too weak"; splitting them requires the per-identity standard, which this slice does not apply |
| disappeared since g1B | **0** | cohort identical: 195 in both, 0 new, 0 gone |
| new since the old g1B evidence | **0** | same |

Limits, stated so nobody over-reads the table: this is a *structural* partition backed by tests-run counts,
mutator type and class concentration. It deliberately does **not** claim per-identity semantic verdicts, and
in particular it does not claim the 40 `SURVIVED` rows are equivalent mutants, nor that the 46 are
permanently unkillable.

**Answer to the Phase H question** — *if exact population admission must consume the whole fresh measurement,
what unresolved authority work remains?*

| Work | Count | Nature |
|---|---|---|
| M21 disappearance records (bound to `fromBaseSha`) | 189 | bookkeeping, no judgement beyond the reason — but the ceremony has never been used |
| appearance admissions | 195 today | unless some are killed first |
| … of the 195: clearly remediation | **109** | `NO_COVERAGE`, 0 tests run — no test reaches the mutant |
| … of the 195: unresolved until diagnosed | **40** | `SURVIVED` with tests running — may add to remediation *or* to the admission/adjudication set |
| … of the 195: tool-limitation candidate set | **46** | 43 adjudicated, 3 still awaiting adjudication, so 46 is a structural candidate set and **not yet** an authoritative one |
| changed-row resolution | **0** | not required — absorbed by M01/M06/M21 (§4.2) |

The finding, at the strength the evidence actually supports: **109 of the 195 are clearly remediation work,
not authority work.** A survivor killed by a new test becomes `KILLED` in the next measurement and passes M07
with no admission, no authorization and no M06 change — which is both the cheaper path and the one that raises
the measured protection number.

A further **40 require per-identity diagnosis first**, and the order matters: each is either a weak assertion
(remediation) or an equivalent/redundant mutant (adjudication). Writing killing tests before diagnosing them
risks spending the effort trying to kill mutants that cannot be killed, and then building authority machinery
around the wrong residual set. Likewise, the tool-limitation set becomes authoritative at 46 only when the
last 3 `TIMED_OUT` identities are adjudicated by the g1E standard; until then 46 is a candidate set.

## 10. Verdict

**No stop condition was triggered.** Audited one by one: this preflight does not weaken ordinary M06; does
not let a candidate authorize its own transition; does not admit an identity absent from a canonical fresh
measurement; does not accept a candidate baseline that is not the exact measured population; does not use
descriptor/source-line similarity as identity authority; does not use baseline-migration as a survivor
waiver; does not use `AUTHORITY_EXCLUDED_IDENTITIES`; does not treat g1E classification eligibility as
population authority (§3: 0/43 are base members); and does not silently admit the other candidate-only
`NON_KILLED` identities — they are enumerated in §9.

### Findings

| Outcome | Status |
|---|---|
| `EXACT_MEASUREMENT_MODEL_INCOMPATIBLE_WITH_PARTIAL_ADMISSION` | **TRIGGERED** — proven in §4 |
| `POPULATION_ADMISSION_REQUIRES_FULL_M06_COHORT_RESOLUTION` | **TRIGGERED** — the floor is 195 admissions, not 43 (§4.1) |
| `EXACT_ROW_POPULATION_ADMISSION_FEASIBLE` | **CONDITIONALLY TRIGGERED** — a safe model exists (§6, §7); it is not a 43-row increment, and it has two preconditions below |
| `CURRENT_POPULATION_CHANGED — REPARTITION_REQUIRED` | **NOT triggered** — three measurements, two heads, zero differences; counts equal g1B; the 43 are 43/43 identical (§2.5) |
| `NO_SAFE_APPEARING_IDENTITY_CEREMONY_FOUND` | **NOT triggered** — a safe model exists; the guarantee that keeps it safe is Phase F's discriminator 8 (authorized X + unauthorized Y still FAILS for Y) |

### The two preconditions on the feasible model

1. **M06's rejection path must become authorization-aware.** This is a change to M06 — not a weakening if the
   four guarantees hold (authorization read from the **base** SHA, exact row bound, single-use, and every
   unauthorized `NON_KILLED` still failing), but it is new authority and it is **the user's decision**. This
   preflight does not make it.
2. **The transition remains all-or-nothing.** Staging is possible only in the sense of *authorize first, consume
   later*; a single consuming transition still carries the complete measurement.

No changed-row primitive is needed: §4.2 shows the two changed rows are already covered by M01's one-way
regression rule and by committing the exact fresh row.

### Answer to the bounded question

> Can TramAI safely support an explicit base-authoritative admission ceremony for candidate-only NON_KILLED
> mutation identities without weakening M06?

**Yes in principle, and the shape is known — but "admit the 43" is not a thing that can exist.** The ceremony
is feasible only as part of a complete fresh-population transition whose failure set is small enough to
review, and its scope should be the identities that are genuinely unkillable, not every identity the
measurement happens to report as surviving.

**What exact transition would move the 43 into the committed population:** a `RECORDED_EVOLUTION` transition
that (a) commits the exact fresh measurement, (b) carries an M21 record for each of the 189 disappeared
identities, (c) carries a base-minted exact-row authorization for each admitted appearing `NON_KILLED`
identity — 195 of them unless some are killed first — and (d) mints the proof by matching the verifier's own
fresh measurement. The 43 ride along; they are not separable. Changed rows need no step: they are resolved by
(a) alone (§4.2).

### Recommendation — the next slice (not implemented here)

**The next slice is cohort remediation, not ceremony implementation.** The ceremony is the narrow answer for
the unkillable; the bulk of the wall is a test-coverage problem, and 93 of those 109 rows sit in three
approval classes in one module.

1. **g1G1a — remediate the clear coverage gaps (primary, `runtime-behaviour` class).** Tests that reach the 109
   `NO_COVERAGE` identities, starting with the 93 in `ApprovalResumeCoordinator`, `DefaultApprovalGateway` and
   `ApprovalSuspensionCoordinator`. Every identity killed this way passes M07 permanently with **no** authority
   change and raises the measured protection number.
2. **g1G1b — diagnose the 40 `SURVIVED` rows individually, before writing anything.** Diagnosis first: each is
   either a weak assertion (remediation) or an equivalent/redundant mutant (adjudication). Killing tests written
   before the diagnosis may chase mutants that cannot be killed, and then build authority around the wrong
   residual.
3. **g1G1c — finish the tool-limitation adjudication (evidence-only).** Apply the g1E standard to the 3
   remaining `ApprovalResumeCoordinator.executeClaimedResume` `TIMED_OUT` identities; only then is the
   tool-limitation set authoritative at 46 rather than a structural candidate set.
4. **g1G2 — design only the appearing-NON_KILLED admission ceremony (design-only, no implementation).** §6
   payload, §7 discriminators as its test specification. The M06 authorization branch is presented as an
   explicit authority decision, with its negative discriminator (authorized X + unauthorized Y still FAILS for
   Y) as the acceptance test. No changed-row primitive: §4.2.
5. **Then, and only then, the population transition PR** — commit the exact fresh population, M21 records for
   the then-current disappeared identities, consume base-preauthorized admissions for the then-current
   candidate-only NON_KILLED identities, with the failure set already minimized by steps 1-3.

Sequencing note that is not optional: the **publish gate** (`verify060MaintainabilityRelease` via
`publish.yml:173`) is blocked while the committed population is stale. This work is on the critical path of
the next release regardless of what happens to the 43, so "leave the population alone" is not a stable
resting place — it is a deadline.

## 11. Verification

| Command / check | Result |
|---|---|
| `git fetch origin`; `git rev-parse origin/epic/0.7.1-control-plane-authority` | `0693662969f7459e7106d3d708b300139820b43a` (40 chars) |
| branch cut from that SHA; `git status --porcelain` | clean |
| `git diff --name-only 0f36ab87..06936629` (custody of the range) | no mutation-target source, no analyzer semantics, no quality config; 1 non-target test |
| `./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks` in detached worktree at `06936629…` | BUILD SUCCESSFUL, 50m14s, 2544 mutants, exit 0 |
| artifact sha256 | `5091cf86e42288e1e4c16fd15a2ff065850b83e4693dd009ddec4bc52071407e` |
| row-for-row comparison vs g1A-A and g1A-B | 0 differing rows, 0 set differences |
| PIT campaign summary vs persisted population | 1667/330/463/84 and per-family counts identical |
| identity self-consistency, duplicates, outcome/status agreement | 0, 0, 0 |
| 43 g1E identities vs fresh and committed populations | 43/43 present, 0/43 committed, rows identical |
| Phase H partition (195 identities against 14 `mutations.xml`) | 195/195 matched to PIT evidence |
| `git status` at end of slice | one new untracked file (this document) |

Reproducibility scripts live in `/tmp` and are **not committed**: `tramai-071g1g0-analyze.py`,
`tramai-071g1g0-partition.py`, `tramai-071g1g0-integrity.py`, `tramai-071g1g0-changed2.py`.

### 11.1 The CI failure on this PR — attribution (unrelated; not fixed here)

CI on this PR failed in `:tramai-orchestration:test`:
`WorkerShutdownCoordinatorTest > shutdown drains a running execution to completion before finishing()` at
`WorkerShutdownCoordinatorTest.kt:213` (1152 tests, 1 failed, 3 skipped). It is **not** attributable to this
change, and that is provable rather than asserted:

| Evidence | Value |
|---|---|
| `WorkerShutdownCoordinatorTest.kt` blob at the Epic base `06936629…` | `36640558ac4f8482c44007ec8ad775f4d2ea0f61` |
| … at this PR's head `642cd07a…` | `36640558ac4f8482c44007ec8ad775f4d2ea0f61` |
| … at `origin/master` | `36640558ac4f8482c44007ec8ad775f4d2ea0f61` |
| this PR's diff | one documentation file |

The assertion is a real race, not an unexplained flake: `shutdown()` calls `observability.onShutdownStarted(...)`
(`WorkerShutdownCoordinator.kt:117`) **before** `pollJob?.cancelAndJoin()` (118) and
`executionSupervisor.activeExecutionsSnapshot()` (119). The test releases its gate as soon as it observes
`shutdownStarted` (`WorkerShutdownCoordinatorTest.kt:204-206`), so a valid interleaving exists in which the
execution completes and deregisters before the snapshot is taken — after which the drain legitimately reports
`drainProgress(0,0)`, while the assertion demands the subsequence `drainProgress(1,0)`. `shutdownStarted`
cannot prove the coordinator captured the execution; only a synchronization point *after* the snapshot can.

**Deliberately not fixed here**: this record is evidence-only, and the test fix belongs in its own slice.
The failed CI job was re-run at the same head SHA, not papered over with an empty commit.

## 12. Non-claims

- No authority file was modified: no population, classification, enrollment or evolution record changed.
- M06 was not modified, weakened or bypassed; its behaviour is documented from source.
- No measurement entered the committed authority; the campaign ran in a detached worktree.
- The 43 remain adjudicated-but-unenrolled, exactly as g1E left them.
- g1A/g1E evidence is cited as historical. Counts asserted as *current* are the ones measured in this slice
  and are labelled as such.
- The publish blocker in §8.1 is derived from task wiring plus the unconditional M06/M21 rules. It is
  deterministic, but it was **not** re-run end-to-end in this slice (that would cost a ~50 minute campaign);
  the last publish run predates the drift.
- **No second campaign was run**, by recorded decision (§2.5). The stability conclusion rests on three
  independent measurements taken at two different heads, not on a same-head repeat.
- The Phase H partition is **structural**: raw status, mutator type, `numberOfTestsRun` from the raw PIT XML,
  and class concentration. It does not claim per-identity semantic verdicts, and it does not claim the 40
  `SURVIVED` rows are equivalent mutants or that the 46 `TIMED_OUT` rows are permanently unkillable.
- **§4.2 corrects an earlier draft of this same record.** The first revision claimed a changed-row authority
  primitive was required; that came from reading `rowDifferences` (fresh→candidate) as a base→candidate
  comparison. The correction is recorded rather than quietly deleted, and the wrong version was the one first
  published in this PR.
- The **CI failure on this PR is not attributable to this change** (blob identical at the Epic base, this head
  and `origin/master`; diff is one documentation file) and was **not** fixed here. §11.1 records the diagnosis
  for a separate slice.
- "109 clearly remediation" means no test currently reaches those mutation points. It is not proof that a test
  can kill each one — that is what writing them establishes.
- Nothing was implemented: no test was written, no survivor was killed, no build logic changed, no
  authorization or record written. Every forward-looking item in §10 is a proposal, including the M06 change,
  which is explicitly left as the user's decision.
- The candidate-only NON_KILLED floor of 195 is a statement about *today's* measurement with *today's* tests.
  Adding tests moves it down; it is not a permanent property of the codebase.
