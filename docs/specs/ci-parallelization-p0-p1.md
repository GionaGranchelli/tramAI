# CI Pipeline Parallelization — P0 + P1 (Phase 1)

**Branch:** `ci/pipeline-parallelization`
**Author:** Giona (via multi-agent PR workflow)
**Date:** 2026-09-01
**Scope:** `.github/workflows/ci.yml` + `.github/workflows/sovereign-runtime-release-candidate.yml` only.
**Zero production code changes.** No `src/main/kotlin/**` changes. No `build-logic/` changes.

## Context

Successful CI currently takes ~48–69 minutes. The bottleneck is the CI DAG, not the tests:

- One monolithic `build` job: ~35 sequential steps mixing quality gates, tests, and release-candidate work.
- Release verification (SBOM, release artifacts, sovereign examples, E2E, closure) runs AFTER all normal tests, serially.
- `zero-egress` waits for the entire build (`needs: build`) and then **rebuilds** SBOM + release artifacts on a fresh runner (~4m10 of duplicated work in a 5m10 job; the actual isolation harness is ~22s).
- 8–10 separate Gradle invocations each pay configuration/startup cost on a 50+ module build.

## Goal

Get PR feedback down to ~20–30 minutes (parallel branches) while preserving **exactly** the same quality, sovereignty, safety, and release guarantees. All existing gates remain mandatory — only *when* and *where* they run changes.

## Target Architecture

```
                    ┌── quality ────────────────┐
                    │                           │
PR ────────────────┼── tests + coverage ────────┼──► PR gate (merge-blocking)
                    │                           │
                    └── consumer-smoke ─────────┘

                    ┌── artifact-prep ──► zero-egress

Sovereign Runtime RC workflow   [release-relevant paths only, separate workflow]
└── verifySovereignRuntimeClosure   ← SINGLE sovereign-verification authority
```

| Job | Contains | Runs on | Blocks merge |
|-----|----------|---------|--------------|
| `quality` | spotless, static analysis, static safety guards, compiler warnings, dependency hygiene — **one** Gradle invocation | every PR | yes |
| `contract-tests` | build-logic contract suites as a **4-entry matrix** (formatting=8, static-safety=71, compiler-deps=64, static-analysis=25), each with its own independent test-count assertion | every PR | yes |
| `tests` | `test` (with thread-dump watchdog), cancellation safety, critical coverage | every PR | yes |
| `consumer-smoke` | `publishToMavenLocal` + kotlin-springboot example test | every PR | yes |
| `artifact-prep` | SBOM (`cyclonedxBom`, `prepareCycloneDxBom`), `prepareSovereignReleaseArtifacts`, `verifySovereignReleaseManifest`, upload artifacts | every PR | yes (producer for zero-egress) |
| `zero-egress` | **download** prepared artifacts + manifest, run `verify-zero-egress.sh`, evidence pack/bundle, attestations | every PR, `needs: artifact-prep` | yes |
| Sovereign Runtime RC workflow | closure: full RC chain + spring-sovereign-starter E2E + API boundary | **path-filtered** (release-relevant paths), separate workflow | yes when it runs |

> **Sovereign verification authority:** ci.yml runs NO sovereign closure. The
> dedicated `sovereign-runtime-release-candidate.yml` workflow is the single
> owner of release-candidate verification — it runs the canonical
> `verifySovereignRuntimeClosure` aggregate (which includes the full RC chain,
> the spring-sovereign-starter E2E suite, and the API boundary) on
> release-relevant PRs, `workflow_dispatch`, and master. This avoids running
> the heavyweight closure on a fresh runner in ci.yml (which would re-run the
> full test+RC graph) and avoids two workflows racing the same expensive
> aggregate.

## P0.1 — Break the monolithic build apart

Restructure `ci.yml` from one `build` job into the six jobs above.

**Job wiring:**
- `quality`, `tests`, `consumer-smoke` run in parallel, no `needs`.
- `artifact-prep` runs in parallel with `quality`/`tests`/`consumer-smoke` (no `needs: build`).
- `zero-egress` has `needs: artifact-prep` only.
- `sovereign-rc` has `needs: tests` only (closure depends on the full suite having passed — preserves the current "closure runs after Run tests" invariant; do NOT run it in parallel with tests).

**Per-job step assignments (from current ci.yml):**

