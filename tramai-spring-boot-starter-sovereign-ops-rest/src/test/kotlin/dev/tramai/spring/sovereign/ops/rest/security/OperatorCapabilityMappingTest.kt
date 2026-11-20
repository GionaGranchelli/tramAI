package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.operator.OperatorCapability
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

/**
 * Capability mapping semantics (0.7.6c): a token's claims are input, server configuration is authority.
 */
class OperatorCapabilityMappingTest {
    private val mappings =
        mapOf(
            "governance-reader" to listOf("inspect-governance"),
            "approval-reviewer" to listOf("decide-approval"),
            "release-manager" to listOf("inspect-governance", "cancel-run"),
        )

    private fun mapping() = OperatorCapabilityMapping("roles", mappings)

    @Test
    fun `a missing role claim grants nothing`() {
        assertThat(mapping().capabilitiesFrom(mapOf("sub" to "alice"))).isEmpty()
    }

    @Test
    fun `an unknown role value grants nothing`() {
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to listOf("superuser")))).isEmpty()
    }

    @Test
    fun `client-asserted admin or wildcard roles grant nothing`() {
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to listOf("admin")))).isEmpty()
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to listOf("*")))).isEmpty()
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to listOf("admin", "*", "root")))).isEmpty()
    }

    @Test
    fun `several recognised roles produce the union of their explicit grants`() {
        val granted = mapping().capabilitiesFrom(mapOf("roles" to listOf("governance-reader", "approval-reviewer")))

        assertThat(granted).containsExactlyInAnyOrder(
            OperatorCapability.INSPECT_GOVERNANCE,
            OperatorCapability.DECIDE_APPROVAL,
        )
    }

    @Test
    fun `a single string role claim is accepted like a collection`() {
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to "approval-reviewer")))
            .containsExactly(OperatorCapability.DECIDE_APPROVAL)
    }

    @Test
    fun `capabilities a mapping does not name are never granted`() {
        val granted = mapping().capabilitiesFrom(mapOf("roles" to listOf("governance-reader")))

        assertThat(granted).containsExactly(OperatorCapability.INSPECT_GOVERNANCE)
        assertThat(granted).doesNotContain(
            OperatorCapability.DECIDE_APPROVAL,
            OperatorCapability.CANCEL_RUN,
            OperatorCapability.ADMINISTER_WORKLOAD,
        )
    }

    @Test
    fun `an unknown capability wire name in server configuration is a configuration error`() {
        assertThatThrownBy {
            OperatorCapabilityMapping("roles", mapOf("ops" to listOf("administer-everything")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("administer-everything")
    }

    @Test
    fun `a wildcard capability in server configuration is a configuration error`() {
        assertThatThrownBy {
            OperatorCapabilityMapping("roles", mapOf("ops" to listOf("*")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a blank role claim name is rejected`() {
        assertThatThrownBy { OperatorCapabilityMapping(" ", emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a claim of the wrong type grants nothing instead of being coerced`() {
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to 42))).isEmpty()
        assertThat(mapping().capabilitiesFrom(mapOf("roles" to mapOf("role" to "approval-reviewer")))).isEmpty()
    }
}
