# Epic 10.1 — Code Quality (formatting, static analysis, hygiene)

Phase 10 — Continuous Code Quality. This document freezes the slicing of Epic 10.1.
Slices are implemented in separate PRs; a slice is only "done" when its merge gate is green.

## Slicing (frozen)

| Slice | Scope | Status |
|---|---|---|
| **10.1a — formatting** | Incremental Kotlin source formatting gate (Spotless + pinned KtLint, git-ratcheted against the exact PR/push base). Changed `.kt` must satisfy one deterministic policy; untouched legacy source is never mass-formatted. | ✅ merged |
| **10.1b — static analysis** | Detekt or equivalent semantic/static analysis: baseline existing violations, prohibit growth, central suppression rationale. | ⏳ planned |
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

Detekt, Sonar rewrite, unused-dependency detection, compiler-warning policy changes, global `-Werror`, forbidden-API scanners, cancellation reimplementation, nondeterminism-enforcement changes, mass legacy formatting, `.gradle.kts` formatting, production runtime behavior changes, public API changes, module-architecture changes, `tramai.quality`.
