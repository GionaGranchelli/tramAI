package dev.tramai.core.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.jvm.internal.DefaultConstructorMarker
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
        // Value-class parameters erase to their underlying type (String) at the
        // JVM level; the synthetic default-argument constructor carries a
        // marker. The guard asserts slot count/shape and the absence of
        // WorkloadMetadata rather than erased parameter names.
        val deploymentParams = primaryParameters(WorkloadDeploymentIdentity::class.java)
        assertThat(deploymentParams).hasSize(4)
        assertThat(deploymentParams).doesNotContain(WorkloadMetadata::class.java)
        assertThat(deploymentParams).contains(WorkloadConfigurationIdentity::class.java)

        val runParams = primaryParameters(GovernedRunIdentity::class.java)
        assertThat(runParams).hasSize(2)
        assertThat(runParams).doesNotContain(WorkloadMetadata::class.java)
        assertThat(runParams).contains(WorkloadDeploymentIdentity::class.java)

        val configurationParams = primaryParameters(WorkloadConfigurationIdentity::class.java)
        assertThat(configurationParams).hasSize(2)
        assertThat(configurationParams).doesNotContain(WorkloadMetadata::class.java)
    }

    private fun primaryParameters(clazz: Class<*>): List<Class<*>> {
        val constructors = clazz.constructors
        val primary = constructors.maxBy { it.parameterCount }
        return primary.parameterTypes
            .filter { it != DefaultConstructorMarker::class.java }
            .toList()
    }
}
