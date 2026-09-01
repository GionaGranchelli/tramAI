# Epic 10.2 — API Compatibility Closure (Track B3)

**Status:** spec v2 — amended per review (two-contract model, hash-bound migrations, stability inversion, real compile proofs)
**Branch:** `feat/0.6.0-api-compatibility` (base: master `b3e9a8f7`, B2 merged)
**PR title (planned):** `build(quality): enforce API compatibility semantics (Epic 10.2, Track B3)`
**Scope rule:** API compatibility *closure*, not API redesign. No runtime
production changes. Feeds typed evidence into the existing `api-architecture`
check of `verify060Architecture` — no new release gate, **no 11th check ID**.

---

## 0. Review decisions (binding, from round-1 review)

1. **Two separate contracts.** A single "diff committed dump vs generated" is
   NOT sufficient — a developer can regenerate and commit a dump, which would
   make the accidental-public-API-expansion guard useless.
   - **Contract 1 — CURRENT SOURCE ↔ CURRENT COMMITTED DUMP.** Ensures the
     committed dump represents the source.
   - **Contract 2 — BASE-BRANCH DUMP ↔ CURRENT DUMP.** Determines what API this
     PR changed. **This comparison drives stability policy.**
2. **Stable API is frozen for 0.6.0.** Any base→current stable API change,
   breaking *or additive*, emits `API_COMPATIBILITY_FAILED`. No intent
   inference — this is what provides the accidental-expansion guard.
3. **Migration entries authorize an exact transition, not merely a module.**
   `api-migrations.yml` binds `module`, `fromSha256`, `toSha256`,
   `targetVersion`, `rationale`, `migration`. Missing, duplicate,
   stale/orphaned, and wrong-hash entries must fail.
4. **Leak rule generalized to stability inversion** (not just internal types):
   `stable` → may expose `stable`; `preview` → `stable`/`preview`;
   `experimental` → `stable`/`preview`/`experimental`; `internal` →
   unrestricted internally. So `stable→preview` is illegal too.
5. **Consumer proofs must prove real compilation.** B9/B10 assert non-empty
   source sets AND resulting compiled classes on the intended minimal consumer
   classpath — not merely task success.

---

## 1. What already exists (inventory, verified on master b3e9a8f7)

| Piece | Location / state |
|-------|------------------|
| Binary-compatibility plugin | `org.jetbrains.kotlinx.binary-compatibility-validator` 0.16.3, applied at root (`build.gradle.kts:23`, `gradle/libs.versions.toml:75`) |
| `apiCheck`/`apiDump`/`apiBuild` tasks | Per module. `apiBuild` writes `build/api/<module>.api` (non-throwing generator); `apiCheck` fails on drift (never invoked by any gate) |
| Committed API dumps | **50** `api/*.api` files; `tramai-bom` has NO dump (platform, `applicable=false`) |
| Baseline API records | `config/quality/0.6.0-baseline.json` → `api.modules` (48 records; 1 applicable stable `:tramai-core`, `:tramai-bom` stable-not-applicable, 33 applicable preview, 6 applicable internal) |
| Metadata verifier | `ApiBaselineVerifier.kt`: baseline empty, duplicate dumps, unclassified module, catalog-stability mismatch, unsafe dump paths, missing dumps, stable-without-apiCheck, nondeterministic dump content |
| Diagnostic codes | `API_BASELINE_EMPTY`, `API_DUMP_MISSING`, `API_DUMP_DUPLICATE`, `API_MODULE_UNCLASSIFIED`, `API_VALIDATION_NOT_CONFIGURED`, `API_COMPATIBILITY_FAILED` (**dead today**), `API_HASH_CHANGED`, `API_DUMP_NONDETERMINISTIC` — all mapped to `api-architecture` (`MaintainabilityBaselinePlugin.kt:1291-1298`) |
| Base SHA resolution | `changePolicyBase` project property already exists (`MaintainabilityBaselinePlugin.kt:818`; CI passes `github.event.pull_request.base.sha`); plugin already shells out to git for worktree creation (line 489) |
| Catalog taxonomy | `ModuleApiStability` = {STABLE, PREVIEW, INTERNAL, EXCLUDED} — **no EXPERIMENTAL**; `ModuleMaturity` = {stable, preview, experimental, internal}. Existing combination rules at `ModuleCatalog.kt:67-73`: published≠excluded, examples excluded, internal-maturity ⇒ internal/excluded API, stable-API ⇒ not experimental-maturity |
| Consumer examples | `examples/sovereign-runtime-consumer-smoke` (Kotlin, publishes-only), `examples/kotlin-springboot-example` (Kotlin, CI publish step). **No Java consumer fixture anywhere** (0 `.java` in examples/) |

