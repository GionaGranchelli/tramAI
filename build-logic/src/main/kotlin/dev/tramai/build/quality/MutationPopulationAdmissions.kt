package dev.tramai.build.quality

/**
 * Base-side preauthorization for admitting an *appearing* candidate-only NON_KILLED identity
 * (0.7.1g1G3): the population-admission ceremony (M30+).
 *
 * The outcome ratchet (M06) forbids a PR from certifying its own new survivor: a mutant that
 * appears as NON_KILLED only in the candidate measurement fails unless it is killed. That rule is
 * correct, and it is also why an adjudicated survivor that is *absent from the committed
 * population* could never be admitted at all. This ledger supplies the missing transition without
 * a privileged mode, by the same temporal trust boundary as the classification enrollment ledger
 * (M22-M29): **the authorization must already exist in the PR's base**.
 *
 * ```
 * P1 - MINT      base:      X absent from the committed population, absent from this ledger
 *                candidate: population unchanged, proposes an authorization for X
 * P2 - CONSUME   base:      the authorization for X present and byte-identical
 *                candidate: the exact authorized row appears as NON_KILLED, and the authorization
 *                           is consumed (removed) in the same transition
 * ```
 *
 * ## Binding semantics, field by field
 *
 * - [identity] - canonical 64-hex schema v2 identity. The only key; descriptor or source-line
 *   similarity is never authority.
 * - [status], [outcome], [family], [module] - the complete persisted row. Authorizing an identity
 *   and admitting a *different* row (status laundering, outcome substitution, re-homing into
 *   another family, module substitution) fails. This is the hole M28 closed for classifications.
 * - [analyzer] - the semantics this authorization is valid under (M16-M19); it may never be
 *   consumed under different analyzer settings.
 * - [populationDigest] - the projection hash of the measurement in which X appears as
 *   NON_KILLED. At consumption it is compared against the verifier's own canonical fresh
 *   measurement, never against anything the candidate supplies, so a candidate can never construct
 *   the measurement that authorizes itself.
 * - [fromBaseSha] - the exact [MutationRatchetAuthority.baseSha] against which this authorization
 *   was first proposed. **Enforced on introduction. Thereafter immutable provenance; it is NOT
 *   required to equal the immediate base SHA of later retaining or consuming transitions.** A
 *   pending authorization legitimately survives intermediate merges, so requiring it to track the
 *   immediate base would make delayed consumption impossible and would itself violate
 *   authorization immutability. Its enforcement value is anti-replay *at mint time* - an old
 *   authorization payload cannot be replayed onto a different authority base - while
 *   consumption-time protection comes from base-side existence, immutability, the exact row, the
 *   analyzer semantics and the trusted measurement digest.
 * - [reason], [issue], [targetPhase] - the recorded adjudication rationale; rewriting them after
 *   the decision fails (the M24/M28 lesson).
 * - [authorizedBy], [authorizedAt] - **audit only, no enforcement value.** Recording them is fine;
 *   letting identity or actor carry trust would make them a bypass.
 *
 * A pending authorization is for an identity that is *absent* from the committed population, so -
 * unlike an enrollment (M25) - target absence is the expected state, not invalidity. It becomes
 * consumable only when the verifier's fresh measurement contains the exact authorized row. If that
 * measurement reports X as KILLED, the authorization is no longer needed and is cleanable, and
 * admission must never be forced.
 */
data class MutationPopulationAdmission(
    val identity: String,
    val status: String,
    val outcome: String,
    val family: String,
    val module: String,
    val analyzer: MutationAnalyzerSemantics,
    val fromBaseSha: String,
    val populationDigest: String,
    val reason: String,
    val issue: String? = null,
    val targetPhase: String? = null,
    val authorizedBy: String? = null,
    val authorizedAt: String? = null,
) {
    /**
     * The enforceable payload: every bound field except the audit-only ones. Base/candidate
     * byte-identity is judged over this, so rewriting any bound field fails while touching audit
     * metadata alone can never manufacture authority.
     */
    fun enforcedPayload(): List<Any?> =
        listOf(
            identity,
            status,
            outcome,
            family,
            module,
            analyzer,
            fromBaseSha,
            populationDigest,
            reason,
            issue,
            targetPhase,
        )

    /** True when [mutant] is the exact persisted row this authorization admits. */
    fun admitsRow(mutant: MutationOutcome): Boolean =
        mutant.identity == identity &&
            mutant.status == status &&
            mutant.outcome == outcome &&
            mutant.family == family &&
            mutant.module == module
}

data class MutationPopulationAdmissions(
    val schemaVersion: String,
    val admissions: List<MutationPopulationAdmission>,
) {
    fun byIdentity(): Map<String, MutationPopulationAdmission> = admissions.associateBy { it.identity }

    companion object {
        /** No authorizations: strictly the most restrictive state (M06 unchanged). */
        val NONE = MutationPopulationAdmissions(schemaVersion = "1", admissions = emptyList())
    }
}
