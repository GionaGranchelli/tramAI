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

## The four failing checks belong to the PR #400 integration run, not to this branch

`7e628de7` reports `success=22/27` with four failing checks. My first report called that "the target branch is
red" and attributed the mutation failure to a missing `-PtramaiMutationBaseSha`. **Both claims are withdrawn.**

The owner retrieved the job logs and artifacts that the API would not serve to this host. Those runs are the
integration checks for **PR #400 against `master`**, so they do not establish that a standalone
`release/0.7.0` checkout fails identically — and the property the withdrawn explanation blamed was in fact
supplied:

| Failing check | Actual finding | Disposition |
|---|---|---|
| `api-architecture` | 18 API-compatibility failures across `tramai-control-plane`, `tramai-persistence-jdbc`, `tramai-security` — missing or mismatched hash-bound migration entries | reconcile the exact transitions and migration ledger; no blanket regeneration |
| `build-logic (release-sovereign)` | `TramaiDocsGuardsPluginTest`: `spring-ai-authority-boundary` references `RuntimeBoundaryProvenance.kt`, absent from the test fixture, though the source exists in the repository | repair the fixture construction; rerun positive and negative tests |
| `Verify Maintainability Baseline` | `verifyChangePolicy` rejects mixed analyzer/tooling and runtime changes under `analyzer-runtime-separation` | keep the policy; classify or separate the changes |
| `tests` | `verifyMutationRatchet` fails M35: admission `fromBaseSha=5bcb030c…` does not match authority base `611845cc…` — with `611845cc…` supplied to the task | provenance mismatch to repair through the authorised mutation-admission process |

Consequence for this task, restated precisely: "exact-head CI green" is an H2 acceptance requirement, and it
must be measured on this branch's own head with the PR base properties supplied. The four failures above are
blockers for **PR #400's integration**, and their repair is not part of Phase 1. What remains true is that a
gate must not be reached by weakening it, and that the mutation-ratchet failure is a provenance question to be
reconciled, not dismissed as an invocation artifact.

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

- The four failing checks are PR #400 integration failures with concrete diagnosed causes (above); the
  missing-property explanation I first published for the mutation ratchet is **withdrawn** - the property was
  supplied and the failure is an authority-base provenance mismatch.
- `verifyPr` is not a CI execution; the PR template's aggregate request is unenforced.
- Publication boundary is proven against generated Gradle metadata; whether an upstream mirror retains a
  historically published fixture jar is a separate question this task does not answer.