## 2. Gaps (what B3 must close)

1. `apiCheck` is enforced nowhere: CI runs `./gradlew test`, `verifyPr` omits
   it, no workflow calls it. A stable-API break merges silently.
2. `API_COMPATIBILITY_FAILED` is dead — no verifier ever emits it.
3. No stable/preview/experimental semantics; `experimental` missing from
   `ModuleApiStability`.
4. No migration-note evidence for intentional preview changes.
5. No Java consumer compilation proof; Kotlin proof not wired as a gate.
6. No stability-inversion (leak) enforcement — an internal *or preview* type
   can appear in a stable API without any gate noticing.

## 3. Design

### 3.1 Evidence model — two contracts, one verifier

All signature comparison reuses BCV's dump format (`apiBuild` per module =
non-throwing current-source dump generator). The gate consumes dumps as typed
diagnostics; **no raw `dependsOn(apiCheck)`** (apiCheck aborts the task graph
before the B2 report is written).

```
Contract 1 (source honesty):
  build/api/<module>.api (apiBuild output = current source)
        vs
  api/<module>.api (committed dump)
  mismatch → API_COMPATIBILITY_FAILED "dump does not represent source"
             (developer changed source and forgot apiDump)

Contract 2 (PR delta, drives policy):
  git show <baseSha>:api/<module>.api   (base-branch dump)
        vs
  api/<module>.api (current committed dump)
  baseSha = -PchangePolicyBase, else origin/master
  mismatch = "this PR changed the API"

Stability policy (applied to Contract-2 results only):
  stable        → ANY change (breaking or additive) → API_COMPATIBILITY_FAILED
  preview       → change requires EXACT api-migrations.yml entry, else FAIL
  experimental  → change requires EXACT entry too (same mechanism)
  internal      → no compat gate (ordinary internal refactors don't fail)
  excluded      → no compat gate
```

Contract-1 violations are **always** failures (stale dump = broken evidence),
for every applicable module. Base-dump resolution failure (git unavailable /
ref not found) = evidence-unavailable → fail-closed: the api-architecture
check FAILs with a typed `API_COMPATIBILITY_FAILED` "base dump unavailable"
diagnostic (consistent with A12/A13 doctrine). A module absent at base (new
module) = empty base dump; every added line is a change subject to policy.

### 3.2 Hash-bound migration registry

New committed file `config/quality/api-migrations.yml`:

```yaml
- module: ":tramai-engine"
  fromSha256: "sha256 of base-branch dump content"
  toSha256:   "sha256 of current committed dump content"
  targetVersion: "0.6.0"
  rationale: "Removed deprecated overload; callers migrate to typed invoke."
  migration: "Replace tramaiEngine.invoke(legacy) with tramaiEngine.call(...)."
```

Enforcement — every condition FAILs:
- **missing**: preview/experimental module has a Contract-2 diff but no entry
- **duplicate**: two entries with identical (module, fromSha256, toSha256)
- **stale/orphaned**: entry whose (module, fromSha256, toSha256) matches no
  actual module transition (module unchanged, or hashes don't match any real
  base→current pair)
- **wrong-hash**: entry exists but fromSha256/toSha256 ≠ computed hashes of
  the actual base/current dump content
