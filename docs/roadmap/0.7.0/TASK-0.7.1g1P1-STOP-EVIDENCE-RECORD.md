# TASK-0.7.1g1P1 — STOP: population-admission scope gap

**Status: STOPPED before minting. No population-admission authorization was created. P1 and P2 remain blocked.**

This record preserves the completed fresh canonical measurement that stopped the first P1 attempt, and the
exact finding that stopped it. It is evidence only: it is **not** mutation authority, **not** admission
authority and **not** P1 mint input.

## 1. Start gate (all conditions held)

| condition | result |
|---|---|
| Epic tip | `18f10dbb2c90fbfeafc374e1d7fe0d8814e342b1` — exact |
| `#459` merged with that SHA | yes (`MERGED`, mergeCommit `18f10dbb…`) |
| working tree | clean |
| `config/quality/mutation-population-admissions.yml` | `schemaVersion: "1"`, `admissions: []` |
| committed population unchanged since the admission design froze | yes — `git diff 80d6847f..origin/epic -- config/quality/` is empty |

Committed population at the gate: 2384 rows, canonical projection digest
`9e2febc775dd1c2c33d8cabc805e54690b2eaacc6d9ce82bea2758e1c7866230`, `measuredCommit 5856530e`.

## 2. Campaign provenance (Phase 1 — complete)

