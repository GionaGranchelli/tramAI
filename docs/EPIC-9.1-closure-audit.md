# Epic 9.1 — Module Layers and Maturity: Closure Audit

**Track:** B (module architecture)
**Audit date:** 2026-08-26 (updated after #298 merge)
**Scope:** Classify every Epic 9.1 acceptance criterion and task against the current repository state (master `8705c5bd`, which includes B1 merged as #298).
**Method:** Each criterion/task is mapped to concrete evidence (file:line or artifact). Verdict: ✅ satisfied / 🟡 partially satisfied / ❌ missing. Only ❌ and 🟡 items are candidates for further production/build changes.

---

## 1. Acceptance criteria

### AC1 — Publishability is not maintained through several hand-written lists

| State | Verdict | Evidence |
|-------|---------|----------|
| master before #298 | ❌ | Hand-written `publishableProjectNames` (build.gradle.kts ~L54), `jarPublishingProjectNames`, `sovereignBundleModuleNames` (~L102), hand-written `api(project(...))` list in tramai-bom/build.gradle.kts (39 entries), hand-written `include(...)` in settings.gradle.kts (58 entries). Catalog was a 6th, separate source → drift was real: `:tramai-scheduler`, `:tramai-spring-boot-starter-local-provider-openai`, `:tramai-spring-boot-starter-sovereign-ops-rest`, `:tramai-spring-boot-starter-sovereign-persistence-jdbc` were `published` in the catalog but absent from `publishableProjectNames`; BOM included `sovereign-ops-rest` which was not publishable. |
| master after #298 (B1 merged) | ✅ | `ModuleManifest.kt` derives `publishableModulePaths()` + `bomModulePaths()` from the catalog (pure functions, no Gradle state). build.gradle.kts −71 lines and tramai-bom −43 lines replace literal lists with derivation. `verifyModuleManifest` (MaintainabilityBaselinePlugin.kt L817) cross-checks against the *actual* Gradle model: settings project set, `tramai.publishableModulePaths` extra-property, and tramai-bom `api` constraint graph. Wired into `verifyPr` (L887). Re-checked after rebase: all 4 previously-omitted modules now derived as published + release-included; BOM structurally cannot reference non-publishable modules (`BOM_DRIFT`/`PUBLISHING_DRIFT` diagnostics exist in VerificationDiagnostic.kt). |

### AC2 — Every module has a documented reason to exist

| State | Verdict | Evidence |
|-------|---------|----------|
| master before #298 | 🟡 | No per-module rationale anywhere machine-readable; prose existed in docs/architecture/modules.md but was not tied to the catalog. |
| master after #298 (B1 merged) | ✅ | `rationale` field in schema v2, filled for all 58 modules (via YAML anchors); enforced non-blank (ModuleCatalogMutationTest M7 "blank owner and rationale are rejected"). Generated `docs/reference/module-matrix.md` surfaces layer/maturity/api/published/owner/release per module. |

### AC3 — CI rejects dependency cycles and forbidden layer edges

| State | Verdict | Evidence |
|-------|---------|----------|
| master | ✅ | `BaselineVerifier.verifyDependencyCycles()` (L483) rejects new cycles (wired L134); `verifyForbiddenEdges()` (L507) runs `ModuleBoundaries.checkEdge()` over the resolved dependency graph; `config/quality/module-boundaries.yml` holds forbidden/known-allowed edges. Both run inside `verifyMaintainabilityBaseline` → part of `verifyPr` and CI. |
| master after #298 | ✅ | Keeps both; mutation suite `ModuleCatalogMutationTest` M1–M8 proves the guards guard: missing module (M1), ghost module (M2), forbidden core→openai edge (M3), dependency cycle A→B→A (M4), BOM drift (M5), publishing drift (M6), derivation guard for internal modules (M6b), blank owner/rationale (M7), invalid policy (M8). All 9 tests pass on master (verified via test-results XML). |

---

## 2. Tasks

| # | Task | master before #298 | master after #298 | Notes |
|---|------|--------|-----------|-------|
| 1 | Machine-readable module manifest (layer, maturity, publishability, public/internal, owner, allowed dependencies, release inclusion) | 🟡 only `layer`/`publishability`/`apiStability` | ✅ schema v2 adds `maturity`, `visibility`, `owner`, `dependencyPolicy`, `releaseInclusion`, `rationale` as typed enums (ModuleCatalog.kt); schemaVersion bumped and validated | All 9.1 fields present |
| 2 | Generate settings, BOM inclusion, publishing lists, module matrix, documentation indexes from manifest where practical | ❌ all hand-written | 🟡 BOM + publishing + matrix generated from catalog; **settings deliberately NOT generated** — equality-validated instead (`verifyModuleManifest` compares settings project set against catalog; B1 spec §3.6: "Do NOT generate settings.gradle.kts") | "Where practical" — settings generation is a conscious non-goal; validation achieves the same guarantee with less risk |
| 3 | Dependency-direction verification | ✅ forbidden edges checked | ✅ same + typed enums | |
| 4 | Detect cycles and forbidden edges | ✅ cycles + forbidden edges | ✅ + M4 mutation proof | |
| 5 | Review modules with overlapping responsibilities or too little independent value | ❌ no artifact | ❌ no artifact | → 9.1b (separate review-only PR) |
| 6 | Consolidate only where it improves ownership/dependency clarity | ❌ | ❌ | → 9.1b; only if evidence justifies it |

---

## 3. Verdict

- **AC1, AC2, AC3: all satisfied on master after #298.** Master before #298 failed AC1 outright (confirmed drift) and was partial on AC2; #298 closed all three.
- **Tasks 1–4: satisfied on master.** Task 2 is partial by design (settings validation instead of generation — acceptable and documented).
- **Tasks 5–6 (overlap review, consolidation): genuinely missing.** They are the remaining 9.1 work, tracked as 9.1b (review-only PR first; consolidation only with evidence, as separate PRs).

## 4. Only missing pieces that warrant production/build changes

1. **9.1b — overlap/consolidation review (tasks 5–6)** — review all 58 modules, classify each KEEP / CLARIFY / CONSOLIDATE with evidence. Review-only PR (no production change). Consolidation candidates, if any, become separately scoped follow-up PRs. Zero justified candidates is a valid completion of Task 6.

## 5. Verification performed

- `verifyChangePolicy`, `verifyMaintainabilityBaseline`, `verifyJUnitTestSignatures` — PASS on master (this audit is docs-only).
- B1 was rebased onto post-#297 master and verified before its merge: `verifyModuleManifest` PASS, `verifyPr` BUILD SUCCESSFUL, `ModuleCatalogMutationTest` 9 tests / 0 failures (XML-confirmed), AC1 drift cases re-checked (4 previously-omitted modules derived correctly; BOM structurally cannot reference non-publishable modules). Merged as #298 (`8705c5bd`).
