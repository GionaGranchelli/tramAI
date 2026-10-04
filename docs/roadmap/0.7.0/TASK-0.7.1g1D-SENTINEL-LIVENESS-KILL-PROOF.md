# TASK-0.7.1g1D — Scoped Sentinel Liveness Kill Proof

Investigation of the g1C control group: the three `NegateConditionals` mutants that appeared to show a
coroutine-sentinel mutation being **KILLED** while 51 structurally similar ones time out.

**Outcome: the controls are not sentinel mutations at all.** They negate ordinary null-check conditionals
that happen to share a source line with a suspension-sentinel comparison. By the g1D stop conditions, the
diagnostic probe (Phase A), the faithfulness proof (Phase C) and the liveness experiment (Phase D) were
**not** performed — see §6 — and no production code, test, build-logic, authority, threshold or
classification was modified. This document is the only artifact.

## 1. Scope

| Item | Value |
|---|---|
| Task branch | `task/0.7.1g1d-sentinel-liveness-kill-proof` |
| Exact base | `5a71dca293b10be0a3208dacf166588ffc738f2a` (Epic tip; 40 characters, verified equal to `origin/epic/0.7.1-control-plane-authority` before branching) |
| Working tree at start | clean (`git status --porcelain` empty) |
| Bounded question | Why are three attributed coroutine-sentinel mutants KILLED while 51 are TIMED_OUT, and is that mechanism transferable? |
| Verdict | `CONTROL_MECHANISM_NOT_TRANSFERABLE` |

### 1.1 Evidence custody

This slice reuses the raw PIT reports and populations measured for g1A and analysed in g1C: 14
`mutations.xml` files (`/tmp/tramai-071g1a-pit-A`), campaign A and B populations, and the g1C Phase B
bytecode attribution. The range `0f36ab87 → 5a71dca2` changes only documentation and the three
build-logic/docs + orchestration-test files already custody-proved in g1C §1.1; no mutation-target input
changed, so no fresh campaign was warranted.

## 2. Phase B — the three KILLED records, reconstructed exactly

Root cause of the g1C control group: g1C attributed sentinel sites at **(class, method, line)**
granularity and then reported statuses at that granularity. A source line can carry **both** the
compiler-generated suspension check **and** ordinary conditionals from the same expression. Counting
mutant statuses per line therefore mixes two different populations.

The three KILLED records, with full evidence:

### 2.1 `ApprovalResumeCoordinator#prepareResume` @ line 126

| Field | Value |
|---|---|
| canonical identity | `84a24307a542aedc17fc0d87a91bebac6f685daa8bad081160b9c962af7be339` |
| family / module | tramai-engine / `:tramai-engine` |
| class | `dev.tramai.engine.approval.ApprovalResumeCoordinator` |
| method + descriptor | `prepareResume(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| mutator | `NegateConditionalsMutator` — negated conditional |
| source | `ApprovalResumeCoordinator.kt:126` |
| block / index | 28 / 160 |
| raw PIT status | **KILLED** |
| numberOfTestsRun | 1 |
| killing test | `[method:missing suspended metadata yields ApprovalNotFoundException()]` |

### 2.2 `ApprovalResumeCoordinator#prepareResume` @ line 131

| Field | Value |
|---|---|
| canonical identity | `01063c9a48c58a603826c50ad15171a04fa47d94c9682c45936534e77fbde1ad` |
| family / module | tramai-engine / `:tramai-engine` |
| class | `dev.tramai.engine.approval.ApprovalResumeCoordinator` |
| method + descriptor | `prepareResume(Ldev/tramai/engine/ResumeApprovalCommand;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| mutator | `NegateConditionalsMutator` — negated conditional |
| source | `ApprovalResumeCoordinator.kt:131` |
| block / index | 35 / 196 |
| raw PIT status | **KILLED** |
| numberOfTestsRun | 1 |
| killing test | `[method:nested approval cancels state and never claims or executes()]` |

### 2.3 `ApprovalResumeCoordinator#revealAndValidateReplayPayload` @ line 218

