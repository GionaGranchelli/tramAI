package dev.tramai.structured.tck

import kotlin.reflect.KType

/**
 * One structured-output contract case for Epic 7.2.
 *
 * A single case drives every assertion of the lifecycle from one source of
 * truth — descriptor compilation, generated schema, raw JSON shape
 * validation, deserialization, runtime value validation, and deterministic
 * repair feedback — so no layer maintains its own independent fixture lists.
 */
internal data class StructuredOutputContractCase(
    /** Stable identifier, also used in failure diagnostics. */
    val id: String,
    /** The target type the handler compiles. */
    val targetType: KType,
    /**
     * JSON that must round-trip successfully end to end. When null, only
     * descriptor compilation + schema assertions run (e.g. root scalars,
     * which the extractor cannot return as bare strings).
     */
    val validJson: String?,
    /** Expected schema fragment assertions keyed by JSON pointer-ish path. */
    val expectedSchema: Map<String, SchemaExpectation> = emptyMap(),
    /** Invalid inputs and the stage at which each must fail. */
    val invalidCases: List<InvalidCase> = emptyList(),
    /** Optional expected deserialized value for validJson. */
    val expectedValue: (Any) -> Boolean = { true },
    /** Optional schema-type assertion for root-level cases. */
    val expectedRootType: String? = null,
    /**
     * When set, descriptor compilation itself must fail with
     * IllegalArgumentException/IllegalStateException containing this
     * fragment. The remaining lifecycle assertions are skipped.
     */
    val expectedCompileFailure: String? = null,
)

/** Where in the pipeline a failure must surface. */
internal enum class FailureStage {
    /** No JSON could be extracted from the response. */
    EXTRACTION,
    /** JSON present but unparseable. */
    JSON_PARSE,
    /** Pre-deserialization shape validation rejected the payload. */
    SHAPE,
    /** Jackson deserialization failed. */
    DESERIALIZATION,
    /** Post-deserialization value validation failed. */
    VALUE_VALIDATION,
}

internal data class InvalidCase(
    val json: String,
    val expectedStage: FailureStage,
    /**
     * Expected fragment of the feedback message. For EXTRACTION / JSON_PARSE /
     * DESERIALIZATION the stage is pinned by the unique error summary; for
     * SHAPE and VALUE_VALIDATION (which share the summary "Structured output
     * failed validation") this fragment is the stage discriminator.
     */
    val expectedFeedbackFragment: String? = null,
)

/**
 * One schema assertion: the rendered schema value at a JSON-pointer-ish path
 * must satisfy [predicate]. The predicate receives the PLAIN value at that
 * path (String / Number / Boolean / null / List / Map), not a node wrapper.
 */
internal data class SchemaExpectation(
    val description: String,
    val predicate: (Any?) -> Boolean,
)
