package dev.tramai.core.model

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

/**
 * Wraps any payload with caller-supplied data-classification metadata.
 *
 * This metadata travels with the payload so engine and policy components can
 * enforce request egress constraints without depending on security-module APIs.
 */
data class ClassifiedDocument<T>(
    val payload: T,
    val classification: DataClassification,
    val source: ClassificationSource,
)
