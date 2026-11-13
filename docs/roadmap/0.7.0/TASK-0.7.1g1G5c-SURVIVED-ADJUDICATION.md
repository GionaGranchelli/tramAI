# TASK-0.7.1g1G5c — SURVIVED mapping closure and semantic adjudication

Parent: **TASK-0.7.1g1G5**. Scope: the **12 frozen SURVIVED identities**, and nothing else. The four NO_COVERAGE identities are untouched.
Base: **`42d9d9487ccf59ca3f348d0babfe9bbda232ef0f`** — the squash merge of #462. Frozen parent digest `92a9d06d5a8e43c5fb659f9d65bdd2e73d6ed5f45c1a8536ac70781b03503584` unchanged; admission ledger still `admissions: []`.

**Endpoint: G5C SURVIVED COHORT CLOSED — G5 REMAINDER = EXACTLY 4 NO_COVERAGE.**

## Final accounting

| | |
| --- | --- |
| input SURVIVED | 12 |
| KILLED | **1** |
| EQUIVALENT | **11** |
| UNREACHABLE | 0 |
| TOOLING_LIMITATION | 0 |
| UNDETERMINED | **0** |
| mappings EXACT | **12 / 12** |
| mappings AMBIGUOUS | 0 |

## Phase 1 — the five ambiguous mappings, repaired

G5a marked five identities AMBIGUOUS because an opcode-shape enumeration cannot recover PIT's block/index coordinates. The authority is the repository's own `MutationIdentity.stableKey()`: `sha256(module ␟ class ␟ method ␟ descriptor ␟ mutator ␟ description ␟ block ␟ index)`, with **line deliberately excluded**. It reproduces **all 68** identities of the P1 candidate-only manifest exactly, so the canonical identity itself carries PIT's coordinates — and matching PIT's emitted rows against the frozen identity resolves each ambiguous identity uniquely:

| identity | resolved line | PIT block/index | instruction |
| --- | --- | --- | --- |
| `1ce5967ee60b` | 108 | 91 / 641 | `pc1100 invokestatic CancellationKt.rethrowIfCancellation` |
| `c7774df8ccdc` | 305 | 16 / 101 | `pc155 invokestatic CancellationKt.rethrowIfCancellation` |
| `3658ba45eaed` | 160 | 31 / 168 | `pc287 invokestatic Intrinsics.checkNotNull` |
| `8e18b4b48968` | 174 | 22 / 127 | `pc189 ifeq` after `instanceof Collection` |
| `d38ac52f2843` | 174 | 24 / 131 | same fast-path test, second inlined stratum copy |

The G5a AMBIGUOUS determinations are recorded as **superseded, not erased** (see `g5aOriginalMapping` per row).

**The SMAP matters here.** `ApprovalRunAttributionKt`'s line table has entries at 171–180 while the checked-in `ApprovalRunAttribution.kt` ends at line 169. The `SourceDebugExtension` map explains it: output line 174 comes from stratum **2 = `_Collections.kt` line 1807** (inlined stdlib), and the `KotlinDebug` stratum maps it back to **our line 104** — `expected.any { metadata.getValue(it).isBlank() }`. Treating "174" as a physical line in our file would have been wrong.

## Phase 2 — control measurement at the new base

Family-only narrowing (53 deletions, `approval` block byte-identical), throwaway `78458d1eecce60c7f8f307b4564fb47afda0da79`, BUILD SUCCESSFUL in 1000 s, `measuredCommit` equal to the throwaway commit, analyzer semantics unchanged. Census: **918** mutants — engine 492, security 426; KILLED 661, SURVIVED 133, NO_COVERAGE 62, TIMED_OUT 62.

Recovery: **12 input · 12 recovered · 0 missing · 0 duplicates**, every control status taken from this post-merge tip. All twelve remained SURVIVED (matrix `SURVIVED → SURVIVED` ×12, **status movement 0**), so no identity was incidentally killed by merged tests, and no identity was killed merely to raise a number.

