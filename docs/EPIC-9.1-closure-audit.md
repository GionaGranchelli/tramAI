# Epic 9.1 — Module Layers and Maturity: Closure Audit

**Track:** B (module architecture)
**Audit date:** 2026-08-26
**Scope:** Classify every Epic 9.1 acceptance criterion and task against the current repository state (master `23def518` + Track B1 branch `feat/0.6.0-module-architecture` `a5433525`).
**Method:** Each criterion/task is mapped to concrete evidence (file:line or artifact). Verdict: ✅ satisfied / 🟡 partially satisfied / ❌ missing. Only ❌ and 🟡 items are candidates for further production/build changes.

---

## 1. Acceptance criteria

### AC1 — Publishability is not maintained through several hand-written lists

| State | Verdict | Evidence |
|-------|---------|----------|
| master | ❌ | Hand-written `publishableProjectNames` (build.gradle.kts ~L54), `jarPublishingProjectNames`, `sovereignBundleModuleNames` (~L102), hand-written `api(project(...))` list in tramai-bom/build.gradle.kts (39 entries), hand-written `include(...)` in settings.gradle.kts (58 entries). Catalog is a 6th, separate source → drift is real: `:tramai-scheduler`, `:tramai-spring-boot-starter-local-provider-openai`, `:tramai-spring-boot-starter-sovereign-ops-rest`, `:tramai-spring-boot-starter-sovereign-persistence-jdbc` are `published` in the catalog but absent from `publishableProjectNames`; BOM includes `sovereign-ops-rest` which is not publishable. |
| B1 branch | ✅ | `ModuleManifest.kt` derives `publishableModulePaths()` + `bomModulePaths()` from the catalog (pure functions, no Gradle state). build.gradle.kts −71 lines and tramai-bom −44 lines replace literal lists with derivation. `verifyModuleManifest` (MaintainabilityBaselinePlugin.kt L817) cross-checks against the *actual* Gradle model: settings project set, `tramai.publishableModulePaths` extra-property, and tramai-bom `api` constraint graph. Wired into `verifyPr` (L887). |

### AC2 — Every module has a documented reason to exist

| State | Verdict | Evidence |
|-------|---------|----------|
| master | 🟡 | No per-module rationale anywhere machine-readable; prose exists in docs/architecture/modules.md but is not tied to the catalog. |
| B1 branch | ✅ | `rationale` field added to schema v2 and filled for all 58 modules (via YAML anchors); enforced non-blank (ModuleCatalogMutationTest M7 "blank owner and rationale are rejected"). Generated `docs/reference/module-matrix.md` surfaces layer/maturity/api/published/owner/release per module. |

### AC3 — CI rejects dependency cycles and forbidden layer edges

| State | Verdict | Evidence |
|-------|---------|----------|
| master | ✅ | `BaselineVerifier.verifyDependencyCycles()` (L483) rejects new cycles (wired L134); `verifyForbiddenEdges()` (L507) runs `ModuleBoundaries.checkEdge()` over the resolved dependency graph; `config/quality/module-boundaries.yml` holds forbidden/known-allowed edges. Both run inside `verifyMaintainabilityBaseline` → part of `verifyPr` and CI. |
| B1 branch | ✅ | Keeps both; adds mutation suite `ModuleCatalogMutationTest` M1–M8 proving the guards guard: missing module (M1), ghost module (M2), forbidden core→openai edge (M3), dependency cycle A→B→A (M4), BOM drift (M5), publishing drift (M6), derivation guard for internal modules (M6b), blank owner/rationale (M7), invalid policy (M8). |

---

## 2. Tasks

| # | Task | master | B1 branch | Notes |
|---|------|--------|-----------|-------|
| 1 | Machine-readable module manifest (layer, maturity, publishability, public/internal, owner, allowed dependencies, release inclusion) | 🟡 only `layer`/`publishability`/`apiStability` | ✅ schema v2 adds `maturity`, `visibility`, `owner`, `dependencyPolicy`, `releaseInclusion`, `rationale` as typed enums (ModuleCatalog.kt); schemaVersion bumped and validated | All 9.1 fields present on B1 |
| 2 | Generate settings, BOM inclusion, publishing lists, module matrix, documentation indexes from manifest where practical | ❌ all hand-written | 🟡 BOM + publishing + matrix generated from catalog; **settings deliberately NOT generated** — equality-validated instead (`verifyModuleManifest` compares settings project set against catalog; spec §3.6: "Do NOT generate settings.gradle.kts") | "Where practical" — settings generation is a conscious non-goal; validation achieves the same guarantee with less risk |
| 3 | Dependency-direction verification | ✅ forbidden edges checked | ✅ same + typed enums | |
| 4 | Detect cycles and forbidden edges | ✅ cycles + forbidden edges | ✅ + M4 mutation proof | |
| 5 | Review modules with overlapping responsibilities or too little independent value | ❌ no artifact | ❌ no artifact | Explicit non-goal of B1 (spec §6: "No module reorganization or consolidation") — genuine follow-up |
| 6 | Consolidate only where it improves ownership/dependency clarity | ❌ | ❌ | Same non-goal; depends on task 5 output |

---

## 3. Verdict

- **AC1, AC2, AC3: all satisfied by the B1 branch.** Master fails AC1 outright (confirmed drift) and is partial on AC2; B1 closes all three.
- **Tasks 1–4: satisfied by B1.** Task 2 is partial by design (settings validation instead of generation — acceptable and documented).
- **Tasks 5–6 (overlap review, consolidation): genuinely missing everywhere.** They are explicit non-goals of B1 and belong to a separate follow-up (9.1b) that should consume the overlap review once B1 merges.

## 4. Only missing pieces that warrant production/build changes

1. **B1 itself** — it is the change that satisfies AC1–AC3. Requires rebase onto post-#297 master (branch predates #297; diff currently shows pull_request_template.md/CONTRIBUTING.md as deletions), verifyPr green, review, merge.
2. **9.1b — overlap/consolidation review (tasks 5–6)** — new task after B1: enumerate modules with overlapping responsibilities or weak independent value, propose consolidation only where ownership/dependency clarity improves, with evidence. No production change until that review lands.

## 5. Verification performed

- `verifyChangePolicy`, `verifyMaintainabilityBaseline`, `verifyJUnitTestSignatures` — PASS on master (this audit is docs-only).
- B1 branch not yet verified locally (unmerged; verification belongs to the B1 PR itself).