- **targetVersion mismatch**: ACTIVE candidate entry with targetVersion ≠
  current project version (enforced for ACTIVE authorizations; LANDED entries
  keep their historical version — E1)
- Stable modules can never use entries (stable change = hard FAIL regardless).

Entry lifecycle (D1/E1) — a registry entry is valid evidence in exactly two states:

| State | Condition | Effect |
|-------|-----------|--------|
| ACTIVE | base hash == `fromSha256` AND current hash == `toSha256` AND `targetVersion` == current project version | authorizes THIS exact transition |
| LANDED | its `to` hash has landed: `to` == current hash with no change (merged steady state), OR `to` == base hash while a further change is in flight (retained history) | valid retained evidence; authorizes nothing; `targetVersion` is historical metadata for the release it documented and MAY differ from the current project version |
| any other state | orphan / stale / wrong hash / wrong version (for ACTIVE) | FAIL |

`targetVersion` is enforced for ACTIVE authorizations only. A LANDED entry
keeps the historical version of the release that shipped it — requiring it to
equal the current project version would recreate the "registry poisons future
builds" problem after every version bump.

A LANDED entry must NOT block the next PR (base==current — the "merged
repository must be a valid steady state" invariant), and must NOT authorize a
later base→current change (Contract-2 requires a new ACTIVE entry for the new
transition at the current project version). Old entries may be retained as
history when the same module changes again.

Sha256 = SHA-256 of the dump file's UTF-8 content. Both hashes must match
*both* actual dumps — the entry authorizes that exact transition, nothing
broader. (The entry must be committed in the same PR as the dump change;
after it lands it becomes LANDED retained history — see the lifecycle table —
and a further dump change requires a new ACTIVE entry.)

### 3.3 Taxonomy closure

- Add `EXPERIMENTAL("experimental")` to `ModuleApiStability` and to
  `ApiBaselineVerifier.VALID_STABILITIES`.
- Generalize the existing combination rule into the strength matrix
  (`ModuleCatalog.kt:71`, one rule replaced):
  strength(apiStability) ≤ strength(maturity) where
  stable=4, preview=3, experimental=2, internal/excluded=unconstrained:
  - stable API ⇒ maturity must be `stable`
  - preview API ⇒ maturity ∈ {preview, stable}
  - experimental API ⇒ maturity ∈ {experimental, preview, stable}
  - internal/excluded API ⇒ any maturity
  Emits `MODULE_CATALOG_INVALID_COMBINATION`. (Preserves the existing
  internal-maturity rule at line 73.)
- No catalog data change required (0 experimental modules today; the 48
  current entries already satisfy the matrix).

### 3.4 Stability-inversion leak scan

Ownership is derived from the committed dumps themselves — no new package
map. Each dump declares its owned public class descriptors (lines of the form
`public ... class dev/tramai/<module>/...`). For each applicable module M:

1. Parse M's committed dump → owned class descriptors (normalized JVM
   descriptors, e.g. `dev/tramai/engine/TramaiEngine`).
2. Scan M's full dump text for referenced TramAI descriptors (class
   declarations, supertypes, method/field type signatures) — all occurrences
   of `dev/tramai/...` FQNs in the dump.
3. A reference to a descriptor owned by module N is a **stability inversion**
   when strength(N) < strength(M) (matrix above; JDK/Kotlin/third-party
   prefixes are ignored — only `dev/tramai/*` types are checked).
4. **Newly-introduced inversions** (present in current dump, absent in base
   dump) → `API_COMPATIBILITY_FAILED` (leak message naming M, N, and the
   descriptor).
5. **Pre-existing inversions** (already in base dump) → `API_*` WARNING
   severity, surfaced but not blocking — the PR gate enforces the matrix on
   what this PR changes; pre-existing violations are tracked as follow-up.
   (If the real catalog has zero pre-existing inversions — expected for
   `tramai-core` — the warning path never fires and behavior is strict.)

### 3.5 Java consumer proof (real compilation)

