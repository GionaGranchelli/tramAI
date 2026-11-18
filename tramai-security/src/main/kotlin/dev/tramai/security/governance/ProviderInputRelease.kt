package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone

/**
 * Whether this specific provider-bound input may be released to a deployment.
 *
 * A compatible trust zone is necessary and never sufficient. Release requires
 * BOTH existing authorities to agree, for the same input:
 *
 * ```text
 * workload zone -> provider deployment zone     TrustZonePolicy
 * classification -> provider deployment zone    ClassificationRoutingRule.allowedZones
 *                          |
 *                          v
 *                   released only if both agree
 * ```
 *
 * Anything else withholds, and no claim can widen the other: a compatible zone
 * pair cannot release a classification that has no release rule, and a
 * classification that permits a zone cannot release across incompatible zones.
 *
 * The classification side reuses [ClassificationRoutingRule.allowedZones] — the
 * existing rule for which provider trust zones may handle a classification —
 * rather than restating it. [ClassificationRoutingRule.allowedFallbackZones] is
 * deliberately NOT consulted: a fallback concession is not a release permission
 * for a specific input.
 *
 * This is a decision only. It does not select a provider, invoke one, transform
 * the input, or minimize it, and it performs no I/O.
 */
class ProviderInputRelease(
    private val trustZonePolicy: TrustZonePolicy = TrustZonePolicy(),
    private val rules: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
) {
    private var minimizationPolicy: ProviderMinimizationPolicy =
        ProviderMinimizationPolicy { _, _ -> MinimizationRequirement.MissingOrDenied }

    constructor(
        trustZonePolicy: TrustZonePolicy,
        rules: Map<DataClassification, ClassificationRoutingRule>,
        minimizationPolicy: ProviderMinimizationPolicy,
    ) : this(trustZonePolicy, rules) {
        this.minimizationPolicy = minimizationPolicy
    }
    /**
     * True only when the zones are compatible AND the classification's rule
     * permits that zone. A classification with no rule releases nothing.
     *
     * Defined as "no refusal", so this view and [refusalFor] cannot disagree.
     */
    fun releases(
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
        providerZone: ProviderTrustZone,
    ): Boolean = refusalFor(workloadZone, classification, providerZone) == null

    /**
     * Why this input is withheld, or `null` when it is released.
     *
     * A classification with no rule and a rule that omits the zone are
     * deliberately the same refusal. The configured rule matrix does not record
     * which of the two an operator intended, so reporting a distinction here
     * would be a claim the facts do not support.
     */
    fun refusalFor(
        workloadZone: ProviderTrustZone,
        classification: DataClassification,
        providerZone: ProviderTrustZone,
    ): ReleaseRefusal? =
        when {
            !trustZonePolicy.allows(workloadZone, providerZone) -> {
                ReleaseRefusal.ZONE_PAIR_NOT_ALLOWED
            }

            rules[classification]?.allowedZones?.contains(providerZone) != true -> {
                ReleaseRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED
            }

            else -> {
                null
            }
        }

    /**
     * Evaluates the explicit data-release decision for one selected [deployment]
     * against [expectedDeploymentId] and the authoritative [minimizationPolicy].
     *
     * Fails closed on missing or inconsistent facts, and never defaults missing
     * minimization policy to [ProviderInputReleaseDecision.AllowRaw].
     */
    fun decide(
        workloadZone: ProviderTrustZone?,
        classification: DataClassification?,
        deployment: ProviderDeployment?,
        expectedDeploymentId: String?,
    ): ProviderInputReleaseDecision {
        if (deployment == null || expectedDeploymentId.isNullOrBlank() || deployment.deploymentId != expectedDeploymentId) {
            return ProviderInputReleaseDecision.Deny(
                deploymentId = deployment?.deploymentId,
                refusal = ReleaseRefusal.INCONSISTENT_DEPLOYMENT_IDENTITY,
            )
        }
        if (workloadZone == null || classification == null) {
            return ProviderInputReleaseDecision.Deny(
                deploymentId = deployment.deploymentId,
                refusal = ReleaseRefusal.MISSING_RELEASE_POLICY,
            )
        }
        val baseRefusal = refusalFor(workloadZone, classification, deployment.trustZone.category)
        if (baseRefusal != null) {
            return ProviderInputReleaseDecision.Deny(
                deploymentId = deployment.deploymentId,
                refusal = baseRefusal,
            )
        }
        return when (val requirement = minimizationPolicy.resolve(classification, deployment)) {
            MinimizationRequirement.MissingOrDenied -> {
                ProviderInputReleaseDecision.Deny(
                    deploymentId = deployment.deploymentId,
                    refusal = ReleaseRefusal.MISSING_RELEASE_POLICY,
                )
            }

            MinimizationRequirement.RawPermitted -> {
                ProviderInputReleaseDecision.AllowRaw(deploymentId = deployment.deploymentId)
            }

            is MinimizationRequirement.Required -> {
                ProviderInputReleaseDecision.AllowWithMinimization(
                    deploymentId = deployment.deploymentId,
                    obligations = requirement.obligations,
                )
            }
        }
    }
}