## Phase 3 — diagnosis

All twelve had real `numberOfTestsRun` at control (2–95), so E (tooling limitation) was excluded from the outset and no identity was diagnosed from mutator or status similarity.

| identity | owner | mutator | line | PIT | hypothesis | disposition |
| --- | --- | --- | --- | --- | --- | --- |
| `1ce5967ee60b` | ApprovalResumeCoordinator.resume | VoidMethodCall | 108 | b91/i641 | structural | **EQUIVALENT** |
| `3658ba45eaed` | ApprovalSuspensionCoordinator.suspendToolExecution | VoidMethodCall | 160 | b31/i168 | structural | **EQUIVALENT** |
| `3a5c5213b226` | ApprovalSuspensionCoordinator.compensateStep | NullReturnVals | 307 | b17/i106 | B | **EQUIVALENT** |
| `56d3366434a6` | DefaultApprovalGateway.requireExistingAttributionMatches | NullReturnVals | 205 | b9/i50 | B | **EQUIVALENT** |
| `6a1ce2f46d2a` | DefaultApprovalGateway.requireExistingAttributionMatches | NullReturnVals | 219 | b26/i141 | B | **EQUIVALENT** |
| `8e18b4b48968` | ApprovalRunAttributionKt.decodeApprovalAttribution | NegateConditionals | 174 | b22/i127 | structural | **EQUIVALENT** |
| `a542bbe6db3f` | DefaultApprovalGateway.persistGoverned | NullReturnVals | 265 | b35/i204 | B | **EQUIVALENT** |
| `a9760bea3a92` | ApprovalResumeCoordinator.resume | NegateConditionals | 102 | b76/i533 | A | **KILLED** |
| `c7774df8ccdc` | ApprovalSuspensionCoordinator.compensateStep | VoidMethodCall | 305 | b16/i101 | structural | **EQUIVALENT** |
| `d38ac52f2843` | ApprovalRunAttributionKt.decodeApprovalAttribution | NegateConditionals | 174 | b24/i131 | structural | **EQUIVALENT** |
| `eb21aaed3192` | ApprovalSuspensionCoordinator.persistSuspendedInvocation | NullReturnVals | 284 | b9/i38 | B | **EQUIVALENT** |
| `f44636049288` | DefaultApprovalGateway.persistUngoverned | NullReturnVals | 234 | b29/i161 | B | **EQUIVALENT** |

## Phase 4 — the one real semantic kill

`a9760bea3a92` is the `ifnonnull` in `"structured-parse-failed: ${e::class.simpleName ?: "unknown"}"`. The production path already exercised it, but the assertions read `startsWith("structured-parse-failed")` — satisfied both by the authoritative diagnostic and by the mutant's `unknown`. The discriminator was therefore missing, not the coverage.

The fix is one assertion contract, tightened in place (2 lines, `d3837a58`): `isEqualTo("structured-parse-failed: StructuredOutputException")`. It is about the diagnostic contract, not about PIT: it triggers a real `StructuredOutputException` through the resume path, captures the authoritative uncertain-outcome audit, and pins the class-name component.

Measurement: candidate `7acbd868adb94658e90ef55d3261d315f6eddda9` — a child of the control throwaway, differing by the test commit alone — BUILD SUCCESSFUL in 923 s.

| requirement | result |
| --- | --- |
| `a9760bea3a92` | **SURVIVED → KILLED**, killing test named below |
| KILLED regressions | **0** |
| identity loss / gain | 0 / 0 |
| new unexplained timeouts | 0 |
| shared identities | 918 / 918 |

Killing test: `ApprovalResumeSuspensionContractTest` › *a cancellation resumed into the structured-parse uncertain path is not replaced by the primary failure*, at `resume` line 102.

## Phase 5 — the eleven equivalence proofs