New fixture `examples/java-consumer-smoke`:
- `src/main/java/...` with real Java sources exercising `:tramai-core`
  stable surface (annotations, core entrypoints) via
  `implementation(project(":tramai-core"))` — the minimal consumer classpath.
- Normal Gradle compilation is strict (`java` plugin, no failOnError
  override): a broken Java source fails `compileJava` like any module.
- `verifyJavaConsumerCompatibility` is a **fail-soft producer owned by the
  fixture project**: it invokes the real toolchain `javac` itself (the
  fixture's compile task is deliberately NOT a dependency — `compileKotlin`
  has no failOnError and would abort the graph before the report), records
  the exit code, and writes marker JSON. Empty source set → FAIL (zero-source
  trap guard); compiler exit ≠ 0 or no classes → marker records failure. The
  task itself never throws.

### 3.6 Kotlin consumer proof (real compilation)

New fixture `examples/kotlin-consumer-smoke`:
- `src/main/kotlin/...` real Kotlin sources against `:tramai-core` on the
  minimal consumer classpath.
- `verifyKotlinConsumerCompatibility` is a fail-soft producer owned by the
  fixture project: it runs `K2JVMCompiler` (via the fixture's own
  `kotlinCompilerClasspath` configuration, resolved at execution time —
  project-owned and lazy), records the exit code, and writes marker JSON.
  Same guards as 3.5.

### 3.7 Wiring into the B2 gate

`verify060Architecture`'s `api-architecture` check gains one new evidence
source: `ApiCompatibilityVerifier` (pure, testable) fed by:
- `apiBuild` outputs (Contract 1) — task dependency, non-throwing
- committed dumps on disk (Contract 1 + 2 current side)
- base dumps via `git show <baseSha>:<path>` (Contract 2 base side) —
  resolved through the existing `changePolicyBase` property (default
  `origin/master`), fail-closed on resolution failure
- `api-migrations.yml` registry
- module catalog (stability classes for policy + matrix)
- consumer compile markers (`consumer-java.json`, `consumer-kotlin.json`)
  written by the fixture-owned producers — read as typed evidence

All emissions are `API_*` codes routed by the existing exhaustive
`baselineCheckFor` → `api-architecture`. The 10 check IDs and the report
schema are unchanged. No new release gate. Consumer compile tasks are
**fail-soft producers**: they never throw, never terminate the graph before
the report; their markers are read as typed api-architecture evidence and the
façade fails only after the report is written.

## 4. RED discriminators (mutation suite, B-series)

New `ApiCompatibilityMutationTest.kt` in build-logic tests. Each must FAIL
before the implementation and PASS after (written RED-first):

- **B0 — source/current-dump mismatch fails**: Contract-1 drift (generated ≠
  committed) → `API_COMPATIBILITY_FAILED` regardless of module stability.
- **B1 — stable base→current change fails, including additive**: `:tramai-core`
  stable dump gains one signature (pure addition) → FAIL. No migration entry
  can rescue a stable change.
- **B2 — preview change without exact migration fails**: preview dump
  changed, registry empty → FAIL.
- **B3 — preview change with exact hash-bound migration passes**: preview
  dump changed, entry matches (module, fromSha256, toSha256, targetVersion) →
  PASS.
- **B4 — internal/excluded drift doesn't fail compatibility**: internal
  module dump changed, no entry → no compat diagnostics (metadata checks may
  still run).
- **B5 — stronger API leaking weaker TramAI type fails**: stable dump
  references preview-owned descriptor (new in current vs base) →
  `API_COMPATIBILITY_FAILED` leak; same for preview→experimental.
- **B6 — experimental classification works end-to-end**: catalog entry with
  `apiStability: experimental` parses, `VALID_STABILITIES` accepts, and a
  Contract-2 diff on it behaves like preview (entry required).
- **B7 — invalid maturity/API-strength combination fails**: `apiStability:
  stable` + `maturity: preview/experimental` →
  `MODULE_CATALOG_INVALID_COMBINATION`; `preview` + `experimental` rejected.