| Field | Value |
|---|---|
| canonical identity | `0b1dfa3878dfeb481301c9688453981c30a605bf6918c983cb1ef44320bee5e7` |
| family / module | tramai-engine / `:tramai-engine` |
| class | `dev.tramai.engine.approval.ApprovalResumeCoordinator` |
| method + descriptor | `revealAndValidateReplayPayload(Ldev/tramai/engine/approval/ResumeUncertainOutcome;Ldev/tramai/engine/ResumeApprovalCommand;Ldev/tramai/engine/SuspendedInvocationMetadata;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| mutator | `NegateConditionalsMutator` — negated conditional |
| source | `ApprovalResumeCoordinator.kt:218` |
| block / index | 14 / 94 |
| raw PIT status | **KILLED** |
| numberOfTestsRun | 1 |
| killing test | `[method:missing replay envelope leaves continuation claimed and uncompleted()]` |

## 3. Why they are killed: they are ordinary conditionals, not suspension checks

Three independent lines of evidence agree.

### 3.1 Bytecode opcodes on the same lines

`javap -p -c -l` over `dev.tramai.engine.approval.ApprovalResumeCoordinator`, attributing each conditional
jump through its `LineNumberTable` (offset → source line):

| line | conditional jumps on that line |
|---|---|
| 126 | `if_acmpne` @257 (**sentinel**); `ifnonnull` @302 (ordinary) |
| 131 | `ifnull` @347 (ordinary); `if_acmpne` @401 (**sentinel**) |
| 218 | `if_acmpne` @143 (**sentinel**); `ifnonnull` @187 (ordinary) |

Every control line holds exactly one `if_acmpne` — the coroutine suspension sentinel — and exactly one
ordinary null-check conditional. The KILLED mutation is the null check; the sentinel's mutation on the
same line is TIMED_OUT.

### 3.2 The status counting is exact — 54 of 54 sentinel lines

For every line that carries a sentinel comparison, the mutant status counts partition exactly along the
two instruction classes:

| quantity | value |
|---|---|
| sentinel-bearing lines | 54 |
| sentinel comparisons on them (from bytecode) | 54 |
| mutants with status TIMED_OUT on those lines | 51 |
| mutants with status NO_COVERAGE on those lines | 3 |
| mutants with status KILLED on those lines | 3 |
| ordinary conditional jumps on those lines | 3 |

`TIMED_OUT + NO_COVERAGE = 54 = 54`, exactly the number of sentinel comparisons, and
`KILLED = 3 = 3`, exactly the number of ordinary conditionals. The partition is forced:
**every sentinel negation hangs or is uncovered, and every kill on those lines belongs to an ordinary
conditional.**

### 3.3 The killing tests assert on the null-check paths

| control | killing test | what it asserts |
|---|---|---|
| `prepareResume` @126 | `missing suspended metadata yields ApprovalNotFoundException()` | absence of suspended metadata → the `ifnonnull` guard produces an exception |
| `prepareResume` @131 | `nested approval cancels state and never claims or executes()` | nested-state `ifnull` branch → observable state assertion |
| `revealAndValidateReplayPayload` @218 | `missing replay envelope leaves continuation claimed and uncompleted()` | absent envelope → the `ifnonnull` guard path |

Each killing test observes a **value or exception produced before or instead of** suspension. None of them
demonstrates that a broken suspension protocol can be detected — which is the only thing that would have
made them a control group for bounded liveness.

## 4. The corrected population picture

Within the attributed scope (the three classes plus four synthetic suspend-lambda classes):

| outcome of the single `NegateConditionals` mutant on each sentinel comparison | count |
|---|---|
| TIMED_OUT | 51 |
| NO_COVERAGE | 3 |
| KILLED | **0** |
| **total sentinel comparisons** | **54** |

Every sentinel comparison in scope carries exactly one `NegateConditionals` mutant, and **not one of them
is killed**. There is therefore no observed instance anywhere in this scope of a test detecting a broken
coroutine suspension check.

## 5. Corrections to the g1C record

The merged g1C document is still correct on its central finding — the 43 in-scope TIMED_OUT identities are
all suspension-sentinel negations, deterministic, and not explained by timeout budget. Two of its
statements were built on the line-level control group and are **superseded by this slice**:

| g1C statement | corrected by this slice |
|---|---|
| §3.4: "The three KILLED sentinel-site mutants do matter, though — detection is not impossible in principle, which is why §5 keeps 'bound the test' alive as a hypothesis to be measured" | The three are ordinary null-check mutations. They are not evidence that a sentinel negation can be detected, so "detection is not impossible in principle" loses its only support. |
| §5.1 step 2: "Start from the three KILLED sentinel-site mutants, not from a timeout. They are the control group" | There is no control group. That step is void; the bounded-liveness hypothesis now rests on zero positive evidence. |

The corrected reading is narrower and less comfortable: 51 of 54 sentinel comparisons hang and 3 are
uncovered. Nothing in the existing evidence shows a suspension-check negation being detected, so **test
strength alone has no demonstrated route to closing the 43.**

## 6. Why Phases A, C and D were not performed

The task's own stop conditions trigger here:

> Stop and report instead of broadening scope if … the KILLED controls are killed for unrelated/non-transferable reasons

and Phase D is explicitly conditional:

> Only if Phase B identifies a credible transferable mechanism

Phase A's diagnostic probe exists to run the Phase C/D experiment. That experiment has no hypothesis left
to test — the control group dissolved into ordinary mutations — so building the probe now would add a
mutation-measurement capability with nothing to measure, in `build-logic`, on a task whose premise did not
survive its own investigation. The stop-and-report condition is the correct exit, and the task's Socratic
clause anticipated exactly this outcome.

Deliberately **not** done: no per-class diagnostic path, no `diagnosticMutationProbe`, no test change, no
attempt to manufacture a timeout that turns PIT green, no M06 or authority change.

### 6.1 What would unblock a future slice

1. **A real control**: any suspension-sentinel negation that is genuinely KILLED. Finding one bounds the
   search — e.g. sweeping the remaining mutation population for a suspend call site whose covering test
   fails for a liveness reason. Without one, bounded liveness is unfalsifiable from inside this repo.
2. **The representation question is not open, and this document was wrong to imply otherwise.** An earlier
   draft of §6.1 said a representation for these identities would be "impossible without weakening M06".
   That is **false**, and the repository itself disproves it: `config/quality/mutation-classifications.yml`
   already carries six `tool-limitation` classifications, one of which —
   `id 1923c2a12c991940f93bbc4a17c54ce268f8299ccc49c57efb6bee7d1ff65914` — records **exactly this
   mechanism**: "PIT negates the compiler-generated COROUTINE_SUSPENDED state-machine dispatch comparison
   (bytecode offset 140 if_acmpne, block 12, reported line 22) … Negation prevents normal continuation
   resumption, deterministically deadlocking the awaiting test and reaching PIT's timeout (reproduced
   isolated at 17s with only ModelRegistryEnforcerTest selected; not a broad-selection artifact)", together
   with proof that the source-semantic conditional on the same line is independently KILLED.
   That entry requires **no** change to raw `TIMED_OUT`, to canonical `NON_KILLED`, or to M06. The three
   layers stay as they are — measurement (`rawStatus = TIMED_OUT`), canonical outcome
   (`outcome = NON_KILLED`, read literally as "PIT did not kill this mutant"), and authority disposition
   (`classification = tool-limitation`) — and an exact-identity, base-authoritative classification is what
   makes such an outcome acceptable.
   What remains open is therefore **adjudication, not representation**: whether these 43 identities satisfy
   that existing base-authoritative standard, which is a governance task for a later slice and explicitly
   not something g1D can assert.

## 7. Verdict

```
CONTROL_MECHANISM_NOT_TRANSFERABLE
```

The three KILLED records are ordinary `ifnull`/`ifnonnull` negations on lines that also carry a
suspension-sentinel comparison. They were killed by named tests asserting on exception and state paths,
they never exercised a permanently-suspended coroutine, and they carry no mechanism that transfers to a
hanging sentinel negation. Phase C's requirement — reproduce a KILLED control and a TIMED_OUT identity
under a scoped probe — cannot be satisfied for the control half, because no sentinel control exists.

## 8. Remaining M06 debt

| Component | Count | Status after this slice |
|---|---|---|
| TIMED_OUT | 43 | explained by g1C, control group disproved here; still NON_KILLED |
| SURVIVED | 36 | untouched |
| NO_COVERAGE | 79 | untouched |
| **total** | **158** | **unchanged** |

No mutant was killed, classified, exempted or removed. M06 is untouched and authority evolution remains
blocked.

## 9. Non-claims and limitations

- No production code, test, build-logic, workflow, threshold, family, baseline, evolution record or
  classification was modified. The only artifact is this document.
- The attribution covers the seven classes inspected in g1C. "No sentinel negation is killed" is a
  statement about those 54 comparisons, not about the whole population; sentinel sites in uninspected
  classes were not enumerated.
- The opcode attribution depends on `LineNumberTable` mapping and on the parser treating one mutation per
  conditional jump; that assumption holds on all sentinel lines here (54/54), and this document does not
  claim the mapping is exact at the `block`/`index` level, which was never established.
- Three non-sentinel lines in the data show more mutants than conditional jumps found by the parser
  (`ApprovalSuspensionCoordinator#validateInitialApprovalRequirement` @133 and @138,
  `#validateRenewedApprovalRequirement` @117: two mutants, one jump). Those lines carry no sentinel and do
  not affect §3.2, but the discrepancy is recorded rather than hidden.
