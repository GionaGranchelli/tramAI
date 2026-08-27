# Change Guide: Adding a Provider

## Start here

A provider adapter is a new module (e.g. `tramai-<vendor>`) implementing `ModelProvider` and proving vendor-wire conformance by enrolling in `ProviderTck`. Providers are **not** service-loaded — registration is explicit via `ProviderRegistry.builder()`.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (framework-integrations layer → provider adapters), read the module card [`docs/modules/tramai-openai.md`](../../modules/tramai-openai.md) or `tramai-deepseek.md` as a template, then follow this guide.

## Owning module

- New module: `tramai-<vendor>` (layer `provider-adapters`, owner `providers`, see `config/quality/module-catalog.yml` anchors `&provider` L16 and entries `tramai-deepseek` L126-131, `tramai-openai` L189-194).
- SPI contract lives in `tramai-core`; shared conformance fixtures in `tramai-testing`.

## Authoritative contracts

- `ModelProvider` — `tramai-core/src/main/kotlin/dev/tramai/core/provider/ModelProvider.kt:19`:
  - `suspend fun complete(request: ModelRequest): ModelResponse` (L23) — the only abstract member
  - `fun providerId(): String` defaults to `simpleName` (L28) — stable id used by registry + TCK
  - `fun supportsCapability(capability: ProviderCapability): Boolean = false` (L34); enum `ProviderCapability { VISION, TOOL_CALLING, STRUCTURED_OUTPUT, STREAMING }` (L9-14)
- `StreamCapable` — `tramai-core/.../provider/StreamCapable.kt:10-18`: `fun stream(request: ModelRequest): Flow<StreamChunk>` (declare iff STREAMING claimed).
- Supporting types: `ModelRequest`/`ModelResponse` (`dev.tramai.core.model`), `ProviderException`/`ProviderFailureCode` (`dev.tramai.core.exception`), `StreamChunk` (`dev.tramai.core.model`). There is **no** `ModelProviderAdapter` interface.

## Files normally changed

- New `tramai-<vendor>/` module: `build.gradle.kts`, source implementing `ModelProvider` (+ `StreamCapable` if streaming), `api/tramai-<vendor>.api` (generated, committed).
- `config/quality/module-catalog.yml` — new entry with `<<: *provider`, `path: ":tramai-<name>"`, `rationale`, `layer: provider-adapters`, `publishability: published`, `apiStability: preview`.
- `config/quality/module-boundaries.yml` — no change unless a new edge is required (defaults forbid `published → internal`, cycles).
- `settings.gradle.kts` — register the project; BOM membership follows from the catalog (manifest verifier checks catalog vs actual projects/published/BOM, `MaintainabilityBaselinePlugin.kt:994-1015`).
- `tramai-<vendor>/src/test/kotlin/.../<Vendor>ProviderTckTest.kt` — the enrollment runner (template: `tramai-deepseek/src/test/kotlin/dev/tramai/deepseek/DeepSeekProviderTckTest.kt:23-76`).

## NOT changed

- **Engine retry/fallback/admission** — `ProviderExecutionCoordinator` owns these; provider adapters do transport/vendor translation only (see ADR-009).
- **`ProviderTck` itself** — the conformance contract is an independent oracle; do not weaken it to make a provider pass.
- **Analyzer/baseline** — never edit `config/quality/0.6.0-baseline.json` in the same PR.
- **`tramai-core` provider registry** — no changes needed unless the SPI itself is extended (that is a separate `public-api` change).

## Required tests / TCK

- `ProviderTck` — `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/provider/ProviderTck.kt` (abstract, `abstract val harness: ProviderTckHarness` L53): ~40 offline tests (identity, capabilities, cancellation, safe-error redaction, HTTP wire, tools, vision, structured, streaming).
- Fixtures: `ProviderTckHarness.kt` (L95), `StubHttpClient`, `ProviderHttpFixtures`, `RecordingProviderFailureDiagnosticObserver`.
- **Enrollment gate:** `tramai-testing/src/test/kotlin/dev/tramai/testing/ProviderTckEnrollmentArchitectureTest.kt:26` — any new `ModelProvider` implementation needs a runner named after it in its module (L55-69, L126-131). Runs inside `verify060Architecture` via the `architectureContractEnrollmentTest` task (`MaintainabilityBaselinePlugin.kt:914-937`, `provider-contracts` check L1018).
- Doc: `docs/reference/provider-compatibility-contract.md` (enroll L9-26, matrix L53-62).

## Compatibility

- **BCV API dump is mandatory and generated:** `./gradlew apiDump` → `<module>/api/<module>.api`; commit it (`ApiCompatibilityVerifier.kt:90`). Verified by `apiCheck` + `api-architecture` in `verify060Architecture` (regenerates + compares, `MaintainabilityBaselinePlugin.kt:947-951`, L1628-1650).
- `providerId()` is a stable identity — changing it after release breaks routing and TCK enrollment.
- Preview API stability: provider modules are `apiStability: preview`; coordinate with the BOM release.

## Failure / cancellation / lifecycle

- Failures: throw `ProviderException` with a `ProviderFailureCode` (`dev.tramai.core.exception`); never leak raw vendor errors to callers.
- Cancellation: propagate `CancellationException` unchanged (the engine's provider-execution boundary owns retry/fallback on admitted attempts).
- Lifecycle: providers are long-lived singletons in the `ProviderRegistry`; no per-request lifecycle hooks unless the vendor SDK requires explicit close (then implement it in the adapter and document it in the card).

## Verification

```bash
./gradlew :tramai-<name>:test --tests '*ProviderTckTest'
./gradlew verify060Architecture   # includes provider-contracts + api-architecture
./gradlew verifyPr                # primary gate
./scripts/verify-zero-egress.sh   # CI-level Docker --network=none harness (not in verifyPr)
```

Use `-PchangeClass=...` per AGENTS.md classification (`runtime-behaviour` default).

## Common mistakes

- Adding a `META-INF/services` file — providers are explicitly registered, not service-loaded.
- Implementing retry/fallback inside the adapter — that belongs in the engine's `ProviderExecutionCoordinator`.
- Forgetting the TCK runner — the enrollment gate fails `verify060Architecture`.
- Editing the analyzer/baseline in the same PR — forbidden per AGENTS.md.
- Changing `providerId()` after first release — breaks routing + TCK.

## Related ADRs / specs

- [ADR-007](../../adr/adr-007.md) — resolve providers from model names with explicit override support
- [ADR-009](../../adr/adr-009.md) — retry orchestration stays in tramai-engine, structured analysis in tramai-structured
- [ADR-013](../../adr/adr-013.md) — streaming: raw text + dedicated streaming contract; [ADR-015](../../adr/adr-015.md) — streaming failover limited to startup
- [spec-003-provider-integration.md](../../specs/spec-003-provider-integration.md) — provider SPI + registration
- [docs/reference/provider-compatibility-contract.md](../../reference/provider-compatibility-contract.md) — TCK enrollment + matrix