- **B8 — malformed/duplicate/orphan/hash-mismatched migration fails**:
  registry with wrong hashes, duplicate (module,from,to), orphan entry for an
  unchanged module, and ACTIVE targetVersion mismatch all FAIL.
- **B8b — landed migration entry lifecycle**: landed A→B with base/current B
  PASSes (steady state); landed A→B does NOT authorize a later B→C; a new
  exact entry authorizes B→C.
- **B8c — landed entries keep historical targetVersion**: landed A→B targeting
  0.5.0 with project now 0.6.0, base==current==B → PASS; that historical entry
  does NOT authorize a new B→C at 0.6.0 (new ACTIVE entry required).
- **B9 — Java real-source consumer compile proof**: fixture has >0 `.java`
  sources; the producer compiles them against `:tramai-core` only and the
  marker records success; deleting the sources fails the guard.
- **B10 — Kotlin real-source consumer compile proof**: fixture has >0 `.kt`
  sources; the producer compiles them against `:tramai-core` only and the
  marker records success; deleting the sources fails the guard.

Hardware rule: B0/B1/B2/B5/B8 are the release-critical REDs. B3/B4 prove the
gate does not over-fire. B6/B7 pin the taxonomy. B9/B10 protect the fixtures.

## 5. Files

### Create
- `config/quality/api-migrations.yml` — migration registry (initially empty)
- `examples/java-consumer-smoke/build.gradle.kts` + `src/main/java/...` — Java consumer fixture
- `examples/kotlin-consumer-smoke/build.gradle.kts` + `src/main/kotlin/...` — Kotlin consumer fixture
- `build-logic/src/test/kotlin/dev/tramai/build/quality/ApiCompatibilityMutationTest.kt` — B0–B10
- `build-logic/.../ApiCompatibilityVerifier.kt` — pure verifier: Contract-1 diff, Contract-2 diff, migration enforcement, inversion scan
- `docs/EPIC-10.2-api-compatibility.md` — this spec (committed first)

### Modify
- `build-logic/.../ModuleCatalog.kt` — add EXPERIMENTAL to `ModuleApiStability`; generalize combination rule to strength matrix
- `build-logic/.../ApiBaselineVerifier.kt` — VALID_STABILITIES + EXPERIMENTAL
- `build-logic/.../MaintainabilityBaselinePlugin.kt` — wire api-architecture evidence source (apiBuild deps, git-show base dumps, registry parse), register `verifyJavaConsumerCompatibility` / `verifyKotlinConsumerCompatibility`
- `config/quality/module-catalog.yml` — add the two new example fixture entries (excluded, like other examples)
- `docs/ROADMAP-0.6.0.md` — mark 10.2 tasks complete after merge

### Do NOT touch
- Any `tramai-*/src/main/**` runtime production file
- `config/quality/0.6.0-baseline.json`, `maintainability-deviations.yml`, `mutation-classifications.yml`, `test-quality.yml`
- `tramai-engine` or any provider runtime code (Track A 8.2g isolation)
- The B2 gate's report schema or check IDs (10 stable IDs unchanged)

## 6. Verification sequence (all must pass)

