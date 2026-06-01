package dev.tramai.engine

import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

internal data class ExecutionSecurityContext(
    val dataClassification: DataClassification? = null,
    val classificationSource: ClassificationSource? = null,
) {
    companion object {
        private val classificationRank = mapOf(
            DataClassification.PUBLIC to 0,
            DataClassification.INTERNAL to 1,
            DataClassification.CONFIDENTIAL to 2,
            DataClassification.RESTRICTED to 3,
        )

        private val sourcePrecedence = mapOf(
            ClassificationSource.DECLARED to 2,
            ClassificationSource.RULE_BASED to 1,
            ClassificationSource.LOCAL_MODEL_ASSISTED to 0,
        )

        fun fromArguments(args: Array<out Any?>): ExecutionSecurityContext {
            var highestClassification: DataClassification? = null
            var highestSource: ClassificationSource? = null
            var highestSourceRank = -1

            for (arg in args) {
                if (arg is ClassifiedDocument<*>) {
                    val rank = classificationRank[arg.classification] ?: 0
                    val highestRank = highestClassification?.let { classificationRank[it] ?: 0 } ?: -1
                    val sourceRank = sourcePrecedence[arg.source] ?: -1

                    if (rank > highestRank || (rank == highestRank && sourceRank > highestSourceRank)) {
                        highestClassification = arg.classification
                        highestSource = arg.source
                        highestSourceRank = sourceRank
                    }
                }
            }

            return ExecutionSecurityContext(
                dataClassification = highestClassification,
                classificationSource = highestSource,
            )
        }
    }
}
