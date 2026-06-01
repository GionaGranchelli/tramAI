package dev.tramai.engine

import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

internal data class ExecutionSecurityContext(
    val dataClassification: DataClassification? = null,
    val classificationSource: ClassificationSource? = null,
) {
    companion object {
        fun fromArguments(args: Array<out Any?>): ExecutionSecurityContext {
            var highestClassification: DataClassification? = null
            var highestSource: ClassificationSource? = null

            for (arg in args) {
                if (arg is ClassifiedDocument<*> &&
                    (highestClassification == null || arg.classification.ordinal > highestClassification.ordinal)
                ) {
                    highestClassification = arg.classification
                    highestSource = arg.source
                }
            }

            return ExecutionSecurityContext(
                dataClassification = highestClassification,
                classificationSource = highestSource,
            )
        }
    }
}
