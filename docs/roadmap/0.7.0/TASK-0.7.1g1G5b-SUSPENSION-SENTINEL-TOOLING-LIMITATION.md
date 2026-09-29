# TASK-0.7.1g1G5b — Suspension-sentinel tooling-limitation adjudication

Parent: **TASK-0.7.1g1G5** — Final fresh-only residual adjudication.
Scope: the **36 TIMED_OUT identities of G5A-M01, and nothing else**. Every other unresolved identity of the frozen 52 is untouched.
Base: **`aa0cc52cc30a9ec53467712b85e2343b780cafca`** — the squash merge of #461 (`doc(0.7.1g1G5a)`), verified as the `origin/epic/0.7.1-control-plane-authority` tip at start.
Frozen parent cohort digest, unchanged: `92a9d06d5a8e43c5fb659f9d65bdd2e73d6ed5f45c1a8536ac70781b03503584`.
G5A-M01 cohort digest: `cda7b483580008f967879925a87ff8a2892cc83667c6720b0e973241cf384f92`.

**Zero production, test, baseline, admission, classification, mutator, timeout or ceiling changes.** The temporary measurement narrowing never landed.

## 1. Start gate

All seven conditions held before any measurement: clean tree (#461 merged); the G5a manifest present at the tip; the frozen-52 digest recomputed equal; G5A-M01 exactly 36 members, every one `mappingConfidence = EXACT`, `mechanism.kind = SUSPENSION_SENTINEL`, frozen status `TIMED_OUT`, `numberOfTestsRun = 0`; and the admission ledger still `schemaVersion: "1"` / `admissions: []`.

## 2. Phase 1 — independent second measurement

| item | value |
| --- | --- |
| base | `aa0cc52cc30a9ec53467712b85e2343b780cafca` |
| throwaway measurement commit | `2341829c35d81e6269ac6d92c87a62a5d312da96` |
| config diff (the whole of it) | family-list narrowing only: 53 deletions in config/quality/test-quality.yml, the approval family block untouched |
| `measuredCommit` in the generated artifact | `2341829c35d81e6269ac6d92c87a62a5d312da96` — equals the throwaway commit |
| result | BUILD SUCCESSFUL in 1014 s |
| analyzer | plugin 1.19.0, engine 1.22.1, 11 mutators in declaration order, timeoutConst 4000, timeoutFactor 1.25 — identical to the discovery campaign |
| raw reports | `[('approval/tramai-engine', '068c0ddc3857c9d313cc322e34fd5fefed6f68e78ba110bd45ab2d907c388ac0'), ('approval/tramai-security', '567bbb4eedc544ef07146f14105c047e828068c559bf43a4641195aae7610b64')]` |

Family census, not a 36-row extract: **918 approval mutants** = `:tramai-engine` 492 + `:tramai-security` 426 → KILLED 661, SURVIVED 133, NO_COVERAGE 62, TIMED_OUT 62. Both report layers agree with the canonical generated population on every status.

The default narrowing script in the workspace was rejected before use: it narrows at *class* level and drops `:tramai-security`, which would have excluded `DefaultApprovalGateway` and `ApprovalRunAttributionKt` — two of the four owners of the 36 — so the reconciliation would have failed while looking like a provenance defect. Three earlier measurement artifacts were rejected rather than reconciled: a partial engine report (342 of 492, mid-write), a malformed security report (`Content is not allowed in trailing section`, caused by two PIT runs sharing one report root — a launch error on my side, not sandbox reaping), and a copied `mutation-baseline.json` that was byte-identical to the committed baseline and therefore never a campaign-2 artifact.

## 3. Phase 2 — identity-exact reproducibility

| requirement | result |
| --- | --- |
| input frozen identities | 36 |
| recovered | 36 |
| missing | 0 |
| duplicates | 0 |
| TIMED_OUT → TIMED_OUT | 36 |
| status movement | 0 |

Joined by the full canonical mutation identity, with identity equality asserted per member against the generated population and the status independently confirmed in the raw report. No member changed status, so the cohort partitions nothing out.

## 4. Phase 3 — mechanism re-proved against base bytecode

Bytecode custody carries over with proof: `19703cfe..aa0cc52c` touches **0 production files**, and the base compile was already shown identical to the compile the discovery campaign measured. Per member:

| identity | owner | line | pc | opcode | case | child suspend call | campaign 1 | campaign 2 | concealment |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `f571f62614e6` | ApprovalResumeCoordinator.authorizeResume | 148 | 144 | `if_acmpne` | None | `Method dev/tramai/engine/approval/ReplayAuthorizatio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `5d7006107262` | ApprovalResumeCoordinator.executeClaimedResume | 183 | 308 | `if_acmpne` | None | `Method "validateClaimedResumeArguments-4uOHQ0s":(Lde` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `f23b4d0921a0` | ApprovalResumeCoordinator.executeClaimedResume | 196 | 567 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/approval/ClaimedRe` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `6cadd3397125` | ApprovalResumeCoordinator.executeClaimedResume | 208 | 804 | `if_acmpne` | None | `Method completeClaimedResume:(Ldev/tramai/engine/Res` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `9e3d60d7a855` | ApprovalResumeCoordinator.prepareResume | 121 | 147 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalCon` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `c3509c6487f8` | ApprovalResumeCoordinator.prepareResume | 138 | 568 | `if_acmpne` | None | `Method dev/tramai/engine/approval/ContinuationClaimS` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `a25dbb8c0826` | ApprovalResumeCoordinator.prepareResume | 140 | 738 | `if_acmpne` | None | `Method dev/tramai/engine/approval/ReplayAuthorizatio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `4865b74caf50` | ApprovalResumeCoordinator.resume | 65 | 143 | `if_acmpne` | None | `Method prepareResume:(Ldev/tramai/engine/ResumeAppro` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `611bdacfe9f5` | ApprovalResumeCoordinator.resume | 66 | 198 | `if_acmpne` | None | `Method authorizeResume:(Ldev/tramai/engine/ResumeApp` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `9b9bfadffcdc` | ApprovalResumeCoordinator.resume | 81 | 465 | `if_acmpne` | None | `Method executeClaimedResume:(Ldev/tramai/engine/appr` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `252596080a3a` | ApprovalResumeCoordinator.resume | 85 | 634 | `if_acmpne` | None | `Method kotlinx/coroutines/BuildersKt.withContext:(Lk` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `827431d2f104` | ApprovalResumeCoordinator.resume | 90 | 803 | `if_acmpne` | None | `Method emitResumeUncertainOutcomeOnce:(Ldev/tramai/e` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `10512d6c6e00` | ApprovalResumeCoordinator.revealAndValidateReplayPayload | 223 | 321 | `if_acmpne` | None | `Method emitResumeUncertainOutcomeOnce:(Ldev/tramai/e` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `09ea31d4d0e8` | ApprovalResumeCoordinator$resume$2.invokeSuspend | 86 | 69 | `if_acmpne` | None | `Method dev/tramai/engine/approval/ApprovalResumeCoor` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `2b197f5cd3cb` | ApprovalSuspensionCoordinator.compensateStep | 301 | 120 | `if_acmpne` | None | `InterfaceMethod kotlin/jvm/functions/Function1.invok` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `8610dc7e970e` | ApprovalSuspensionCoordinator.compensateSuspension | 293 | 187 | `if_acmpne` | None | `Method compensateStep:(Lkotlin/jvm/functions/Functio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `7f06a51c7951` | ApprovalSuspensionCoordinator.compensateSuspension | 295 | 478 | `if_acmpne` | None | `Method compensateStep:(Lkotlin/jvm/functions/Functio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `6e83174c379e` | ApprovalSuspensionCoordinator.suspendToolExecution | 145 | 158 | `if_acmpne` | None | `Method resolveGovernedSuspension:(Lkotlin/coroutines` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `c3a3a1cf672f` | ApprovalSuspensionCoordinator.suspendToolExecution | 152 | 362 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalGat` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `43dd0cdff07a` | ApprovalSuspensionCoordinator.suspendToolExecution | 218 | 1072 | `if_acmpne` | None | `Method persistSuspendedInvocation:(Ldev/tramai/engin` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `16f71d24dc95` | ApprovalSuspensionCoordinator.suspendToolExecution | 219 | 1420 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalLif` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `ce65c0b75a83` | ApprovalSuspensionCoordinator.suspendToolExecution | 239 | 1785 | `if_acmpne` | None | `Method compensateSuspension:(Ljava/lang/String;JLdev` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `8d5bb41c2eaa` | ApprovalSuspensionCoordinator$compensateSuspension$2$1.invokeSuspend | 293 | 63 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/SuspendedInvocatio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `e22c7dcc1e36` | ApprovalSuspensionCoordinator$compensateSuspension$2$2.invokeSuspend | 294 | 64 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalCon` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `050d2b83c852` | DefaultApprovalGateway.persistGoverned | 248 | 145 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/approval/GovernedA` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `c98ac92dc495` | DefaultApprovalGateway.persistGoverned | 253 | 228 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/GovernedSuspendedI` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `e602fa999053` | DefaultApprovalGateway.persistGoverned | 261 | 306 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalCon` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `d68d0252ca2d` | DefaultApprovalGateway.persistUngoverned | 223 | 130 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalSto` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `2436750ccd76` | DefaultApprovalGateway.persistUngoverned | 225 | 185 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/SuspendedInvocatio` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `ae609c23ce29` | DefaultApprovalGateway.persistUngoverned | 230 | 243 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalCon` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `f5e8c455520a` | DefaultApprovalGateway.requestApproval-Atj0Sqo | 82 | 159 | `if_acmpne` | None | `Method "resolveGovernedIdentity-jFE5hGw":(Ljava/lang` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `6f9609fdbbab` | DefaultApprovalGateway.requestApproval-Atj0Sqo | 93 | 304 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/approval/ApprovalG` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `4245a1aa59c9` | DefaultApprovalGateway.requestApproval-Atj0Sqo | 105 | 457 | `if_acmpne` | None | `InterfaceMethod dev/tramai/core/approval/ApprovalSto` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `e44e3a71b7ef` | DefaultApprovalGateway.requestApproval-Atj0Sqo | 107 | 618 | `if_acmpne` | None | `Method requireExistingAttributionMatches:(Ldev/trama` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `9f7ce30fc6da` | DefaultApprovalGateway.requestApproval-Atj0Sqo | 112 | 799 | `if_acmpne` | None | `Method persistUngoverned:(Ldev/tramai/engine/approva` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |
| `1b17d7d30c23` | DefaultApprovalGateway.requireExistingAttributionMatches | 210 | 151 | `if_acmpne` | None | `InterfaceMethod dev/tramai/engine/approval/GovernedA` | TIMED_OUT | TIMED_OUT | BYTECODE_ABSENCE |

Every member: `if_acmpne` immediately after `dup` of the child call's result, i.e. `result != COROUTINE_SUSPENDED`. No member entered on mutator/status similarity.

## 5. Phase 4 — authored-branch concealment audit

**36 BYTECODE_ABSENCE_PROOF, 0 SIBLING_PROTECTION_REQUIRED.** For each of the 36 the source-attributed line carries **exactly one** conditional jump, and it is the mapped sentinel — so there is no authored branch on that line for a tooling-limitation classification to conceal. This is g1E's proof form A directly; the sibling path was not needed by any member, and no member's line has more than one conditional jump.

## 6. Phase 5 — protocol-only argument

1. Kotlin emits the sentinel comparison to implement the suspend-function protocol: after a child suspend call returns, the compiler must ask whether the child suspended.
2. The conditional encodes no TramAI business or governance decision; the 36 child calls are the ordinary suspend operations of the approval flow (`prepareResume`, `authorizeResume`, `executeClaimedResume`, `completeClaimedResume`, `emitResumeUncertainOutcomeOnce`, `persistGoverned`, `persistUngoverned`, `compensateStep`, `compensateSuspension`, `resolveGovernedSuspension`, `persistSuspendedInvocation`, `requireExistingAttributionMatches`, `resolveGovernedIdentity`, `ReplayPayload`, `BuildersKt.withContext`).
3. The branch answers only: did the child return `COROUTINE_SUSPENDED`?
4. Inverting or removing that branch breaks coroutine execution mechanics — it changes which frame resumes — rather than representing an alternative valid business outcome.
5. PIT reports `TIMED_OUT` with **no test count reported at all** in two independent campaigns at the same base. Precisely: the raw report carries **no `numberOfTestsRun` element for 62 of 62 TIMED_OUT mutations**; the frozen manifest's `0` is the audit's coercion of that absence, not a measured zero.
6. The tooling behaviour is therefore not evidence that an authored TramAI condition survived its tests.
7. Authored conditions sharing a source line: none exist (Phase 4), so nothing is deferred to the sibling path.

`TOOLING_LIMITATION` does **not** mean the compiler branch is semantically irrelevant. It means the mutation result cannot be read as ordinary test inadequacy or equivalence, because PIT cannot execute or measure this mutation normally.

## 7. Phase 6 — controls (anti-blanket-exemption)

| owner class | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | total |
| --- | --- | --- | --- | --- | --- |
| ApprovalResumeCoordinator | 107 | 24 | 1 | 18 | 150 |
| ApprovalResumeCoordinator$resume$2 | 2 | 2 | 0 | 1 | 5 |
| ApprovalSuspensionCoordinator | 71 | 6 | 2 | 8 | 87 |
| ApprovalSuspensionCoordinator$compensateSuspension$2$1 | 2 | 2 | 0 | 1 | 5 |
| ApprovalSuspensionCoordinator$compensateSuspension$2$2 | 2 | 2 | 0 | 1 | 5 |
| DefaultApprovalGateway | 57 | 8 | 2 | 12 | 79 |

92 representative KILLED `NegateConditionals` mutants exist in these same owner classes (e.g. `ApprovalResumeCoordinator.prepareResume` line 131, line 126; `resolveAndValidateResumeTool` line 362), so the classes are not exempted from the mutator. These are supporting controls; the load-bearing proof remains exact sentinel mapping + repeatable TIMED_OUT + no concealed authored branch.

## 8. Phase 7 — adjudication

| outcome | count |
| --- | --- |
| TOOLING_LIMITATION | 36 |
| UNDETERMINED | 0 |

None is called EQUIVALENT, none was killed here. Expected and actual coincide at 36/36.

## 9. Phase 8 — parent G5 accounting

| | |
| --- | --- |
| frozen cohort before | 52 UNDETERMINED |
| settled by G5b | 36 TOOLING_LIMITATION |
| remaining UNDETERMINED | 16 — SURVIVED 12, NO_COVERAGE 4 |

The 16 already-adjudicated identities of the 68-row P1 manifest are not part of the frozen G5 52 and are not counted here. P1 mint remains blocked; P2 consumption remains blocked.

## 10. Verification

- frozen-52 digest recomputed — unchanged
- start gate: 7/7 conditions
- campaign 2: `measuredCommit` = throwaway commit, analyzer semantics identical, 918/918 family census, both report layers well-formed
- 36/36 identity-exact reconciliation, 0 missing, 0 duplicates, 0 status movement
- Phase 3/4 re-proof per member: `if_acmpne` + `dup`, one conditional jump per line, 36/36 BYTECODE_ABSENCE
- manifest JSON validated; admission ledger still empty; zero forbidden changes
- `spotlessCheck`, `verifyStaticAnalysis`, `verifyChangePolicy -PchangePolicyBase=aa0cc52cc30a…`
- exact-head CI remains the final authority

## 11. Limits of this disposition

The disposition rests on the mutated instruction being compiler-emitted protocol rather than authored logic, on there being no authored conditional on the same line, and on TIMED_OUT reproducing in an independent campaign. It does not claim the branch is unreachable in principle, and it does not close the 16 remaining identities.

## 12. Next

G5c (SURVIVED 12) and G5d (NO_COVERAGE 4), with the 7 ambiguous mappings of G5a remaining barred from disposition until the per-mutator skip rule makes their instruction unique. The final full P1 campaign runs only once all 52 are closed.
