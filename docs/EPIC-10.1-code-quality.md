# Epic 10.1 — Code Quality (formatting, static analysis, hygiene)

Phase 10 — Continuous Code Quality. This document freezes the slicing of Epic 10.1.
Slices are implemented in separate PRs; a slice is only "done" when its merge gate is green.

## Slicing (frozen)

| Slice | Scope | Status |
|---|---|---|
| **10.1a — formatting** | Incremental Kotlin source formatting gate (Spotless + pinned KtLint, git-ratcheted against the exact PR/push base). Changed `.kt` must satisfy one deterministic policy; untouched legacy source is never mass-formatted. | ✅ merged (`731126bf`, PR #339) |
| **10.1b — static analysis** | Detekt 1.23.8 (pinned), one central config + one central baseline, baseline-backed growth protection, fail-closed. | ✅ merged (`3aa4ef72`, PR #342) |
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

## 10.1b — Baseline-backed Kotlin static analysis (merged `3aa4ef72`, PR #342)

**Invariant:** existing static-analysis debt may remain temporarily, but it may only shrink. New static-analysis findings must not cross the PR gate unnoticed or be hidden by casually expanding the baseline.

**Baseline philosophy:** the Detekt baseline freezes pre-existing debt. It is a **ceiling, not an allowance budget**. A green gate means zero NEW findings, not zero findings.

### Design decisions

- **Tool:** Detekt 1.23.8 (pinned in `StaticAnalysisPlugin`), executed as a root-scoped CLI task (`verifyStaticAnalysis`) over the whole repository. No per-module Detekt plugins/configs/baselines. T0 proved 1.23.8 parses the repository's Kotlin 2.3 source identically under JDK 21 and JDK 25 (1884/1884 representative findings, symmetric difference 0).
- **One authority:** `config/detekt/detekt.yml` (standard/default rules only; no `allRules`, no `detekt-formatting`, no KtLint wrappers), `config/detekt/baseline.xml` (one central baseline, 4782 frozen findings), one report location `build/reports/static-analysis/` (detekt.xml + detekt.sarif + detekt.html + summary.txt).
- **Model note:** the CLI runs without a compile classpath (pure source analysis). Rules that require type resolution are not analyzed in this slice and are deliberately excluded from the contract.
- **Growth contract (`DetektBaselineGrowthVerifier`):** removals allowed; additions fail with `DETEKT_BASELINE_GROWTH` unless the PR is an explicit `baseline-migration`; deletion, emptying, malformed, and duplicate-ID baselines fail; bootstrap (base absent) is one-time only — keyed on the base file's absence, so delete-and-recreate can never reactivate it.
- **Change policy:** `config/detekt/baseline.xml` is a recognized canonical baseline alongside `config/quality/0.6.0-baseline.json`; a baseline-migration PR must change at least one of them and may not touch runtime production.
- **Wiring:** `verifyStaticAnalysis` joins the root `check` lifecycle and `verifyPr`; CI runs it explicitly against the exact base (`pull_request.base.sha` / `event.before`). Fail-closed: tool failure is not zero findings.
- **Suppression policy:** zero source-level `@Suppress` introduced; exceptions belong in the central config with rationale.
- **Scope:** `**/src/**/*.kt` repository-wide (main, test, custom source sets, build-logic, examples), excluding `**/build/**` and `**/.gradle/**`. The `src` path segment keeps the `dev.tramai.build` package fully covered.

### Enforcement proof

- P0-A..P0-O permanent contract suite (`StaticAnalysis*Test`, 25 tests) + configuration-cache cold→warm + `ChangePolicyEvaluator` canonical-baseline tests. Mutation campaign M01..M10 against the discriminators.

## 10.1c — Compiler + dependency hygiene (in progress)

**Frozen slice (roadmap):** compiler-warning review / `-Werror` where feasible; unused-dependency enforcement. Deliberately NOT a legacy-cleanup or module-architecture slice.

**Track A decision (2026-08-30):** proceed with a warning baseline + custom dependency verifier; corrections applied: `-Werror` exit status cannot perform baseline identity (rejected as the mechanism); diagnostic identity uses Kotlin internal diagnostic names, not text alone; dependency enforcement starts from zero (genuine removals, not a debt baseline); exemptions are module+configuration+coordinate+rationale scoped.

### T0 evidence (survey, pre-implementation)

- **Compiler warnings:** 526 repository-wide on Kotlin 2.3.0 — 253 main, ~250 test/testFixtures, 23 examples, 67 build-logic. JDK21 vs JDK25 parity proven: 526=526, symmetric difference 0 (the `tramai.kotlin-library` toolchain-21 pin makes the Gradle-daemon JDK irrelevant to the warning set).
- **Category breakdown:** 197 annotation-target future-change (Kotlin 2.3), 65 unnecessary `!!`, 57 Java-deprecations, 49+39 Tramai internal-API markers (intentional design), 28 supertype param-name mismatch, 21 unchecked casts, 20 AuditEmission deprecation (intentional migration), 15 platform-class-mapped-to-Kotlin, 11 unnecessary safe calls, ~24 misc. ~108 (21%) are intentional patterns.
- **Global `-Werror` verdict: rejected** — 526 existing warnings make it an adoption migration, not a gate.
- **Dependency survey:** 63 modules; ~37 declared-but-unused candidates. After triage: ~10 genuine removals (kotlinx-coroutines-core in 7 provider/boundary modules where main never references it; kotlin-test-junit5 in 4 modules whose tests use JUnit directly; okio in tramai-mcp; assertj in one example) and the rest are the known non-static classes: JDBC drivers via URL (h2 ×5, postgresql), kotlin-reflect (Spring Boot Kotlin runtime), auto-config modules (flyway, HikariCP, spring-boot-autoconfigure), Jackson modules via ServiceLoader registration. Those belong in the exemption catalog, not a baseline.
- **Dependency-analysis plugin (autonomousapps) rejected for this slice:** 3.19.1 imports `kotlin-bom 2.4.0` (leaks kotlin-daemon-client 2.4.0 onto the build classpath → `NoSuchMethodError` against the 2.3.0 daemon) and its kotlin-metadata-jvm caps at metadata 2.2; upstream 3.9.0 deliberately reverted Kotlin 2.3.20 compilation. Not adoptable at the repo's pinned Kotlin without breaking the dependency contract.

### T0.5 proof (diagnostic extraction, PASS)

Standalone `K2JVMCompiler` (kotlin-compiler-embeddable 2.3.0, resolved as a Gradle configuration like the Detekt CLI) reproduces the Gradle warning set exactly on 5 representative module/source-set combinations (13/13, 43/43, 10/10, 30/30, 16/16; symmetric difference 0). Invocation: `-jvm-target 21 -Xrender-internal-diagnostic-names` (+ `-Xfriend-paths` and the module's own output dirs on the classpath for test source sets). Output: `<repo-relative>.kt:<line>:<col>: warning: [DIAGNOSTIC_NAME] message`. Compiler exits 0 with warnings — the gate compares a warning inventory, never `-Werror` exit status.

### Compiler-warning gate (`verifyCompilerWarnings`)

- **Invariant:** existing compiler-warning debt may remain temporarily, but it may only shrink. New warnings must not cross the gate unnoticed or be hidden by editing the baseline.
- **Baseline:** `config/warnings/baseline.json` — identity = repository-relative path + `[DIAGNOSTIC_NAME]` + normalized message fingerprint + multiplicity. Line/column excluded (lines move). Frozen from the 526-warning inventory.
- **Deviation (documented):** build-logic's 67 warnings are NOT gated. kotlin-dsl compilation cannot be reproduced by a standalone kotlinc (embedded-compiler default imports/accessors), and root listeners cannot capture included-build compiler output (empirically proven). The gate baseline is the modules-only 459 (78 identities), exact T0 parity; build-logic stays frozen in the T0 inventory only, to be gated by a real-compile-output approach in a follow-up slice.
- **Mechanism:** per-module standalone `K2JVMCompiler` runs over the module classpaths (compileClasspath / testCompileClasspath + own outputs + friend-paths) for modules touched by the PR delta; parse with a strict, fail-closed regex; compare against baseline; any warning without a matching baseline entry fails. Removals allowed; additions fail; malformed/missing baseline fails closed.
- **Wiring:** joins `check`, `verifyPr`, and CI (label-gated bootstrap class where needed, mirroring 10.1b's `baseline-migration`).

### Dependency gate (`verifyDependencyHygiene`)

- **Invariant:** no unused direct dependency declared for main compilation/runtime semantics, except explicitly documented non-static usages.
- **Model:** start from zero — the ~10 genuine unused declarations are removed in this PR; the gate enforces forward hygiene. No dependency-debt baseline.
- **Mechanism:** per-module declared-dependency vs source-reference analysis (imports + compiled references where useful), main vs test configuration scoping. Not "Dependency Analysis Plugin Lite" — deliberately narrow.
- **Exemption catalog:** module + configuration + coordinate + rationale entries only (e.g. `tramai-vectorstore-pgvector` / `implementation` / `org.postgresql:postgresql` / "JDBC driver loaded through DriverManager"). No bare coordinate-wide exemptions. A stale exemption (dependency removed or now used) fails.

### Frozen discriminators

Compiler (C): C1 clean compilation passes · C2 introduced warning fails · C3 additional occurrence of a baseline warning fails · C4 warning removal passes · C5 line movement does not create a false new warning · C6 unknown diagnostic fails · C7 malformed/missing baseline fails closed · C8 JDK21/JDK25 same result · C9 `check` owns the verifier · C10 `verifyPr`/CI own the verifier.

Dependency (D): D1 used direct dependency passes · D2 genuinely unused implementation dependency fails · D3 test-only use does not justify an `implementation` declaration · D4 runtimeOnly is not statically misclassified · D5 ServiceLoader exemption passes · D6 reflection/auto-config exemption passes · D7 undeclared/stale exemption fails · D8 module-scoped exemption cannot exempt another module · D9 `check` owns the verifier · D10 `verifyPr`/CI own the verifier.

Mutation campaign (M-series) against the discriminators; both gates in the 10.1b root-owned build-logic pattern; configuration-cache cold→warm certified; docs flip on exact-head green.
