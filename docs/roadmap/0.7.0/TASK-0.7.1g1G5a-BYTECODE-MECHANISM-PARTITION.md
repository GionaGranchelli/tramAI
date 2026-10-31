# TASK-0.7.1g1G5a — Exact bytecode mechanism partition of the frozen 52

**Diagnosis only. Every identity remains UNDETERMINED. No disposition is assigned, no admission is minted, no test, production file, configuration or authority artifact was changed.**

## 1. Custody and base proof

| item | value |
|---|---|
| exact base | `19703cfe9d81fbca11c8e11cde5af876ff731f1c` (post-`#460` Epic tip), local branch fast-forwarded, tree clean |
| `18f10dbb..19703cfe` touch set | docs only (the P1 STOP record) — so production bytecode is unchanged across the two tips |
| admission ledger at the base | `schemaVersion: "1"`, `admissions: []` |
| parent cohort manifest | `docs/roadmap/0.7.0/TASK-0.7.1g1P1-CANDIDATE-ONLY-68-MANIFEST.json` |
| rows extracted with `unresolved == true` | 52 |
| recomputed sorted-identity digest | `92a9d06d5a8e43c5fb659f9d65bdd2e73d6ed5f45c1a8536ac70781b03503584` — **matches the required digest** |

## 2. Compilation and javap provenance

```
module          :tramai-engine        command   ./gradlew :tramai-engine:compileKotlin
JDK             21.0.12.1             Kotlin    2.3.0
class output    tramai-engine/build/classes/kotlin/main (335 classes)
javap           javap -p -c -l -s
classes analysed 7  (the 6 owners named by the cohort manifest + the generated
                     compensateSuspension$2$2 lambda), SHA-256 per class recorded in the manifest
```

The exact-base compile is **byte-identical to the compile the discovery measurement used**: the class set and the per-class hash aggregate agree exactly (`5d2c2af4…` over the sorted per-class digests). The bytecode inspected is therefore the bytecode PIT measured, at a verified commit — not a stale build.

## 3. Mapping method (and why block/index is not authority)

PIT's `block`/`index` are **not** JVM bytecode offsets, and PIT's source line is not the exact mutated instruction. The mapping used:

1. restrict the candidate instruction set for a `method + mutator` to line-bearing instructions of that mutator's kind;
2. align that pc-ordered set to **every** PIT mutant of the same `method + mutator` (including KILLED members of the population), in `(block, index)` order;
3. require the corroboration that PIT's reported line equals the mapped pc's line-table line;
4. where counts differ, leave the row **AMBIGUOUS** and refuse to select an instruction.

The alignment rule was derived, not assumed, from two independent observations: for `resume`, PIT reported 11 `NegateConditionals` mutants against 13 conditional branches, and PIT's line sequence `[65,66,68,80,81,85,90,102,98,113,109]` equals the pc-ordered line sequence of exactly 11 branches once the two **no-line** compiler prologue branches (`ifeq` at pc 4 and 21, the parameter/`$this` guards) are removed. The same relation holds for `suspendToolExecution` (8 = 10 − 2). PIT does not mutate those no-line prologue branches; every other line-bearing conditional is mutated in pc order.

Result: **45 EXACT, 7 AMBIGUOUS, 0 silently inferred from source-line similarity**.

## 4. Distribution of the frozen 52

```
by raw status        TIMED_OUT 36 · SURVIVED 12 · NO_COVERAGE 4
by mutator           NegateConditionals 39 · NullReturnVals 8 · VoidMethodCall 5
by mechanism (G5a)   SUSPENSION_SENTINEL 36 · OTHER_CONDITIONAL 8 · NULL_GUARD 1 · UNRESOLVED 7
mapping confidence   EXACT 45 · AMBIGUOUS 7
final dispositions   0
```

## 5. Mechanism cohorts

| cohort | mechanism | members | raw status | generalization |
|---|---|---|---|---|
| **G5A-M01** | coroutine suspension-sentinel comparison | **36** | TIMED_OUT 36 | **EXACT_STRUCTURAL** |
| G5A-M02 | null guard (`ifnonnull`) | 1 | SURVIVED 1 | NO_GENERALIZATION |
| G5A-M03 | line-bearing integer/flag conditional | 8 | SURVIVED 6, NO_COVERAGE 2 | PARTIAL_ONLY |
| G5A-M04 | unresolved mapping (candidate-count mismatch) | 7 | SURVIVED 5, NO_COVERAGE 2 | NO_GENERALIZATION |

**G5A-M01** is the whole TIMED_OUT cohort and the only cohort with structural generalization. Its rule, verified programmatically for 36/36 members, is the canonical Kotlin state-machine suspension codegen:

```
iconst_N ; putfield label        <- state is set before the child call
invoke       <child suspend fn>  <- the child suspends
dup ; aload  <sentinel>          <- reload the call result and COROUTINE_SUSPENDED
if_acmpne                        <- THE MUTATED INSTRUCTION
```

