package dev.tramai.core.policy

/**
 * Strict ordering for [DataClassification], most sensitive highest.
 *
 * Defined as an exhaustive `when` over the enum so that adding a new
 * classification becomes a compile-time error until its ranking semantics are
 * explicitly defined. Resolution takes the highest rank, so an explicit
 * stronger classification can never be silently downgraded to a weaker one.
 *
 * This lives in `tramai-core` rather than a consumer module because more than
 * one module has to agree on the ordering; a second copy would be a second
 * meaning.
 */
val DataClassification.rank: Int
    get() =
        when (this) {
            DataClassification.PUBLIC -> RANK_PUBLIC
            DataClassification.INTERNAL -> RANK_INTERNAL
            DataClassification.CONFIDENTIAL -> RANK_CONFIDENTIAL
            DataClassification.RESTRICTED -> RANK_RESTRICTED
        }

// Declared below the property they rank, so the KDoc above stays attached to
// `rank` rather than to the first constant. Naming these values keeps the
// detector's magic-number rule happy; it must not cost `rank` its documentation.
private const val RANK_PUBLIC = 0
private const val RANK_INTERNAL = 1
private const val RANK_CONFIDENTIAL = 2
private const val RANK_RESTRICTED = 3

/**
 * Authority of the source that produced a classification.
 * Lower value = LESS authoritative: DECLARED > RULE_BASED > LOCAL_MODEL_ASSISTED.
 *
 * When several inputs carry the same highest classification, resolution keeps
 * the LEAST authoritative source, so recorded provenance is never more
 * confident than the weakest evidence that supports it. Exhaustive `when` so a
 * new source requires explicit ranking.
 */
val ClassificationSource.authorityRank: Int
    get() =
        when (this) {
            ClassificationSource.DECLARED -> 2
            ClassificationSource.RULE_BASED -> 1
            ClassificationSource.LOCAL_MODEL_ASSISTED -> 0
        }
