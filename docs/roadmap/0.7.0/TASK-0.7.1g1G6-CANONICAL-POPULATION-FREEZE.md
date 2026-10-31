# TASK-0.7.1g1G6 — Final canonical population reconciliation and digest freeze

Parent: **TASK-0.7.1**. This is the gate between G5 closure and P1, not further mutant adjudication.
Checkpoint: **`284faa4fe590eaf20c91843c119053fb6c384b4d`** (the squash merge of #465). Admission ledger: `admissions: []` — unchanged by this task.

**Endpoint: CANONICAL POPULATION FROZEN — P1 MINT UNBLOCKED**

## Why this gate exists

G5 closed 52 = 36 + 12 + 4 with remainder 0. That proves the *known* custody set is closed. It does not prove that the final unrestricted canonical campaign contains the population we believe it does — which is precisely why remainder-0 is not itself mint authority.

## Campaign

The **real committed canonical configuration with no narrowing** (worktree config diff empty), at the checkpoint, in an isolated worktree: **BUILD SUCCESSFUL in 49m 3s**, 14 reports across all 7 families, `measuredCommit` equal to the checkpoint, analyzer semantics unchanged.

| | |
| --- | --- |
| rows | **2544** |
| statuses | 1836 KILLED · 448 SURVIVED · 190 NO_COVERAGE · 70 TIMED_OUT |
| outcomes | 1836 KILLED · 708 NON_KILLED |
| by family | approval 918 · evidence 808 · structuredOutput 382 · policy 209 · retry 155 · tools 57 · routing 15 |
| **complete-population digest** | **`9aebd3202288c82ff006f2db33c95cac0772746fa3c3061569167cd3f45df9b0`** |

The digest is the repository's own `MutationRatchetVerifier.canonicalProjection()` recipe. My implementation was **validated before use** by reproducing the committed population's digest `9e2febc775dd1c2c33d8cabc805e54690b2eaacc6d9ce82bea2758e1c7866230` exactly. `7081ed74…` is **not reused** — the measured artifact and custody record have both moved since it was produced.

## Reconciliation

| | |
| --- | --- |
| committed population | 2384 |
| fresh population | 2544 |
| shared | 2195 |
| base-only (disappeared) | 189 |
| candidate-only (appeared) | 349 — approval 296, evidence 53 |
| changed status among shared | 52 |
| identity loss / gain / substitution | 0 / 0 / 0 |
| **unexplained disappearance** | **0** |

## Disappearance custody

All **189/189** base-only identities match a fresh row on the source-level key `(module, class, method, descriptor, mutator, description)`. The canonical identity deliberately excludes the source line but includes PIT's block/index, so an edited mutation site keeps its key while its identity changes — the relocation the G1 lineage described.

**Caveat, stated plainly:** this is a key-level relocation match, not a proof of semantic identity, and `mutation-evolution.yml` is empty and cites none of the 189, so it is *not* the custody mechanism here. The match is the evidence; the ledger is not covering this.

## Appearing-population admissibility

**67 appearing NON_KILLED identities, 67 covered, 0 missing, 0 double-covered:**

| source | count |
| --- | --- |
| prior adjudications in the P1 candidate-only manifest | 16 |
| G5b TOOLING_LIMITATION | 36 |
| G5c EQUIVALENT | 11 |
| G5d UNREACHABLE | 4 |

The twelfth G5c identity was **KILLED**, so it is not in the appearing population. The union is exact and disjoint.

## Determinism

The population was independently re-derived from the 14 raw PIT reports (with XML unescaping): **2544 unique identities, identical identity set, 0 status mismatches, 0 family mismatches, and the identical digest**. The projection is reproducible, not merely asserted.

## Authority

**No authority-semantic change was made.** The ledger is still `admissions: []`; no baseline, classification, enrollment, mutator, timeout or ceiling file changed; the campaign ran in an isolated worktree whose outputs were not committed. Nothing was modified to satisfy the measurement.

## Preserved caveat

The four G5d **UNREACHABLE** classifications are artifact-relative: they rest on `resolveGovernedSuspension()` and `resolveGovernedIdentity()` being unable to suspend. If either resolver ever gains a genuine suspension point, those four become re-adjudication candidates rather than remaining grandfathered.

## Not done

P1 authorizations were not minted. P2 consumption was not performed. No authority file was touched.
