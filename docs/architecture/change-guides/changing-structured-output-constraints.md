# Change Guide: Changing Structured-Output Constraints

## Start here

Structured output is: `@AiService` return type → compiled `StructuredTypeDescriptor` → JSON schema → shape validation → Jackson deserialize → value validation. **Constraints are compiled at descriptor build time** (`@AiRange`, `@AiMinItems`, `@AiDescription`, nullability) and enforced by `StructuredJsonShapeValidator` (pre-deserialize) + `StructuredValueValidator` (post-deserialize). Changing a constraint means changing the compiler + validator + TCK fixtures — the TCK pins every failure stage.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (structured-output ownership → `tramai-structured`), read the module cards [`tramai-structured.md`](../../modules/tramai-structured.md) + [`tramai-core.md`](../../modules/tramai-core.md), then follow this guide.

## Owning module

- Contracts + annotations: `tramai-core/structured`, `tramai-core/annotations` (`AiRange`, `AiMinItems`, `AiDescription`).
- Handler + descriptor machinery: `tramai-structured`.
- Engine consumption: `tramai-engine/structured` (`StructuredResponseCoordinator`).

## Authoritative contracts

- `StructuredOutputHandler` — `tramai-core/src/main/kotlin/dev/tramai/core/structured/StructuredOutputHandler.kt`: `createContract` L12, `analyze` L17-20, `generateSchema` L25, `deserialize` L30-33, `serialize` L38.
- `StructuredOutputResult` — `Success(value, rawResponse)` L10-15; `Failure(rawResponse, errorSummary, feedbackMessage)` L20-36 (ABI-stable 3-arg ctor, mutable diagnostic L35).
- `StructuredOutputFailureCode` — `CONTRACT_FAILED / OUTPUT_REJECTED / REPAIR_EXHAUSTED / HANDLER_FAILED` (L8-17).
- Descriptor model — `tramai-structured/.../descriptor/StructuredTypeDescriptor.kt`: sealed `Scalar` L22, `Enum(values)` L35, `Collection(item, minItems)` L41, `Object(typeName, properties)` L48; `StructuredPropertyDescriptor(name, type, required, description, range, accessor)` L70-77; `NumericRange` L80.
- Constraint compile point — `KotlinStructuredTypeCompiler.kt` `compileProperty` L64-92: `@AiMinItems` (collection-only, L70-82), `required = !nullable` (L87), `@AiDescription` (L88), `@AiRange`→`NumericRange` (L89). JavaBean counterpart: `JacksonJavaBeanStructuredTypeCompiler.kt`.
- Fingerprint — `StructuredContractFingerprint.kt:21-28` (SHA-256 of descriptor; excludes typeName L50-53 and accessors so Kotlin/JavaBean parity holds).

## Files normally changed

| Constraint | Compile | Schema | Shape-validate | Value-validate |
|---|---|---|---|---|
| `@AiRange` | `KotlinStructuredTypeCompiler.kt:89` | `StructuredSchemaRenderer.kt` | — | `StructuredValueValidator.kt:74-81` |
| `@AiMinItems` | `KotlinStructuredTypeCompiler.kt:70-82` | renderer minItems | `StructuredJsonShapeValidator.kt:36` (array-ness) | `StructuredValueValidator.kt:36-42` |
| required/nullable | `compileProperty:87` | renderer `required` list | `StructuredJsonShapeValidator.kt:62-72` | `StructuredValueValidator.kt:18-21` |
| new annotation | new key in descriptor + compiler | renderer | validator | validator |

Plus: `StructuredSchemaRenderer.kt` (descriptor→schema, `additionalProperties:false` L80), `StructuredContractFingerprint.kt` (fingerprint changes when the descriptor changes), TCK fixtures in `tramai-structured/src/test/.../tck/`.

## NOT changed

- **`StructuredResponseCoordinator`** (engine) — it consumes the handler contract; constraint semantics don't live there. Only `StructuredOutputContractTck` failure stages are engine-visible.
- **The TCKs** — `StructuredOutputContractTck.kt` pins failure stages; never weaken it.
- **Kotlin/JavaBean parity** — descriptor fingerprints exclude typeName + accessors so both compilers must produce identical descriptors; don't introduce a Kotlin-only constraint.
- **Analyzer/baseline** — same-PR edits forbidden.
- Failure stage mapping: a new constraint must map to exactly one `FailureStage` — don't blur EXTRACTION/JSON_PARSE/SHAPE/DESERIALIZATION/VALUE_VALIDATION.

