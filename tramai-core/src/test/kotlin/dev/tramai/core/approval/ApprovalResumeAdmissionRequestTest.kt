package dev.tramai.core.approval

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApprovalResumeAdmissionRequestTest {
    private val runIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("test-workload"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("test-config"),
                            version = ConfigurationVersion("1"),
                        ),
                    environmentId = EnvironmentId("test-env"),
                    deploymentId = DeploymentId("test-dep"),
                ),
            runId = RunId("run-123"),
        )
    private val binding = WorkflowCheckpointBinding("wf-test", "run-123")

    @Test
    fun `regression - synthetic four-argument copy resets continuationVersion to zero`() {
        // Characterization and regression test for Stable API binary compatibility preservation.
        // Because ApprovalResumeAdmissionRequest preserves its historical 4-arg primary constructor,
        // the Kotlin-generated copy() delegates to that constructor and discards body properties.
        val versioned =
            ApprovalResumeAdmissionRequest(
                approvalId = "appr-1",
                toolName = "send_email",
                runIdentity = runIdentity,
                checkpointBinding = binding,
                continuationVersion = 42L,
            )
        assertThat(versioned.continuationVersion).isEqualTo(42L)

        val copied = versioned.copy(toolName = "write_email")
        assertThat(copied.toolName).isEqualTo("write_email")
        // Discarded by synthetic data-class copy() — documented limitation
        assertThat(copied.continuationVersion).isEqualTo(0L)
    }

    @Test
    fun `copyWithContinuationVersion preserves version provenance by default`() {
        val versioned =
            ApprovalResumeAdmissionRequest(
                approvalId = "appr-1",
                toolName = "send_email",
                runIdentity = runIdentity,
                checkpointBinding = binding,
                continuationVersion = 42L,
            )

        val copied = versioned.copyWithContinuationVersion(toolName = "write_email")
        assertThat(copied.approvalId).isEqualTo("appr-1")
        assertThat(copied.toolName).isEqualTo("write_email")
        assertThat(copied.runIdentity).isEqualTo(runIdentity)
        assertThat(copied.checkpointBinding).isEqualTo(binding)
        assertThat(copied.continuationVersion).isEqualTo(42L)
    }

    @Test
    fun `five-argument copy overload explicitly sets continuationVersion`() {
        val versioned =
            ApprovalResumeAdmissionRequest(
                approvalId = "appr-1",
                toolName = "send_email",
                runIdentity = runIdentity,
                checkpointBinding = binding,
                continuationVersion = 42L,
            )

        val updated = versioned.copy(continuationVersion = 99L)
        assertThat(updated.continuationVersion).isEqualTo(99L)
    }

    @Test
    fun `withContinuationVersion derives versioned request from unversioned`() {
        val unversioned =
            ApprovalResumeAdmissionRequest(
                approvalId = "appr-1",
                toolName = "send_email",
                runIdentity = runIdentity,
                checkpointBinding = binding,
            )
        assertThat(unversioned.continuationVersion).isEqualTo(0L)

        val versioned = unversioned.withContinuationVersion(7L)
        assertThat(versioned.continuationVersion).isEqualTo(7L)
        assertThat(versioned.approvalId).isEqualTo("appr-1")
    }

    @Test
    fun `equals, hashCode, and toString take continuationVersion into account`() {
        val req1 = ApprovalResumeAdmissionRequest("appr-1", "tool", runIdentity, binding, 1L)
        val req2 = ApprovalResumeAdmissionRequest("appr-1", "tool", runIdentity, binding, 2L)
        val req1Clone = req1.copyWithContinuationVersion()

        assertThat(req1).isEqualTo(req1Clone)
        assertThat(req1.hashCode()).isEqualTo(req1Clone.hashCode())
        assertThat(req1).isNotEqualTo(req2)
        assertThat(req1.toString()).contains("continuationVersion=1")
        assertThat(req2.toString()).contains("continuationVersion=2")
    }
}
