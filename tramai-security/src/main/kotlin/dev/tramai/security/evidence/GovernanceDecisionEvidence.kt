package dev.tramai.security.evidence

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.security.governance.AuthorizationRefusal
import dev.tramai.security.governance.CandidateAuthorizationDecision
import dev.tramai.security.governance.CandidateSelectionDecision
import dev.tramai.security.governance.CandidateViabilityDecision
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.SelectionRefusal
import dev.tramai.security.governance.ViabilityRefusal
import java.time.Instant

/**
 * Binds an existing governance decision to the context required to explain it historically.
 *
 * The envelope adds context to a decision; it never re-decides. The typed decision stays
 * authoritative and is carried as-is, so no second decision vocabulary exists and the closed
 * reason families remain the only reason families.
 *
 * [identity] is the canonical [GovernedRunIdentity] — not a parallel workload/run tuple.
 * [correlationId] is supplied and preserved, never regenerated. [policyVersion] and
 * [workflowDigest] are captured as consulted, never recomputed from current state, which is what
 * lets a historical record be interpreted without re-evaluating today's policy.
 * [eventId] is the caller-owned decision identity: the caller already creates it, so no identity
 * is minted here and no timestamp is consulted.
 *
 * [subjectDigest] is the explicitly bound, caller-supplied subject of the decision. Authorization
 * and viability decisions do not carry a candidate, so without an explicit subject two distinct
 * candidates refused for the same reason would collapse into one historical subject; the subject is
 * therefore required rather than derived from the decision. Callers produce it with
 * [CandidateSubjectDigest.of]. For a [CandidateSelectionDecision.Selected] decision the bound
 * subject must agree with the candidate the decision carries: disagreement is refused rather than
 * silently preferred, so the record cannot describe a selection of a candidate it does not name.
 *
 * This type depends on no workflow, orchestration, scheduling or engine type, so an external
 * runtime holding governance inputs can bind a decision through the same boundary.
 */
data class GovernanceDecisionEnvelope<T : Any>(
    val identity: GovernedRunIdentity,
    val correlationId: String,
    val policyVersion: String,
    val eventId: String,
    val subjectDigest: String,
    val decision: T,
    val workflowDigest: String? = null,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(policyVersion.isNotBlank()) { "policyVersion must not be blank" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(RuntimeEvidenceBundleWriter.DIGEST_REGEX.matches(subjectDigest)) {
            "subjectDigest must match ${RuntimeEvidenceBundleWriter.DIGEST_REGEX}: $subjectDigest"
        }
        val selected = decision as? CandidateSelectionDecision.Selected
        if (selected != null) {
            require(subjectDigest == CandidateSubjectDigest.of(selected.candidate)) {
                "bound subjectDigest does not match the candidate this selection decision carries; " +
                    "refusing to bind a selection to a subject it does not name"
            }
        }
    }

    /**
     * Projects this envelope into the existing `runtime-evidence.v1` shape.
     *
     * The outcome and reason are derived from the typed decision, so a caller cannot assert a
     * reason the decision does not carry. [at] and [actor] are supplied by the caller; nothing
     * volatile is read here.
     */
    fun toRuntimeEvidenceRecord(
        at: Instant,
        actor: String? = null,
    ): RuntimeEvidenceRecord {
        val outcome = GovernanceDecisionOutcome.of(decision)
        val attribution = GovernedIdentityAttribution.of(identity)
        RuntimeEvidenceAttribution.validate(identity.runId.value, attribution)
        return RuntimeEvidenceRecord(
            eventId = eventId,
            eventType = GOVERNANCE_DECISION_EVENT_TYPE,
            workflowRunId = identity.runId.value,
            correlationId = correlationId,
            actor = actor,
            createdAt = at,
            source = RuntimeEvidenceSource(component = GOVERNANCE_DECISION_COMPONENT, module = policyVersion),
            decision = RuntimeEvidenceDecision(kind = outcome.kind, reasonCode = outcome.reasonCode),
            digests =
                RuntimeEvidenceDigests(
                    subjectDigest = subjectDigest,
                    payloadDigest = payloadDigest(outcome, attribution),
                ),
            metadata = RuntimeEvidenceAttribution.merge(emptyMap(), attribution),
        )
    }

    private fun payloadDigest(
        outcome: GovernanceDecisionOutcome,
        attribution: Map<String, String>,
    ): String {
        val canonical =
            CanonicalDigestBuilder()
                .apply {
                    appendField("eventType", GOVERNANCE_DECISION_EVENT_TYPE)
                    appendField("decisionKind", outcome.kind)
                    appendNullableField("reasonCode", outcome.reasonCode)
                    appendField("policyVersion", policyVersion)
                    appendNullableField("workflowDigest", workflowDigest)
                    appendNullableField("correlationId", correlationId)
                    appendMetadataField("attribution", attribution)
                }.build()
        return EvidenceDigest.sha256(canonical)
    }
}

