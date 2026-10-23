package dev.tramai.core.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

/**
 * Metadata validation plus the structural proof that owner/purpose metadata is
 * separate from identity equality: identity composition types must not embed a
 * [WorkloadMetadata] field, so ownership changes can never alter identity.
 */
class WorkloadMetadataTest {
    @Test
    fun `valid metadata constructs`() {
        val metadata = WorkloadMetadata(owner = "Payments Team", purpose = "Fraud review")

        assertThat(metadata.owner).isEqualTo("Payments Team")
        assertThat(metadata.purpose).isEqualTo("Fraud review")
    }

    @Test
    fun `blank owner or purpose is rejected`() {
        assertThatThrownBy { WorkloadMetadata(owner = " ", purpose = "Fraud review") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("owner")
        assertThatThrownBy { WorkloadMetadata(owner = "Payments Team", purpose = "  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("purpose")
    }

    @Test
    fun `control characters are rejected in metadata`() {
        assertThatThrownBy { WorkloadMetadata(owner = "Payments\u0000Team", purpose = "Fraud review") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WorkloadMetadata(owner = "Payments Team", purpose = "Fraud\nreview") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `over-limit owner or purpose is rejected`() {
        assertThatThrownBy { WorkloadMetadata(owner = "a".repeat(257), purpose = "Fraud review") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("256")
        assertThatThrownBy { WorkloadMetadata(owner = "Payments Team", purpose = "a".repeat(513)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("512")
    }

    @Test
    fun `identity composition types do not embed metadata`() {
        // Plain JVM classes expose their real parameter types — no value-class
        // erasure. The guard asserts the exact constructor shape and the
        // absence of WorkloadMetadata: ownership can never alter identity.
        val deploymentParams =
            WorkloadDeploymentIdentity::class.java.constructors
                .single()
                .parameterTypes
                .toList()
        assertThat(deploymentParams).containsExactly(
            WorkloadId::class.java,
            WorkloadConfigurationIdentity::class.java,
            EnvironmentId::class.java,
            DeploymentId::class.java,
        )

        val runParams =
            GovernedRunIdentity::class.java.constructors
                .single()
                .parameterTypes
                .toList()
        assertThat(runParams).containsExactly(
            WorkloadDeploymentIdentity::class.java,
            RunId::class.java,
        )

        val configurationParams =
            WorkloadConfigurationIdentity::class.java.constructors
                .single()
                .parameterTypes
                .toList()
        assertThat(configurationParams).containsExactly(
            ConfigurationId::class.java,
            ConfigurationVersion::class.java,
        )
    }
}
