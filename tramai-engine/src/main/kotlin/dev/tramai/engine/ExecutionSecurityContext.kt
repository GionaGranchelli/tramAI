package dev.tramai.engine

import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

/**
 * Strict ordering for [DataClassification] used to pick the highest
 * classification across multiple [ClassifiedDocument] arguments.
 *
 * Defined as an exhaustive `when` over the enum so that adding a new
 * classification value becomes a compile-time error until its ranking
 * semantics are explicitly defined.
 */
internal val DataClassification.rank: Int
    get() = when (this) {
        DataClassification.PUBLIC -> 0
        DataClassification.INTERNAL -> 1
        DataClassification.CONFIDENTIAL -> 2
        DataClassification.RESTRICTED -> 3
    }

/**
 * Lower value = LESS authoritative source.
 *
 * Authority order (most to least): DECLARED > RULE_BASED > LOCAL_MODEL_ASSISTED
 *
 * For conservative audit metadata we retain the LEAST authoritative source
 * when multiple [ClassifiedDocument]s share the same highest
 * classification. Exhaustive `when` so new sources require explicit ranking.
 */
internal val ClassificationSource.authorityRank: Int
    get() = when (this) {
        ClassificationSource.DECLARED -> 2
        ClassificationSource.RULE_BASED -> 1
        ClassificationSource.LOCAL_MODEL_ASSISTED -> 0
    }

internal data class ExecutionSecurityContext(
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