- No fresh PIT campaign was run, and no diagnostic probe exists, so no mutant status was re-measured in
  this slice.

## 10. Commands run

```
git fetch origin && git rev-parse origin/epic/0.7.1-control-plane-authority    # == 5a71dca2...
git status --porcelain                                                       # empty
git checkout -B task/0.7.1g1d-sentinel-liveness-kill-proof 5a71dca293b10be0a3208dacf166588ffc738f2a
python3 /tmp/tramai-071g1d-controls.py     # exact KILLED records + killing tests from raw PIT XML
python3 /tmp/tramai-071g1d-linejoin.py     # per-line bytecode conditionals vs per-line mutant statuses
# javap -p -c -l -classpath tramai-engine/build/classes/kotlin/main dev.tramai.engine.approval.ApprovalResumeCoordinator
```

## 11. Working-tree proof

### 11.1 Before the task commit

```
$ git rev-parse HEAD
5a71dca293b10be0a3208dacf166588ffc738f2a          # the base, unmoved
$ git status --porcelain                           # empty at task start
```

### 11.2 After the task commit

```
$ git rev-parse HEAD^
5a71dca293b10be0a3208dacf166588ffc738f2a          # the task commit's parent is the base
$ git diff --name-only HEAD^ HEAD
docs/roadmap/0.7.0/TASK-0.7.1g1D-SENTINEL-LIVENESS-KILL-PROOF.md
$ git diff --name-only 5a71dca2 HEAD | wc -l
1                                                  # exactly one file changed relative to base
$ git status --porcelain                           # empty
```

As in g1C §9.2, the head SHA is deliberately not hardcoded: it is the SHA of the commit containing this
paragraph. The checkable invariants are `HEAD^ == base`, one changed file, clean status.

## 12. Outcome

```
CONTROL_MECHANISM_NOT_TRANSFERABLE — 0 of 54 SENTINEL NEGATIONS ARE KILLED
```

The control group g1C handed to g1D does not exist: those three kills are ordinary null-check mutations
sharing a line with a suspension check. The bounded-liveness approach therefore has no positive evidence
behind it, and g1D stopped at Phase B rather than building a probe and a test to chase it. The 43 M06
TIMED_OUT identities remain unexplained in the only sense that matters — nothing in the suite demonstrably
detects a broken suspension protocol.

That does not make the debt unrepresentable. The authority already has an exact-identity,
base-authoritative way to record a deterministic PIT timeout on a compiler-generated suspension check as
`tool-limitation` without changing raw `TIMED_OUT`, canonical `NON_KILLED` or M06 (§6.1). So the open
question is **adjudication**: whether these 43 identities satisfy that existing standard, established
identity by identity rather than inferred in bulk.
