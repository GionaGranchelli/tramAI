# TASK 0.7.4h — Governance evidence integration, CI enforcement and epic closure

Epic 0.7.4. Integration branch `release/0.7.0`.

## Gate H0 — dependency state (established, not inferred)

| Item | Value | How established |
|---|---|---|
| `release/0.7.0` head | `7e628de74d6753be3659f5781b7ca7d98ec7b193` | `git fetch` + `git rev-parse origin/release/0.7.0` |
| PR #538 (0.7.4g) merged | **yes** — `7e628de7` *is* that merge, and is the branch head | `rev-parse` on the fetched ref |
| Work tree used | `/home/gionag/tramai-h1`, branch `task/0.7.4h1-publication-boundary` | `git worktree list` |
| Local divergence from base at start | none (branch cut from the exact base) | branch creation from `origin/release/0.7.0` |

Documents read for this phase: root `AGENTS.md`, `build-logic/AGENTS.md`, `.github/AGENTS.md`, the 0.7.4g task
document and qualification report, and the workflow set under `.github/workflows/`.

## What is mechanically enforced in CI today, and what is not

`release/0.7.0` runs seven workflows: `ci.yml`, `architecture-authority.yml`, `maintainability-baseline.yml`,
`maintainability-full.yml`, `mutation-authority-integration.yml`, `publish.yml`,
`sovereign-runtime-release-candidate.yml`.

**The two deferred integration items were not addressed by newer commits.** Verified at `7e628de7`:

- `verifyModuleMatrixDrift` — referenced in **no** workflow, and in **no** `verifyPr` lane.
- `verifyModuleDocContract` — same: **no** workflow, **no** `verifyPr` lane.
- `./gradlew verifyPr` itself is invoked by **no** workflow; `ci.yml` runs selected constituent tasks instead.

So the aggregate the PR template asks authors to report is never the gate CI actually applies. A green PR can
therefore mean "the constituent tasks that CI happens to run passed", which is exactly the gap Phase 2 closes.

Genuinely enforced (individual tasks wired into workflows): `verifyPublicApi`-family via
`api-architecture (verify060Architecture)` in `architecture-authority.yml`; `build-logic` test partitions,
`verifyCancellationSafety`, `verifyCriticalCoverage`, `verifyMutationRatchet`, dependency hygiene, static
analysis and formatting in `ci.yml`; maintainability baseline in its own workflow.

Established by 0.7.4g and **not** re-litigated here: the seven evidence invariants (I1–I7) are qualified by
the merged suites, so Phase 3 reuses that evidence rather than duplicating it.

## The target branch is red at its own head — with the mechanism

`7e628de7` reports `success=22/27` with four failing checks. My first report called that "the branch is red"
and treated it as a content defect. **Correcting that:** the failure I could diagnose is an **invocation
artifact**, not a content defect.

At least one failure is verified from the job log: `tests` fails on `:verifyMutationRatchet` —
`Mutation ratchet (base 611845cc4bb54ad26507cae5cc69257f7e4c767b) baseline verification FAILED`. `611845cc` is
not this PR's base; the run was triggered by a **push** to `release/0.7.0`, where the canonical
`-PtramaiMutationBaseSha` property is absent and the task falls back to a different authority — the same
invocation artifact 0.7.4g recorded when the ratchet was run without its base property.

`api-architecture (verify060Architecture)`, `build-logic (release-sovereign)` and
`Verify Maintainability Baseline` fail at the same head. Their logs were not retrievable in this session
(repeated `i/o timeout` against the jobs-log endpoint), so their cause is **UNRESOLVED** rather than assumed.
The locally reproduced `ReleaseVerificationPluginTest` failure family — 7 failures (T1b, T2, T5, T7, T7c, T9,
T15, cause `Could not create task ':verifySovereignRuntimeSignedBundle'`) — reproduces identically in a clean
detached worktree at the pristine base, so it is pre-existing, whichever way it resolves.

Consequence for this task: a claim of "exact-head CI green" cannot be made for this branch until those four
checks are green under the canonical invocation. That is a **prerequisite for H2's certification**, and it is
not part of Phase 1. It must be fixed by making the invocation correct or the task correct — never by
weakening a gate.

## Acceptance matrix

| Gate | Claim | Evidence form |
|---|---|---|
| H0 | Dependency state exact; no inference from old reports | this document |
| H1 | All eligible modules qualified; zero unexpected fixture publications; internal usage works; negative control fails for the intended reason | PR #539 + `evidence/0.7.4h1-publication-boundary/` |
| H2 | Exact-head CI proves the integration gates were selected, executed and passed; removal is mechanically detected | Phase 2 PR (blocked on the four failing base checks above) |
| H3 | The seven invariants still qualified at the closure head, reusing 0.7.4g evidence | Phase 3 evidence ledger |
| H4 | Zero surviving valid security-critical mutants in the new integration protections | Phase 4 mutation record |

## Scope

In scope: publication boundary for test fixtures; CI enrollment of the unenforced gates; adversarial wiring
tests; epic closure report with a truthful limitation ledger.

Out of scope, explicitly: new governance behaviour; reopening 0.7.4g proofs without a reproducible defect;
any baseline regeneration or deviation-ceiling increase; any new authentication or identity implementation
(S18/S19/T1 stay unresolved and visible); release cutting.

## PR boundaries

- **Phase 1 / H1** — PR #539 (`task/0.7.4h1-publication-boundary`): test-fixture publication boundary plus the
  `verifyNoPublishedTestFixtures` gate. Change class `build-logic`.
- **Phase 2 / H2** — `task/0.7.4h2-governance-ci-enforcement`: workflow edits only (`ci-workflow`), because the
  repository's change policy separates workflow edits from build-logic changes. Wiring tests that belong to
  build-logic are prepared in a separate preparatory PR if the policy requires it.
- **Phase 3 / H3** — `task/0.7.4h3-governance-evidence-closure`: documentation and evidence only, run after
  H1 and H2 are merged.

## Known limits recorded here

- Four failing checks at the base head, one diagnosed as an invocation artifact, three **UNRESOLVED** for want
  of logs.
- `verifyPr` is not a CI execution; the PR template's aggregate request is unenforced.
- Publication boundary is proven against generated Gradle metadata; whether an upstream mirror retains a
  historically published fixture jar is a separate question this task does not answer.
