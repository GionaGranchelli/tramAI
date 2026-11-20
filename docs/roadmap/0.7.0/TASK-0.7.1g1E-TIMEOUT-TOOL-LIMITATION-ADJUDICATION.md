# TASK-0.7.1g1E — TIMED_OUT Tool-Limitation Adjudication

Adjudication slice for the 43 M06 TIMED_OUT identities, plus the governance question g1D left open:
by what trusted mechanism can an adjudicated classification become base authority?

**Two results.** (1) All 43 identities satisfy the repository's existing `tool-limitation` evidentiary
standard, identity by identity, from bytecode evidence. (2) **No executable enrollment ceremony exists** —
the classifications that already exist in the authority were created before the ratchet that now forbids
candidate-side classification additions. Nothing was enrolled, and no production code, test, build-logic,
authority file, threshold or classification was modified.

## 1. Scope

| Item | Value |
|---|---|
| Task branch | `task/0.7.1g1e-timeout-tool-limitation-adjudication` |
| Exact base | `607e0f05ebc660f70d2ddcfbd0553b0a449d1516` (Epic tip after #435; verified equal to `origin/epic/0.7.1-control-plane-authority` before branching) |
| Working tree at start | clean (`git status --porcelain` empty) |
| Bounded question | Do the 43 M06 TIMED_OUT identities satisfy the existing exact-identity `tool-limitation` standard, and what is the trusted mechanism by which an adjudicated classification becomes base authority? |
| Verdict | `CLASSIFICATION_ENROLLMENT_CEREMONY_MISSING` |

## 2. Phase A — the standard, reconstructed from precedent

`config/quality/mutation-classifications.yml` holds 23 classification records: 17 `equivalent-mutant` and 6
`tool-limitation`. The six are the policy, so the checklist below is derived from what they actually
contain, not from what would be convenient:

| criterion | entries carrying it |
|---|---|
| exact canonical identity (`id` = SHA-256) | 6 / 6 |
| mutation point attributed to a bytecode construct (offset / block / line) | 6 / 6 |
| compiler-generated coroutine sentinel (vs source-authored guard) | 3 / 6 |
| deterministic non-termination stated | 6 / 6 |
| **isolated** or reduced reproduction | **1 / 6** |
| an independently KILLED mutant on the same construct | 6 / 6 |
| explicit "no source-level behavioural condition" | 1 / 6 |

Two facts from that matrix govern this slice:

1. **Isolated reproduction is not a universal requirement.** Only
   `id 1923c2a1…` mentions it ("reproduced isolated at 17s with only ModelRegistryEnforcerTest selected;
   not a broad-selection artifact"). The other five carry no isolation evidence. Requiring it of the 43
   would invent a rule the repository does not have.
2. **The precedent covers both kinds of non-termination**: three entries are compiler-generated
   `COROUTINE_SUSPENDED` dispatch negations, three are source-authored guards whose negation makes a loop
   non-terminating. The 43 belong to the first kind, which is the better-evidenced case.

The one criterion every entry carries is the last: proof that the classification does not conceal an
untested source-level decision. That is the criterion this slice had to settle.

## 3. Phase B — settling the concealment criterion

Every precedent proves concealment is absent by citing an independently KILLED mutant. That works when the
mutated source line also carries an authored conditional. Most of the 43 do not: in the attributed scope
the suspension check is typically the **only** conditional the compiler emits on that line.

Sibling-kill is therefore one *proof technique*, not the definition. The policy objective is:

> prove the classification does not conceal an untested source-level behavioural decision

Two acceptable proofs are used in Phase C:

- **(a) sibling kill** — the line carries an authored conditional whose mutant is independently KILLED.
- **(b) bytecode proof of absence** — the line's conditional-jump set contains exactly one instruction and
  it is the suspension check reading `COROUTINE_SUSPENDED`. There is then no authored Boolean, null or
  branch decision at that location to conceal, and the mutant's status is TIMED_OUT rather than
  NO_COVERAGE, which independently proves a covering test reached the mutated instruction (PIT reports
  NO_COVERAGE when no test reaches a mutation).

## 4. Phase C — 43 / 43 adjudicated

Verdict distribution: **43 TOOL_LIMITATION_ELIGIBLE**, 0 INSUFFICIENT_EVIDENCE, 0
NOT_TOOL_LIMITATION. Evidence form: 39 by bytecode proof of absence, 4 by sibling kill.

Shared evidence for every row: raw status TIMED_OUT in campaign A **and** campaign B, identical in both;
identity present in the committed authority and in both fresh populations; `numberOfTestsRun = 0`;
module `:tramai-engine`, mutator `NegateConditionalsMutator` / "negated conditional".

| # | class#method @line | block/index | bytecode conditional(s) on the line | evidence |
|---|---|---|---|---|
| 1 | `ApprovalResumeCoordinator#resume` @65 | 9/59 | `if_acmpne`@143 **(sentinel)** | bytecode proof of absence |
| 2 | `ApprovalResumeCoordinator#resume` @66 | 14/95 | `if_acmpne`@198 **(sentinel)** | bytecode proof of absence |
| 3 | `ApprovalResumeCoordinator#resume` @68 | 26/155 | `if_acmpne`@303 **(sentinel)** | bytecode proof of absence |
| 4 | `ApprovalResumeCoordinator#resume` @81 | 42/264 | `if_acmpne`@465 **(sentinel)** | bytecode proof of absence |
| 5 | `ApprovalResumeCoordinator#resume` @85 | 54/356 | `if_acmpne`@634 **(sentinel)** | bytecode proof of absence |
| 6 | `ApprovalResumeCoordinator#resume` @90 | 67/459 | `if_acmpne`@803 **(sentinel)** | bytecode proof of absence |
| 7 | `ApprovalResumeCoordinator#resume` @98 | 85/575 | `if_acmpne`@1003 **(sentinel)** | bytecode proof of absence |
| 8 | `ApprovalResumeCoordinator#resume` @109 | 105/704 | `if_acmpne`@1216 **(sentinel)** | bytecode proof of absence |
| 9 | `ApprovalResumeCoordinator#prepareResume` @121 | 11/68 | `if_acmpne`@147 **(sentinel)** | bytecode proof of absence |
| 10 | `ApprovalResumeCoordinator#prepareResume` @126 | 24/131 | `if_acmpne`@257 **(sentinel)**, `ifnonnull`@302 (authored) | sibling kill |
| 11 | `ApprovalResumeCoordinator#prepareResume` @131 | 39/224 | `ifnull`@347 (authored), `if_acmpne`@401 **(sentinel)** | sibling kill |
| 12 | `ApprovalResumeCoordinator#prepareResume` @138 | 53/332 | `if_acmpne`@568 **(sentinel)** | bytecode proof of absence |
| 13 | `ApprovalResumeCoordinator#prepareResume` @140 | 61/423 | `if_acmpne`@738 **(sentinel)** | bytecode proof of absence |
| 14 | `ApprovalResumeCoordinator#authorizeResume` @148 | 11/67 | `if_acmpne`@144 **(sentinel)** | bytecode proof of absence |
| 15 | `ApprovalResumeCoordinator#authorizeResume` @162 | 53/271 | `if_acmpne`@495 **(sentinel)** | sibling kill |
| 16 | `ApprovalResumeCoordinator#revealAndValidateReplayPayload` @223 | 31/170 | `if_acmpne`@321 **(sentinel)** | bytecode proof of absence |
| 17 | `ApprovalResumeCoordinator$resume$2#invokeSuspend` @86 | 5/32 | `if_acmpne`@69 **(sentinel)** | sibling kill |
| 18 | `ApprovalSuspensionCoordinator#suspendToolExecution` @145 | 11/74 | `if_acmpne`@158 **(sentinel)** | bytecode proof of absence |
| 19 | `ApprovalSuspensionCoordinator#suspendToolExecution` @152 | 35/206 | `if_acmpne`@362 **(sentinel)** | bytecode proof of absence |
| 20 | `ApprovalSuspensionCoordinator#suspendToolExecution` @165 | 60/388 | `if_acmpne`@633 **(sentinel)** | bytecode proof of absence |
| 21 | `ApprovalSuspensionCoordinator#suspendToolExecution` @218 | 109/668 | `if_acmpne`@1072 **(sentinel)** | bytecode proof of absence |
| 22 | `ApprovalSuspensionCoordinator#suspendToolExecution` @219 | 132/853 | `if_acmpne`@1420 **(sentinel)** | bytecode proof of absence |
| 23 | `ApprovalSuspensionCoordinator#suspendToolExecution` @239 | 157/1067 | `if_acmpne`@1785 **(sentinel)** | bytecode proof of absence |
| 24 | `ApprovalSuspensionCoordinator#compensateSuspension` @293 | 12/90 | `if_acmpne`@187 **(sentinel)** | bytecode proof of absence |
| 25 | `ApprovalSuspensionCoordinator#compensateSuspension` @294 | 20/164 | `if_acmpne`@330 **(sentinel)** | bytecode proof of absence |
| 26 | `ApprovalSuspensionCoordinator#compensateSuspension` @295 | 30/239 | `if_acmpne`@478 **(sentinel)** | bytecode proof of absence |
| 27 | `ApprovalSuspensionCoordinator#compensateStep` @301 | 10/63 | `if_acmpne`@120 **(sentinel)** | bytecode proof of absence |
| 28 | `ApprovalSuspensionCoordinator$compensateSuspension$2$1#invokeSuspend` @293 | 5/28 | `if_acmpne`@63 **(sentinel)** | bytecode proof of absence |
| 29 | `ApprovalSuspensionCoordinator$compensateSuspension$2$2#invokeSuspend` @294 | 4/29 | `if_acmpne`@64 **(sentinel)** | bytecode proof of absence |
| 30 | `ApprovalSuspensionCoordinator$compensateSuspension$2$3#invokeSuspend` @295 | 4/29 | `if_acmpne`@63 **(sentinel)** | bytecode proof of absence |
| 31 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @82 | 9/68 | `if_acmpne`@159 **(sentinel)** | bytecode proof of absence |
| 32 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @93 | 23/160 | `if_acmpne`@304 **(sentinel)** | bytecode proof of absence |
| 33 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @105 | 35/243 | `if_acmpne`@457 **(sentinel)** | bytecode proof of absence |
| 34 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @107 | 46/328 | `if_acmpne`@618 **(sentinel)** | bytecode proof of absence |
| 35 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @112 | 60/426 | `if_acmpne`@799 **(sentinel)** | bytecode proof of absence |
| 36 | `DefaultApprovalGateway#requestApproval-Atj0Sqo` @114 | 72/515 | `if_acmpne`@968 **(sentinel)** | bytecode proof of absence |
| 37 | `DefaultApprovalGateway#requireExistingAttributionMatches` @210 | 14/79 | `if_acmpne`@151 **(sentinel)** | bytecode proof of absence |
| 38 | `DefaultApprovalGateway#persistUngoverned` @223 | 10/61 | `if_acmpne`@130 **(sentinel)** | bytecode proof of absence |
| 39 | `DefaultApprovalGateway#persistUngoverned` @225 | 17/101 | `if_acmpne`@185 **(sentinel)** | bytecode proof of absence |
| 40 | `DefaultApprovalGateway#persistUngoverned` @230 | 25/142 | `if_acmpne`@243 **(sentinel)** | bytecode proof of absence |
| 41 | `DefaultApprovalGateway#persistGoverned` @248 | 12/74 | `if_acmpne`@145 **(sentinel)** | bytecode proof of absence |
| 42 | `DefaultApprovalGateway#persistGoverned` @253 | 22/132 | `if_acmpne`@228 **(sentinel)** | bytecode proof of absence |
| 43 | `DefaultApprovalGateway#persistGoverned` @261 | 31/181 | `if_acmpne`@306 **(sentinel)** | bytecode proof of absence |

The four sibling-kill rows are the two-conditional lines:

- `ApprovalResumeCoordinator#authorizeResume@162` — the authored conditional on this line is
  independently KILLED by `[method:claim failure propagates before executor or completion()]`, so no source branch is concealed.
- `ApprovalResumeCoordinator#prepareResume@126` — the authored conditional on this line is
  independently KILLED by `[method:missing suspended metadata yields ApprovalNotFoundException()]`, so no source branch is concealed.
- `ApprovalResumeCoordinator#prepareResume@131` — the authored conditional on this line is
  independently KILLED by `[method:nested approval cancels state and never claims or executes()]`, so no source branch is concealed.
- `ApprovalResumeCoordinator$resume$2#invokeSuspend@86` — the authored conditional on this line is
  independently KILLED by `[method:a standalone resume recovers and installs the persisted identity()]`, so no source branch is concealed.

Supporting context — how much of each class is actually killed (so no class is being blanket-exempted):

| class | mutants | KILLED | TIMED_OUT | NO_COVERAGE | SURVIVED |
|---|---|---|---|---|---|
| `ApprovalResumeCoordinator` | 150 | 36 | 28 | 63 | 23 |
| `ApprovalResumeCoordinator$resume$2` | 5 | 1 | 1 | 2 | 1 |
| `ApprovalSuspensionCoordinator` | 87 | 37 | 10 | 26 | 14 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$1` | 5 | 0 | 1 | 2 | 2 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$2` | 5 | 0 | 1 | 2 | 2 |
| `ApprovalSuspensionCoordinator$compensateSuspension$2$3` | 5 | 0 | 1 | 2 | 2 |
| `DefaultApprovalGateway` | 79 | 32 | 13 | 26 | 8 |

Full per-identity records (identity, descriptor, block/index, jumps, other mutants, checks, verdict)
were produced by `/tmp/tramai-071g1e-adjudicate.py` and are not duplicated here; the table above is
identity-addressed by class#method@line plus block/index, which together with the mutator and description
reproduce the canonical identity exactly.

## 5. A parser correction, and re-verification of g1D

While attributing the four synthetic suspend-lambda classes, the bytecode parser used in g1C/g1D was found
to miss `aload_N`-encoded local reads (the operand is in the opcode name, not an argument), so sentinel
comparisons in classes compiled that way were not attributed. The detector was corrected to accept both
`aload N` and `aload_N` forms.

g1D's partition was then **re-run unchanged** to check that this did not alter it:

```
sentinel-bearing lines 54 | sentinel comparisons 54 | TIMED_OUT 51 | NO_COVERAGE 3 | KILLED 3 | ordinary conditionals 3
partition still exact: True
```

g1D's conclusion is unaffected: the four synthetic identities were already handled separately there, and
the corrected detector merely adds their instruction-level attribution here (single `if_acmpne` per class,
mapped to lines 86 / 293 / 294 / 295).

## 6. Phase D — the enrollment boundary: no executable ceremony exists

The verifier states the intended policy in `MutationRatchetVerifier.kt:53-59`:

> Classification authority semantics: a PR may only REMOVE classifications (when the underlying mutant
> dies), never add or re-author one. New classifications are adjudicated on master during an enrollment
> ceremony and become part of the base; anything a candidate adds is either self-approval of its own
> survivor (M08) or fabrication (M09).

The question is whether that ceremony exists as tooling. It does not.

| question | finding |
|---|---|
| 1. Existing task/workflow/ceremony for base-side adjudication? | **None.** No Gradle task, workflow or script references classification enrollment. Searching the repository for `enroll` returns only unrelated enrollments (provider TCK, maintainability scanner suites, module catalog). |
| 2. Executable and tested? | **No** for classifications. The one executable mainline authority mechanism is mutation-population evolution, which is M21-only (proven in g1B) and cannot carry a classification. |
| 3. Does it preserve M08 for ordinary PRs? | M08/M09 are unconditional in the candidate direction. `AUTHORITY_EXCLUDED_IDENTITIES` (`:530`) is passed only into the M01 branch (`:88` vs its only use at `:278`), so it bypasses neither M06 nor M08/M09/M14/M15. |
| 4. Does it require direct Epic/master authority mutation? | A direct mainline commit does not help: on push, CI verifies against `github.event.before` (`.github/workflows/ci.yml:223-242`), so the same M06/M09 rules apply to the commit that would do the enrolling. |
| 5. A change class/label that changes verifier semantics? | Two exist and neither helps: the `mutation-population-evolution` label selects `-PtramaiMutationEvolution=recorded-evolution` (M21 only), and `-PchangeClass=baseline-migration` affects `verifyChangePolicy`, not verifier semantics. |
| 6. Is "adjudicated on master" tooling or governance prose? | **Prose.** The six existing `tool-limitation` records were added in `d5b19e35` (2026-09-03); `MutationRatchetVerifier.kt` first appears in `0c02ff64` (2026-09-05). The precedent predates the rule that now forbids creating it. |

So the state is: a **correct policy with no way to exercise it**. M06 is doing its job — it prevents a
candidate from deciding for itself that a new NON_KILLED result is acceptable — but the complementary
operation it presupposes (a trusted transition from unresolved NON_KILLED to base-adjudicated NON_KILLED)
has no implementation.

### 6.1 What the missing ceremony must satisfy

Any future ceremony has to demonstrate two discriminators, not one:

1. an identity adjudicated and enrolled on the base side is accepted when a later candidate retains it
   byte-identically (the M03 path);
2. a **fresh, unrelated** candidate classification still fails M08 — approved-from-base must stay allowed
   while candidate-self-approval stays rejected.

Without (2), fixing the gap would simply reopen the hole M08 closes.

## 7. What was deliberately not done

- No classification records were written, in either file or draft form.
- No enrollment was attempted through `mutation-classifications.yml`, `mutation-baseline.json`,
  `mutation-evolution.yml`, a label, a change class, or `AUTHORITY_EXCLUDED_IDENTITIES`.
- M06, M08, M09, the raw-status mapping, canonical outcomes, the mutator set, timeouts and target families
  are untouched. `baseline-migration` was not used: it is reserved for analyzer schema/identity/
  cardinality changes and this is none of those.
- No test was changed and no mutant was killed, removed or exempted.
- No fresh PIT campaign was run; the adjudication uses the raw reports and both populations already
  custody-proved in g1C/g1D.

## 8. Remaining M06 debt

| Component | Count | Status after this slice |
|---|---|---|
| TIMED_OUT | 43 | **adjudicated eligible for `tool-limitation`; NOT enrolled — no mechanism exists** |
| SURVIVED | 36 | untouched |
| NO_COVERAGE | 79 | untouched |
| **total** | **158** | **unchanged** |

Adjudicated is not enrolled, and enrolled would not be killed. The identities remain NON_KILLED and remain
M06 failures until a trusted base-side enrollment exists.

## 9. Non-claims and limitations

- The 43 are eligible **by the current precedent**, which is a governance artifact that may be tightened;
  this slice did not vote on the standard, it applied it.
- Eligibility is not approval. Nothing here asserts that these classifications *should* be enrolled, only
  that they meet the evidence standard the repository already applies to the same mechanism elsewhere.
- Instruction attribution for the two two-conditional lines (126, 131) is by status/behaviour partition,
  not by a `block`/`index` → bytecode-offset mapping, which remains unestablished. Those rows carry the
  strongest concealment evidence (a named killing test) and the weakest attribution evidence.
- The 4 synthetic-class rows rest on a single `if_acmpne` per class plus the class containing exactly one
  `COROUTINE_SUSPENDED` reference; their `invokeSuspend` line tables are small (5 entries).
- Coverage for the bytecode-proof rows is inferred from TIMED_OUT ≠ NO_COVERAGE, not from a named covering
  test list (PIT records `killingTest` only for killed mutants).
- Eligibility was assessed only for the 43; the 3 NO_COVERAGE sentinel comparisons found in g1D and the 36
  SURVIVED / 79 NO_COVERAGE identities were not adjudicated.

## 10. Commands run

```
git fetch origin && git rev-parse origin/epic/0.7.1-control-plane-authority   # == 607e0f05...
git status --porcelain                                                       # empty
git checkout -B task/0.7.1g1e-timeout-tool-limitation-adjudication 607e0f05ebc660f70d2ddcfbd0553b0a449d1516
python3 /tmp/tramai-071g1e-adjudicate.py   # per-identity bytecode attribution + verdicts
python3 /tmp/tramai-071g1d-linejoin.py     # g1D partition re-verified with the corrected detector
# javap -p -c -l -classpath tramai-engine/build/classes/kotlin/main <7 classes>
```

## 11. Working-tree proof

### 11.1 Before the task commit

```
$ git rev-parse HEAD
607e0f05ebc660f70d2ddcfbd0553b0a449d1516          # the base, unmoved
$ git status --porcelain                           # empty at task start
```

### 11.2 After the task commit

```
$ git rev-parse HEAD^
607e0f05ebc660f70d2ddcfbd0553b0a449d1516          # the task commit's parent is the base
$ git diff --name-only HEAD^ HEAD
docs/roadmap/0.7.0/TASK-0.7.1g1E-TIMEOUT-TOOL-LIMITATION-ADJUDICATION.md
$ git diff --name-only 607e0f05 HEAD | wc -l
1                                                  # exactly one file changed relative to base
$ git status --porcelain                           # empty
```

## 12. Outcome

```
CLASSIFICATION_ENROLLMENT_CEREMONY_MISSING
43 / 43 TIMED_OUT IDENTITIES ADJUDICATED ELIGIBLE — NOTHING ENROLLED
```

The 43 are evidentially ready for `tool-limitation` under the repository's own existing standard, and the
enrollment boundary has no implementation: the six classifications that exist were created two days before
the ratchet that forbids creating them. The next slice should therefore build the ceremony — with the two
discriminators in §6.1 — rather than touching the authority. Until then the debt stays at 158 and M06
keeps doing exactly what it was built to do.
