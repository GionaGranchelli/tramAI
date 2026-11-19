package dev.tramai.engine.provider

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.MinimizationObligation
import dev.tramai.security.governance.MinimizationOutcome
import dev.tramai.security.governance.MinimizationRequirement
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ObligationMinimizer
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderInputTransportGateTest {
    private val workloadId =
        WorkloadDeploymentIdentity(
            WorkloadId("wl-1"),
            WorkloadConfigurationIdentity(ConfigurationId("cfg-1"), ConfigurationVersion("v1")),
            EnvironmentId("prod"),
            DeploymentId("dep-1"),
        )
    private val deployment =
        ProviderDeployment("eu-prod", "openai", NamedTrustZone(TrustZoneName("eu"), ProviderTrustZone.EU_CLOUD))

    private val minimizer =
        ObligationMinimizer { text ->
            val replaced = text.replace("sk-secret-12345", "[REDACTED]")
            MinimizationOutcome(replaced, if (replaced != text) 1 else 0, inspectionCompleted = true)
        }

    private fun baseRouting(): ProviderRoutingConfiguration =
        ProviderRoutingConfiguration(
            enabled = true,
            workloadZones = mapOf(workloadId to ProviderTrustZone.LOCAL),
            providerDeployments = mapOf("openai" to deployment),
            allowedZonePairs = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
            rules =
                mapOf(
                    DataClassification.INTERNAL to
                        ClassificationRoutingRule(
                            allowedZones = setOf(ProviderTrustZone.EU_CLOUD),
                            allowedFallbackZones = emptySet(),
                        ),
                ),
        )

    @Test
    fun `production configuration without minimization policy fails closed`() {
        val config = ProviderGovernanceConfiguration.from(baseRouting())!!
        val gate = ProviderInputTransportGate(config)
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("sk-secret-12345")))

        assertThrows<ProviderInputReleaseException> {
            gate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        }
    }

    @Test
    fun `production configuration withMinimization projects secret and copy reverts to default-deny`() {
        val configured =
            baseRouting().withMinimization(
                minimizationPolicy = { _, _ ->
                    MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                },
                minimizers = mapOf(MinimizationObligation.REDACT_SECRETS to minimizer),
            )
        val gate = ProviderInputTransportGate(ProviderGovernanceConfiguration.from(configured)!!)
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("leaked sk-secret-12345")))

        val projected =
            gate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        assertEquals("leaked [REDACTED]", projected.messages.single().content)
        assertFalse(projected.toString().contains("sk-secret-12345"))

        val copied = configured.copy(enabled = true)
        val copiedGate = ProviderInputTransportGate(ProviderGovernanceConfiguration.from(copied)!!)
        assertThrows<ProviderInputReleaseException> {
            copiedGate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        }
    }
}
