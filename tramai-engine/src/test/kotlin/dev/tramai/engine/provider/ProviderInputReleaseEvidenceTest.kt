package dev.tramai.engine.provider

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
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
import dev.tramai.security.governance.ProviderMinimizationPolicy
import dev.tramai.security.governance.TrustZoneName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderInputReleaseEvidenceTest {
    private val workloadId =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId("wl-evidence"),
            environmentId =
                dev.tramai.core.identity
                    .EnvironmentId("prod"),
            deploymentId =
                dev.tramai.core.identity
                    .DeploymentId("dep-1"),
            configuration =
                dev.tramai.core.identity.WorkloadConfigurationIdentity(
                    dev.tramai.core.identity
                        .ConfigurationId("cfg-1"),
                    dev.tramai.core.identity
                        .ConfigurationVersion("1"),
                ),
        )
    private val run = GovernedRunIdentity(workloadId, RunId("run-evidence-1"))
    private val primaryDeployment =
        ProviderDeployment("eu-prod", "openai", NamedTrustZone(TrustZoneName("eu"), ProviderTrustZone.EU_CLOUD))
    private val fallbackDeployment =
        ProviderDeployment("eu-fb", "anthropic", NamedTrustZone(TrustZoneName("eu-fb"), ProviderTrustZone.EU_CLOUD))

    private val minimizer =
        ObligationMinimizer { text ->
            val replaced = text.replace("sk-secret-12345", "[REDACTED]")
            MinimizationOutcome(replaced, if (replaced != text) 1 else 0, inspectionCompleted = true)
        }

    private fun configWith(policy: ProviderMinimizationPolicy): ProviderGovernanceConfiguration =
        ProviderGovernanceConfiguration.from(
            ProviderRoutingConfiguration(
                enabled = true,
                workloadZones = mapOf(workloadId to ProviderTrustZone.LOCAL),
                providerDeployments = mapOf("openai" to primaryDeployment, "anthropic" to fallbackDeployment),
                allowedZonePairs = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
                rules =
                    mapOf(
                        DataClassification.INTERNAL to
                            ClassificationRoutingRule(
                                allowedZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
                                allowedFallbackZones = setOf(ProviderTrustZone.EU_CLOUD),
                            ),
                    ),
            ).withMinimization(
                minimizationPolicy = policy,
                minimizers = mapOf(MinimizationObligation.REDACT_SECRETS to minimizer),
            ),
        )!!

    @Test
    fun `permitted raw release records RAW_PERMITTED evidence with zero redactions`() {
        val recorded = mutableListOf<ProviderInputReleaseEvidence>()
        val gate =
            ProviderInputTransportGate(
                configWith(ProviderMinimizationPolicy { _, _ -> MinimizationRequirement.RawPermitted }),
                ProviderInputReleaseEvidenceRecorder { recorded.add(it) },
            )

        gate.authorizeAndProject(
            finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("hello"))),
            selectedDeployment = primaryDeployment,
            effectiveClassification = DataClassification.INTERNAL,
            workloadZone = ProviderTrustZone.LOCAL,
            correlation = ProviderInputReleaseCorrelation(run = run, attempt = 1),
        )

        val ev = recorded.single()
        assertEquals("wl-evidence", ev.workloadId)
        assertEquals("run-evidence-1", ev.runId)
        assertEquals("eu-prod", ev.selectedDeploymentId)
        assertEquals(ProviderInputReleaseOutcome.RAW_PERMITTED, ev.outcome)
        assertNull(ev.reasonCode)
        assertEquals(0, ev.redactionCount)
        assertEquals(1, ev.attempt)
        assertEquals(ProviderInputReleaseProvenance.TRAMAI_ENFORCED, ev.provenance)
    }

    @Test
    fun `required minimization records MINIMIZED evidence without secret canary`() {
        val recorded = mutableListOf<ProviderInputReleaseEvidence>()
        val gate =
            ProviderInputTransportGate(
                configWith(
                    ProviderMinimizationPolicy { _, _ ->
                        MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                    },
                ),
                ProviderInputReleaseEvidenceRecorder { recorded.add(it) },
            )

        gate.authorizeAndProject(
            finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("key sk-secret-12345"))),
            selectedDeployment = primaryDeployment,
            effectiveClassification = DataClassification.INTERNAL,
            workloadZone = ProviderTrustZone.LOCAL,
            correlation = ProviderInputReleaseCorrelation(run = run, attempt = 1),
        )

        val ev = recorded.single()
        assertEquals(ProviderInputReleaseOutcome.MINIMIZED, ev.outcome)
        assertEquals(1, ev.redactionCount)
        assertNull(ev.reasonCode)
        assertFalse(ev.toString().contains("sk-secret-12345"))
        assertFalse(ev.toString().contains("[REDACTED]"))
    }

    @Test
    fun `denied release records DENIED evidence with safe reason code`() {
        val recorded = mutableListOf<ProviderInputReleaseEvidence>()
        val gate =
            ProviderInputTransportGate(
                configWith(ProviderMinimizationPolicy { _, _ -> MinimizationRequirement.MissingOrDenied }),
                ProviderInputReleaseEvidenceRecorder { recorded.add(it) },
            )

        assertThrows<ProviderInputReleaseException> {
            gate.authorizeAndProject(
                finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("key sk-secret-12345"))),
                selectedDeployment = primaryDeployment,
                effectiveClassification = DataClassification.INTERNAL,
                workloadZone = ProviderTrustZone.LOCAL,
                correlation = ProviderInputReleaseCorrelation(run = run, attempt = 1),
            )
        }

        val ev = recorded.single()
        assertEquals(ProviderInputReleaseOutcome.DENIED, ev.outcome)
        assertEquals("MISSING_RELEASE_POLICY", ev.reasonCode)
        assertNull(ev.redactionCount)
        assertFalse(ev.toString().contains("sk-secret-12345"))
    }

    @Test
    fun `retry and fallback record separate evidence per evaluated attempt and deployment`() {
        val recorded = mutableListOf<ProviderInputReleaseEvidence>()
        val gate =
            ProviderInputTransportGate(
                configWith(
                    ProviderMinimizationPolicy { _, _ ->
                        MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                    },
                ),
                ProviderInputReleaseEvidenceRecorder { recorded.add(it) },
            )

        gate.authorizeAndProject(
            finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("sk-secret-12345"))),
            selectedDeployment = primaryDeployment,
            effectiveClassification = DataClassification.INTERNAL,
            workloadZone = ProviderTrustZone.LOCAL,
            correlation = ProviderInputReleaseCorrelation(run = run, attempt = 1),
        )
        gate.authorizeAndProject(
            finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("sk-secret-12345"))),
            selectedDeployment = fallbackDeployment,
            effectiveClassification = DataClassification.INTERNAL,
            workloadZone = ProviderTrustZone.LOCAL,
            correlation = ProviderInputReleaseCorrelation(run = run, attempt = 2),
        )

        assertEquals(2, recorded.size)
        assertEquals(listOf("eu-prod", "eu-fb"), recorded.map { it.selectedDeploymentId })
        assertEquals(listOf(1, 2), recorded.map { it.attempt })
    }

    @Test
    fun `evidence recorder failure propagates and blocks transport`() {
        val gate =
            ProviderInputTransportGate(
                configWith(ProviderMinimizationPolicy { _, _ -> MinimizationRequirement.RawPermitted }),
                ProviderInputReleaseEvidenceRecorder { error("evidence sink down") },
            )

        assertThrows<IllegalStateException> {
            gate.authorizeAndProject(
                finalRequest = ModelRequest(model = "m", messages = listOf(Message.text("hello"))),
                selectedDeployment = primaryDeployment,
                effectiveClassification = DataClassification.INTERNAL,
                workloadZone = ProviderTrustZone.LOCAL,
                correlation = ProviderInputReleaseCorrelation(run = run, attempt = 1),
            )
        }
    }
}