One **uninterrupted** full canonical campaign, no narrowing, in an isolated worktree
(`/tmp/tramai-g1p1-measure`, detached at the exact base; the real repository's baseline was never touched):

```
./gradlew generateCriticalMutationBaseline --no-configuration-cache --console=plain
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
BUILD SUCCESSFUL in 49m 20s
7 families / 14 family-module reports (canonicalMutationProbe invoked once per family)
measuredCommit reported by the generator: 18f10dbb2c90fbfeafc374e1d7fe0d8814e342b1
```

Measured population: **2544 rows** — KILLED 1835, SURVIVED 449, NO_COVERAGE 190, TIMED_OUT 70.
Analyzer semantics: PIT plugin `1.19.0`, engine `1.22.1`, `timeoutConst 4000`, `timeoutFactor 1.25`, and the
11 mutators in declaration order: `CONDITIONALS_BOUNDARY, INCREMENTS, INVERT_NEGS, MATH, NEGATE_CONDITIONALS,
TRUE_RETURNS, FALSE_RETURNS, PRIMITIVE_RETURNS, EMPTY_RETURNS, NULL_RETURNS, VOID_METHOD_CALLS`.

**Population digest**

```
7081ed7416f759ecca7cae00c7cf4e6261ef0cd16db49fe32bc3bb6f280bbddd
```

> **PRE-ADJUDICATION DISCOVERY MEASUREMENT — NOT YET P1 MINT AUTHORITY.**

This digest is the digest of the discovery measurement. It must not be reused automatically: if any test or
production change moves a mutant's status, P1 requires a new full canonical campaign and a new digest.

## 3. Fresh vs committed population (Phase 2 — complete)

| set | count |
|---|---|
| committed (authority) | 2384 |
| fresh (measurement) | 2544 |
| shared | 2195 |
| base-only | 189 |
| candidate-only | 349 |
| candidate-only KILLED | 281 |
| **candidate-only NON_KILLED** | **68** |
| shared rows whose status changed | 52 |

Integrity: fresh = shared + candidate-only; committed = shared + base-only; identity loss explained exactly;
0 duplicates on either side; 0 unexplained rows.

## 4. The 68 candidate-only NON_KILLED cohort

All 68 are `approval` / `:tramai-engine`:

```
TIMED_OUT     36
SURVIVED      28
NO_COVERAGE    4
---------------
total         68
```

Exact inventory with every required field (class, method, methodDescription, mutator, description, line, block,
index, fresh status, canonical outcome, family, module, numberOfTestsRun, source-level key, g1A/g1B context,
prior adjudication and source):

`docs/roadmap/0.7.0/TASK-0.7.1g1P1-CANDIDATE-ONLY-68-MANIFEST.json`

Digest of the sorted 52 unresolved canonical identities (Phase 0 custody input for the successor task):

```
92a9d06d5a8e43c5fb659f9d65bdd2e73d6ed5f45c1a8536ac70781b03503584
```

## 5. Phase 3 result — the STOP

```
fresh candidate-only NON_KILLED      68
admission-ready (prior adjudication) 16
unresolved                           52
```

The two N values differ, and a P1 admission basis must already exist before the task: P1 is an authorization
ceremony, not an adjudication task. **No partial ledger was minted.**

### 5.1 The 16 with firm prior adjudication (final dispositions)

All sixteen are `SURVIVED` in the fresh measurement and all sixteen have a final disposition of **EQUIVALENT**.
Three of them also appear in the residual-25 manifest as `UNDETERMINED`, which is their *intermediate*
pre-adjudication state; the residual-11 manifest records their **final** disposition.

| identity | final disposition | source |
|---|---|---|
| `098258d70a23…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `492f9a132e14…` | EQUIVALENT (forwarding to `resume` case-5 check) | residual-11 manifest |
| `60f7b020e009…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `62911071da6c…` | EQUIVALENT (emit's only escape is cancellation; removal measured unobservable) | residual-11 manifest |
| `63e5372ba37b…` | EQUIVALENT (forwarded to `resume` case-2 check before the cast) | residual-11 manifest |
| `6cef3fd2e23d…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `70d345f72064…` | EQUIVALENT (discarded lambda result) | residual-25 manifest |
| `ab78ed8577ff…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `d5b70d305cec…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `e0ae69141eab…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `eb8827c4f4ca…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `ebc96b3e8599…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `eec481d97a43…` | EQUIVALENT (case-0 entry check; the "KILLED at base" anecdote was itself corrected in g1G4e) | residual-25 manifest |
| `f175e8698db5…` | EQUIVALENT (discarded lambda result) | residual-25 manifest |
| `f60db12bdb9e…` | EQUIVALENT (case-0 entry check) | residual-25 manifest |
| `f802165800d8…` | EQUIVALENT (discarded lambda result) | residual-25 manifest |

**Explicit check required before any later P1:** a fresh NON_KILLED row may never be authorized on the authority
"this mutant is KILLED". Verified here: **no** identity in the 16 carries a final disposition of `KILLED`.
The one historical anomalous KILL in this lineage (`eec481d97a43`) is a raw-control correction whose final
adjudicated disposition is EQUIVALENT. No disposition/measurement drift was found in the 16.

### 5.2 The 52 unresolved

```
TIMED_OUT     36
SURVIVED      12
NO_COVERAGE    4
---------------
total         52
```

Every one of the 52 is referenced in-repo, but **only as observation or debt lineage**:

- `TASK-0.7.1g1A` records each of them in its *complete fresh-only identity inventory* as an observation
  (`STATUS / NON_KILLED`), not as a disposition;
- `TASK-0.7.1g1B` records their source-level key and the A/B/C debt category. A/B/C is a **lineage/debt
  classification**, not a semantic disposition: A means predecessor rows at a source-level key were uniformly
  non-killed, B means mixed predecessor outcomes, C means no predecessor at that key. None of the three proves
  EQUIVALENT, TOOLING_LIMITATION or UNREACHABLE;
- **zero** of the 52 appear anywhere under `config/quality/**` (no classification, no enrollment, no admission);
- **zero** appear in `TASK-0.7.1g1G2`, which classifies mechanisms at group level, not identities.

40 of the 52 report `numberOfTestsRun = 0` in the preserved PIT reports (relevant context for the TIMED_OUT
mechanism work, not a disposition).

**These 52 are new relative to the committed mutation authority (`5856530e`), but they were not newly
discovered by this track.** `g1A` already recorded the larger fresh-only population. What this campaign
discovered is that 52 of those historical fresh-only identities escaped semantic adjudication when `g1G4`
scoped itself to the 118 cohort. That is a **scope gap**, not fresh code drift. The approval family grew from
768 to 918 mutants since the committed measurement, which is why the discovery measurement sees them at the
current base.

## 6. Reproduction and independent validation

The canonical aggregation was reproduced independently from the 14 preserved PIT reports:

- first attempt disagreed — cause was in the independent parser, not the repository: it did not XML-unescape
  (`&lt;init&gt;` and similar), which shifted identities;
- after unescaping, the second aggregation reproduced the canonical generator exactly:
  **2544/2544 identities, identical identity set, 0 row mismatches, 0 duplicates, same digest
  `7081ed74…`**.

Recipe: read every `build/reports/maintainability/mutation/<family>/<module>/mutations.xml`; module = `:` +
report directory, family = parent directory; identity = SHA-256 of `\x1f`-joined
`module, mutatedClass, mutatedMethod, methodDescription, mutator, description, block, index` (XML-unescaped,
block/index as integers); outcome = `KILLED` if status is `KILLED`, otherwise `NON_KILLED`; digest = SHA-256 of
the sorted `identity|status|outcome|family|module` lines, then a `topology=` line (sorted family → sorted modules
joined by `+`, entries joined by `,`) and an `analyzer=` line (`pluginVersion|engineVersion|sorted mutators
joined by +|timeoutConst|timeoutFactor`), each terminated by a newline.

## 7. Preserved artifacts

Held outside git (raw PIT output is large and not a repository convention) at `/tmp/xk9p1-fresh/`; SHA-256 of
each is recorded in the manifest JSON under `artifactHashes` (14 `mutations.xml` reports, the generated
baseline, the generated init script).

## 8. State after this record

- no admission minted; admission ledger still `admissions: []`;
- `mutation-baseline.json`, `mutation-evolution.yml` (0 records), `mutation-classifications.yml`, the
  classification-enrollment ledger, mutators, timeout policy, target families, deviation ceilings and gate
  semantics are all unchanged;
- no production file and no test changed by the P1 attempt;
- P2 not executed; M06 unchanged; no M21 record added; no authorization consumed.

## 9. Consequence for the track

The ceremony did exactly what it was designed to do: it exposed the gap **before** authority was minted. A P2
population transition would fail M06 for the 52 until something adjudicates them.

Next: an adjudication increment over exactly this frozen 52-identity cohort (A/B/C debt categories are
explicitly **not** admissible as disposition authority), starting from the Epic tip created by merging this
record. Only after all 52 are firmly disposed does the track rerun a full canonical campaign, derive the new
candidate-only NON_KILLED set and restart `P1 MINT → P2 CONSUME` against the new digest.
