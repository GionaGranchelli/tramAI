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
- **targetVersion mismatch**: entry.targetVersion ≠ project version
  (`0.6.0`)
- Stable modules can never use entries (stable change = hard FAIL regardless).

Sha256 = SHA-256 of the dump file's UTF-8 content. Both hashes must match
*both* actual dumps — the entry authorizes that exact transition, nothing
broader. (This also means the entry must be committed in the same PR as the
dump change, and becomes stale the moment the dump changes again.)

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
- `verifyJavaConsumerCompatibility` asserts: source set non-empty (>0 `.java`
  files) AND compiled class files exist in the build output after
  compilation. Empty source set → FAIL (zero-source trap guard, mirrors
  A-series); compile failure → FAIL; no classes → FAIL.

### 3.6 Kotlin consumer proof (real compilation)

New fixture `examples/kotlin-consumer-smoke` (or reuse existing example if
its classpath is already minimal — decision at implementation: prefer a
minimal fixture so the classpath assertion is honest):
- `src/main/kotlin/...` real Kotlin sources against `:tramai-core` on the
  minimal consumer classpath.
- `verifyKotlinConsumerCompatibility` asserts: >0 `.kt` sources AND compiled
  classes present. Same guards as 3.5.

Both compile tasks are wired as dependencies of the `api-architecture`
evidence collection (they feed `verify060Architecture`); failures surface as
task failures inside the gate run, not as new check IDs.

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

All emissions are `API_*` codes routed by the existing exhaustive
`baselineCheckFor` → `api-architecture`. The 10 check IDs and the report
schema are unchanged. No new release gate. Consumer compile tasks are
dependencies of the check (fail fast as task failures, report still written
for the diagnostic evidence).

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
  unchanged module, and targetVersion mismatch all FAIL.
- **B9 — Java real-source consumer compile proof**: fixture has >0 `.java`
  sources and compiles against `:tramai-core` only; deleting the sources
  fails the guard.
- **B10 — Kotlin real-source consumer compile proof**: fixture has >0 `.kt`
  sources and compiles against `:tramai-core` only; deleting the sources
  fails the guard.

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
6. `verifyJavaConsumerCompatibility` + `verifyKotlinConsumerCompatibility` PASS with real sources; deleting fixture sources fails them.
7. Full `verifyPr` — PASS.
8. Zero runtime production diffs (`git diff` on `tramai-*/src/main`).

## 7. Acceptance criteria (Epic 10.2)

- [ ] Two contracts enforced: source↔committed dump (B0), base↔current drives policy (B1–B4)
- [ ] Stable API frozen: any base→current change, breaking or additive, fails (B1)
- [ ] Preview/experimental changes require exact hash-bound migration evidence (B2/B3/B8)
- [ ] Internal refactors with unchanged dumps do not fail (B4)
- [ ] Stability inversion impossible: stronger API cannot expose weaker TramAI types (B5)
- [ ] `experimental` classification accepted end-to-end (B6); invalid maturity/API combos rejected (B7)
- [ ] Java + Kotlin consumer proofs compile real sources on minimal classpaths (B9/B10)
- [ ] Evidence lands in `api-architecture` of `verify060Architecture`; 10 check IDs unchanged; no raw `dependsOn(apiCheck)`
- [ ] No runtime production changes; `verifyPr` green
