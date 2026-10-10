# TASK — Mutation Authority Population Refresh

**Status:** recorded at 0.7.3 closure. **Not started. Not part of Epic 0.7.3.**
**Primary change class:** to be determined by the campaign design (see investigation item 8 — the current
change-class model may forbid a pure population refresh).
**Owner authorization required:** the MINT transition must land in the base before any CONSUME.

## Question

> The repository-wide mutation population has evolved materially since the last accepted authority
> measurement. What is the correct staged, base-authoritative MINT→CONSUME migration for the new
> population without weakening the mutation ratchet or allowing candidate self-authorization?

## Why this exists (measured evidence, 0.7.3h verification)

The pristine epic base `f8af25106ca3430cf6a6b63835b5e961279efba2` already fails
`verifyCriticalMutationBaseline`. Nothing here is introduced by 0.7.3.

| | committed | fresh (at the epic base) |
| --- | --- | --- |
| population | 2384 | 2585 |
| KILLED | 1595 | 1870 |
| SURVIVED | 430 | **450** |
| NO_COVERAGE | 294 | 195 |
| TIMED_OUT | 65 | 70 |
| `measuredCommit` | `5856530e` | `f8af2510` |

- The committed `testQuality.mutation` block in `config/quality/0.6.0-baseline.json` is a hand-written
  placeholder: `status: "pending"`, note *"Requires PITest plugin configuration"*, keys a subset of
  `MutationData`. `MutationBaselineVerifier` rejects it before any comparison
  (`TEST_QUALITY_STATUS_PENDING` + `MUTATION_REPORT_MISSING`). The file is byte-identical at base and at
  the 0.7.3 head. `testQuality.coverage` is pending the same way and `testQuality.testPerformance` is
  absent — same family, different gates.
- The committed measurement revision is **175 commits** behind the epic base (140 production files,
  145 test files, 37,551 test insertions), so identity churn is expected: 427 identities added,
  226 removed, 52 moved, concentrated in rewritten classes (`ApprovalResumeCoordinator` 71/91,
  `ApprovalSuspensionCoordinator` 48/74, `RuntimeEvidenceContractValidator` 46/50,
  `RuntimeEvidenceBundleWriter` 34/30, `DefaultApprovalGateway` 27/77).
- Fresh deeper diagnostics: **628** "Surviving mutant … is unclassified" + **186** "Missing-test
  survivor … has no issue or target phase" = 814.
- Ledger capacity: `mutation-classifications.yml` holds **23** identities (17 equivalent-mutant,
  6 tool-limitation); `mutation-population-admissions.yml` holds **67**. Measured survivors: 450, of
  which **8** are classified.

**Why 0.7.3 could not repair it:** both ledgers state that *"the authorization must already exist in the
PR's BASE. A candidate may never create the authority it uses"*, with MINT and CONSUME required in
**separate** transitions (M22–M29 classification enrolment; M30–M39 population admission). A single
transition cannot mint the ~814 authorizations it would need.

**Measurement facts to carry forward:** the authoritative command is
`./gradlew generateCriticalMutationBaseline --no-configuration-cache --rerun-tasks` (≈45 min in a
detached worktree; it issues a nested per-family `canonicalMutationProbe` build). It is **not perfectly
deterministic at the timeout boundary**: two runs at identical code produced SURVIVED 450/TIMED_OUT 70
and SURVIVED 449/TIMED_OUT 71. Any acceptance rule must tolerate that boundary, and the artefact is
rewritten on every run, which makes the tree dirty and blocks the next `generateCriticalMutationBaseline`
until it is committed.

## Investigation items

1. genuine new mutation identities caused by production evolution.
2. identity migrations caused by rewritten code.
3. survivors representing **missing tests** (which need an issue or target phase, not a blanket label).
4. genuine **equivalent mutants** (adjudicated from bytecode, as the existing 17 entries are).
5. genuine **tool limitations**.
6. `NO_COVERAGE` cases and the appropriate target phase.
7. `TIMED_OUT` cases **individually, not categorically**.
8. whether the change-class model **intentionally** forbids a pure population refresh, or contains a
   policy gap — measured: `verifyChangePolicy -PchangeClass=baseline-migration` fails with
   `[analyzer-runtime-separation]: A baseline-migration PR must include an analyzer change
   (build-logic/). Found no changes in build-logic/`, while the same baseline-only change passes as
   `runtime-behaviour` and as `build-logic`.
9. the coverage and test-performance placeholders found in the same maintainability family.

## Explicit non-goals

- **Do not pre-adjudicate the ~814 identities in this record.** The numbers above are scope, not verdicts.
- Do not mass-classify survivors, and do not auto-classify `TIMED_OUT` as tool limitation or
  `NO_COVERAGE` as missing-test.
- Do not treat every `SURVIVED` mutant as issue-backed debt.
- Do not weaken `verifyCriticalMutationBaseline`, and do not alter the MINT→CONSUME authority model.
- Do not change build logic merely to permit a baseline refresh.
- Do not merge the experimental measurement branch `task/0.7.3-mutation-baseline-migration`
  (`0f98ba4b`): it is measurement evidence only and was deliberately excluded from 0.7.3.

## Exit criteria for the campaign

1. a MINT transition in the base carrying the enrolment/authorization records, adjudicated per identity;
2. a CONSUME transition whose base contains that authorization, committing the exact fresh measurement
   with row-for-row equality, matching analyzer semantics and `populationDigest`, and removal of the
   consumed authorization in the same transition;
3. `verifyCriticalMutationBaseline`, `verifyFullMaintainabilityBaseline` and the mutation-ratchet gates
   green on that base, with no survivor suppressed or reclassified to achieve it;
4. the coverage/performance placeholders resolved or explicitly classified (item 9).
