# Epic 10.2 — API Compatibility Closure (Track B3)

**Status:** spec — committed before production/build changes
**Branch:** `feat/0.6.0-api-compatibility` (base: master `b3e9a8f7`, B2 merged)
**PR title (planned):** `build(quality): enforce API compatibility semantics (Epic 10.2, Track B3)`
**Scope rule:** API compatibility *closure*, not API redesign. No runtime
production changes. Feeds typed evidence into the existing `api-architecture`
check of `verify060Architecture` — no new release gate.

---

## 1. What already exists (inventory, verified on master b3e9a8f7)

| Piece | Location / state |
|-------|------------------|
| Binary-compatibility plugin | `org.jetbrains.kotlinx.binary-compatibility-validator` 0.16.3, applied at root (`build.gradle.kts:23`, `gradle/libs.versions.toml:75`) |
| `apiCheck`/`apiDump`/`apiBuild` tasks | Per module. `:tramai-core:tasks --all` shows `apiCheck`/`apiDump`; `apiBuild` writes `build/api/<module>.api` |
| Committed API dumps | **50** `api/*.api` files (tramai modules + `examples/tool-governance`); `tramai-bom` has NO dump (platform, `applicable=false`) |
| Baseline API records | `config/quality/0.6.0-baseline.json` → `api.modules` (48 records: 1 applicable stable `:tramai-core`, `:tramai-bom` stable-but-not-applicable, 33 applicable preview, 6 applicable internal) |
| Metadata verifier | `ApiBaselineVerifier.kt` (B1-era): baseline empty, duplicate dumps, unclassified module, catalog-stability mismatch, unsafe dump paths, missing dumps, stable-without-apiCheck, nondeterministic dump content |
| Diagnostic codes | `API_BASELINE_EMPTY`, `API_DUMP_MISSING`, `API_DUMP_DUPLICATE`, `API_MODULE_UNCLASSIFIED`, `API_VALIDATION_NOT_CONFIGURED`, `API_COMPATIBILITY_FAILED` (**dead — declared + mapped in B2 gate, never emitted**), `API_HASH_CHANGED`, `API_DUMP_NONDETERMINISTIC` |
| B2 gate wiring | `MaintainabilityBaselinePlugin.kt:1291-1298`: all `API_*` codes → `api-architecture` check |
| Catalog taxonomy | `ModuleApiStability` = {STABLE, PREVIEW, INTERNAL, EXCLUDED} — **no EXPERIMENTAL** (EXPERIMENTAL exists only in `ModuleMaturity`) |
| Consumer smoke | `examples/sovereign-runtime-consumer-smoke` (Kotlin, compiles against published artifacts in sovereign workflows only); `examples/kotlin-springboot-example` (Kotlin, compiles against mavenLocal in ci.yml publish step). **No Java consumer fixtures anywhere** (0 `.java` files in examples/) |

## 2. Gaps (what B3 must close)

1. **`apiCheck` is not enforced anywhere.** CI runs `./gradlew test` (not
   `check`), `verifyPr` does not include apiCheck, and no workflow calls it.
   A stable-API break currently **merges silently**. (Confirmed: `apiCheck`
   fails correctly when the dump is tampered, but nothing invokes it.)
2. **No semantic-compat emitter.** `API_COMPATIBILITY_FAILED` is dead. The
   metadata verifier only checks the *baseline records*, never the actual
   dump diff vs the committed `.api`.
3. **No stable/preview/experimental semantics.** All modules get the same
   treatment; roadmap task 10.2-2 (classify) and 10.2-3 (fail CI on
   accidental stable changes) are unmet. `experimental` classification
   missing from `ModuleApiStability`.
4. **No migration-note evidence** for intentional preview changes (10.2-4).
5. **No Java consumer compilation proof** (10.2-5). Kotlin source-compat
   examples exist but aren't wired as a gate (10.2-6 partial).
6. **No internal-type leak detection through stable APIs** (10.4 rule:
   "Stable APIs do not expose internal implementation types").