Every member is this construct; no member is a value comparison. This is a *hypothesis generator with structure*, not a disposition: it says the 36 share one mechanism, and that mechanism is the coroutine suspension protocol rather than an authored condition.

## 6. The g1E comparison, explicitly

| criterion | answer |
|---|---|
| same exact type of compiler-generated construct? | **YES** — 36/36 identical sentinel-comparison shape |
| `numberOfTestsRun == 0` consistently? | **YES** — 36/36 |
| deterministic/reproducible timeout evidence exists? | **YES** for the single fresh campaign · **NOT_YET** for a second independent campaign |
| authored source branch hidden by the mutation? | **NO** — the mutated instruction is the protocol comparison; the source line is only the call site |
| semantic behaviour independently protected? | **NOT_YET** — not established in a diagnosis increment |
| independently killed control available where needed? | **YES** for same-mutator, different-role controls in the same methods (authorizeResume 6, prepareResume 7, resume 5, resolveAndValidateResumeTool 6, revealAndValidateReplayPayload 2 conditional mutants are KILLED) · **NOT_YET** for a same-role control |
| safe to apply g1E reasoning at cohort level? | **NOT_YET** |

The remaining gap is exactly two measurements: a second campaign for timeout determinism, and per-mechanism evidence that the sentinel branch is protocol-only with authored behaviour protected elsewhere. Applying the g1E precedent now would require *assuming* the very thing it asks to be proven.

## 7. SURVIVED (12) — hypotheses only

- **OTHER_CONDITIONAL 6** and **NULL_GUARD 1**: value/flag comparisons and one null guard that tests already execute. Hypotheses, each requiring its own discriminator: a missing behavioural assertion; a discarded-result equivalence; a forwarding-to-later-check equivalence; or a cancellation-only path. These are the rows where new tests are most likely to convert SURVIVED into KILLED.
- **UNRESOLVED 5**: no unique instruction is established, so no semantic claim is made at all. The next step is evidence work on the per-mutator skip rule (PIT mutates a subset of each mutator's opcode family), not tests.

No discriminator is written in this increment; the manifest names the required evidence per identity.

## 8. NO_COVERAGE (4) — reachability hypotheses only

- **OTHER_CONDITIONAL 2**: reachable-if-some-path-tests-it hypotheses; the reachability of the predecessor condition must be established before any disposition.
- **UNRESOLVED 2**: ambiguous mapping first.

Neither the "missing test path" nor the "compiler-only seam" hypothesis is asserted here; both remain to be decided by the reachability work in G5d.

## 9. Ambiguous mappings (7) — recorded, not resolved

```
564517b2fdab  candidate count 17 != 10     3658ba45eaed  candidate count 17 != 10
1ce5967ee60b  candidate count 17 != 11     8e18b4b48968  candidate count 16 != 8
d38ac52f2843  candidate count 16 != 8      c7774df8ccdc  candidate count 5 != 3
5a7edac3a982  candidate count 11 != 8
```

Each is a `method + mutator` whose line-bearing candidate set exceeds the PIT mutant count, so no bijection exists yet. Per the mapping rule these rows are **AMBIGUOUS** and are barred from later disposition until the exact instruction is unique.

## 10. Recommended next increments (from the data)

1. **G5b — TIMED_OUT tooling-mechanism adjudication (36, cohort G5A-M01).** Largest, single-mechanism, EXACT_STRUCTURAL. Evidence needed: a second independent campaign at the same SHA for timeout determinism; a same-role control; the per-mechanism argument that the sentinel branch is protocol-only and that the authored behaviour is protected by the resumed-frame checks in the same methods.
2. **G5c — SURVIVED semantic discriminators (12).** Expect the 6 OTHER_CONDITIONAL members to be genuine missing-assertion kills; the 5 UNRESOLVED members must be re-mapped first.
3. **G5d — NO_COVERAGE reachability (4).** Two need the reachability proof, two need re-mapping.
4. **G5a-2 (fold into G5c/G5d) — resolve the per-mutator skip rule** so the 7 ambiguous rows map uniquely. This is evidence work on PIT's mutation eligibility, not a test-writing task.

The split is a recommendation derived from the partition, not a constraint; if G5b's measurement shows the 36 divide by method or by successor semantics, G5b must be split along that boundary.

## 11. What G5a did not do

No test added or changed; no production change; no mutation configuration, timeout, mutator, target-family, ceiling or gate change; no baseline, classification, enrollment, admission or M21 change; no disposition assigned; P1 still blocked, P2 not executed.

**G5A MECHANISM PARTITION COMPLETE — 52 STILL UNDETERMINED; NEXT EVIDENCE INCREMENT SELECTED FROM THE BYTECODE MAP.**
