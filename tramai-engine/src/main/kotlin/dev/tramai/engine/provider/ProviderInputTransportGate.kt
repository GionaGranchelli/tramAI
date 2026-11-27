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
    private val evidenceRecorder: ProviderInputReleaseEvidenceRecorder = configuration.releaseEvidenceRecorder,
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
        correlation: ProviderInputReleaseCorrelation? = null,
    ): ModelRequest {
        val decision =
            release.decide(
                workloadZone = workloadZone,
                classification = effectiveClassification,
                deployment = selectedDeployment,
                expectedDeploymentId = selectedDeployment.deploymentId,
            )
        if (decision is ProviderInputReleaseDecision.Deny) {
            recordDenied(correlation, selectedDeployment.deploymentId, decision.refusal.name)
            refuse(
                "Provider-input release denied for deployment " +
                    "'${selectedDeployment.deploymentId}': ${decision.refusal.name}",
            )
        }
        return when (val projection = projector.project(finalRequest, selectedDeployment, decision)) {
            is ProviderInputProjectionResult.Projected -> {
                if (projection.deploymentId != selectedDeployment.deploymentId) {
                    recordDenied(correlation, selectedDeployment.deploymentId, "DEPLOYMENT_MISMATCH")
                    refuse(
                        "Provider-input projection deployment mismatch for " +
                            "'${selectedDeployment.deploymentId}'",
                    )
                }
                val outcome =
                    if (decision is ProviderInputReleaseDecision.AllowWithMinimization) {
                        ProviderInputReleaseOutcome.MINIMIZED
                    } else {
                        ProviderInputReleaseOutcome.RAW_PERMITTED
                    }
                recordEvidence(
                    correlation = correlation,
                    deploymentId = selectedDeployment.deploymentId,
                    outcome = outcome,
                    redactionCount = projection.metadata.redactionCount,
                )
                projection.providerBoundRequest
            }

            is ProviderInputProjectionResult.Refused -> {
                recordDenied(correlation, selectedDeployment.deploymentId, projection.reason.name)
                refuse(
                    "Provider-input projection refused for deployment " +
                        "'${selectedDeployment.deploymentId}': ${projection.reason.name}",
                )
            }
        }
    }

    private fun recordDenied(
        correlation: ProviderInputReleaseCorrelation?,
        deploymentId: String,
        reasonCode: String,
    ) {
        evidenceRecorder.record(
            ProviderInputReleaseEvidence(
                workloadId =
                    correlation
                        ?.run
                        ?.deployment
                        ?.workloadId
                        ?.value,
                runId = correlation?.run?.runId?.value,
                selectedDeploymentId = deploymentId,
                outcome = ProviderInputReleaseOutcome.DENIED,
                reasonCode = reasonCode,
                redactionCount = null,
                attempt = correlation?.attempt,
            ),
        )
    }

    private fun recordEvidence(
        correlation: ProviderInputReleaseCorrelation?,
        deploymentId: String,
        outcome: ProviderInputReleaseOutcome,
        redactionCount: Int,
    ) {
        evidenceRecorder.record(
            ProviderInputReleaseEvidence(
                workloadId =
                    correlation
                        ?.run
                        ?.deployment
                        ?.workloadId
                        ?.value,
                runId = correlation?.run?.runId?.value,
                selectedDeploymentId = deploymentId,
                outcome = outcome,
                reasonCode = null,
                redactionCount = redactionCount,
                attempt = correlation?.attempt,
            ),
        )
    }

    private fun refuse(message: String): Nothing = throw ProviderInputReleaseException(message)
}

internal class GovernedReleaseContext(
    val workloadZone: ProviderTrustZone,
    val effectiveClassification: DataClassification,
    private val transportGate: ProviderInputTransportGate,
    private val run: dev.tramai.core.identity.GovernedRunIdentity? = null,
) {
    fun enforce(
        finalRequest: ModelRequest,
        selectedDeployment: ProviderDeployment,
        attempt: Int? = null,
    ): ModelRequest =
        transportGate.authorizeAndProject(
            finalRequest = finalRequest,
            selectedDeployment = selectedDeployment,
            effectiveClassification = effectiveClassification,
            workloadZone = workloadZone,
            correlation = ProviderInputReleaseCorrelation(run = run, attempt = attempt),
        )
}
