# TASK-0.7.1g1G3 — Appearing Population Admission Ceremony

**Status:** CEREMONY IMPLEMENTED; NOTHING ADMITTED OR PREAUTHORIZED
**Base:** Epic `35d5bcdf97cb6d05c3915e6472cc0b11b216d8bb` (post-#448)
**Rules:** M30–M39, enforced by `verifyMutationRatchet` (never by the maintainability baseline)
**Change class:** `build-logic` — the repository-defined class for analyzer/tooling and analyzer-adjacent quality machinery (`ChangePolicyEvaluator`). The new admission ledger is authority configuration, but it is **not** a canonical baseline path under `ChangePolicyEvaluator.isBaselinePath()`, so this slice is not a baseline migration and the admission ceremony is not an analyzer-semantic change.

## 1. The boundary this closes

M06 forbids a new `NON_KILLED` identity from appearing in a candidate measurement: a PR may not
certify its own survivor. That rule is correct, and it is also why an adjudicated survivor that is
**absent from the committed population** could never be admitted — admission would always look
exactly like the thing M06 exists to stop.

The two authorities are deliberately distinct:

| | Classification enrollment | Population admission |
|---|---|---|
| rules | M22–M29 | **M30–M39** |
| ledger | `config/quality/mutation-classification-enrollments.yml` | **`config/quality/mutation-population-admissions.yml`** |
| subject | an identity *already in* the base population | an identity *absent from* the base population that will **appear** |
| target rule | M25: the target must exist and be `NON_KILLED` in the base | **absence is the expected state and is never invalid** |

```
P1 — MINT      base:      X absent from the committed population, absent from the ledger
               candidate: population unchanged, proposes an authorization for X
P2 — CONSUME   base:      the authorization for X present and byte-identical
               candidate: the exact authorized row appears as NON_KILLED, and the authorization
                          is consumed (removed) in the same transition
```

## 2. Binding semantics

| Binding | Meaning | When enforced |
|---|---|---|
| `identity` | canonical 64-hex schema v2 identity — the only key | mint and consume |
| `status`, `outcome`, `family`, `module` | the exact persisted row: no status laundering, no outcome substitution, no re-homing | consume (M32) |
| `analyzer` | the semantics the authorization is valid under | consume (M33) |
| `populationDigest` | the projection hash of the **complete canonical fresh population** the admission is part of | consume (M34) |
| `fromBaseSha` | the exact authority base the authorization was first proposed against | **mint only** (M35), immutable thereafter |
| `reason`, `issue`, `targetPhase` | the recorded adjudication rationale | mint and while pending (M36) |
| `authorizedBy`, `authorizedAt` | **audit only — no enforcement value** | never |

**`fromBaseSha` is deliberately not compared at consumption.** The verifier sees only base vs
candidate populations and has no git ancestry or lineage knowledge, and a pending authorization
must legitimately survive intermediate merges. It is enforced on introduction (an old
authorization payload cannot be replayed onto a different authority base) and then required to
remain byte-identical. The security of consumption comes from base-side existence, immutability,
the exact row, the exact analyzer semantics and the trusted measurement digest — not from the SHA.

**`populationDigest` binds the whole transition, not one identity.** This is what "binds the
neighbours" means: if any other identity in the population moves between minting and consumption,
the digest no longer matches, the authorization cannot be consumed, and it must be re-minted against
the new measurement. The hash compared at consumption is the verifier's **own** fresh measurement
(`MutationEvolutionEvidence.proof.projectionHash`), never a value read from the ledger, so a
candidate can never construct the evidence that authorizes itself (§6.2). At mint the digest is a
commitment and has no authority: an incorrect digest simply produces an authorization that can
never be consumed. When no trusted measurement proof exists at all, admission **fails closed**.

## 3. Rule table

| # | Scenario | Expected | Rule |
|---|---|---|---|
| 1 | base-authorized exact appearing row, row identical, consumed | PASS | M30 |
| 2 | same-transition authorization + population addition | FAIL | M31 |
| 3 | no base authorization + new NON_KILLED identity | FAIL | **M06, unchanged** |
| 4 | authorization by identity only, row changed | FAIL | M32 |
| 5 | raw status changed from the authorized value | FAIL | M32 |
| 6 | canonical outcome changed | FAIL | M32 |
| 7 | authorized row absent from the fresh measurement | never admitted, never silently satisfied | M37 |
| 8 | authorized X **plus** unauthorized NON_KILLED Y | **FAIL for Y** | M06 + M30 |
| 9 | new KILLED identity | PASS | M07 |
| 10 | base-only disappearance without an M21 record | FAIL | M21 |
| 11 | valid M21 removal + valid admission in one transition | PASS (compositional) | M21 + M30 |
| 12 | candidate population ≠ the exact fresh measurement | FAIL | M21 proof |
| 13 | authorization retained after consumption | FAIL | M38 |
| 14 | authorization rewritten before consumption | FAIL | M36 |
| 15 | authorized identity becomes KILLED before consumption | cleanable, never forced | M37 exception / M39 advisory |
| 16 | every passing transition usable as the next base | PASS by construction | invariant test |

Family and module substitution (rows 4–6) is additionally unreachable by construction: the
canonical identity is computed over family and module, so a different family or module is a
different identity, and a *forged* row is already rejected by the M20 row self-validation. Those
clauses of M32 are defence in depth, not the load-bearing check.

## 4. Loader vs ceremony

The loader (`MutationPopulationAdmissionLoader`) establishes **structural** validity only:
canonical identity, commit-shaped `fromBaseSha`, 64-hex digest, analyzer fields structurally
complete (non-blank plugin/engine, non-empty mutators, positive `timeoutConst`), required fields
present, duplicates rejected. It deliberately does **not** try to establish that a digest
corresponds to a real campaign — that is the verifier's job at consumption, against its own
measurement. An absent ledger is `NONE`, the most restrictive state; a malformed one is a hard
failure, never a silent degrade to "no authorizations".

## 5. Sequencing consequence (mandatory order)

The digest binds the complete future population, so admission eligibility and admission readiness
are different states:

```
58 = admission-eligible by adjudication (12 EQUIVALENT + 46 TOOLING_LIMITATION)
58 ≠ ready for P1 population authorization
```

Authorizing them now, against today's digest `D0`, and then resolving even one of the 118
undetermined identities would change the projection to `D1` and render all 58 unusable — correctly,
because that is what binding the neighbours means. The required order is therefore:

```
g1G3 mechanism  →  resolve the 118  →  take the final canonical fresh measurement
                →  P1 authorize every remaining admission against THAT digest  →  merge P1
                →  P2 exact population transition + M21 + RECORDED_EVOLUTION  →  freeze
```

No test, configuration or ledger entry may be added to the empty admissions list before that
sequence reaches its P1 step.

## 6. Endpoint of this slice

| Required | State |
|---|---|
| mechanism implemented | YES |
| M30+ discriminator suite | as recorded in `MutationPopulationAdmissionCeremonyTest` |
| unauthorized Y still fails M06 | proven (`findingId == Y`) |
| same-transition self-authorization impossible | proven (M31) |
| row / analyzer / digest substitution impossible | proven (M32/M33/M34) |
| single-use lifecycle | proven (M37/M38) |
| next-base invariant | proven |
| `population` admissions written | **0** |
| preauthorizations written | **0** |
| baseline / M21 / production changes | **0** |
