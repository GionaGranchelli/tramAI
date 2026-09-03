# Epic 10.5 — CI redesign: closure audit

**Status: ✅ COMPLETE — certified on current master after the P3-E measured closure (PRs #360–#375).**
**Date:** 2026-09-03 · **Master:** post-#373 (`35cad1c3`) + PR #375.

## What shipped (implementation track)

| PR | Change |
|---|---|
| #360 | Split monolithic CI `build` job into six parallel jobs (quality, contract-tests, tests, consumer-smoke, artifact-prep, zero-egress); artifact-once via pure-verifier manifest (`ci-parallelization-p0-p1` spec) |
| #361/#368 | Parallelize maintainability build-logic tests (MB 4-lane matrix, JDK 21 quality-contracts partitions) |
| #364/#366 | Compiler-warnings impact narrowing: delta-driven compile graph pruning (changed modules + dependents only) |
| #365 | Quality gates into three parallel lanes |
| #369 | Fix cache-fragile TestKit fixture (explicit inputs) |
| #370 | Enable build-cache writes on MB lanes |
| #373 | RC closure `--no-daemon` |
| #374 | *Measurement only — superseded*: drop `--rerun-tasks` from PR closure alone did not reduce the RC wall (25.4 min; fresh runner re-executes regardless) |
| #375 | PR-specific `verifySovereignRuntimePullRequest` aggregate: sovereign release-chain proof (bundle dry-run, verification-repo closure, consumer smoke, doc-intel evidence run, spring e2e, API boundary, closure/ops docs) WITHOUT the repository test/check graph; `verifySovereignRuntimeClosure --rerun-tasks` retained for workflow_dispatch/release certification |

## P3-E measured evidence (ordinary-PR profile, comment-only core touch, `ProviderTransport.kt` marker)

| Metric | Pre-10.5 | #372 baseline (closure, `--rerun-tasks`) | #374 (closure, no rerun) | #375 (PR aggregate) |
|---|---|---|---|---|
| CI wall | ~48–69 min (monolithic) | 17.0 min | 18.0 min | 16.0 min |
| Maintainability Baseline | serial | 10.6 min | 12.8 min | 2.1 min (cache-warm, #370) |
| Sovereign RC validate | serial in CI | 19.25 / >30 min | 25.4 min | **2.8 min** |
| Merge-critical wall | ~48–69 min | 19–30+ min | ~25 min | **≈ 16 min (CI-bound)** |

RC Gradle step alone: **2.15 min** vs 19–30+ min closure. Root cause of the old wall: the closure aggregate re-ran the whole CI stack on the RC lane (via `check`: compiler-warnings ~20 min, static analysis, module-test fan-out).

## Impact classification (measured, adopted)

- **Leaf/ordinary** (docs, examples, leaf modules; no RC trigger): ≈ 8–10 min — bounded by `tests` (~9.4 min) / MB.
- **High-fanout core / compiler-global** (tramai-core/engine and dependents; release-relevant paths trigger RC): ≈ 16–17 min — inside the 15–18 min compiler-global band. A core change legitimately approaches whole-product compile for warning verification; forcing it to 8 min would cost disproportionate CI engineering.

## Acceptance criteria

| Criterion | Evidence | Verdict |
|---|---|---|
| `check`, not only `test`, is a mandatory PR gate | quality + contract-test + tests lanes gate every PR; **mechanically enforced** — Protect Master ruleset requires: `tests`, `quality (static …)`, `quality (hygiene …)`, `quality (compiler-warnings …)`, `consumer-smoke`, `artifact-prep`, `zero-egress`, `Verify Maintainability Baseline` | ✅ |
| Fast feedback stays reasonably quick via task separation and caching | measured: 16–17 min core / ~10 min leaf / RC 2.8 min (from 48–69 min) | ✅ |
| Expensive assurance on scheduled or release cadence | full `verifySovereignRuntimeClosure --rerun-tasks` = release certification (workflow_dispatch); mutation enforcement = Signal's 10.3c–d track (per-lane boundary) | ✅ |

## Governance note (mandatory-gate evidence)

The deduplication in #375 relies on mandatory CI proving tests/quality on the exact commit. That premise is now **mechanically true**:

- `Protect Master` ruleset (`rulesets/17116579`, updated 2026-09-03) = `deletion` + `non_fast_forward` + `required_status_checks` with the 8 always-on CI/MB contexts listed above. Merge is blocked until they pass (`mergeable_state: blocked` while pending, `clean` when green — observed on #375 head `e794abaa`).
- Excluded from the required list, deliberately: the 4-entry `contract-tests` matrix (GitHub truncates those check names at 100 chars with `…`; exact-context matching is unreliable) and `validate` (Sovereign RC workflow is path-gated — it cannot be *globally* required; when it runs on release-relevant PRs it must be green before merge, per repo convention).

No legacy branch-protection rule exists (`/branches/master/protection` → 404); the ruleset is the sole protection.

## Reconciliations

- `docs/ROADMAP-0.6.0.md` §Epic 10.5 — status line added (this doc).
- `docs/specs/ci-parallelization-p0-p1.md` — P0/P1 architecture landed; P2 (config cache) deferred by measured evidence (no config-dominated bottleneck remains; see P3-E Q5); the PR-scoped sovereign aggregate is the P0.2 "single sovereign authority" made non-duplicating.
- Configuration-cache enablement (CI-wide) was **evaluated and declined**: it does not attack any measured bottleneck (RC runs `--no-configuration-cache` by design; compiler-warnings cost is real compilation). Revisit only if a configuration-dominated job appears.
- Cross-workflow cache-write policy on CI/RC lanes was **evaluated and declined** for the per-PR path (same-commit race + base-sensitive gate inputs not demonstrably in cache keys); MB lanes already write (#370) — evidenced by the 2.1-min MB re-run.