internal const val GOVERNANCE_DECISION_EVENT_TYPE = "governance.decision"
internal const val GOVERNANCE_DECISION_COMPONENT = "governance-decision"
internal const val AUTHORIZATION_DECISION_KIND = "governance.authorization"
internal const val VIABILITY_DECISION_KIND = "governance.viability"
internal const val SELECTION_DECISION_KIND = "governance.selection"

/**
 * The stable evidence code for a typed governance decision.
 *
 * This is a mapping over the existing closed reason families, not a second reason vocabulary: the
 * code is always the enum constant's own name, and each family maps to a distinct [kind], so a
 * non-viable candidate can never be read as not-authorized and a selection escape refusal can
 * never be read as an ordinary strategy decline.
 */
internal data class GovernanceDecisionOutcome(
    val kind: String,
    val reasonCode: String?,
) {
    companion object {
        fun of(decision: Any): GovernanceDecisionOutcome =
            when (decision) {
                is CandidateAuthorizationDecision.Authorized -> {
                    GovernanceDecisionOutcome(AUTHORIZATION_DECISION_KIND, null)
                }

                is CandidateAuthorizationDecision.NotAuthorized -> {
                    GovernanceDecisionOutcome(AUTHORIZATION_DECISION_KIND, decision.reason.name)
                }

                is CandidateViabilityDecision.Viable -> {
                    GovernanceDecisionOutcome(VIABILITY_DECISION_KIND, null)
                }

                is CandidateViabilityDecision.NotViable -> {
                    GovernanceDecisionOutcome(VIABILITY_DECISION_KIND, decision.reason.name)
                }

                is CandidateSelectionDecision.Selected -> {
                    GovernanceDecisionOutcome(SELECTION_DECISION_KIND, null)
                }

                is CandidateSelectionDecision.NoSelection -> {
                    GovernanceDecisionOutcome(SELECTION_DECISION_KIND, decision.reason.name)
                }

                else -> {
                    error(
                        "no governance evidence code for ${decision::class.qualifiedName}; " +
                            "refusing to emit evidence for a decision the governance model does not define",
                    )
                }
            }
    }
}

/**
 * The deterministic candidate subject digest.
 *
 * Public because a caller binding a per-candidate authorization or viability decision needs the
 * same subject identity the selection path produces. The deployment is part of the canonical form
 * deliberately: two deployments of one provider/model pair can sit in different trust zones or
 * authority domains, and hashing provider and model alone would collapse them into one subject.
 * Raw provider, model and deployment values are never included in the digest input as plain text.
 */
object CandidateSubjectDigest {
    fun of(candidate: ProviderCandidate): String =
        EvidenceDigest.sha256(
            CanonicalDigestBuilder()
                .apply {
                    appendField("providerId", candidate.providerId)
                    appendField("modelId", candidate.modelId)
                    appendField("deploymentId", candidate.deployment.deploymentId)
                    appendField("deploymentProviderId", candidate.deployment.providerId)
                    appendField("trustZoneName", candidate.deployment.trustZone.name.value)
                    appendField("trustZoneCategory", candidate.deployment.trustZone.category.name)
                }.build(),
        )
}

/**
 * The governance decision kinds this seam produces, registered as one canonical evidence family.
 */
internal val GOVERNANCE_DECISION_KINDS: Set<String> =
    setOf(AUTHORIZATION_DECISION_KIND, VIABILITY_DECISION_KIND, SELECTION_DECISION_KIND)

/**
 * The closed reason family for each governance decision kind, derived mechanically from the existing
 * refusal vocabularies so the evidence validator cannot drift from the decision model.
 */
internal val GOVERNANCE_REASON_CODES: Map<String, Set<String>> =
    mapOf(
        AUTHORIZATION_DECISION_KIND to AuthorizationRefusal.entries.map { it.name }.toSet(),
        VIABILITY_DECISION_KIND to ViabilityRefusal.entries.map { it.name }.toSet(),
        SELECTION_DECISION_KIND to SelectionRefusal.entries.map { it.name }.toSet(),
    )

/**
 * The governed identity as the evidence attribution map, using the key set
 * [RuntimeEvidenceAttribution] already defines and guards.
 */
internal object GovernedIdentityAttribution {
    fun of(identity: GovernedRunIdentity): Map<String, String> =
        mapOf(
            "identity.workloadId" to identity.deployment.workloadId.value,
            "identity.configurationId" to identity.deployment.configuration.id.value,
            "identity.configurationVersion" to identity.deployment.configuration.version.value,
            "identity.environmentId" to identity.deployment.environmentId.value,
            "identity.deploymentId" to identity.deployment.deploymentId.value,
        )
}