## Required tests / TCK

- `tramai-structured/src/test/kotlin/dev/tramai/structured/tck/StructuredOutputContractCase.kt` — case model + `FailureStage` enum (L41-52).
- `tck/StructuredOutputContractTck.kt` — lifecycle verifier: compile-failure L49-61, schema L63-83, valid round-trip L85-96, per-stage failure pinning L98-131, SHAPE vs VALUE_VALIDATION direct proof L142-168, deterministic repair L170-181.
- `tck/JacksonStructuredOutputContractTckTest.kt` (553 lines) — concrete fixtures (required-string L31-49, nullable L52-65, nested L83-100, root-array L136-149, enum regression).
- `JacksonStructuredOutputHandlerContractEvolutionTest.kt` — field evolution: new required field breaks old JSON, `@AiRange`/`@AiMinItems`.
- `JacksonStructuredOutputHandlerTest.kt`, `JacksonStructuredOutputHandlerBoundaryTest.kt`, `JavaBeanStructuredOutputHandlerTest.kt`, `descriptor/StructuredDescriptorSchemaValidationAgreementTest.kt`.

## Compatibility

- **Constraint semantics are the contract** — changing `@AiRange` bounds or `@AiMinItems` silently breaks downstream consumers; extend `ContractEvolutionTest` deliberately.
- **Fingerprint changes** alter cache keys and repair semantics (`StructuredDescriptorCache.kt:15-33`, `StructuredContractFingerprint.kt`).
- **Schema drift** — the rendered schema (`StructuredSchemaRenderer.kt`) is what clients compile against; `StructuredDescriptorSchemaValidationAgreementTest.kt` pins agreement between descriptor and schema.
- **ABI** — `StructuredOutputResult`/`StructuredOutputFailureCode` shapes are stable public API; changing the 3-arg ctor or failure codes is a `public-api`-classified change.

## Failure / cancellation / lifecycle

- Failure classification: `StructuredResponseCoordinator.kt` (engine) maps — missing handler → `ConfigurationException` (L51-53); parse/validation failure → `OUTPUT_REJECTED` (L175-189) + `safeStructuredOutputFailure` (L195-198); repair loop appends raw response as assistant + `feedbackMessage` as user (L391-392), terminal `REPAIR_EXHAUSTED` (L382-388); handler exceptions → `HANDLER_FAILED` always terminal (`rethrowOrSanitizeStructuredHandlerFailure` L395-436); contract failures → `CONTRACT_FAILED` (`rethrowContractFailure` L438-463).
- Lifecycle: `createContract` is cached per handler (`StructuredDescriptorCache`); new constraint annotations must be safe under concurrent cache access (ConcurrentHashMap).

## Verification

```bash
./gradlew :tramai-structured:test --tests '*StructuredOutputContractTck*' --tests '*ContractEvolution*' --tests '*SchemaValidationAgreement*'
./gradlew :tramai-engine:test --tests '*StructuredResponseCoordinator*'
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=public-api   # if annotations/contracts change
```

## Common mistakes

- Adding a constraint to the Kotlin compiler only — JavaBean parity breaks the fingerprint.
- Changing a constraint without updating the schema renderer — schema/descriptor agreement test fails.
- Blurring failure stages — the TCK pins each stage; a new constraint must map to exactly one.
- Hand-editing `StructuredOutputResult` shapes — ABI break.

## Related ADRs / specs

- [ADR-003](../../adr/adr-003.md) — structured output is the default non-String contract
- [ADR-004](../../adr/adr-004.md) — custom Jackson-based schema generation
- [ADR-009](../../adr/adr-009.md) — structured analysis stays in tramai-structured
- [spec-002-structured-output.md](../../specs/spec-002-structured-output.md) — structured-output contract
- Module card [`tramai-structured.md`](../../modules/tramai-structured.md)
