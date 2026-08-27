# Change Guide: Changing Structured-Output Constraints

**Applies to:** structured-output contract handling in `tramai-core` (contracts), `tramai-structured` (handler + descriptor compiler), and `tramai-engine` (`StructuredResponseCoordinator`).

## TL;DR

Structured output is: `@AiService` return type → compiled `StructuredTypeDescriptor` → JSON schema → shape validation → Jackson deserialize → value validation. **Constraints are compiled at descriptor build time** (`@AiRange`, `@AiMinItems`, `@AiDescription`, nullability) and enforced by `StructuredJsonShapeValidator` (pre-deserialize) + `StructuredValueValidator` (post-deserialize). Changing a constraint means changing the compiler + validator + TCK fixtures — the TCK pins every failure stage.

## 1. The contracts (`tramai-core/src/main/kotlin/dev/tramai/core/structured/`)

- `StructuredOutputHandler.kt`: interface — `createContract` (L12), `analyze` (L17-20), `generateSchema` (L25), `deserialize` (L30-33), `serialize` (L38).
- `StructuredOutputContract.kt` (`targetType`, `schemaJson`, L8-13).
- `StructuredOutputResult.kt` — `Success(value, rawResponse)` L10-15; `Failure(rawResponse, errorSummary, feedbackMessage)` L20-36 (mutable diagnostic `failure` L35, ABI-stable 3-arg ctor).
- `StructuredOutputFailureCode.kt`: `CONTRACT_FAILED / OUTPUT_REJECTED / REPAIR_EXHAUSTED / HANDLER_FAILED` (L8-17).
- Constraint annotations: `tramai-core/.../annotations/AiRange.kt`, `AiMinItems.kt`, `AiDescription.kt`.

## 2. The handler (`tramai-structured/.../JacksonStructuredOutputHandler.kt`, 182 lines)

- Pure orchestration (L18-27): delegates to descriptor machinery — `typeCompiler` L34, cache L35, `schemaRenderer` L36, `shapeValidator` L37, `valueValidator` L38.
- `analyze` pipeline (L46-111): `extractJsonCandidate` (fenced/bare/bracket, L134-181) → `readTree` (L66-75) → shape validation (L79-85) → Jackson deserialize (L87-96) → value validation (L98-105) → Success/Failure with repair `feedbackMessage`.
- `generateSchema` L113, `deserialize` L116-127, `serialize` L129.

## 3. The compiled descriptor model (`tramai-structured/.../descriptor/`)

- `StructuredTypeDescriptor.kt` — sealed: `Scalar` L22, `Enum(values)` L35 (first-class), `Collection(item, minItems)` L41, `Object(typeName, properties)` L48; `StructuredPropertyDescriptor(name, type, required, description, range, accessor)` L70-77; `NumericRange` L80 (from `@AiRange`).
- `StructuredTypeCompiler.kt` — facade (L17): scalars L36-40, List→Collection L41-49, Map→error L50, enum L53-57, Kotlin-vs-JavaBean dispatch via `kotlin.Metadata` L58-62/73-74, recursion via `CompileContext`.
- `KotlinStructuredTypeCompiler.kt` — cycle detection L21-31; **constraints compiled here** in `compileProperty` (L64-92): `@AiMinItems` (collection-only, compile error otherwise, L70-82), `required = !nullable` (L87), `@AiDescription` (L88), `@AiRange`→`NumericRange` (L89). `JacksonJavaBeanStructuredTypeCompiler.kt` is the JavaBean counterpart.
- `StructuredSchemaRenderer.kt` — descriptor→schema: minimum/maximum, minItems, `required` list, `additionalProperties:false` (L80).
- `StructuredJsonShapeValidator.kt` — pre-deserialization: required presence/nullability (L62-72), unknown-key rejection (L55-60), array-ness (L36); enum membership deliberately delegated to Jackson (L12-16).
- `StructuredValueValidator.kt` — post-deserialization runtime: `@AiMinItems` (L36-42), `@AiRange` bounds (L74-81), nullability (L18-21).
- `StructuredContractFingerprint.kt` — SHA-256 of descriptor (L21-28); excludes typeName (L50-53) and accessors so Kotlin/JavaBean parity holds.
- `StructuredDescriptorCache.kt` — per-handler ConcurrentHashMap (L15-33).

