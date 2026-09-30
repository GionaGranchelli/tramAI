# TASK-0.7.1g1G5d — Exact NO_COVERAGE reachability closure

Parent: **TASK-0.7.1g1G5**. Scope: exactly the **four frozen NO_COVERAGE identities**. No widening, no substitution, no production change.
Base: **`cd46f176b20ef7e0e0b2b0e251569214dfc90619`** — the squash merge of #463, branched directly from it, clean tree.

**G5D COMPLETE — FINAL G5 NO_COVERAGE COHORT CLOSED.** (subject to the regression campaign recorded below)

## Baseline and start gate

| condition | result |
| --- | --- |
| HEAD == `cd46f176b20e…` | yes, worktree branched directly from that SHA |
| clean working tree | yes |
| four canonical identities exist | 4/4 |
| all four still NO_COVERAGE | 4/4, `numberOfTestsRun = 0` |
| baseline-only target / substituted target | none |
| source-coordinate approximation as the same mutant | none |

The authoritative view is the G5c candidate campaign (`7acbd868`), whose production+test content is identical to the merged `cd46f176` (the merge squashed exactly that test plus docs). In it all four are present and NO_COVERAGE.

**Mutator naming:** the task brief labels the two "NRV" identities `NonReturningVoidMethodCall`. The frozen manifest records `org.pitest.mutationtest.engine.gregor.mutators.returns.NullReturnValsMutator`, description *"replaced return value with null"*. The frozen identities are authoritative and unchanged — a label discrepancy, not an identity discrepancy.

## Identity table

| identity | owner | mutator | PIT block/index | PC | mutated instruction | state |
| --- | --- | --- | --- | --- | --- | --- |
| `564517b2fdab` | ApprovalSuspensionCoordinator.suspendToolExecution | VoidMethodCallMutator | 13/96 | **pc194** | invokestatic kotlin/ResultKt.throwOnFailure | NO_COVERAGE → **UNREACHABLE** |
| `66c311cf4f0a` | ApprovalSuspensionCoordinator.suspendToolExecution | NullReturnValsMutator | 12/78 | **pc163** | areturn (aload 20 — the COROUTINE_SUSPENDED exit) | NO_COVERAGE → **UNREACHABLE** |
| `5a7edac3a982` | DefaultApprovalGateway.requestApproval-Atj0Sqo | VoidMethodCallMutator | 11/92 | **pc204** | invokestatic kotlin/ResultKt.throwOnFailure | NO_COVERAGE → **UNREACHABLE** |
| `e52f1c1f6570` | DefaultApprovalGateway.requestApproval-Atj0Sqo | NullReturnValsMutator | 10/72 | **pc164** | areturn (aload 12 — the COROUTINE_SUSPENDED exit) | NO_COVERAGE → **UNREACHABLE** |

## Phase A — bytecode-exact mapping

PIT never reports bytecode offsets, and its block/index counter includes ASM node kinds that `javap` does not expose — a label-and-line-count reconstruction of that counter matched only 4 of 36 calibration identities, so it was abandoned rather than tuned until it fit.

Instead each identity is bracketed. `pitBlock` is **monotone in pc within a method** (verified true for both methods), and each method carries in-method calibration points from the committed G5b/G5c manifests with both a known pc and known PIT coordinates:

- `suspendToolExecution`: (158, 11, 74) · (287, 31, 168) · (362, 35, 206) · (1072, 109, 668) · (1420, 132, 853) · (1785, 157, 1067)
- `requestApproval-Atj0Sqo`: (159, 9, 68) · (304, 23, 160) · (457, 35, 243) · (618, 46, 328) · (799, 60, 426)

Each target's block therefore brackets a pc window, and exactly one candidate instruction of the mutated kind lies inside:

| identity | window | candidates in window | selected |
| --- | --- | --- | --- |
| `564517b2fdab` (block 13) | pc158 … pc287 | `throwOnFailure` at 194 | **pc194** |
| `66c311cf4f0a` (block 12) | pc158 … pc287 | `areturn` at 163 | **pc163** |
| `5a7edac3a982` (block 11) | pc159 … pc304 | `throwOnFailure` at 204 | **pc204** |
| `e52f1c1f6570` (block 10) | pc159 … pc304 | `areturn` at 164 | **pc164** |

Both `areturn` sites are preceded by a load of a local holding `COROUTINE_SUSPENDED` — they are the suspension exits; both `throwOnFailure` sites are the resumed-path unwraps of the same call, one block later. The mapping is additional to, and independent of, the source line, which is useless here: **line 142 is the function header of `suspendToolExecution` and line 73 the header of `requestApproval`**, so every state-machine instruction inherits it.