1. RED-first: `:build-logic:test --tests "ApiCompatibilityMutationTest"` fails before implementation, passes after.
2. `:build-logic:test` full suite.
3. `verify060Architecture` — PASS, report byte-identical across two runs (determinism; api-architecture still 10-check schema).
4. Deliberate failure: tamper `tramai-core/api/tramai-core.api` (add a line) → gate FAILs, report written, `api-architecture` red with `API_COMPATIBILITY_FAILED`; revert, re-run → PASS.
5. Deliberate failure: `apiDump`-style stale dump (edit source, don't update dump) → Contract-1 FAIL; revert → PASS.
6. Consumer producers: `verifyJavaConsumerCompatibility` + `verifyKotlinConsumerCompatibility` write `ok:true` markers with real sources; deleting fixture sources makes the markers record failure and `verify060Architecture` FAILs via api-architecture while still writing the report (fail-soft marker contract).
7. Full `verifyPr` — PASS.
8. Zero runtime production diffs (`git diff` on `tramai-*/src/main`).

## 7. Acceptance criteria (Epic 10.2)

- [x] Two contracts enforced: source↔committed dump (B0), base↔current drives policy (B1–B4)
- [x] Stable API frozen: any base→current change, breaking or additive, fails (B1)
- [x] Preview/experimental changes require exact hash-bound migration evidence (B2/B3/B8)
- [x] Internal refactors with unchanged dumps do not fail (B4)
- [x] Stability inversion impossible: stronger API cannot expose weaker TramAI types (B5)
- [x] `experimental` classification accepted end-to-end (B6); invalid maturity/API combos rejected (B7)
- [x] Java + Kotlin consumer proofs compile real sources on minimal classpaths (B9/B10)
- [x] Evidence lands in `api-architecture` of `verify060Architecture`; 10 check IDs unchanged; no raw `dependsOn(apiCheck)`
- [x] No runtime production changes; `verifyPr` green

---

## Closure audit (Epic 10.2 ✅ COMPLETE)

Machinery landed via **PR #307** (`d5486a35`, Track B3) and the typed **#346** (`564b4d05`) gate; certified on current master after 10.1d (`868071aa`). No verifier redesign — the acceptance criteria are re-proven against the live repository.

| # | Acceptance criterion | Evidence (current master) | Verdict |
|---|---|---|---|
| 1 | Stable API changes cannot merge silently | `ApiCompatibilityVerifier` Contract 2: `stable` → any base→current dump change FAILs | ✅ |
| 2 | Stable/preview/experimental/internal classifications enforced | Stability taxonomy in verifier; committed-dump classification checked against catalog (2 pre-existing classification WARNINGs tracked as follow-up) | ✅ |
| 3 | Migration authorization exact and hash-bound | `ApiMigrationEntry.authorizes`: module + `targetVersion` + `fromSha256` + `toSha256` must all match; ACTIVE/LANDED lifecycle in `config/quality/api-migrations.yml` | ✅ |
| 4 | Stale migration entries fail | `verifyRegistryHygiene` — orphan/stale/wrong-hash entries FAIL (gate green ⇒ none present) | ✅ |
| 5 | Internal/experimental cannot become stable surface | Stability-inversion scan: new inversions FAIL; only 5 pre-existing WARNINGs (baselined, `:tramai-platform` preview→`:tramai-server` internal) | ✅ |
| 6 | Java consumer compilation is real | `examples/java-consumer-smoke`: real `javac` via `verifyJavaConsumerCompatibility` — `{"classes":2,"exitCode":0,"ok":true,"sources":1}` | ✅ |
| 7 | Kotlin source-consumer compilation is real | `examples/kotlin-consumer-smoke`: real K2JVMCompiler via `verifyKotlinConsumerCompatibility` — `{"classes":2,"exitCode":0,"ok":true,"sources":1}` | ✅ |
| 8 | API dumps authoritative and reproducible | Contract 1: generated (apiBuild) vs committed `api/<module>.api` must match, fail-closed on empty intersection | ✅ |
| 9 | No duplicate compatibility authority | Single `ApiCompatibilityVerifier` wired into `verify060Architecture`; no other BCV/API verifier in build-logic | ✅ |

**Execution:** `verify060Architecture --rerun-tasks` → `0.6.0 architecture verification PASSED` (BUILD SUCCESSFUL); both consumer tasks → BUILD SUCCESSFUL. Non-blocking WARNINGs: 5 pre-existing stability inversions, 1 `API_HASH_CHANGED` (BCV public surface, #352), 2 `API_MODULE_UNCLASSIFIED` (`:tramai-persistence-jdbc` internal-vs-catalog-preview; `:tramai-spring-boot-starter-sovereign` unclassified), 1 protocol-entry increase 68→97 — all surfaced but not blocking; classification follow-ups tracked as independent issues (10.1 pattern, cf. #347).
