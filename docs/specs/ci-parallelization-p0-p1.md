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
release-relevant ──┤
                    └── sovereign-rc (closure)   [path-filtered]
```

| Job | Contains | Runs on | Blocks merge |
|-----|----------|---------|--------------|
| `quality` | spotless, static analysis, static safety guards, compiler warnings, dependency hygiene, build-logic contract tests — **one** Gradle invocation | every PR | yes |
| `tests` | `test` (with thread-dump watchdog), cancellation safety, critical coverage | every PR | yes |
| `consumer-smoke` | `publishToMavenLocal` + kotlin-springboot example test | every PR | yes |
| `artifact-prep` | SBOM (`cyclonedxBom`, `prepareCycloneDxBom`), `prepareSovereignReleaseArtifacts`, `verifySovereignReleaseManifest`, upload artifacts | every PR | yes (producer for zero-egress) |
| `zero-egress` | **download** prepared artifacts + manifest, run `verify-zero-egress.sh`, evidence pack/bundle, attestations | every PR, `needs: artifact-prep` | yes |
| `sovereign-rc` | document intelligence example, spring sovereign starter tests + E2E, `verifySovereignRuntimeClosure` | **path-filtered** (runtime/release-relevant paths only) | yes when it runs |

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
- `sovereign-rc`: steps 248–271 (document intelligence example, spring sovereign starter tests, E2E, closure) + `Upload test reports on failure` equivalent. **Path-filtered** per P0.2.

## P0.2 — Stop treating every PR like a release candidate

- `sovereign-rc` job gets `paths:` filter = the same list as `sovereign-runtime-release-candidate.yml` (build.gradle.kts, gradle.properties, tramai-bom/**, tramai-core/**, tramai-security/**, tramai-sovereign/**, tramai-standalone/**, tramai-engine/**, tramai-structured/**, tramai-persistence-file/**, tramai-spring-sovereign/**, tramai-spring-core/**, tramai-spring-boot-starter/**, tramai-spring-boot-starter-sovereign-persistence-file/**, tramai-spring-boot-starter-sovereign-ops/**, tramai-spring-boot-starter-sovereign-ops-actuator/**, tramai-spring-boot-starter-sovereign-ops-micrometer/**, tramai-spring-boot-starter-sovereign-ops-observability/**, examples/sovereign-runtime-consumer-smoke/**, docs/releases/**, docs/reference/releasing.md, .github/workflows/sovereign-runtime-release-candidate.yml).
- Document this in a job comment: full RC verification remains available via the existing `sovereign-runtime-release-candidate.yml` workflow (unchanged) for workflow_dispatch and release-relevant PRs.
- `artifact-prep` + `zero-egress` still run on EVERY PR: sovereignty/zero-egress is a safety guarantee, not release bookkeeping. Only the expensive closure/sovereign-examples chain is path-gated.

## P1.1 — Generate release artifacts exactly once

Achieved by the `artifact-prep` → download → `zero-egress` wiring above.

Removed duplicated work in `zero-egress`:
- ~~cyclonedxBom~~ (~3m06)
- ~~prepareCycloneDxBom~~ (~14s)
- ~~prepareSovereignReleaseArtifacts~~ (~50s)
- ~~verifySovereignReleaseManifest~~ (seconds)

**Provenance (Socratic Clause):** zero-egress evidence must still prove the artifacts it verifies are the artifacts `artifact-prep` produced. Preserve the manifest verification chain in `zero-egress`:
- After downloading, `verifySovereignReleaseManifest` still runs (cheap, ~seconds) to confirm the downloaded bundle is complete/valid — keep it in `zero-egress`.
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

- Replace the 4 separate `:build-logic:test` invocations (formatting=8, static-safety=71, compiler+dependency=64, static-analysis=25) with **one**:

```yaml
- name: Run build-logic contract tests
  timeout-minutes: 30
  run: |
    ./gradlew :build-logic:test \
      --tests 'dev.tramai.build.quality.FormattingGate*Test' \
      --tests 'dev.tramai.build.quality.StaticSafety*Test' \
      --tests 'dev.tramai.build.quality.CancellationWiringTest' \
      --tests 'dev.tramai.build.quality.CompilerWarnings*Test' \
      --tests 'dev.tramai.build.quality.DependencyHygiene*Test' \
      --tests 'dev.tramai.build.quality.StaticAnalysis*Test' \
      --no-daemon
    python3 - <<'EOF'
    import glob, re
    total = 0
    for f in glob.glob('build-logic/build/test-results/test/TEST-*.xml'):
        m = re.search(r'tests="(\d+)"', open(f).read())
        total += int(m.group(1)) if m else 0
    assert total == 168, f'expected exactly 168 build-logic contract tests, discovered {total}'
    EOF
```

  (8 + 71 + 64 + 25 = 168; the single assertion preserves the non-vacuity guarantees of all four original assertions.)

**Do NOT** create a new `verifyPullRequestQuality` build-logic task in this PR — the multi-task Gradle invocation achieves the same CI win with zero build-logic risk. Add the aggregate task later if local DX demands it.

## Explicitly deferred (NOT in this PR)

- **P2 configuration cache** — `org.gradle.configuration-cache=false` stays. Incremental enablement is a separate PR with build-logic compat work.
- **P2 lazy build-logic configuration** — eager project/task enumeration is a build-logic code change; separate PR.
- **P3 consolidate build-logic suites** — covered for CI by P1.2's single invocation; source-level suite consolidation is optional later.

## Verification

- `python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` — YAML valid.
- `actionlint .github/workflows/ci.yml` if available (or `npx actionlint`) — workflow valid. If actionlint is not installed, skip with a note; do NOT add tooling to the repo.
- Manual review: every step from the old `build` job appears in exactly one new job; every `if:` condition and label branch preserved; no gate dropped.
- `git diff` review: `.github/workflows/ci.yml` and `.github/workflows/sovereign-runtime-release-candidate.yml` are the ONLY changed files.
- Real CI verification happens on push (GitHub Actions). The PR body must state that a green run on the branch is required before merge.

## Files to NOT touch

- Any file under `src/`, `build-logic/src/`, `examples/`, `docs/`, `scripts/`, `gradle/`
- `gradle.properties`, `build.gradle.kts`, `settings.gradle.kts`
- `.github/workflows/maintainability-baseline.yml`, `maintainability-full.yml`, `publish.yml`
- `.github/workflows/sovereign-runtime-release-candidate.yml` (unless adding a `paths:`-sync comment — read-only)

## Files you MAY modify

- `.github/workflows/ci.yml` — full restructure (this is the deliverable)
- `.github/workflows/sovereign-runtime-release-candidate.yml` — comment-only change documenting that ci.yml's `sovereign-rc` job shares its path list (do not change triggers or steps)