## Reachability evidence — all four UNREACHABLE

Each mutated instruction is on the suspension protocol of one specific call: `resolveGovernedSuspension()` (source line 145) in `suspendToolExecution`, and `resolveGovernedIdentity(workflowRunId)` (source line 82) in `requestApproval`.

**Neither callee can suspend.**

- `resolveGovernedSuspension`: 50 instructions, **0 `getCOROUTINE_SUSPENDED` loads, 0 `if_acmpne`** — no sentinel comparison at all.
- `resolveGovernedIdentity-jFE5hGw`: 32 instructions, **0 `getCOROUTINE_SUSPENDED`, 0 `if_acmpne`**; its invokes are only `Continuation.getContext`, `GovernedRunScope$Key` accessors, `GovernedRunIdentity`/`RunId` getters, `Intrinsics.areEqual`, `makeConcatWithConstants` and an exception constructor.
- `GovernedRunScope.resolve(context)` is an ordinary **non-suspend** function, and `currentCoroutineContext()`/`Continuation.getContext` do not suspend.

A suspend function can return `COROUTINE_SUSPENDED` only through the sentinel comparison emitted after a call. With no such comparison and no suspending callee, both resolvers always return their value directly. Therefore:

1. in each caller the sentinel test is always false, so the `COROUTINE_SUSPENDED` exit (pc163, pc164) **cannot execute** — the NullReturnVals identity;
2. the resumed continuation for that call can only be constructed by a real suspension at that call, so the resumed-path `throwOnFailure` (pc194, pc204) **cannot execute** — the VoidMethodCall identity;
3. nothing else reaches them: **no branch or switch entry in either method targets pc194 or pc204**.

That is control-flow dominance plus callee analysis, not "I could not find a test". No legal production execution reaches any of the four.

**Why not TOOLING_LIMITATION:** there is no independent evidence that the instruction executes while PIT fails to attribute coverage. The instructions cannot execute at all.
**Why not EQUIVALENT:** equivalence answers a different question, and these mutants never cross the reachability boundary. It is neither claimed nor needed.

## Test evidence

**No tests were added, and that is the correct outcome.** Tests are permitted only for identities proven REACHABLE. Making these instructions execute would require either changing production semantics (forbidden) or fabricating a coroutine state the runtime cannot produce. A test whose only purpose would be to move PIT's coverage counter is exactly what this task forbids.

## Phase E — pair analysis

| pair | same compiler-generated construct | same instruction | one dominates the other |
| --- | --- | --- | --- |
| `564517b2fdab` / `66c311cf4f0a` (`suspendToolExecution`) | **YES** — block 12 is the `COROUTINE_SUSPENDED` exit, block 13 the resumed-path `throwOnFailure`, one block apart | NO | NO |
| `5a7edac3a982` / `e52f1c1f6570` (`requestApproval-Atj0Sqo`) | **YES** — block 10 the exit, block 11 the resumed unwrap | NO | NO |

Both pairs attach to a suspend-typed resolver with no suspension point, so they share one root cause across both methods. Whether one legitimate test would cover both is moot: neither member is reachable. Block/index adjacency was not treated as evidence of equivalence.

## Final classification

| state | count |
| --- | --- |
| KILLED | 0 |
| EQUIVALENT | 0 |
| **UNREACHABLE** | **4** |
| TOOLING_LIMITATION | 0 |
| UNDETERMINED | **0** |

Parent accounting: 52 = 36 (G5b TOOLING_LIMITATION) + 12 (G5c: 1 KILLED, 11 EQUIVALENT) + 4 (G5d UNREACHABLE). **G5 remainder: 0.**

## Diff scope

Tests: none. Production: none. Documentation: this report and the four-identity manifest. The measurement narrowing is evidence machinery, never committed.

## Regression gate

The identity-exact campaign runs from a fresh worktree at `cd46f176b20e` with the proven family-only narrowing (53 deletions, `approval` block byte-identical). Results are appended to this report and to the manifest when it completes: 4/4 accounted, 0 identity loss, 0 gain/substitution, 0 new timeouts, 0 regressions of previously KILLED identities, no widening.

## Limits

`UNREACHABLE` here rests on the callees being unable to suspend (no sentinel comparison, no suspending callee) plus the absence of any other branch into the resumed block. It is a statement about this compiled artifact at this base: if either resolver ever gains a suspension point — or if a call site acquires a genuine suspension — these instructions become live and these identities must be re-adjudicated rather than inherited.
