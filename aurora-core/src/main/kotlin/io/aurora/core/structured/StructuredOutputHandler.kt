package io.aurora.core.structured

import kotlin.reflect.KType

interface StructuredOutputHandler {
    fun createContract(targetType: KType): StructuredOutputContract

    fun analyze(
        rawResponse: String,
        targetType: KType,
    ): StructuredOutputResult
}
