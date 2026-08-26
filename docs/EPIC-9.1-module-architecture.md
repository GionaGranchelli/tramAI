# Epic 9.1 — Module Architecture Closure (Track B1)

**Branch:** `feat/0.6.0-module-architecture`
**Base:** latest merged `origin/master` (`8f8850ba`, PR #296)
**PR title:** `feat(build): make module architecture authoritative`

## 1. Objective

TramAI gets **one authoritative description of its module architecture**,
machine-enforced. By the end of this PR:

- `config/quality/module-catalog.yml` is the single source of truth for every
  Gradle module's layer, maturity, publishability, API stability, visibility,
  owner, dependency policy, release inclusion, and reason-to-exist.
- Publishing, BOM membership, and release classification are **derived or
  validated** from that manifest — no more independent handwritten lists that
  can drift.
- Settings ↔ manifest equality is enforced.
- A deterministic module matrix is generated and CI-fenced against drift.
- A mutation/discriminator suite proves every guard actually guards.

**Hard constraints:**
- **Zero runtime production changes.** No `src/main/kotlin` change in any
  `tramai-*` module, no change to `ProviderExecutionCoordinator`,
  `ProviderAttemptExecutor`, `StreamingExecutionCoordinator`,
  `ProviderCircuitBreaker`, retry/fallback code. No change to
  `tramai-engine` at all.
- **Do NOT edit** `config/quality/0.6.0-baseline.json`,
  `config/quality/maintainability-deviations.yml`,
  `config/quality/mutation-classifications.yml`, or `config/quality/test-quality.yml`.
  `verifyPr` must stay green with zero deviation changes.
- No new third-party dependencies in `build-logic` or anywhere else
  (SnakeYAML already exists in build-logic; reuse it).
- Keep the **existing 10-layer taxonomy** — do not adopt the roadmap's 7
  proposed layers. The implemented finer-grained model is authoritative:
  `core-contracts, runtime-execution, governance-security, persistence,
  provider-adapters, framework-integrations, operations-observability,
  higher-capabilities, applications-examples, testing-support`.

## 2. Context — what exists today

- `config/quality/module-catalog.yml` — 58 module entries with raw-string
  fields: `path`, `layer`, `publishability`, `apiStability`. `schemaVersion: "1"`.
- `config/quality/module-boundaries.yml` — `forbiddenEdges` (layer rules,
  published→excluded, published→internal, self-edge) plus `knownAllowedEdges`
  (exact module pairs, including BOM-inclusion edges).
- `build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleCatalog.kt` —
  parses the catalog; `ModuleEntry(path, layer, publishability, apiStability)`
  all raw `String`; validates layers/publishability/apiStability against
  hardcoded sets; `validateAgainstProjects()` + `BaselineVerifier.verifyModuleCatalog()`
  already enforce catalog↔Gradle-project equality and catalog↔baseline
  layer/publishability agreement.
- `build.gradle.kts` — `publishableProjectNames` (line ~54), `jarPublishingProjectNames`,
  `sovereignBundleModuleNames` (line ~102) — all handwritten.
- `tramai-bom/build.gradle.kts` — handwritten `api(project(":..."))` constraint
  list (39 entries).
- `settings.gradle.kts` — handwritten `include(...)` list (58 modules).

**Confirmed drift to fix:**
- `:tramai-scheduler`, `:tramai-spring-boot-starter-local-provider-openai`,
  `:tramai-spring-boot-starter-sovereign-ops-rest`,
  `:tramai-spring-boot-starter-sovereign-persistence-jdbc` are catalogued
  `published` but **absent from `publishableProjectNames`**.
- The BOM includes `:tramai-spring-boot-starter-sovereign-ops-rest`, which is
  not in `publishableProjectNames` (a BOM constraint to a non-published module).

## 3. Deliverables

### 3.1 Extend the manifest schema (typed)

Replace raw strings with typed enums in `ModuleCatalog.kt`:

```kotlin
enum class ModuleLayer { CORE_CONTRACTS, RUNTIME_EXECUTION, GOVERNANCE_SECURITY,
    PERSISTENCE, PROVIDER_ADAPTERS, FRAMEWORK_INTEGRATIONS,
    OPERATIONS_OBSERVABILITY, HIGHER_CAPABILITIES, APPLICATIONS_EXAMPLES,
    TESTING_SUPPORT }
enum class ModuleMaturity { STABLE, PREVIEW, EXPERIMENTAL, INTERNAL }
enum class ModulePublishability { PUBLISHED, INTERNAL, EXCLUDED }
enum class ModuleApiStability { STABLE, PREVIEW, INTERNAL, EXCLUDED }
enum class ModuleVisibility { PUBLIC, INTERNAL, EXCLUDED }
enum class ReleaseInclusion { INCLUDED, INTERNAL_ONLY, EXCLUDED }

data class ModuleEntry(
    val path: String,
    val layer: ModuleLayer,
    val maturity: ModuleMaturity,
    val publishability: ModulePublishability,
    val apiStability: ModuleApiStability,
    val visibility: ModuleVisibility,
    val owner: String,
    val dependencyPolicy: String,
    val releaseInclusion: ReleaseInclusion,
    val rationale: String,
)
```

The YAML stays readable (`layer: runtime-execution` etc.); enums are parsed
from the YAML strings and validated by construction. Keep the existing string
getters (`layerFor`, `publishabilityFor`, `apiStabilityFor`, `isPublished`)
working — they are consumed by `BaselineVerifier`, `ApiBaselineVerifier`, and
`MeasurementContext`.

**Bump `schemaVersion` to `"2"`** in the YAML and validate it in the parser.

### 3.2 Per-module fields (fill every entry)

Every one of the 58 entries gets:
- `layer` — unchanged values (10 existing layers)
- `maturity` — `stable | preview | experimental | internal`
  (distinct from apiStability: maturity = module as a product/component,
  apiStability = compatibility promise of exposed API)
- `publishability` — unchanged values (`published | internal | excluded`)
- `apiStability` — unchanged values (`stable | preview | internal | excluded`)
- `visibility` — `public | internal | excluded`
- `owner` — team/area name, non-blank (use areas: `core`, `runtime`,
  `providers`, `persistence`, `framework`, `security`, `observability`,
  `capabilities`, `examples`, `testing` — assign sensibly)
- `dependencyPolicy` — one of `core`, `runtime`, `provider-adapter`,
  `framework`, `testing` (see 3.4)
- `releaseInclusion` — `included | internal_only | excluded`
- `rationale` — one concise sentence: the module's documented reason to exist

Mapping guidance (encode what exists — do NOT reorganize modules):
- `apiStability: stable` → `maturity: stable` (tramai-core, tramai-bom)
- `apiStability: preview` → `maturity: preview`
- `apiStability: internal` → `maturity: internal`, `visibility: internal`
- `publishability: excluded` (examples) → `maturity: internal`,
  `visibility: internal`, `releaseInclusion: excluded`
- `publishability: internal` → `releaseInclusion: internal_only`
- `publishability: published` → `visibility: public`,
  `releaseInclusion: included`

**Drift reconciliation (publishability):** the four modules named in
§2 (`:tramai-scheduler`, `:tramai-spring-boot-starter-local-provider-openai`,
`:tramai-spring-boot-starter-sovereign-ops-rest`,
`:tramai-spring-boot-starter-sovereign-persistence-jdbc`) are currently
classified `published` in the manifest but are NOT in `publishableProjectNames`.
After 3.6 (derivation), publishing is derived from the manifest, so these four
**join the publishable set**. Their `dependencyPolicy` should be
`provider-adapter`/`framework` as appropriate. Keep their classification as
`published` — do not silently demote them to internal.

### 3.3 Reason-to-exist (rationale)

`rationale` is a required field, validated non-blank. It lives in the manifest
— no separate Markdown module registry.

### 3.4 Formalize dependency policies

Add a `dependencyPolicies` section to `module-catalog.yml` (or a sibling
`dependency-policies` block at the top of the file). The AUTHORITATIVE policy
set implemented in this PR (calibrated to the actual dependency graph, per the
"encode what exists" rule):

```yaml
dependencyPolicies:
  core: { allowedLayers: [core-contracts, testing-support] }
  runtime: { allowedLayers: [core-contracts, runtime-execution, governance-security, testing-support] }
  provider-adapter: { allowedLayers: [core-contracts, runtime-execution, provider-adapters, testing-support] }
  framework: { allowedLayers: [core-contracts, runtime-execution, governance-security, persistence, provider-adapters, framework-integrations, operations-observability, higher-capabilities, testing-support] }
  testing: { allowedLayers: [core-contracts, framework-integrations, testing-support] }
  example: { allowedLayers: [core-contracts, runtime-execution, governance-security, persistence, provider-adapters, framework-integrations, operations-observability, higher-capabilities, applications-examples, testing-support] }
  bom: { allowedLayers: [core-contracts, runtime-execution, governance-security, persistence, provider-adapters, framework-integrations, operations-observability, higher-capabilities, applications-examples, testing-support] }
```

Note: `runtime` allows `governance-security` because `tramai-engine` depends on
`tramai-security`; `framework` allows `operations-observability` and
`higher-capabilities` because platform/server/rag/vectorstore modules sit in
those layers; `testing` allows `framework-integrations` for the spring consumer
test modules; `example` and `bom` allow all layers by design (examples
demonstrate everything, the BOM constrains everything — the `bom` policy must
allow all layers because the BOM's constraint graph references every published
module).

Each module's `dependencyPolicy` maps to `allowedLayers`. The verifier checks
that every actual dependency edge respects the policy's allowed layers.
`config/quality/module-boundaries.yml` remains authoritative for
layer-direction `forbiddenEdges` and `knownAllowedEdges` exceptions — do not
delete it. The policy check runs **in addition to** the boundary check.

Exceptions stay in `module-boundaries.yml` (`knownAllowedEdges`) — do not
add a new exception mechanism.

### 3.5 Remove publishability/BOM duplication — derivation

**BOM derivation (required):** replace the handwritten constraint list in
`tramai-bom/build.gradle.kts` with a derivation from the manifest:

- BOM membership = `publishability == published && releaseInclusion == included`.
- Implement as a `build-logic` convention plugin or helper that reads
  `config/quality/module-catalog.yml` (SnakeYAML) and produces the module list
  for the BOM constraints block.
- `tramai-bom/build.gradle.kts` must NOT contain the literal list anymore;
  it calls the shared helper.
- The BOM constraint list after derivation must include exactly the modules
  with `publishability: published && releaseInclusion: included`.

**Publishing derivation (required):** `publishableProjectNames` in
`build.gradle.kts` is derived from the manifest (`publishability == published`)
instead of the handwritten list. Same helper. This brings the four drifted
modules into the publishable set (§2). `jarPublishingProjectNames` and
`sovereignBundleModuleNames` semantics stay as they are (computed from the
derived list), but their source is the manifest-derived set, not a second
handwritten list. If `sovereignBundleModuleNames` must keep an explicit set,
derive it from the manifest-derived published set and keep the bundle list a
documented subset with a comment; do NOT hand-maintain a third copy of the
module names.

**Validation (required):** a `verifyModuleManifest` task (build-logic) that
fails when:
- a module is in the BOM but the manifest says `publishability != published`
  or `releaseInclusion != included` (BOM drift)
- a module is in `publishableProjectNames` but the manifest says
  `publishability != published` (publishing drift)
- a manifest-published module is missing from the derived publishable set

### 3.6 Settings ↔ manifest equality (conservative)

Do NOT generate `settings.gradle.kts`. Keep the handwritten `include(...)`
list. Enforce exact-set equality (already partially exists via
`validateAgainstProjects` / `verifyModuleCatalog`; keep and strengthen):
- every Gradle project path must appear in the manifest
- every manifest entry must be a real Gradle project
- failure codes: `MODULE_CATALOG_MISSING_ENTRY`, `MODULE_CATALOG_UNKNOWN_ENTRY`

### 3.7 Generate the module matrix (deterministic)

Add a deterministic generation task `generateModuleMatrix` (build-logic) that
writes `docs/reference/module-matrix.md` from the manifest:

```markdown
# TramAI Module Matrix

<!-- generated from config/quality/module-catalog.yml — do not edit manually -->

| Module | Layer | Maturity | API | Published | Owner | Release |
|--------|-------|----------|-----|-----------|-------|---------|
| tramai-core | core-contracts | stable | stable | Yes | core | included |
| tramai-engine | runtime-execution | preview | preview | Yes | runtime | included |
...
```

- Deterministic ordering (sort by path), no timestamps, no `Instant.now()`.
- Add a drift check: `verifyModuleMatrixDrift` compares the committed
  `docs/reference/module-matrix.md` against a freshly generated one and fails
  on any difference. Wire it so CI catches drift (dependsOn the generator).

### 3.8 Strong validation rules

`ModuleCatalog.parse()` rejects (typed diagnostics, reuse existing
`DiagnosticCode`s where semantically correct; add new codes under the
`MODULE_CATALOG_*` family only if none fits):

- `visibility == public && publishability == internal` → invalid
- `apiStability == stable && maturity == experimental` → invalid
- `releaseInclusion == included && publishability == excluded` → invalid
- `releaseInclusion == included && publishability == internal` → invalid
- blank `owner` → invalid
- blank `rationale` → invalid
- unknown `dependencyPolicy` → invalid
- module missing from settings → fail
- settings module missing from manifest → fail
- BOM module excluded by manifest → fail
- published module absent from derived publishable set → fail
- `maturity == internal` requires `apiStability == internal` (sanity)

Keep all existing rules (published→apiStability != excluded, examples excluded,
excluded → apiStability excluded, duplicate path, etc.).

### 3.9 Mutation/discriminator suite (proof the guards guard)

Add build-logic unit tests (JUnit) that mutate the manifest/fixture and prove
the verifier fails. Use a temporary fixture catalog (copy of the real one,
mutated) — do NOT mutate the real file in tests. Required mutations:

- **M1 missing module** — delete a catalog entry → `MODULE_CATALOG_MISSING_ENTRY`
- **M2 ghost module** — add `path: ":tramai-does-not-exist"` →
  `MODULE_CATALOG_UNKNOWN_ENTRY`
- **M3 forbidden core dependency** — `:tramai-core → :tramai-openai` →
  architecture failure (existing forbidden-edge path)
- **M4 dependency cycle** — A → B → A → cycle failure (existing cycle path)
- **M5 BOM drift** — manifest says internal/excluded but module is in BOM
  (or vice versa) → BOM drift failure
- **M6 publishability drift** — manifest `published`, derived publishable set
  does not contain it → failure
- **M7 blank owner/rationale** → manifest validation failure
- **M8 invalid policy** — `dependencyPolicy: banana` → failure

Each test asserts the exact `DiagnosticCode` and a stable message fragment.
Follow the existing build-logic test conventions (see
`build-logic/src/test/kotlin/dev/tramai/build/quality/*VerifierTest.kt`).
Tests must construct fixtures with temp files (JUnit `@TempDir`) — never touch
`config/quality/` during tests.

## 4. Files

### Create
- `build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleManifest.kt`
  (or extend `ModuleCatalog.kt` — prefer extending existing files over new
  ones where the change is additive)
- `docs/reference/module-matrix.md` (generated artifact, committed)
- build-logic tests for 3.8/3.9 (extend existing test files or add new ones)

### Modify
- `config/quality/module-catalog.yml` — schemaVersion 2, full new fields
- `build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleCatalog.kt` —
  typed enums, new fields, new validation rules
- `build-logic/src/main/kotlin/dev/tramai/build/quality/BaselineVerifier.kt` —
  wire new checks (policy, BOM, publishing, matrix drift) into the verify flow
- `tramai-bom/build.gradle.kts` — derive constraints from manifest
- `build.gradle.kts` — derive `publishableProjectNames` from manifest
- `build-logic` convention plugin registration if needed for
  `generateModuleMatrix` / `verifyModuleManifest` / `verifyModuleMatrixDrift`
  tasks (wire into `check` where appropriate)

### Do NOT touch
- Any `tramai-*/src/main/kotlin/**` file (especially `tramai-engine`)
- `config/quality/0.6.0-baseline.json`, `maintainability-deviations.yml`,
  `mutation-classifications.yml`, `test-quality.yml`
- `settings.gradle.kts` content (only validation against it, no generation)
- `.github/workflows/**` unless a drift-check CI step is strictly needed
  (prefer wiring into existing `check` aggregation first)

## 5. Verification (must all pass)

```bash
cd ~/Development/aurora-b1
git status --short                    # no .hermes/, no stray files
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :build-logic:test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew generateModuleMatrix
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew verifyModuleMatrixDrift
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew verifyPr
```

`verifyPr` green is the gate. If a new task must run inside `check`, wire it
there; do not weaken anything.

## 7. Accepted Release-Surface Reconciliation (architecture decision)

**Decision:** the manifest classification wins over the legacy handwritten
release list. This is an intentional release-surface correction, not
incidental generation.

The following previously-drifted published modules are therefore **restored
to the 0.6.0 release set** (they were classified `published` in the old
architecture catalog and are present in the v0.5.0 baseline publishable list,
but had been dropped from the handwritten `publishableProjectNames`):

| Module | Publishable | Sovereign bundle |
|--------|-------------|------------------|
| `:tramai-scheduler` | Yes | No (explicit bundle exclusion) |
| `:tramai-spring-boot-starter-local-provider-openai` | Yes | Yes |
| `:tramai-spring-boot-starter-sovereign-ops-rest` | Yes | Yes |
| `:tramai-spring-boot-starter-sovereign-persistence-jdbc` | Yes | Yes |

Rationale: the manifest is now the single source of truth; the handwritten
`publishableProjectNames` had drifted from it. Deriving publishing from the
manifest restores these four to the release set. The sovereign bundle derives
from the published set minus an explicit exclusion list (`scheduler`,
provider adapters, framework adapters, vector stores, testing) so the bundle
stays scoped to the signed sovereign runtime.

If any of the four should NOT ship in 0.6.0, the fix is to demote it in the
manifest (`publishability: internal` + `releaseInclusion: internal_only`) —
not to reintroduce a handwritten publishing list.

## 8. Non-goals (this PR)

- No runtime/provider behavior changes
- No module reorganization or consolidation
- No settings generation (only equality validation)
- No deletion of `module-boundaries.yml`
- No new dependency direction taxonomy beyond the existing layers
- No API-baseline changes (that is Track B3 / Epic 10.2)
- No unified `verify060Architecture` façade (that is Track B2 / Epic 10.4)
