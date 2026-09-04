# Epic 12.2 — Example and documentation migration: pre-certification audit

**Status: PRE-CERTIFICATION AUDIT (not COMPLETE).** The final acceptance
criterion — *"Every public example compiles against published 0.6.0
artifacts"* — remains **PENDING_PUBLISHED_ARTIFACT_CERTIFICATION** until the
real 0.6.0 artifacts are assembled and the published-artifact matrix is
executed.

- Audit base: `master` `c032582` (after Epics 8.2/11.1 closure records).
- Prior work treated as evidence (not repeated): #321 (examples: canonical
  lifecycle ownership, `PolicyConfiguration.preview()` removal from public
  examples, explicit tool/provider governance, safe engine close, unified
  Spring starter migration, standalone version-property cleanup), #323 (docs:
  stale version snippets, standalone-consumer navigation, graph staleness
  notice).
- Audit date: 2026-09-04. Docs fixes in this record's PR are the only code
  changes; nothing in Signal's lane was touched.

---

## Roadmap task classification

| # | Roadmap task | Verdict | Evidence / remaining action |
|---|---|---|---|
| 1 | Migrate all examples to the canonical lifecycle API | **COMPLETE** (#321) | `use {}`/deterministic `close()` in every example main + test (tool-governance, governed-workflow, approval-resume, sovereign-*, spring-sovereign-starter, support-agent). Re-verified by the example compile/test runs below. |
| 2 | Migrate examples away from deprecated preview APIs | **COMPLETE** (#321) | No `PolicyConfiguration.preview()` usage remains in any public example (grep-verified); tool-governance builds deny-by-default policy via `governedPolicyFor`. `PolicyConfiguration.preview()` remains only as a documented 0.4.x-compat preset in production (`tramai-security`) and its own tests. |
| 3 | Explicit safe-error handling in examples | **COMPLETE** | Example mains catch typed failures (`WorkflowGateRejectedException`, `PolicyViolationException`, `ApprovalSuspendedException`) and print `class: message` — no stack dumps, no swallowed errors. |
| 4 | Governed examples declare outbound-network/tool policy | **COMPLETE** (#321) | tool-governance + governed-workflow declare exact tools/models/providers/permissions + approval risk levels; no wildcard allowlists. |
| 5 | Module guides reflect the current modularized surface | **COMPLETE (this audit)** | `spring-boot.md` already canonical (unified starter, `tramai.profile`, migration section). This audit currentized `module-guide.md` L1 (flowchart/decision table/template now name `tramai-spring-boot-starter`, not the legacy `tramai-spring` facade) and re-pointed the Spring property-binding/secrets mentions in `providers.md` + `production-hardening.md` to the starter (`tramai-spring-core` holds the auto-config). |
| 6 | Architecture diagrams/docs match actual package/module boundaries | **NEEDS CURRENTIZATION → resolved (this audit)** | See P0 item below: `module-dependency-graph.md` reclassified HISTORICAL; current topology authorities named and already pointed to by `ARCHITECTURE.md` + `modules.md`. `module-matrix.md` is generated from the current catalogue (60 modules). |
| 7 | Remove duplicated/obsolete design documents or give one canonical successor | **COMPLETE / no action** | Sampled candidate pairs (`architecture/overview.md` vs ARCHITECTURE.md, `human-approval-workflow-ergonomics` design doc vs `guides/approval-workflow-ergonomics`, `orchestrator-vision.md`): each is referenced and plays a distinct role (map / design boundary / vision). No duplicate pair found that lacks a canonical owner. |
| 8 | Historical documents clearly historical, out of primary navigation | **COMPLETE with one fix (this audit)** | `module-dependency-graph.md` retitled HISTORICAL (below). Release/roadmap/board docs are unlinked or phase-marked; primary navigation (`ARCHITECTURE.md`, `docs/architecture/modules.md`, `docs/modules/README.md`) points only at live authorities. |

---

## P0 audit item — module-dependency-graph.md

**Finding confirmed:** the document title claimed *"TramAI 0.6.0 — Module
Dependency Graph"* while its substantive inventory is the v0.5.0 baseline
snapshot (48 modules); the live module catalogue authority lists 60 modules.

**Resolution (option 2 — intentionally historical, reclassified):**

1. **Regeneration through the supported machinery cannot produce an
   authoritatively current document.** `generateModuleDependencyGraph`
   (`build-logic/.../quality/BaselineGenerator.kt`,
   `generateModuleDependencyGraphMarkdown`) hard-codes the canonical
   `BASELINE_TAG` identity (`v0.5.0`) and schema-version-1 header onto every
   output regardless of the scanned tree, and resolves that identity via
   `git rev-parse v0.5.0^{commit}` (the tag exists remotely; a regeneration
   would still stamp a v0.5.0-labeled header over the current inventory and
   would strip #323's staleness notice). There is no "current tree" markdown
   mode. This is recorded as a **known machinery gap**, not a release
   blocker: the current topology has other live authorities.
2. **The document is intentionally historical**: it is the v0.5.0
   release-baseline snapshot retained as evidence (referenced from
   `docs/releases/0.6.0-maintainability-baseline.md`).
3. **Reclassified in this PR**: retitled `TramAI — Module Dependency Graph
   (v0.5.0 baseline snapshot — HISTORICAL)`; the notice now states the
   generator limitation explicitly and names the live authorities
   (`config/quality/module-catalog.yml`, `docs/reference/module-matrix.md`,
   `./gradlew verify060Architecture`). Primary navigation
   (`ARCHITECTURE.md` row, `docs/architecture/modules.md`) already labeled
   the file a v0.5.0 snapshot before this PR; the document itself no longer
   contradicts that label.

---

## Scans (no stale residue in active docs/examples)

- **Preview API residue in public examples:** none (only the documented
  production compat preset + its tests remain).
- **Stale type/module names:** all `tramai-*` names used by
  `docs/module-guide.md` exist in the current module catalogue (52 module
  paths + example entries = 60); `tramai-spring` is a real legacy facade
  entry, no longer the module steered to in active guides (this audit).
- **Stale starter coordinates:** `dev.tramai:tramai-spring` (old coordinate)
  appears only in historical release docs and as the labelled legacy entry;
  active guides/examples use the unified starter.
- **Hard-coded old versions in active docs/examples:** `examples/README.md`
  "0.4.0 is the latest published release" is **accurate** (v0.5.0 tag exists
  but Central publication is pending per `docs/STATUS.md`); guides use
  `<version>`/`TRAMAI_VERSION` placeholders after #323; `limitations.md`
  stale "0.3.x line" corrected to 0.5.x in this audit.
- **Historical docs in primary navigation:** none; the graph was the only
  borderline case and is now explicitly historical.

---

## Example compilation rehearsal (local candidate)

Rehearsal mirrors the publish-workflow local-dry-run shape
(`.github/workflows/publish.yml`), run against the current candidate
published locally — **not** against published 0.6.0 artifacts.

| Step | Command | Result |
|---|---|---|
| Local candidate publication | `./gradlew publishToMavenLocal --no-configuration-cache` | **BUILD SUCCESSFUL** (current trunk candidate, v0.5.0) |
| Sovereign signed-bundle validation | `./gradlew verifySovereignRuntimeSignedBundle --no-configuration-cache` | **BUILD SUCCESSFUL** (bundle published to `build/sovereign-runtime-release-verification-repo`; requires `-PtramaiPublishReleaseUrl=` override when an ambient release URL is set — the task's local-only guard) |
| Standalone sovereign consumer smoke | `./gradlew verifySovereignRuntimeConsumerSmoke --no-configuration-cache` | **BUILD SUCCESSFUL** (consumer resolves `dev.tramai:*:0.5.0` from the verification repo only; no mavenLocal) |
| Standalone Spring Boot consumer smoke | `./gradlew -p examples/kotlin-springboot-example smokeTest --no-configuration-cache -PtramaiVersion=0.5.0` | **BUILD SUCCESSFUL** (resolves from mavenLocal) |

**Rehearsal outcome:** `LOCAL_CANDIDATE_PASS` — all four steps green
(2026-09-04, master `c032582` + the docs-only fixes in this PR).

**Excluded from the rehearsal (recorded):** `kotlin-native-smoke-example`
requires the Kotlin/Native toolchain download and is not part of the CI
local-dry-run either; its `compileKotlin` was verified at `-PtramaiVersion=0.5.0`
in #321. The in-root consumer-smoke modules (`examples/java-consumer-smoke`,
`examples/kotlin-consumer-smoke`) compile against project dependencies in the
normal PR test lanes, not published coordinates — they are covered by the
final published-artifact matrix, not by this rehearsal.

---

## Requirement table

| Requirement | Current evidence | Verdict | Remaining action |
|---|---|---|---|
| Tasks 1–8 current (no stale types/modules/versions/preview APIs/coords; one canonical page per concept) | This audit (scans + classification above) + #321/#323 evidence | **Materially complete** | None before certification |
| Architecture graph authority | Graph reclassified HISTORICAL; catalogue + matrix + `verify060Architecture` are the live authorities | **Resolved (P0)** | None |
| Public examples compile against **published 0.6.0 artifacts** | — | **PENDING_PUBLISHED_ARTIFACT_CERTIFICATION** | Execute the example/standalone-consumer matrix against the real 0.6.0 published artifacts once they exist; then mark 12.2 ✅ COMPLETE |
| Local publication rehearsal | publishToMavenLocal + signed bundle + sovereign consumer smoke + Spring Boot smoke all BUILD SUCCESSFUL | **`LOCAL_CANDIDATE_PASS`** | None — rehearsal only, never treated as the published-artifact certification |

**Deliberately not touched:** Signal's mutation/PIT/config-quality authority,
Epic 12.3 independent review, Epic 12.4 final release command, 12.1 frozen
benchmark/resource machinery.
