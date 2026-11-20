package dev.tramai.engine

import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.authorityRank
import dev.tramai.core.policy.rank

// Exposed by SuspendedInvocationStore SPI — SuspendedInvocationMetadata.securityContext
// is part of the public API. A stable DTO for external consumers can be introduced
// when the SPI use cases are better understood.
data class ExecutionSecurityContext(
    val dataClassification: DataClassification? = null,
    val classificationSource: ClassificationSource? = null,
) {
    companion object {
        fun fromArguments(args: Array<out Any?>): ExecutionSecurityContext {
            var highestClassification: DataClassification? = null
            var leastAuthoritativeSource: ClassificationSource? = null
            var leastAuthoritativeSourceRank = Int.MAX_VALUE

            for (arg in args) {
                if (arg !is ClassifiedDocument<*>) continue
                val rank = arg.classification.rank
                val highestRank = highestClassification?.rank ?: -1
                val sourceRank = arg.source.authorityRank

                when {
                    rank > highestRank -> {
                        highestClassification = arg.classification
                        leastAuthoritativeSource = arg.source
                        leastAuthoritativeSourceRank = sourceRank
                    }

                    rank == highestRank && sourceRank < leastAuthoritativeSourceRank -> {
                        leastAuthoritativeSource = arg.source
                        leastAuthoritativeSourceRank = sourceRank
                    }
                }
            }

            return ExecutionSecurityContext(
                dataClassification = highestClassification,
                classificationSource = leastAuthoritativeSource,
            )
        }
    }
}
