package dev.tramai.build.quality

import org.gradle.api.GradleException

/**
 * Base-side preauthorization for a future classification enrollment (0.7.1g1F).
 *
 * The mutation ratchet (M08/M09) deliberately rejects classifications a
 * candidate adds for itself: a PR cannot decide that its own new NON_KILLED
 * survivor is acceptable. The verifier's documented complement — new
 * classifications "adjudicated on master during an enrollment ceremony and
 * becoming part of the base" — had no implementation, so an adjudicated
 * survivor could never be enrolled at all.
 *
 * This ledger supplies that missing transition without a privileged mode. An
 * enrollment is an authorization that some *later* transition may consume:
 *
 *   1. authorization transition — a PR adds an enrollment for an identity that
 *      is already NON_KILLED in the governing base population. The mutant stays
 *      unresolved; no classification exists yet.
 *   2. consumption transition — a later PR, whose BASE already contains the
 *      authorization, adds the exact classification and removes the consumed
 *      authorization.
 *
 * Trust is temporal and base-derived, never mode-derived: the authorization
 * that permits enrollment must exist in the PR's base, so a candidate can
 * never create the authority it uses in the same transition.
 *
 * The payload is the whole [MutationClassification] record, not the identity.
 * Authorizing an id alone would let a later transition substitute a different
 * classification type or rewrite the rationale; data-class equality over the
 * full payload makes "exact authorization" literal.
 */
data class MutationClassificationEnrollment(
    val classification: MutationClassification,
)

data class MutationClassificationEnrollments(
    val schemaVersion: String,
    val enrollments: List<MutationClassificationEnrollment>,
) {
    fun byIdentity(): Map<String, MutationClassification> {
        val keyed = enrollments.map { it.classification.id to it.classification }
        return keyed.toMap()
    }

    companion object {
        /** No authorizations: strictly the most restrictive state (M08/M09 unchanged). */
        val NONE = MutationClassificationEnrollments(schemaVersion = "1", enrollments = emptyList())
    }
}
