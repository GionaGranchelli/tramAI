package dev.tramai.structured.descriptor

/**
 * Canonical, language-neutral description of a structured output target type.
 *
 * A [StructuredTypeDescriptor] is compiled once per target type from either
 * Kotlin reflection or Jackson JavaBean introspection (see the two compiler
 * implementations). After compilation, schema generation, raw JSON shape
 * validation, runtime value validation, and contract fingerprinting consume
 * only this model — they never inspect [kotlin.reflect.KType], [kotlin.reflect.KClass],
 * or Jackson [com.fasterxml.jackson.databind.JavaType] again.
 *
 * Kotlin-vs-Java differences are allowed while creating the descriptor and
 * must disappear after compilation.
 */
internal sealed interface StructuredTypeDescriptor {

    /** Whether a JSON `null` is accepted at this position. */
    val nullable: Boolean

    /** A primitive scalar: string, integer, number, or boolean. */
    data class Scalar(
        val kind: ScalarKind,
        override val nullable: Boolean,
    ) : StructuredTypeDescriptor

    /**
     * A finite set of allowed string values.
     *
     * First-class because schema semantics (`type: string` + `enum`) and
     * deserialization semantics (enum constant name) must never drift apart
     * (regression: #262 allowed the schema to describe enums as name/ordinal
     * objects that could never deserialize).
     */
    data class Enum(
        val values: List<String>,
        override val nullable: Boolean,
    ) : StructuredTypeDescriptor

    /** A list/collection of one item type. */
    data class Collection(
        val item: StructuredTypeDescriptor,
        val minItems: Int?,
        override val nullable: Boolean,
    ) : StructuredTypeDescriptor

    /** A structured object with named, ordered properties. */
    data class Object(
        val typeName: String,
        val properties: List<StructuredPropertyDescriptor>,
        override val nullable: Boolean,
    ) : StructuredTypeDescriptor
}

/** JSON scalar kinds mapped from both Kotlin and Java primitive/wrapper types. */
internal enum class ScalarKind {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
}

/**
 * One property of a [StructuredTypeDescriptor.Object].
 *
 * [accessor] is the language-specific runtime reader used by value validation.
 * It is intentionally excluded from contract fingerprinting: it is runtime
 * machinery, not a semantic property of the contract.
 */
internal data class StructuredPropertyDescriptor(
    val name: String,
    val type: StructuredTypeDescriptor,
    val required: Boolean,
    val description: String?,
    val range: NumericRange?,
    val accessor: ValueAccessor,
)

/** Inclusive numeric constraint compiled from `@AiRange`. */
internal data class NumericRange(
    val min: Double,
    val max: Double,
)

/** Reads a property value from a deserialized instance for validation. */
internal fun interface ValueAccessor {
    fun read(target: Any): Any?
}
