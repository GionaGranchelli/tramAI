# Change Guide: Adding an Event

## Start here

Every `tramai.*` runtime event is **declared once in `RuntimeEventCatalogue`**, referenced through the compile-time `RuntimeEvents` registry, and emitted via `emitRuntimeEvent`. The architecture guard scans both bytecode (LDC constants) and source: any `tramai.*` literal that does not go through `RuntimeEvents.X` fails the build — even if the literal already exists in the catalogue.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (observability/event ownership → `tramai-core`), read the module cards [`tramai-core.md`](../../modules/tramai-core.md) + [`tramai-observability.md`](../../modules/tramai-observability.md), then follow this guide.

## Owning module

- `tramai-core` — the catalogue, attribute/metric registries, typed event builder, renderer.
- `tramai-observability` — the fail-closed architecture guard test + OTel observer.
- `tramai-engine` / `tramai-orchestration` — emission helpers (`emitRuntimeEvent`).

## Authoritative contracts

All in `tramai-core/src/main/kotlin/dev/tramai/core/observation/event/`:

- `RuntimeEventCatalogue.kt` (926 lines): `object` L16 owns `allEvents: List<RuntimeEventDefinition>` L17; init fails fast on duplicates/type conflicts/undeclared metrics (L862-891); `event(name)` errors on unknown id (L893-894). Dynamic namespace `DynamicAttributeNamespaces.WORKFLOW_CONTEXT` (L914-926).
- `RuntimeEventModel.kt`: `RuntimeAttributeKey<T>(name, valueType)` L55, `RuntimeMetricDefinition` L62-68, `RuntimeEventDefinition` L80-91 (name, domain, allowedAttributes, requiredAttributes, sensitivity, auditEligible, evidenceEligible, metricMapping, spanEligible, failurePolicy).
- `RuntimeAttributes.kt`: `object` L8 — one `RuntimeAttributeKey<T>("tramai…", T::class)` per canonical key.
- `RuntimeMetrics.kt`: `object` L8, descriptors, `val all` L151.
- `RuntimeEvents.kt`: compile-time registry (L6-80), e.g. `val WORKER_HEARTBEAT = RuntimeEventCatalogue.event("tramai.worker.heartbeat")` L7.
- `RuntimeEvent.kt`: typed builder — `set` rejects non-allowed attrs (L52-57) and wrong types (L61-64); `build` enforces required (L69-72); `RuntimeEvent.of(definition|name)` L30-42; `RuntimeEventValidator` L81-98.

## Files normally changed

1. `RuntimeAttributes.kt` — new `RuntimeAttributeKey` (if a new attribute is needed).
2. `RuntimeMetrics.kt` — new `RuntimeMetricDefinition` (if a new metric is needed).
3. `RuntimeEventCatalogue.kt` — new `RuntimeEventDefinition(...)` entry in `allEvents`.
4. `RuntimeEvents.kt` — `val X = RuntimeEventCatalogue.event("tramai…")`.
5. `docs/reference/runtime-event-catalogue.md` — **regenerate** (see NOT changed → renderer).
6. Emission call site in the owning module — via `emitRuntimeEvent` (`tramai-engine/.../engine/RuntimeEventEmission.kt:13-15`, same file in `tramai-orchestration`).

## NOT changed

- **`RuntimeEventCatalogueDocumentationTest.kt`** and the renderer — the reference doc is generated; never hand-edit it (header "do not edit by hand"; drift fails with "Regenerate it from RuntimeEventCatalogueRenderer").
- **The architecture guard test** — `RuntimeEventCatalogueArchitectureTest.kt` and `RuntimeEventCatalogueVerifierRulesTest.kt` pin mutation semantics; never weaken them.
- **Existing event names/attribute types** — renaming is a breaking change to observability consumers (catalogue init fails on conflicts, but external dashboards/alerts depend on the names).
- **Analyzer/baseline** — same-PR edits forbidden.

## Required tests / TCK

- `RuntimeEventCatalogueTest.kt` (tramai-core, 51 lines) — uniqueness/metadata/canonical types/determinism.
- `RuntimeEventCatalogueDocumentationTest.kt` (tramai-core test, L13-26) — generated doc matches renderer.
- `RuntimeEventCatalogueArchitectureTest.kt` (tramai-observability, 208 lines) — two fail-closed layers:
  1. **Bytecode scan** (L96-122): ASM9 `visitLdcInsn` over core/engine/orchestration/observability classes, skips only `dev/tramai/core/observation/event/`, fails on any `"tramai."` LDC, fails closed on zero classes (L101-103).
  2. **Repo-wide source scan** `sourceLiterals()` (L182-207): every `tramai-*` module's `src/main/**/*.kt`, regex `"tramai\.[a-zA-Z0-9_.-]*"`; only exact allowlist `configPropertyLiterals` (L44-80) passes.
- `RuntimeEventCatalogueVerifierRulesTest.kt` — pins mutation semantics.

## Compatibility

- Event/attribute/metric names are the long-term observability contract — external dashboards, alerts, and audit tooling depend on them.
- The catalogue init fails fast on duplicate names, conflicting attribute types, required attrs outside allowed set, undeclared metric mappings, duplicate metric names (L862-891) — a new event must be consistent with all registries at once.
- The guard fails closed: zero classes scanned = failure, so a broken classpath can never silently pass.

## Failure / cancellation / lifecycle

- Emission honors `failurePolicy` (`RuntimeEventFailurePolicy`, `RuntimeEventModel.kt:37`) — emit failures must not break the invocation flow.
- The guard re-runs whenever a literal is added anywhere: `tramai-observability/build.gradle.kts:41-46` declares every module's production Kotlin sources as test inputs (no Gradle up-to-date skip).
- Lifecycle: events are emitted at defined points in the engine/orchestration flows; a new event must declare its domain (`RuntimeEventDomain`) and sensitivity.

## Verification

```bash
./gradlew :tramai-observability:test --tests '*RuntimeEventCatalogueArchitectureTest*' --tests '*RuntimeEventCatalogueVerifierRulesTest*'
./gradlew :tramai-core:test --tests '*RuntimeEventCatalogue*'
./gradlew verifyPr
```

## Common mistakes

- Hand-editing `docs/reference/runtime-event-catalogue.md` — the drift test fails; regenerate instead.
- Emitting a string literal instead of referencing `RuntimeEvents.X` — the guard flags even already-catalogued literals.
- Adding an event with a required attribute that is not in `allowedAttributes` — init fails fast.
- Skipping the metric mapping — undeclared metric mappings fail init (L882-886).

## Related ADRs / specs

- [ADR-006](../../adr/adr-006.md) — enable OpenTelemetry observability automatically when present
- [spec-004-observability.md](../../specs/spec-004-observability.md) — observability contract
- [`docs/reference/runtime-event-catalogue.md`](../../reference/runtime-event-catalogue.md) — generated catalogue (do not hand-edit)
