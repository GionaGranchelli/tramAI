package dev.tramai.security.governance

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.2 classification → trust-zone resolution boundary.
 *
 * Each test audits one property: determinism, explicit failure when resolution
 * cannot complete, and that no input or absent configuration widens trust.
 */
class WorkloadGovernanceResolverTest {
    private val identity =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId("claims-triage"),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId("claims-triage-v1"),
                    version = ConfigurationVersion("3"),
                ),
            environmentId = EnvironmentId("production"),
            deploymentId = DeploymentId("eu-west-amsterdam-01"),
        )

    private val sovereign = ProviderRoutingConfiguration.sovereignDefaults()

    private fun signal(
        classification: DataClassification,
        source: ClassificationSource = ClassificationSource.DECLARED,
    ) = WorkloadClassificationSignal(classification, source)

    private fun resolve(
        signals: List<WorkloadClassificationSignal>,
        deploymentZone: ProviderTrustZone?,
        rules: Map<DataClassification, ClassificationRoutingRule> = sovereign,
    ) = WorkloadGovernanceResolver.resolve(identity, signals, deploymentZone, rules)

    private fun resolved(resolution: WorkloadGovernanceResolution) =
        resolution as? WorkloadGovernanceResolution.Resolved
            ?: error("expected Resolved, got $resolution")

    private fun refused(resolution: WorkloadGovernanceResolution) =
        resolution as? WorkloadGovernanceResolution.Refused
            ?: error("expected Refused, got $resolution")

    @Test
    fun `an authoritative classified workload resolves to one classification and one zone`() {
        val result = resolved(resolve(listOf(signal(DataClassification.CONFIDENTIAL)), ProviderTrustZone.LOCAL))

        assertEquals(DataClassification.CONFIDENTIAL, result.classification)
        assertEquals(ClassificationSource.DECLARED, result.source)
        assertEquals(ProviderTrustZone.LOCAL, result.trustZone)
        assertEquals(identity, result.identity)
    }

    @Test
    fun `resolution is deterministic across repeated and reordered inputs`() {
        val forward =
            resolve(
                listOf(
                    signal(DataClassification.INTERNAL, ClassificationSource.RULE_BASED),
                    signal(DataClassification.RESTRICTED, ClassificationSource.DECLARED),
                ),
                ProviderTrustZone.LOCAL,
            )
        val reversed =
            resolve(
                listOf(
                    signal(DataClassification.RESTRICTED, ClassificationSource.DECLARED),
                    signal(DataClassification.INTERNAL, ClassificationSource.RULE_BASED),
                ),
                ProviderTrustZone.LOCAL,
            )

        assertEquals(forward, reversed)
        assertEquals(
            forward,
            resolve(
                listOf(
                    signal(DataClassification.INTERNAL, ClassificationSource.RULE_BASED),
                    signal(DataClassification.RESTRICTED, ClassificationSource.DECLARED),
                ),
                ProviderTrustZone.LOCAL,
            ),
        )
    }

    @Test
    fun `a weaker signal can never downgrade an explicit stronger classification`() {
        val result =
            resolved(
                resolve(
                    listOf(
                        signal(DataClassification.PUBLIC, ClassificationSource.RULE_BASED),
                        signal(DataClassification.RESTRICTED, ClassificationSource.DECLARED),
                    ),
                    ProviderTrustZone.LOCAL,
                ),
            )

        assertEquals(DataClassification.RESTRICTED, result.classification)
    }

    @Test
    fun `equally strong claims keep the least authoritative source`() {
        val result =
            resolved(
                resolve(
                    listOf(
                        signal(DataClassification.CONFIDENTIAL, ClassificationSource.DECLARED),
                        signal(DataClassification.CONFIDENTIAL, ClassificationSource.LOCAL_MODEL_ASSISTED),
                    ),
                    ProviderTrustZone.LOCAL,
                ),
            )

        assertEquals(DataClassification.CONFIDENTIAL, result.classification)
        assertEquals(ClassificationSource.LOCAL_MODEL_ASSISTED, result.source)
    }

    @Test
    fun `an unclassified workload is refused`() {
        val outcome = refused(resolve(emptyList(), ProviderTrustZone.LOCAL))

        assertEquals(WorkloadGovernanceFailure.NOT_CLASSIFIED, outcome.failure)
        assertEquals(identity, outcome.identity)
    }

    @Test
    fun `an unclassified workload is refused even when a trust zone is supplied`() {
        val outcome = refused(resolve(emptyList(), ProviderTrustZone.LOCAL, sovereign))

        assertEquals(WorkloadGovernanceFailure.NOT_CLASSIFIED, outcome.failure)
    }

    @Test
    fun `a classification with no routing rule is refused rather than defaulted`() {
        val outcome =
            refused(
                resolve(listOf(signal(DataClassification.CONFIDENTIAL)), ProviderTrustZone.LOCAL, emptyMap()),
            )

        assertEquals(WorkloadGovernanceFailure.NO_RESOLVABLE_TRUST_ZONE, outcome.failure)
    }

    @Test
    fun `a classification whose rule permits no zone is refused`() {
        val rules =
            mapOf(
                DataClassification.CONFIDENTIAL to
                    ClassificationRoutingRule(allowedZones = emptySet(), allowedFallbackZones = emptySet()),
            )

        val outcome = refused(resolve(listOf(signal(DataClassification.CONFIDENTIAL)), ProviderTrustZone.LOCAL, rules))

        assertEquals(WorkloadGovernanceFailure.NO_RESOLVABLE_TRUST_ZONE, outcome.failure)
    }

    @Test
    fun `an unresolvable deployment zone is refused rather than assumed`() {
        val outcome = refused(resolve(listOf(signal(DataClassification.PUBLIC)), null))

        assertEquals(WorkloadGovernanceFailure.DEPLOYMENT_ZONE_NOT_PERMITTED, outcome.failure)
    }

    @Test
    fun `restricted data deployed in a global cloud zone is refused and never widened`() {
        val outcome = refused(resolve(listOf(signal(DataClassification.RESTRICTED)), ProviderTrustZone.GLOBAL_CLOUD))

        assertEquals(WorkloadGovernanceFailure.DEPLOYMENT_ZONE_NOT_PERMITTED, outcome.failure)
    }

    @Test
    fun `restricted data deployed in an eu cloud zone is refused`() {
        val outcome = refused(resolve(listOf(signal(DataClassification.RESTRICTED)), ProviderTrustZone.EU_CLOUD))

        assertEquals(WorkloadGovernanceFailure.DEPLOYMENT_ZONE_NOT_PERMITTED, outcome.failure)
    }

    @Test
    fun `confidential data in an eu cloud zone resolves`() {
        val result = resolved(resolve(listOf(signal(DataClassification.CONFIDENTIAL)), ProviderTrustZone.EU_CLOUD))

        assertEquals(ProviderTrustZone.EU_CLOUD, result.trustZone)
    }

    @Test
    fun `public data in a global cloud zone resolves`() {
        val result = resolved(resolve(listOf(signal(DataClassification.PUBLIC)), ProviderTrustZone.GLOBAL_CLOUD))

        assertEquals(ProviderTrustZone.GLOBAL_CLOUD, result.trustZone)
        assertTrue(result.classification == DataClassification.PUBLIC)
    }

    @Test
    fun `a fallback-permitted zone does not widen the primary decision`() {
        val rules =
            mapOf(
                DataClassification.RESTRICTED to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.LOCAL),
                        allowedFallbackZones = setOf(ProviderTrustZone.LOCAL),
                    ),
            )

        val outcome =
            refused(
                resolve(listOf(signal(DataClassification.RESTRICTED)), ProviderTrustZone.GLOBAL_CLOUD, rules),
            )

        assertEquals(WorkloadGovernanceFailure.DEPLOYMENT_ZONE_NOT_PERMITTED, outcome.failure)
    }

    @Test
    fun `every classification resolves deterministically to its strongest claim`() {
        val expected =
            mapOf(
                DataClassification.PUBLIC to DataClassification.PUBLIC,
                DataClassification.INTERNAL to DataClassification.INTERNAL,
                DataClassification.CONFIDENTIAL to DataClassification.CONFIDENTIAL,
                DataClassification.RESTRICTED to DataClassification.RESTRICTED,
            )

        expected.forEach { (declared, strongest) ->
            val signals =
                listOf(
                    signal(DataClassification.PUBLIC, ClassificationSource.LOCAL_MODEL_ASSISTED),
                    signal(declared, ClassificationSource.DECLARED),
                )
            val result = resolved(resolve(signals, ProviderTrustZone.LOCAL))

            assertEquals(strongest, result.classification, "strongest claim must win for $declared")
        }
    }
}