- `quality`: steps 41–148 (spotlessCheck ×2 event variants, formatting contract tests, static analysis ×2, static safety guards + contract tests, compiler warnings ×2, dependency hygiene, compiler-warnings + dependency-hygiene contract tests, static-analysis contract tests). Keep all `if:` event conditions and `baseline-migration` label handling verbatim.
- `tests`: steps 150–215 (Run tests with containment watchdog + thread-dump upload, Print compiled fingerprints, cancellation safety ×2, critical coverage ×2).
- `consumer-smoke`: steps 217–225 (Publish to Maven Local, Run example smoke test).
- `artifact-prep`: steps 236–247 (cyclonedxBom, prepareCycloneDxBom, prepareSovereignReleaseArtifacts, verifySovereignReleaseManifest) + upload SBOM/digest/artifacts steps 273–293 + attest step 295.
- `zero-egress`: keep steps 308–389, but REPLACE the four rebuild steps (Generate CycloneDX SBOM, Compute SBOM digest, Prepare sovereign release artifacts, Verify sovereign release manifest) with `actions/download-artifact@v4` downloads of the artifacts uploaded by `artifact-prep`:
  - `cyclonedx-sbom` → `build/supply-chain/sbom/`
  - `cyclonedx-sbom-digest` → `build/supply-chain/sbom/`
  - `sovereign-release-artifacts` → `build/sovereign-release/`
  - Keep a `verifySovereignReleaseManifest` run AFTER download: since the task is
    now a **pure verifier** (its `prepareSovereignReleaseArtifacts` dependsOn was
    removed in this PR), it validates the downloaded bundle without re-running
    the producer graph. Contract-tested by
    `VerifySovereignReleaseManifestIsPureTest`.
- NO `sovereign-rc` job in ci.yml — sovereign/RC verification lives in the
  dedicated `sovereign-runtime-release-candidate.yml` workflow (single
  authority, runs `verifySovereignRuntimeClosure` on release-relevant paths).

## P0.2 — Stop treating every PR like a release candidate

- ci.yml runs NO sovereign closure. The dedicated `sovereign-runtime-release-candidate.yml`
  workflow is the SINGLE authority: it runs `verifySovereignRuntimeClosure`
  (canonical aggregate: full RC chain + spring-sovereign-starter E2E + API
  boundary) on release-relevant PRs, `workflow_dispatch`, and master.
- The RC workflow's `paths:` filter was widened to cover what the closure graph
  executes: `build-logic/**`, `examples/sovereign-document-intelligence/**`,
  `examples/spring-sovereign-starter/**`, and `.github/workflows/ci.yml`
  (a future ci.yml-only edit must still exercise sovereign verification).
- `artifact-prep` + `zero-egress` still run on EVERY PR: sovereignty/zero-egress
  is a safety guarantee, not release bookkeeping. Only the expensive
  closure/sovereign chain is path-gated, and it runs exactly once per
  release-relevant PR in the RC workflow.

## P1.1 — Generate release artifacts exactly once

Achieved by the `artifact-prep` → download → `zero-egress` wiring above.

Removed duplicated work in `zero-egress`:
- ~~cyclonedxBom~~ (~3m06)
- ~~prepareCycloneDxBom~~ (~14s)
- ~~prepareSovereignReleaseArtifacts~~ (~50s)
- ~~verifySovereignReleaseManifest~~ (seconds)

**Provenance (Socratic Clause):** zero-egress evidence must still prove the artifacts it verifies are the artifacts `artifact-prep` produced. Preserve the manifest verification chain in `zero-egress`:
- After downloading, `verifySovereignReleaseManifest` still runs (cheap, ~seconds) to confirm the downloaded bundle is complete/valid — keep it in `zero-egress`. **This PR makes it a pure verifier:** its `dependsOn("prepareSovereignReleaseArtifacts")` is removed from `TramaiSovereignVerificationPlugin`, so it validates the downloaded bundle WITHOUT re-running the producer graph (which would rebuild release JARs). Aggregates that need both still declare both explicitly (`generateSovereignReleaseEvidenceIndex`, `verifySovereignDocumentIntelligenceEvidenceRun`). Contract-tested by `VerifySovereignReleaseManifestIsPureTest` (fails if the producer dependency returns).
- Keep `verifySovereignEvidencePackContainsReleaseBundle` (proves the evidence pack contains the release bundle).
- Keep all attestation steps (attest-build-provenance for evidence pack, zero-egress report, release artifact manifest; attest+sbom).
- `artifact-prep` is the explicitly trusted producer: it runs on the same commit, same event, and uploads with immutable names. Digest is verified by the existing evidence-pack checks.

## P1.2 — Collapse the Gradle invocation explosion

In the `quality` job, replace the 5+ separate Gradle invocations with **one**:

```yaml
- name: Run all quality gates
  run: |
    ./gradlew spotlessCheck verifyStaticAnalysis verifyStaticSafetyGuards verifyCompilerWarnings verifyDependencyHygiene --no-daemon
```

