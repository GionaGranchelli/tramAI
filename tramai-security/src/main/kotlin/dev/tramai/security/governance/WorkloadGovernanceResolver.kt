package dev.tramai.security.governance

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.authorityRank
import dev.tramai.core.policy.rank
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone

/**
 * One authoritative classification claim about a workload, paired with the
 * source that produced it.
 *
 * A workload may carry several claims at once (for example a caller-declared
 * classification and a rule-based one). Resolution keeps the strongest
 * classification, so a weaker claim can never downgrade a stronger one.
 */
data class WorkloadClassificationSignal(
    val classification: DataClassification,
    val source: ClassificationSource,
)

/**
 * Stable, safe reasons why a governed workload could not be resolved.
 *
 * Carries no raw values and no free-form text, so a refusal can be surfaced in
 * evidence without leaking governed data.
 */
enum class WorkloadGovernanceFailure {
    /** The workload supplied no classification claim at all. */
    NOT_CLASSIFIED,

    /**
     * The classification has no routing rule, or its rule permits no trust zone.
     * Trust cannot be inferred from absence, so this refuses rather than falling
     * back to a wider zone.
     */
    NO_RESOLVABLE_TRUST_ZONE,

    /**
     * The workload's deployment zone is not permitted for its classification.
     * Never narrowed or widened silently: an incompatible pair is refused.
     */
    DEPLOYMENT_ZONE_NOT_PERMITTED,
}

/**
 * The outcome of resolving an authoritative workload to one classification and
 * one trust zone. Both cases name the same [WorkloadDeploymentIdentity], so a
 * refusal is attributable to exactly one governed workload.
 */
sealed interface WorkloadGovernanceResolution {
    data class Resolved(
        val identity: WorkloadDeploymentIdentity,
        val classification: DataClassification,
        val source: ClassificationSource,
        val trustZone: ProviderTrustZone,
    ) : WorkloadGovernanceResolution

    data class Refused(
        val identity: WorkloadDeploymentIdentity,
        val failure: WorkloadGovernanceFailure,
    ) : WorkloadGovernanceResolution
}

/**
 * Resolves an authoritative workload to one deterministic classification and
 * one deterministic trust zone, for downstream policy to consume without
 * recomputing either.
 *
 * The resolution is pure and side-effect free: it reads its arguments, touches
 * no provider, tool, network or filesystem, and returns a value. The same
 * identity, signals, deployment zone and rules always produce the same outcome.
 *
 * Fails closed. A missing claim, a classification with no permitted zone, or a
 * deployment zone outside the permitted set for that classification all refuse;
 * no path defaults to a permissive or wider trust zone.
 *
 * Classification and trust-zone *vocabulary* are reused as-is
 * ([DataClassification], [ClassificationSource], [ProviderTrustZone],
 * [ClassificationRoutingRule]).
 */
object WorkloadGovernanceResolver {
    /**
     * @param signals authoritative classification claims about the workload.
     * @param deploymentZone the trust zone the workload actually runs in, or
     *   `null` when it cannot be established — which refuses.
     * @param rules permitted zones per classification; a classification absent
     *   from this map is unpermitted, never unconstrained.
     */
    fun resolve(
        identity: WorkloadDeploymentIdentity,
        signals: List<WorkloadClassificationSignal>,
        deploymentZone: ProviderTrustZone?,
        rules: Map<DataClassification, ClassificationRoutingRule>,
    ): WorkloadGovernanceResolution {
        if (signals.isEmpty()) {
            return WorkloadGovernanceResolution.Refused(identity, WorkloadGovernanceFailure.NOT_CLASSIFIED)
        }
        return resolveFor(identity, strongest(signals), deploymentZone, rules)
    }

    /**
     * The strongest claim, and among equally strong claims the LEAST
     * authoritative source, so recorded provenance is never more confident than
     * the weakest evidence supporting that classification.
     */
    private fun strongest(signals: List<WorkloadClassificationSignal>): WorkloadClassificationSignal {
        val strongestRank = signals.maxOf { it.classification.rank }
        return signals
            .filter { it.classification.rank == strongestRank }
            .minByOrNull { it.source.authorityRank }
            ?: error("unreachable: signals is non-empty")
    }

    private fun resolveFor(
        identity: WorkloadDeploymentIdentity,
        signal: WorkloadClassificationSignal,
        deploymentZone: ProviderTrustZone?,
        rules: Map<DataClassification, ClassificationRoutingRule>,
    ): WorkloadGovernanceResolution {
        val permitted = rules[signal.classification]?.allowedZones.orEmpty()
        val zone = deploymentZone?.takeIf { it in permitted }
        return when {
            permitted.isEmpty() -> {
                WorkloadGovernanceResolution.Refused(identity, WorkloadGovernanceFailure.NO_RESOLVABLE_TRUST_ZONE)
            }

            zone == null -> {
                WorkloadGovernanceResolution.Refused(identity, WorkloadGovernanceFailure.DEPLOYMENT_ZONE_NOT_PERMITTED)
            }

            else -> {
                WorkloadGovernanceResolution.Resolved(
                    identity = identity,
                    classification = signal.classification,
                    source = signal.source,
                    trustZone = zone,
                )
            }
        }
    }
}