## 3. Design

### 3.1 Semantic compat evidence = real dump diff (reuse BCV, don't reimplement)

The gate does NOT reimplement signature analysis. It reuses BCV's own
`apiBuild` (per-module, produces the current dump) and diffs against the
committed `api/<module>.api` — the same comparison `apiCheck` performs, but
consumed as **typed diagnostics** instead of a raw task failure:

```
apiBuild (per module, non-throwing generator)
        ↓
committed api/<module>.api  vs  build/api/<module>.api
        ↓
diff non-empty?
   ├── stable module        → API_COMPATIBILITY_FAILED (hard FAIL)
   ├── preview module       → needs matching api-migrations.yml entry, else FAIL
   ├── experimental module  → needs matching entry too (looser wording), else FAIL
   └── internal/excluded    → informational only (no gate)
```

This is exactly the `apiCheck` semantics, but the failure becomes typed
evidence inside `verify060Architecture`'s existing `api-architecture` check —
no new release gate, no second implementation of signature comparison.

### 3.2 Migration-note registry

New committed file `config/quality/api-migrations.yml`:

```yaml
- module: ":tramai-engine"
  signature: "dev/tramai/engine/TramaiEngine"
  kind: preview
  note: "Removed deprecated overload; use typed invoke instead."
  date: 2026-08-27
```

Preview/experimental dump diffs are matched by (module, any listed signature
that appears in the diff). A diff without a matching entry fails. Stable
modules are NOT permitted to use migration notes — stable change = hard fail,
period. Internal refactors that don't touch the dump = zero diff = no entry
needed (satisfies "ordinary internal refactors do not fail").

### 3.3 Taxonomy closure

- Add `EXPERIMENTAL("experimental")` to `ModuleApiStability` and to
  `ApiBaselineVerifier.VALID_STABILITIES`.
- Catalog sanity rule (already exists for maturity): `stable` API cannot have
  `experimental` maturity — extend the existing rule check to cover the new
  value (one line in `ModuleCatalog` validation).
- No catalog data change required (0 modules are experimental today).

### 3.4 Internal-type leak detection (dump scan)

Stable/preview modules' committed dumps are scanned for JVM descriptors that
reference classes belonging to modules whose catalog `apiStability == internal`
or `excluded` (e.g. `dev/tramai/<module>/` prefixes). A reference from a
stable dump to an internal module's types → `API_COMPATIBILITY_FAILED` with a
leak-specific message. This is a text scan over the BCV dump format (the same
format `apiCheck` already commits), not a new ABI analyzer.

### 3.5 Java consumer proof

New fixture module `examples/java-consumer-smoke` — pure Java source
(`src/main/java`) that exercises the stable public surface (`:tramai-core`:
annotations, core entrypoints) through `implementation(project(":tramai-core"))`.
A build-logic Test-less compile check: `verifyJavaConsumerCompatibility`
compiles that module's Java sources against the module classpath and fails on
compile error. This proves Java *source* usability of the committed API, not
binary linkage — the binary proof stays with apiCheck/BCV.

### 3.6 Kotlin source-compat proof

