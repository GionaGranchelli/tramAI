package dev.tramai.security.classification

import dev.tramai.core.policy.DataClassification

data class ClassificationRule(
    val id: String,
    val classification: DataClassification,
    val priority: Int = 0,
    val pattern: String? = null,
    val metadataEquals: Map<String, String> = emptyMap(),
)
