package dev.tramai.security.classification

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

data class ClassificationDecision(
    val classification: DataClassification,
    val source: ClassificationSource,
    val matchedRuleIds: List<String>,
    val usedDefault: Boolean,
)
