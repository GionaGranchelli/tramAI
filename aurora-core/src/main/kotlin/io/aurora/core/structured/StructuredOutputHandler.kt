package io.aurora.core.structured

import kotlin.reflect.KType

/**
 * Pluggable structured output adapter owned by the `aurora-structured` boundary.
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
}