## 4. What a constraint change touches

| Constraint | Compile | Schema | Shape-validate | Value-validate |
|---|---|---|---|---|
| `@AiRange` | `KotlinStructuredTypeCompiler.kt:89` | `StructuredSchemaRenderer.kt` | — | `StructuredValueValidator.kt:74-81` |
| `@AiMinItems` | `KotlinStructuredTypeCompiler.kt:70-82` (collection-only) | renderer minItems | `StructuredJsonShapeValidator.kt:36` (array-ness) | `StructuredValueValidator.kt:36-42` |
| required/nullable | `compileProperty:87` | renderer `required` list | `StructuredJsonShapeValidator.kt:62-72` | `StructuredValueValidator.kt:18-21` |
| new annotation | new key in descriptor + compiler | renderer | validator | validator |

The **fingerprint** (SHA-256 over descriptor) changes whenever the compiled descriptor changes — cache keys and repair semantics follow.

## 5. Mandatory contract tests

- `tramai-structured/src/test/kotlin/dev/tramai/structured/tck/StructuredOutputContractCase.kt` — case model + `FailureStage` enum `EXTRACTION/JSON_PARSE/SHAPE/DESERIALIZATION/VALUE_VALIDATION` (L41-52).
- `tck/StructuredOutputContractTck.kt` — lifecycle verifier: compile-failure L49-61, schema L63-83, valid round-trip L85-96, per-stage failure pinning L98-131, direct layer proof for SHAPE vs VALUE_VALIDATION L142-168, deterministic repair L170-181.
- `tck/JacksonStructuredOutputContractTckTest.kt` (553 lines) — concrete fixtures: required-string L31-49, nullable L52-65, nested L83-100, root-array L136-149, enum regression.
- `JacksonStructuredOutputHandlerTest.kt`, `JacksonStructuredOutputHandlerBoundaryTest.kt`, `JacksonStructuredOutputHandlerContractEvolutionTest.kt` (field evolution: new required field breaks old JSON), `JavaBeanStructuredOutputHandlerTest.kt`, `descriptor/StructuredDescriptorSchemaValidationAgreementTest.kt`.

## 6. Engine consumption (what the coordinator enforces)

`tramai-engine/.../structured/StructuredResponseCoordinator.kt` (485 lines, internal class L39):

- Missing handler → `ConfigurationException` (L51-53); `operation.structuredContract(handler)` (L55) = `handler.createContract(requireNotNull(returnType))` (`TramaiEngine.kt:901-905`).
- Success path → BEFORE_RESPONSE_RETURN policy (L142-150), memory persist (L151-164), `onCallCompleted(parseSuccess=true)` (L165).
- Failure path → diagnostic `OUTPUT_REJECTED` (L175-189), throws `safeStructuredOutputFailure` (L195-198).
- Repair loop: appends raw response as assistant + `feedbackMessage` as user (L391-392), terminal `REPAIR_EXHAUSTED` on last attempt (L382-388); handler exceptions → `HANDLER_FAILED` always terminal (`rethrowOrSanitizeStructuredHandlerFailure` L395-436); contract failures → `CONTRACT_FAILED` (`rethrowContractFailure` L438-463).
- Test: `tramai-engine/src/test/kotlin/dev/tramai/engine/structured/StructuredResponseCoordinatorTest.kt`.

## 7. Mandatory verification

```bash
./gradlew :tramai-structured:test --tests '*StructuredOutputContractTck*' --tests '*ContractEvolution*' --tests '*SchemaValidationAgreement*'
./gradlew :tramai-engine:test --tests '*StructuredResponseCoordinator*'
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=public-api   # if annotations/contracts change
```

**Guardrails:** constraint semantics are the contract — changing `@AiRange` bounds or `@AiMinItems` silently breaks downstream consumers, so the ContractEvolution tests must be extended deliberately; Kotlin/JavaBean descriptor parity is pinned by the fingerprint (typeName + accessors excluded); failure stages are pinned — a new constraint must map to exactly one `FailureStage`.
