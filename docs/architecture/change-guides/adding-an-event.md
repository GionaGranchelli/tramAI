# Change Guide: Adding an Event

**Applies to:** the runtime event catalogue in `tramai-core` and the ASM/source architecture guard in `tramai-observability`.

## TL;DR

Every `tramai.*` runtime event is **declared once in `RuntimeEventCatalogue`**, referenced through the compile-time `RuntimeEvents` registry, and emitted via `emitRuntimeEvent`. The architecture guard scans both bytecode (LDC constants) and source: any `tramai.*` literal that does not go through `RuntimeEvents.X` fails the build — even if the literal already exists in the catalogue.

## 1. The mechanism (all in `tramai-core/src/main/kotlin/dev/tramai/core/observation/event/`)

- `RuntimeEventCatalogue.kt` (926 lines): `object RuntimeEventCatalogue` (L16) owns `allEvents: List<RuntimeEventDefinition>` (L17). Init block (L862-891) fails fast on: duplicate event names (L864), conflicting attribute key types (L868), required attrs outside allowed set (L874-879), undeclared metric mappings (L882-886), duplicate metric names (L889). `event(name)` errors on unknown id (L893-894). Dynamic namespace: `DynamicAttributeNamespaces.WORKFLOW_CONTEXT` (L914-926).
- `RuntimeEventModel.kt`: `RuntimeAttributeKey<T>(name, valueType)` (L55), `RuntimeMetricDefinition` (L62-68), `RuntimeEventDefinition` (L80-91: name, domain, allowedAttributes, requiredAttributes, sensitivity, auditEligible, evidenceEligible, metricMapping, spanEligible, failurePolicy), enums `RuntimeEventDomain` (L8), `RuntimeEventSensitivity` (L23), `RuntimeEventFailurePolicy` (L37), `RuntimeMetricInstrumentType` (L45).
- `RuntimeAttributes.kt`: one `RuntimeAttributeKey<T>("tramai…", T::class)` per canonical key (object L8).
- `RuntimeMetrics.kt`: `object RuntimeMetrics` (L8), descriptors (e.g. L9-15), `val all` L151.
- `RuntimeEvents.kt`: compile-time registry (L6-80), e.g. `val WORKER_HEARTBEAT = RuntimeEventCatalogue.event("tramai.worker.heartbeat")` (L7).
- `RuntimeEvent.kt`: typed builder — `set(key, value)` rejects non-allowed attrs (L52-57) and wrong value types (L61-64); `build()` enforces required (L69-72); `RuntimeEvent.of(definition|name)` (L30-42); `RuntimeEventValidator` (L81-98).
- Emission: `tramai-engine/.../engine/RuntimeEventEmission.kt:13-15` (`emitRuntimeEvent` → `onEngineEvent(event)`, honors failure policy); same file in `tramai-orchestration`.

## 2. Adding an event = 4 edits + docs regen

1. New `RuntimeAttributeKey` in `RuntimeAttributes.kt` (if a new attribute is needed)
2. New `RuntimeMetricDefinition` in `RuntimeMetrics.kt` (if a new metric is needed)
3. `RuntimeEventDefinition(...)` entry in `RuntimeEventCatalogue.kt` `allEvents`
4. `val X = RuntimeEventCatalogue.event("tramai…")` in `RuntimeEvents.kt`
5. Regenerate `docs/reference/runtime-event-catalogue.md` (see §4)

Custom (non-`tramai.`) third-party definitions are supported via `RuntimeEvent.of(definition)` (`RuntimeEvent.kt:22-29`).

## 3. The architecture guard (fail-closed, both layers)

`tramai-observability/src/test/kotlin/dev/tramai/observability/RuntimeEventCatalogueArchitectureTest.kt` (208 lines):

1. **Bytecode scan** (test L96-122): loads core/engine/orchestration/observability classes via `moduleClassesLocations()` (L124-133), skips only package `dev/tramai/core/observation/event/` (L37, L105), collects String LDC constants starting `"tramai."` via ASM9 `visitLdcInsn` (L155-173); fails on any found (L116-121), fails closed on zero classes (L101-103).
2. **Repo-wide source scan** `sourceLiterals()` (L182-207): every `tramai-*` module's `src/main/**/*.kt`, regex `"tramai\.[a-zA-Z0-9_.-]*"` (L186); only the exact allowlist `configPropertyLiterals` (L44-80) passes `classifySourceLiteral` (L92-93). Even a literal already in the catalogue is an offender — it must reference `RuntimeEvents.X`.

Mutation semantics pinned by `RuntimeEventCatalogueVerifierRulesTest.kt` (same dir, 57 lines). `tramai-observability/build.gradle.kts:41-46` declares every module's production Kotlin sources as test inputs so the guard re-runs when a literal is added anywhere (no Gradle up-to-date skip).

## 4. Docs generation (drift-checked, no Gradle task)

- `RuntimeEventCatalogueRenderer.kt` (tramai-core, 53 lines) deterministically renders the catalogue (L10-52).
- `RuntimeEventCatalogueDocumentationTest.kt` (tramai-core test, L13-26) asserts `docs/reference/runtime-event-catalogue.md` (header: "do not edit by hand") equals `renderer.render()` — drift fails with "Regenerate it from RuntimeEventCatalogueRenderer".
- There is **no** Gradle regen task; regenerate the checked-in file manually to match.
- `RuntimeEventCatalogueTest.kt` (51 lines) asserts uniqueness/metadata/canonical types/determinism.

## 5. Mandatory verification

```bash
./gradlew :tramai-observability:test --tests '*RuntimeEventCatalogueArchitectureTest*' --tests '*RuntimeEventCatalogueVerifierRulesTest*'
./gradlew :tramai-core:test --tests '*RuntimeEventCatalogue*'
./gradlew verifyPr
```

**Guardrails:** event/attribute/metric names are the long-term contract — changing a name is a breaking change to observability consumers; the catalogue init fails fast on duplicates/type conflicts; the guard fails closed (zero classes scanned = failure), so a broken classpath can never silently pass.
