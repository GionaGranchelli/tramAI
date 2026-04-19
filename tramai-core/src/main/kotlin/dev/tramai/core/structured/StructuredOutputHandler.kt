package dev.tramai.core.structured

import kotlin.reflect.KType

/**
 * Pluggable structured output adapter owned by the `tramai-structured` boundary.
 */
interface StructuredOutputHandler {
    /**
     * Produces the contract the engine should inject into the prompt.
     */
    fun createContract(targetType: KType): StructuredOutputContract

    /**
     * Parses and validates a raw response for [targetType].
     */
    fun analyze(
        rawResponse: String,
        targetType: KType,
    ): StructuredOutputResult

    /**
     * Generates a JSON schema for the given [type].
     */
    fun generateSchema(type: kotlin.reflect.KType): String

    /**
     * Deserializes an untyped JSON payload into the given [targetType].
     */
    fun deserialize(
        input: Any,
        targetType: kotlin.reflect.KType
    ): Any

    /**
     * Serializes an object [value] into an untyped JSON representation.
     */
    fun serialize(value: Any): Any
}
