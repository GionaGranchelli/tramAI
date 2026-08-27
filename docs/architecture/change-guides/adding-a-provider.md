# Change Guide: Adding a Provider

**Applies to:** a new provider adapter module (e.g. `tramai-<vendor>`), `tramai-core` provider SPI, `tramai-testing` ProviderTck, build configuration.

## TL;DR

Providers are **not** service-loaded — registration is explicit via `ProviderRegistry.builder()`. A provider adapter implements `ModelProvider` (one abstract method) and proves vendor-wire conformance by enrolling in `ProviderTck` (one runner test per provider, architecture-enforced).

## 1. Implement the SPI

- `ModelProvider` — `tramai-core/src/main/kotlin/dev/tramai/core/provider/ModelProvider.kt:19`:
  - `suspend fun complete(request: ModelRequest): ModelResponse` (L23) — the only abstract member
  - `fun providerId(): String` defaults to `simpleName` (L28) — stable id used by registry + TCK
  - `fun supportsCapability(capability: ProviderCapability): Boolean = false` (L34); enum `ProviderCapability { VISION, TOOL_CALLING, STRUCTURED_OUTPUT, STREAMING }` (L9-14)
- If STREAMING is claimed, implement `StreamCapable` — `tramai-core/.../provider/StreamCapable.kt:10-18`: `fun stream(request: ModelRequest): Flow<StreamChunk>`.
- Supporting types: `ModelRequest`/`ModelResponse` (`dev.tramai.core.model`), `ProviderException`/`ProviderFailureCode` (`dev.tramai.core.exception`), `StreamChunk` (`dev.tramai.core.model`). There is **no** `ModelProviderAdapter` interface.
- Transport translation only: retries, fallbacks, and admission live in the engine's `ProviderExecutionCoordinator`, never inside the adapter.

## 2. Register (manual, not ServiceLoader)

- `ProviderRegistry` — `tramai-core/.../provider/ProviderRegistry.kt:32` is a pure facade over an immutable `ProviderRoutingPlan`. Entry points: `builder()` (L38), `singleProvider(provider)` (L40), `from(plan)` (L46).
- Builder registration: `fun provider(name: String, provider: ModelProvider, default: Boolean = false): Builder` (L52); plus `model(...)` (L56), `fallbackModel`/`fallbackProvider` (L58/62), `defaultProvider(...)` (L66), `build()` (L68). The plan is the exact object consulted at runtime (`ProviderRoutingPlan.kt:35-75`).

## 3. Enroll in ProviderTck (mandatory)

- Contract: `tramai-testing/src/testFixtures/kotlin/dev/tramai/testing/provider/ProviderTck.kt` (abstract, `abstract val harness: ProviderTckHarness` L53) — ~40 offline tests: identity, capabilities, cancellation, safe-error redaction, HTTP wire, tools, vision, structured, streaming.
- Fixtures in the same dir: `ProviderTckHarness.kt` (L95), `StubHttpClient`, `ProviderHttpFixtures`, `RecordingProviderFailureDiagnosticObserver`.
- **Enrollment gate:** `tramai-testing/src/test/kotlin/dev/tramai/testing/ProviderTckEnrollmentArchitectureTest.kt:26` pins runners by name (L32-41); any new `ModelProvider` implementation needs `<ProviderName>TckTest.kt` in the same module's `src/test/kotlin` (L55-69, L126-131).
- Runner template: `tramai-deepseek/src/test/kotlin/dev/tramai/deepseek/DeepSeekProviderTckTest.kt:23-76`.
- Execution: `architectureContractEnrollmentTest` task (build-logic `MaintainabilityBaselinePlugin.kt:914-937`) feeds the `provider-contracts` check of `verify060Architecture` (L1018). Doc: `docs/reference/provider-compatibility-contract.md` (enroll L9-26, matrix L53-62).

## 4. Module catalog + boundaries + BOM

- `config/quality/module-catalog.yml`: anchor `&provider` (L16) `maturity: preview, visibility: public, owner: providers, dependencyPolicy: provider-adapter`; entry shape (tramai-deepseek L126-131, tramai-openai L189-194): `path: ":tramai-<name>"`, `<<: *provider`, `rationale: "Provider adapter for ..."`, `layer: provider-adapters`, `publishability: published`, `apiStability: preview`.
- `config/quality/module-boundaries.yml`: forbidden edges (L9+), enforced by `verify060Architecture` dependency probes.
- Add the project to `settings.gradle.kts` and to the published BOM set — the manifest verifier checks catalog vs actual projects/published/BOM (`MaintainabilityBaselinePlugin.kt:994-1015`).

## 5. API baseline (generated, must commit)

- Binary Compatibility Validator (BCV 0.16.3, `build.gradle.kts:19`, `gradle/libs.versions.toml:75`) gives every subproject `apiCheck`/`apiDump`.
- Run `./gradlew apiDump` → creates `<module>/api/<module>.api`; commit it (`ApiCompatibilityVerifier.kt:90`). Verified by `apiCheck` + `api-architecture` in `verify060Architecture` (regenerates + compares, `MaintainabilityBaselinePlugin.kt:947-951`, L1628-1650).

## 6. Mandatory verification

```bash
./gradlew :tramai-<name>:test --tests '*ProviderTckTest'
./gradlew verify060Architecture   # includes provider-contracts + api-architecture
./gradlew verifyPr                # primary gate: subproject + build-logic tests, baseline, change policy
./scripts/verify-zero-egress.sh   # CI-level Docker --network=none harness (not in verifyPr)
```

Use `-PchangeClass=...` per AGENTS.md classification (`runtime-behaviour` default).

**Guardrails:** no service-loader files; provider adapters own transport/vendor translation only (retry/fallback lives in the engine); a new module without its TCK runner fails `verify060Architecture`; never edit analyzer/baseline in the same PR.
