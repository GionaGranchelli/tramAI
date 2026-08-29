# Epic 10.1 — Code Quality (formatting, static analysis, hygiene)

Phase 10 — Continuous Code Quality. This document freezes the slicing of Epic 10.1.
Slices are implemented in separate PRs; a slice is only "done" when its merge gate is green.

## Slicing (frozen)

| Slice | Scope | Status |
|---|---|---|
| **10.1a — formatting** | Incremental Kotlin source formatting gate (Spotless + pinned KtLint, git-ratcheted against the exact PR/push base). Changed `.kt` must satisfy one deterministic policy; untouched legacy source is never mass-formatted. | ✅ merged (`731126bf`, PR #339) |
| **10.1b — static analysis** | Detekt 1.23.8 (pinned), one central config + one central baseline, baseline-backed growth protection, fail-closed. | 🚧 PR in progress |
| **10.1c — compiler + dependency hygiene** | Compiler-warning review / `-Werror` where feasible; unused-dependency enforcement. | ⏳ planned |
| **10.1d — forbidden/lifecycle/security static guards + closure** | Forbidden APIs; raw thread/global-scope creation; cancellation broad catches (consume the existing cancellation verifier — do not reimplement); unbounded response-body reads; direct sensitive payload logging; final `check`/CI integration. | ⏳ planned |

## 10.1a — Incremental Kotlin formatting gate (implemented)

**Invariant:** every Kotlin `.kt` source file changed relative to the certified integration base must satisfy one deterministic repository formatting policy. Legacy files that have not changed must not force a repository-wide formatting migration.

### Design decisions

- **Tool:** Spotless Gradle plugin (root project only) with an explicitly pinned KtLint formatter engine. Both versions live in `gradle/libs.versions.toml` (`spotless`, `ktlint`). Spotless's implicit default KtLint version is never used.
- **Single authority:** one Spotless instance at the root targets `**/*.kt` repository-wide (including the `build-logic` included build) via root-relative file targets. No generic `tramai.quality` convention plugin (explicitly rejected by the 9.2 survey — quality policy remains root-owned). No competing formatter invocation anywhere.
- **Ratchet:** `ratchetFrom` the exact base — `-PtramaiFormattingBaseRef=<sha>` in CI (PR: `github.event.pull_request.base.sha`; push: `github.event.before`), `origin/master` locally when the property is absent. A moving branch name is never the authoritative CI comparison point.
- **Policy:** root `.editorconfig` is the single style authority (UTF-8, LF, final newline, no trailing whitespace, spaces, 4-space Kotlin indent, `ktlint_code_style = ktlint_official`). Deliberately conservative: no enforced line length (official style leaves it off) so adoption needs no reformat of untouched legacy source.
- **Wiring:** `spotlessCheck` joins the root `check` lifecycle (plugin default, not disabled); `verifyPr` depends on `spotlessCheck`. CI runs `spotlessCheck` explicitly with the exact base property. `ignoreFailures` / `enforceCheck` bypasses are never set.
- **Scope:** `**/*.kt` only. Kotlin Gradle scripts (`*.gradle.kts`) are excluded this slice — deferred until Track B's root/build-logic decomposition stabilizes. Generated/task-output paths (`**/build/**`, `**/.gradle/**`) are excluded narrowly.

### Adoption model

The gate is incremental by construction: files unchanged since the formatting base are invisible to the ratchet. Touching a legacy file activates the contract for that file. `./gradlew spotlessApply` is the deterministic repair path and only ever rewrites changed files.

### Enforcement proof

- P0-A..P0-J discriminator campaign (changed-bad fails, repair works, legacy untouched passes, touched legacy fails, exact supplied base authoritative, build-logic covered, generated output ignored, `check` owns the gate, `verifyPr` owns the gate, configuration-cache cold→warm).
- M01..M06 hand-applied mutation campaign against those discriminators.
- Configuration-cache compatibility proven with `--configuration-cache-problems=fail` (cold stores, warm reuses, zero problems).

## Out of scope until their own slices

Detekt formatting/KtLint wrappers, Sonar rewrite, unused-dependency detection, compiler-warning policy changes, global `-Werror`, forbidden-API scanners, cancellation reimplementation, nondeterminism-enforcement changes, mass legacy formatting, `.gradle.kts` formatting, production runtime behavior changes, public API changes, module-architecture changes, `tramai.quality`.

## 10.1b — Baseline-backed Kotlin static analysis (in progress)

**Invariant:** existing static-analysis debt may remain temporarily, but it may only shrink. New static-analysis findings must not cross the PR gate unnoticed or be hidden by casually expanding the baseline.

**Baseline philosophy:** the Detekt baseline freezes pre-existing debt. It is a **ceiling, not an allowance budget**. A green gate means zero NEW findings, not zero findings.

### Design decisions

- **Tool:** Detekt 1.23.8 (pinned in `StaticAnalysisPlugin`), executed as a root-scoped CLI task (`verifyStaticAnalysis`) over the whole repository. No per-module Detekt plugins/configs/baselines. T0 proved 1.23.8 parses the repository's Kotlin 2.3 source identically under JDK 21 and JDK 25 (1884/1884 representative findings, symmetric difference 0).
- **One authority:** `config/detekt/detekt.yml` (standard/default rules only; no `allRules`, no `detekt-formatting`, no KtLint wrappers), `config/detekt/baseline.xml` (one central baseline), one report location `build/reports/static-analysis/` (detekt.xml + detekt.sarif + detekt.html + summary.txt).
- **Model note:** the CLI runs without a compile classpath (pure source analysis). Rules that require type resolution are not analyzed in this slice and are deliberately excluded from the contract.
- **Growth contract (`DetektBaselineGrowthVerifier`):** removals allowed; additions fail with `DETEKT_BASELINE_GROWTH` unless the PR is an explicit `baseline-migration`; deletion, emptying, malformed, and duplicate-ID baselines fail; bootstrap (base absent) is one-time only — keyed on the base file's absence, so delete-and-recreate can never reactivate it.
- **Change policy:** `config/detekt/baseline.xml` is a recognized canonical baseline alongside `config/quality/0.6.0-baseline.json`; a baseline-migration PR must change at least one of them and may not touch runtime production.
- **Wiring:** `verifyStaticAnalysis` joins the root `check` lifecycle and `verifyPr`; CI runs it explicitly against the exact base (`pull_request.base.sha` / `event.before`). Fail-closed: tool failure is not zero findings.
- **Suppression policy:** zero source-level `@Suppress` introduced; exceptions belong in the central config with rationale.
- **Scope:** `**/src/**/*.kt` repository-wide (main, test, custom source sets, build-logic, examples), excluding `**/build/**` and `**/.gradle/**`. The `src` path segment keeps the `dev.tramai.build` package fully covered.

### Enforcement proof

- P0-A..P0-O permanent contract suite (`StaticAnalysis*Test`, 24 tests) + configuration-cache cold→warm + `ChangePolicyEvaluator` canonical-baseline tests. Mutation campaign M01..M10 against the discriminators.
