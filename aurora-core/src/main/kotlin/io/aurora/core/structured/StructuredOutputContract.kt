package io.aurora.core.structured

import kotlin.reflect.KType

data class StructuredOutputContract(
    val targetType: KType,
    val schemaJson: String,
)