Handling requirements:
- The `verifyStaticAnalysis` and `verifyCompilerWarnings` steps currently have PR-vs-push variants and a `baseline-migration` label branch. **Merge all variants into one step** using a single shell script that computes the base ref and label once:
  - `BASE_REF`: PR → `${{ github.event.pull_request.base.sha }}`; push → `${{ github.event.before }}` (guard the zero-push-before case as today).
  - `CHANGE_CLASS`: append `-PchangeClass=baseline-migration` when the PR has the `baseline-migration` label (keep the `toJson(github.event.pull_request.labels)` grep).
  - Run ONE `./gradlew spotlessCheck verifyStaticAnalysis verifyStaticSafetyGuards verifyCompilerWarnings verifyDependencyHygiene --no-daemon -PtramaiStaticAnalysisBaseRef=$BASE_REF -PtramaiCompilerWarningsBaseRef=$BASE_REF -PtramaiFormattingBaseRef=$BASE_REF [optional -PchangeClass=baseline-migration]`.
  - **Important:** static-safety-guards and dependency-hygiene currently take no base-ref properties. Passing extra `-P` properties is harmless (unused by those tasks), but DO NOT drop the base-ref properties for the tasks that need them.
  - If `github.event_name` is neither pull_request nor a normal push (e.g. first push with all-zero before), fall back to no base-ref flags — match the current `if:` semantics so a gate that should be skipped is skipped rather than failing.

- Replace the 4 separate `:build-logic:test` invocations (formatting=8, static-safety=71, compiler+dependency=64, static-analysis=25) with a **4-entry matrix job** `contract-tests` — each family runs its own Gradle invocation on its own runner, in parallel, with its own independent test-count assertion.

```yaml
contract-tests:
  runs-on: ubuntu-latest
  strategy:
    fail-fast: false
    matrix:
      include:
        - name: formatting
          test-filter: "--tests 'dev.tramai.build.quality.FormattingGate*Test'"
          expected: 8
        - name: static-safety
          test-filter: "--tests 'dev.tramai.build.quality.StaticSafety*Test' --tests 'dev.tramai.build.quality.CancellationWiringTest'"
          expected: 71
        - name: compiler-deps
          test-filter: "--tests 'dev.tramai.build.quality.CompilerWarnings*Test' --tests 'dev.tramai.build.quality.DependencyHygiene*Test'"
          expected: 64
        - name: static-analysis
          test-filter: "--tests 'dev.tramai.build.quality.StaticAnalysis*Test'"
          expected: 25
```

**Why a matrix, not one consolidated invocation (measured):** the first live run showed the single consolidated `:build-logic:test` (all six `--tests` filters, one Gradle process) took **19m33s** — the four families serialize inside one Gradle test task. A matrix runs them on parallel runners, so wall time becomes the slowest family, and each family keeps its own independent 8/71/64/25 assertion (a single `total == 168` would let drift between suites hide: 7+72+64+25 also sums to 168).

**Do NOT** create a new `verifyPullRequestQuality` build-logic task in this PR — the multi-task Gradle invocation achieves the same CI win with zero build-logic risk. Add the aggregate task later if local DX demands it.

## Explicitly deferred (NOT in this PR)

- **P2 configuration cache** — `org.gradle.configuration-cache=false` stays. Incremental enablement is a separate PR with build-logic compat work.
- **P2 lazy build-logic configuration** — eager project/task enumeration is a build-logic code change; separate PR.
- **P3 consolidate build-logic suites** — covered for CI by P1.2's single invocation; source-level suite consolidation is optional later.

## Verification

- `python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` — YAML valid.
- `actionlint .github/workflows/ci.yml` if available (or `npx actionlint`) — workflow valid. If actionlint is not installed, skip with a note; do NOT add tooling to the repo.
- Manual review: every step from the old `build` job appears in exactly one new job; every `if:` condition and label branch preserved; no gate dropped.
- `git diff` review: `.github/workflows/ci.yml`, `.github/workflows/sovereign-runtime-release-candidate.yml`, the build-logic plugin one-liner, and the new contract test are the ONLY changed files.
- `VerifySovereignReleaseManifestIsPureTest` passes (and is non-vacuous: it fails if the producer dependsOn is restored).
- Real CI verification happens on push (GitHub Actions). The PR body must state that a green run on the branch is required before merge.

## Files to NOT touch

- Any file under `src/`, `examples/`, `scripts/`, `gradle/`
- `gradle.properties`, `build.gradle.kts`, `settings.gradle.kts`
- `.github/workflows/maintainability-baseline.yml`, `maintainability-full.yml`, `publish.yml`
- Any sovereign verification task class file other than the single dependsOn line in `TramaiSovereignVerificationPlugin.kt`

## Files you MAY modify

- `.github/workflows/ci.yml` — full restructure (the deliverable)
- `.github/workflows/sovereign-runtime-release-candidate.yml` — runs `verifySovereignRuntimeClosure` as the single sovereign authority; widened path filter
- `build-logic/src/main/kotlin/dev/tramai/build/sovereign/TramaiSovereignVerificationPlugin.kt` — REMOVE `dependsOn("prepareSovereignReleaseArtifacts")` from `verifySovereignReleaseManifest` registration (make it a pure verifier)
- `build-logic/src/test/kotlin/dev/tramai/build/sovereign/VerifySovereignReleaseManifestIsPureTest.kt` — NEW contract test
