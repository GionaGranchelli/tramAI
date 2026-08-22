# Structured-Output Contract TCK

Epic 7.2 (PR #266). One reusable test kit proving a target type has one
coherent contract across every structured-output layer.

## What the TCK drives

Each `StructuredOutputContractCase` (in
`tramai-structured/src/test/kotlin/dev/tramai/structured/tck/`) carries the
target type, valid JSON, schema expectations, invalid inputs with their
expected failure stage, and a value predicate. The runner
(`StructuredOutputContractTck`) walks the full lifecycle:

```
compile descriptor
      ↓
generate schema
      ↓
analyze valid JSON
      ↓
shape validation
      ↓
deserialization
      ↓
value validation
      ↓
success value
```

plus deterministic-repair verification: the same invalid input always yields
the same `errorSummary` and `feedbackMessage`.

## Failure stages

Invalid fixtures declare where they must fail:

| Stage | How it is pinned |
|---|---|
| `EXTRACTION` | unique summary "Could not extract JSON content…" |
| `JSON_PARSE` | unique summary "Could not parse the JSON payload" |
| `SHAPE` | shared summary "Structured output failed validation" + feedback phrasing ("is required", "Expected an array") |
| `DESERIALIZATION` | unique summary "Could not deserialize the JSON payload" |
| `VALUE_VALIDATION` | shared summary + feedback phrasing ("must be between", "must not be null", "must contain at least") |

`SHAPE` and `VALUE_VALIDATION` share the summary by design (existing handler
behaviour), so the TCK pins those two stages via the stage-specific feedback
text.

## Schema rule → enforcement owner

Every emitted schema rule either has a matching validation rule or a
documented reason why validation is delegated to deserialization.

| Schema rule | Enforcement owner |
|---|---|
| `type` (scalar/object/array/enum) | Jackson deserialization |
| `properties` shape (missing/extra keys) | shape validator (required presence) + Jackson (unknown keys) |
| `required` (non-nullable fields) | shape validator (`Property 'x' is required`) |
| `additionalProperties: false` | **delegated to Jackson deserialization** (shape validator does not reject unknown keys) |
| `minimum` / `maximum` (`@AiRange`) | value validator |
| `minItems` (`@AiMinItems`) | value validator |
| `description` (`@AiDescription`) | schema only — no runtime semantic effect |
| `enum` allowed values | **delegated to Jackson deserialization** (deliberate: invalid enum values surface as "Could not deserialize the JSON payload", matching pre-descriptor behaviour; #262 regression class) |
| `nullable` | shape validator (null required field rejected) + value validator (null non-nullable property rejected) |

The invariant is not "one validator validates everything"; it is one declared
contract with a documented enforcement owner.

## Fingerprint evolution

`StructuredContractFingerprintEvolutionTest` builds one baseline descriptor
and mutates exactly one semantic element at a time (property added/removed,
requiredness, nullability, scalar kind, range, description, minItems,
collection item contract, enum membership, nested descriptor) — each must
change the SHA-256 hash.

Inverse tests prove non-semantic differences never change the hash: different
`ValueAccessor`, different compiler instance, repeated compilation, and —
critically — **different `Object.typeName`**. `typeName` is compiler /
diagnostic metadata, not part of the JSON contract: equivalent Kotlin and
JavaBean DTOs have different class names but must fingerprint identically.
The canonical fingerprint walk therefore excludes it.

## Mutation evidence

The TCK is mutation-sensitive. Temporarily breaking production code turns it
RED:

- ignoring `@AiRange` in `StructuredValueValidator` → `range constraint case`
  and `java bean annotated scalar case` fail
- disabling required-property shape enforcement → 5 cases fail
  (`required-string`, `nested object`, `java bean missing primitive`,
  `java bean annotated scalar`, `deterministic repair`)
- dropping range/description from the fingerprint canonical walk →
  `numeric range change` and `description change` evolution tests fail

## Files

- `StructuredOutputContractCase.kt` — fixture model (id, targetType,
  validJson, schema expectations, invalid cases, value predicate,
  compile-failure expectation)
- `StructuredOutputContractTck.kt` — lifecycle runner
- `JacksonStructuredOutputContractTckTest.kt` — the matrix (24 cases)
- `StructuredContractFingerprintEvolutionTest.kt` — one-mutation-at-a-time
  fingerprint evolution + inverse tests (18 tests)

Existing `JacksonStructuredOutputHandler*Test` /
`JavaBeanStructuredOutputHandlerTest` suites remain the regression oracle and
are not rewritten by the TCK.
