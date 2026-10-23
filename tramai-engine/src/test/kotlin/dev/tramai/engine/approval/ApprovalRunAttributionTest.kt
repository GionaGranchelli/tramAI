package dev.tramai.engine.approval

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Attribution codec for governed approvals (0.7.1d1).
 *
 * The invariants under test are the ones the ownership model depends on: the run id is never stored
 * twice, a partial reserved set is corruption rather than legacy, and application metadata can never
 * determine (or silently corrupt) framework attribution.
 */
class ApprovalRunAttributionTest {
    @Test
    fun `an encoded identity decodes back to the same identity with the run id from the binding`() {
        val identity = governedIdentity(runId = "run-0.7.1d1")

        val decoded = decodeApprovalAttribution(identity.runId.value, encodeApprovalAttribution(identity))

        assertThat(decoded).isEqualTo(ApprovalRunAttribution.Governed(identity))
    }

    @Test
    fun `encoding stores exactly the five components and never a run id`() {
        val encoded = encodeApprovalAttribution(governedIdentity(runId = "run-0.7.1d1"))

        assertThat(encoded.keys).containsExactlyInAnyOrderElementsOf(ApprovalAttributionKeys.ALL)
        assertThat(encoded).doesNotContainValue("run-0.7.1d1")
    }

    @Test
    fun `the run id is read from the binding, not from the encoded identity`() {
        val identity = governedIdentity(runId = "run-0.7.1d1")

        // The binding is authoritative for the run id; the persisted components cannot override it.
        val decoded = decodeApprovalAttribution("run-binding", encodeApprovalAttribution(identity))

        assertThat((decoded as ApprovalRunAttribution.Governed).identity.runId).isEqualTo(RunId("run-binding"))
    }

    @Test
    fun `an approval with no reserved keys is legacy and stays un-attributed`() {
        assertThat(decodeApprovalAttribution("run-legacy", mapOf("requestedBy" to "operator"))).isEqualTo(
            ApprovalRunAttribution.Ungoverned,
        )
    }

    @Test
    fun `every partial reserved set is corruption rather than a partial identity`() {
        val encoded = encodeApprovalAttribution(governedIdentity())

        for (missing in ApprovalAttributionKeys.ALL) {
            val partial = encoded - missing
            val failure =
                assertFailsWith<ApprovalAttributionCorruptionException> {
                    decodeApprovalAttribution("run-partial", partial)
                }
            assertThat(failure.message.orEmpty()).contains("incomplete", "run-partial")
        }
    }

    @Test
    fun `a blank component is corruption, not absence`() {
        val encoded = encodeApprovalAttribution(governedIdentity()).toMutableMap()
        encoded[ApprovalAttributionKeys.CONFIGURATION_VERSION] = "   "

        assertFailsWith<ApprovalAttributionCorruptionException> {
            decodeApprovalAttribution("run-blank", encoded)
        }
    }

    @Test
    fun `application metadata supplying a reserved key is rejected, never overwritten`() {
        val injected = governedIdentity(runId = "run-attacker").deployment
        val applicationMetadata =
            mapOf(
                "requestedBy" to "operator",
                ApprovalAttributionKeys.DEPLOYMENT to injected.deploymentId.value,
            )

        val failure =
            assertFailsWith<ApprovalAttributionCollisionException> {
                mergeApprovalAttribution(applicationMetadata, ApprovalRunAttribution.Governed(governedIdentity()))
            }

        // Rejection, not silent overwrite: the injected value must never reach persistence.
        assertThat(failure.message.orEmpty()).contains(ApprovalAttributionKeys.DEPLOYMENT)
        assertThat(applicationMetadata[ApprovalAttributionKeys.DEPLOYMENT]).isEqualTo(injected.deploymentId.value)
    }

    @Test
    fun `merging governed attribution preserves application metadata and adds the reserved set`() {
        val identity = governedIdentity()

        val merged =
            mergeApprovalAttribution(
                mapOf("requestedBy" to "operator", "decisionComment" to "reviewed"),
                ApprovalRunAttribution.Governed(identity),
            )

        assertThat(merged)
            .containsEntry("requestedBy", "operator")
            .containsEntry("decisionComment", "reviewed")
            .containsAllEntriesOf(encodeApprovalAttribution(identity))
        assertThat(merged.keys).hasSize(7)
    }

    @Test
    fun `merging un-attributed legacy approvals is byte-compatible`() {
        val applicationMetadata = mapOf("requestedBy" to "operator")

        assertThat(mergeApprovalAttribution(applicationMetadata, ApprovalRunAttribution.Ungoverned))
            .isEqualTo(applicationMetadata)
    }

    private fun governedIdentity(runId: String = "run-0.7.1d1"): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims-triage"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-triage-prod"),
                            version = ConfigurationVersion("v7"),
                        ),
                    environmentId = EnvironmentId("prod-eu"),
                    deploymentId = DeploymentId("deploy-42"),
                ),
            runId = RunId(runId),
        )
}
