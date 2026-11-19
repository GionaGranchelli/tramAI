package dev.tramai.engine.provider

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.ProviderInputProjectionResult
import dev.tramai.security.governance.ProviderInputProjector
import dev.tramai.security.governance.ProviderInputRelease
import dev.tramai.security.governance.ProviderInputReleaseDecision

/**
 * Typed, content-free refusal thrown when pre-transport provider-input release
 * or projection fails closed.
 */
internal class ProviderInputReleaseException(
    message: String,
) : RuntimeException(message)

/**
 * Engine-owned pre-transport gate that evaluates the P0-A1 release decision and
 * invokes the P0-A2 projector for the exact selected [ProviderDeployment].
 */
internal class ProviderInputTransportGate(
    private val configuration: ProviderGovernanceConfiguration,
) {
    private val release =
        ProviderInputRelease(
            trustZonePolicy = configuration.trustZonePolicy,
            rules = configuration.rules,
            minimizationPolicy = configuration.minimizationPolicy,
        )
    private val projector = ProviderInputProjector(configuration.minimizers)

    fun authorizeAndProject(
        finalRequest: ModelRequest,
        selectedDeployment: ProviderDeployment,
        effectiveClassification: DataClassification,
        workloadZone: ProviderTrustZone,
    ): ModelRequest {
        val decision =
            release.decide(
                workloadZone = workloadZone,
                classification = effectiveClassification,
                deployment = selectedDeployment,
                expectedDeploymentId = selectedDeployment.deploymentId,
            )
        if (decision is ProviderInputReleaseDecision.Deny) {
            refuse(
                "Provider-input release denied for deployment " +
                    "'${selectedDeployment.deploymentId}': ${decision.refusal.name}",
            )
        }
        return when (val projection = projector.project(finalRequest, selectedDeployment, decision)) {
            is ProviderInputProjectionResult.Projected -> {
                if (projection.deploymentId != selectedDeployment.deploymentId) {
                    refuse(
                        "Provider-input projection deployment mismatch for " +
                            "'${selectedDeployment.deploymentId}'",
                    )
                }
                projection.providerBoundRequest
            }

            is ProviderInputProjectionResult.Refused -> {
                refuse(
                    "Provider-input projection refused for deployment " +
                        "'${selectedDeployment.deploymentId}': ${projection.reason.name}",
                )
            }
        }
    }

    private fun refuse(message: String): Nothing = throw ProviderInputReleaseException(message)
}

internal class GovernedReleaseContext(
    val workloadZone: ProviderTrustZone,
    val effectiveClassification: DataClassification,
    private val transportGate: ProviderInputTransportGate,
) {
    fun enforce(
        finalRequest: ModelRequest,
        selectedDeployment: ProviderDeployment,
    ): ModelRequest =
        transportGate.authorizeAndProject(
            finalRequest = finalRequest,
            selectedDeployment = selectedDeployment,
            effectiveClassification = effectiveClassification,
            workloadZone = workloadZone,
        )
}