**Caller-discard (6 rows, NullReturnVals).** The mutated instruction is the function's `areturn`, immediately preceded by `getstatic kotlin/Unit.INSTANCE` — verified at pc161 (`compensateStep`, line 307), pc100 and pc244 (`requireExistingAttributionMatches`, lines 205 and 219), pc341 (`persistGoverned`, line 265), pc267 (`persistUngoverned`, line 234), pc54 (`persistSuspendedInvocation`, line 284). The mutant returns null where Unit was returned; every call site pops it. The resumed path after each suspend call begins with an explicit `pop`: `compensateStep` pc252, `persistSuspendedInvocation` pc1231, `persistUngoverned` pc880, `persistGoverned` pc1049, `requireExistingAttributionMatches` pc699. The value is never observed, so returning null is unobservable.

**Catch-handler dominance (2 rows, VoidMethodCall).** `resume` registers `CancellationException → 1088` and `Exception → 1093`, and the mutated `pc1100` executes inside the **1093 (Exception)** handler. `compensateStep` registers `CancellationException → 147` / `Exception → 150`, with `pc155` inside the **150** handler. JVM dispatch order gives the earlier, more specific handler the cancellation, so the helper's only branch (`if (this is CancellationException) throw this`) cannot fire. Removing the call cannot change behaviour.

**Compiler guard passed on (1 row, VoidMethodCall).** `pc285 aload 7 → pc287 Intrinsics.checkNotNull → pc290 aload 7 → pc293 invokespecial CreateApprovalChallenge(...)`. The guard protects a value that is immediately passed as a non-null Kotlin constructor parameter, whose own parameter check raises the same NPE one instruction later. Same exception type, same observable outcome on any valid path.

**Successor convergence (2 rows, NegateConditionals).** The mutated conditional is the inlined `any` fast path — `pc186 instanceof Collection; pc189 ifeq` — whose negation routes Collection receivers onto the plain-Iterable loop. Empty input returns `false` on both paths, and non-empty input runs the identical loop, so no observable difference exists.

Every proof is instruction-level or control-flow-level on this identity. Neighbouring G4 results were used as context only, never as a substitute.

## Phase 8 — parent accounting

| | |
| --- | --- |
| G5 parent | 52 |
| G5b TOOLING_LIMITATION | −36 |
| G5c input SURVIVED | 12 |
| G5c KILLED | 1 |
| G5c EQUIVALENT | 11 |
| G5c unresolved | 0 |
| remaining for G5d | **4 — exactly the frozen NO_COVERAGE identities** |

No widening. P1 mint remains blocked; P2 consumption remains blocked; the final full canonical campaign still runs only after G5d closes.

## Prohibited changes

No change to `mutation-baseline.json`, the population-admission ledger, the classification or enrollment ledgers, the mutation-evolution ledger, the mutator set, timeout policy, target-family semantics, mutation ceilings, or verifier/admission semantics. **No production code changed** — no production defect was discovered. The temporary measurement narrowing is evidence machinery only and was never committed to the Epic.

## Verification

- frozen parent digest unchanged; admission ledger still empty
- control and candidate campaigns: same base, same narrowing, same analyzer, `measuredCommit` equal to their throwaway commits, 918/918 identities shared
- 12/12 recovered; 12/12 mappings EXACT; 0 UNDETERMINED
- exactly one status movement, 0 KILLED regressions, 0 identity loss/gain, 0 new timeouts
- focused test class green; `spotlessCheck`, `verifyStaticAnalysis`, `verifyChangePolicy -PchangePolicyBase=42d9d9487ccf…`
- exact-head CI is the final authority

## Limits

EQUIVALENT here means: on every reachable path, the mutant and the original cannot be distinguished by any observable behaviour — proven at instruction/control-flow level. It does not claim the instruction is unreachable, and it does not claim PIT's TIMED_OUT machinery was involved (no identity in this cohort was a tooling limitation). The four NO_COVERAGE identities are untouched and are G5d's subject.