Wire the existing `examples/kotlin-springboot-example` compile (currently only
exercised in CI's publish step) into the gate as `verifyKotlinConsumerCompatibility`
— compile-only against project deps, fails on source-incompatible changes.
Reuses what exists; no new example authored.

### 3.7 Wiring into the B2 gate

`verify060Architecture`'s `api-architecture` check gains a new evidence source
(alongside the existing baseline diagnostics): the dump-diff + migration +
leak results from §3.1–3.4, emitted as `API_COMPATIBILITY_FAILED` diagnostics
and routed through the existing exhaustive `baselineCheckFor` mapping. The
consumer-compile proofs stay as separate verify tasks listed in the PR
verification (not folded into the JSON report — they're compile tasks, not
diagnostics).

## 4. RED discriminators (mutation suite, B-series)

New `ApiCompatibilityMutationTest.kt` in build-logic tests. Each test must
FAIL before the implementation and PASS after (written RED-first):

- **B1 — stable dump drift**: committed `:tramai-core` dump + one extra
  signature vs generated → `API_COMPATIBILITY_FAILED` → `api-architecture`
  FAIL (no migration note allowed for stable).
- **B2 — preview dump drift without migration note**: preview module dump
  changed, `api-migrations.yml` empty → FAIL.
- **B3 — preview dump drift WITH matching migration note**: preview dump
  changed, matching entry present → PASS.
- **B4 — internal refactor no-diff**: internal module source changed but dump
  identical → PASS (zero diagnostics).
- **B5 — internal-type leak in stable dump**: stable dump contains a class
  descriptor from an internal module → `API_COMPATIBILITY_FAILED` (leak
  message) → FAIL.
- **B6 — experimental classification accepted**: catalog entry with
  `apiStability: experimental` parses without `MODULE_CATALOG_INVALID_*`;
  VALID_STABILITIES accepts it.
- **B7 — stable + experimental maturity rejected**: existing sanity rule
  extended: `apiStability: stable` + `maturity: experimental` →
  `MODULE_CATALOG_INVALID_COMBINATION`.
- **B8 — migration registry parse**: valid yml parses; unknown `kind` →
  failure diagnostic; blank `signature` → failure diagnostic.
- **B9 — java consumer fixture is real**: `verifyJavaConsumerCompatibility`
  task discovers >0 Java sources and succeeds on the current committed API
  (guards against the zero-source trap, mirroring A-series).
- **B10 — kotlin consumer fixture is real**: `verifyKotlinConsumerCompatibility`
  discovers >0 Kotlin sources and succeeds.

Hardware rule: B1/B2/B5 are the release-critical REDs. B3/B4 prove the gate
does not over-fire on legitimate work. B6–B10 protect the fixtures themselves.

## 5. Files

### Create
- `config/quality/api-migrations.yml` — migration registry (initially empty or with the documented initial state)
- `examples/java-consumer-smoke/build.gradle.kts` + `src/main/java/...` — Java consumer fixture
- `build-logic/src/test/kotlin/dev/tramai/build/quality/ApiCompatibilityMutationTest.kt` — B1–B10
- `docs/EPIC-10.2-api-compatibility.md` — this spec (committed first)
- `build-logic/.../ApiCompatibilityVerifier.kt` — dump diff + migration + leak scan (pure, testable)

### Modify
- `build-logic/.../ModuleCatalog.kt` — add EXPERIMENTAL to `ModuleApiStability`
- `build-logic/.../ApiBaselineVerifier.kt` — VALID_STABILITIES + EXPERIMENTAL
- `build-logic/.../MaintainabilityBaselinePlugin.kt` — wire api-architecture evidence source + register `verifyJavaConsumerCompatibility` / `verifyKotlinConsumerCompatibility`
- `build.gradle.kts` (or plugin) — `apiBuild` dependency wiring for the gate; consumer compile tasks
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
5. `verifyJavaConsumerCompatibility` + `verifyKotlinConsumerCompatibility` PASS with real sources.
6. Full `verifyPr` — PASS.
7. Zero runtime production diffs (`git diff` on `tramai-*/src/main`).

## 7. Acceptance criteria (Epic 10.2)

- [ ] Stable API changes cannot merge silently (apiCheck semantics enforced in gate)
- [ ] Preview/experimental changes require migration-note evidence
- [ ] Internal refactors with unchanged dumps do not fail
- [ ] `experimental` classification accepted end-to-end
- [ ] Java consumer compile proof exists and is wired
- [ ] Kotlin consumer compile proof exists and is wired
- [ ] Internal types cannot leak through stable API dumps
- [ ] Evidence lands in `api-architecture` check of `verify060Architecture` (no new gate)
- [ ] B1–B10 discriminators prove each guard
- [ ] No runtime production changes; `verifyPr` green
