package dev.tramai.core.structured

import kotlin.reflect.KType

/**
 * Structured output contract presented to the model and parser.
 */
data class StructuredOutputContract(
    /** Kotlin return type that should be materialized. */
    val targetType: KType,
    /** JSON schema-like prompt fragment describing the expected output. */
    val schemaJson: String,
)