/**
 * Authoritative policy source for provider-input minimization obligations.
 *
 * Integration obligation (P0-A3): callers must derive this policy from TramAI's
 * trusted governance configuration and bind `expectedDeploymentId` from the
 * selected route, never from caller-supplied input.
 */
fun interface ProviderMinimizationPolicy {
    fun resolve(
        classification: DataClassification,
        deployment: ProviderDeployment,
    ): MinimizationRequirement
}

/**
 * Authoritative minimization requirement for a (classification, deployment) pair.
 */
sealed interface MinimizationRequirement {
    /** Explicit policy permitting raw input without minimization. */
    data object RawPermitted : MinimizationRequirement

    /** Explicit policy requiring all [obligations] to be satisfied before release. */
    class Required(
        obligations: Set<MinimizationObligation>,
    ) : MinimizationRequirement {
        val obligations: Set<MinimizationObligation> =
            java.util.Collections.unmodifiableSet(obligations.toSet())

        init {
            require(this.obligations.isNotEmpty()) {
                "MinimizationRequirement.Required must declare at least one MinimizationObligation"
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is Required && obligations == other.obligations)

        override fun hashCode(): Int = obligations.hashCode()

        override fun toString(): String = "Required(obligations=$obligations)"
    }

    /** Missing, conflicting, or insufficient release policy (fails closed). */
    data object MissingOrDenied : MinimizationRequirement
}

/**
 * Stable, non-sensitive minimization obligation identifier.
 */
enum class MinimizationObligation {
    REDACT_PII,
    REDACT_SECRETS,
    MINIMIZE_TO_POLICY,
}

/**
 * Explicit data-release decision for one selected provider deployment.
 */
sealed interface ProviderInputReleaseDecision {
    val deploymentId: String?

    /** Input may cross to [deploymentId] without minimization. */
    data class AllowRaw(
        override val deploymentId: String,
    ) : ProviderInputReleaseDecision

    /** Input may cross to [deploymentId] only after all [obligations] are satisfied. */
    class AllowWithMinimization(
        override val deploymentId: String,
        obligations: Set<MinimizationObligation>,
    ) : ProviderInputReleaseDecision {
        val obligations: Set<MinimizationObligation> =
            java.util.Collections.unmodifiableSet(obligations.toSet())

        init {
            require(this.obligations.isNotEmpty()) {
                "AllowWithMinimization requires at least one MinimizationObligation"
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is AllowWithMinimization && deploymentId == other.deploymentId && obligations == other.obligations)

        override fun hashCode(): Int = 31 * deploymentId.hashCode() + obligations.hashCode()

        override fun toString(): String = "AllowWithMinimization(deploymentId=$deploymentId, obligations=$obligations)"
    }

    /** Input is withheld from [deploymentId] for [refusal]. */
    data class Deny(
        override val deploymentId: String?,
        val refusal: ReleaseRefusal,
    ) : ProviderInputReleaseDecision
}

/**
 * Stable reason why a provider-bound input was withheld from a deployment.
 */
enum class ReleaseRefusal {
    /** The workload-to-deployment zone pair is not an explicitly listed pair. */
    ZONE_PAIR_NOT_ALLOWED,

    /** The classification's routing rule does not permit the deployment's zone. */
    CLASSIFICATION_ZONE_NOT_PERMITTED,

    /** Required release policy or minimization facts are missing or incomplete. */
    MISSING_RELEASE_POLICY,

    /** Deployment identity is missing or does not match the selected deployment. */
    INCONSISTENT_DEPLOYMENT_IDENTITY,
}
