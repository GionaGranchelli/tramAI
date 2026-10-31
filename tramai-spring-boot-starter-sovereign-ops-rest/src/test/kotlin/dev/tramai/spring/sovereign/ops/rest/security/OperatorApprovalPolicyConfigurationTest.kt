package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.ApprovalUnauthorizedReason
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Start-up validation of the approver-role configuration (0.7.6e contract §3 and §7.2).
 *
 * The contract's rule has three parts, and the previous implementation enforced only the first two:
 *
 * 1. a blank role is rejected — `ApproverRole` itself does this;
 * 2. a **malformed** role is rejected by a shape policy (non-blank, no whitespace, no control characters,
 *    bounded length) — implemented in [requireApproverRoleShape] and asserted here;
 * 3. an **unconfigured reference** — an approver role named in `approval-policies` that appears in no
 *    `approver-role-mappings` value — is a startup failure, because no verified principal could ever
 *    satisfy it.
 *
 * Part 3 is the one worth being explicit about: accepting that configuration is *fail-safe* at decision
 * time (every decision is refused), but it is not the agreed invariant. A deployment would boot looking
 * authorized while it is permanently unable to approve anything, and the operator would find out from
 * refused decisions rather than from startup.
 *
 * These tests invoke the auto-configuration's own bean method. That it is the method actually used at
 * startup is proven elsewhere, by contexts that boot with real configuration
 * (`OperatorAuthenticationHttpTest`, `OperatorChainShadowingTest`, and the sovereign-lab profile boot).
 */
class OperatorApprovalPolicyConfigurationTest {
    private val autoConfiguration = OperatorAuthenticationAutoConfiguration()

    private fun properties() =
        OperatorSecurityProperties().apply {
            enabled = true
            issuer = "https://idp.example/"
            audience = "tramai-control-plane"
            jwkSetUri = "https://idp.example/jwks"
            roleMappings = mapOf("approval-reviewer" to listOf("decide-approval"))
        }

    // ── Reference consistency (§7.2, "unconfigured reference") ────────────

    @Test
    fun `a policy role no mapping can grant is a startup failure`() {
        val unsatisfiable =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf("finance-reviewer"))
                approvalPolicies = mapOf("v1" to listOf("medical-reviewer"))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(unsatisfiable) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approval-policy-unsatisfiable")
            .hasMessageContaining("medical-reviewer")
    }

    @Test
    fun `one unsatisfiable role among satisfiable ones is still a startup failure`() {
        val partiallySatisfiable =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf("finance-reviewer"))
                approvalPolicies = mapOf("v1" to listOf("finance-reviewer", "medical-reviewer"))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(partiallySatisfiable) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approval-policy-unsatisfiable")
            .hasMessageContaining("medical-reviewer")
    }

    @Test
    fun `a satisfiable configuration produces a policy that grants the configured role`() {
        val satisfiable =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf("finance-reviewer"))
                approvalPolicies = mapOf("v1" to listOf("finance-reviewer"))
            }

        val policy = autoConfiguration.operatorApprovalPolicy(satisfiable)

        assertThat(policy.configuredPolicyVersions()).containsExactly("v1")
        assertThat(policy.evaluate("v1", authorityHolding("finance-reviewer"))).isNull()
        assertThat(policy.evaluate("v1", authorityHolding("medical-reviewer")))
            .isEqualTo(ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED)
        assertThat(policy.evaluate("v2", authorityHolding("finance-reviewer")))
            .isEqualTo(ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE)
    }

    // ── Malformed roles (§7.2, "malformed") ───────────────────────────────

    @Test
    fun `whitespace inside a policy role is a startup failure`() {
        val malformed =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf("medical reviewer"))
                approvalPolicies = mapOf("v1" to listOf("medical reviewer"))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(malformed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approver-role-malformed")
    }

    @Test
    fun `a whitespace-only mapping value is a startup failure`() {
        val malformed =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf(" "))
                approvalPolicies = mapOf("v1" to listOf("medical-reviewer"))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(malformed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approver-role-malformed")
    }

    @Test
    fun `a control character in a mapping value is a startup failure`() {
        val malformed =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf("medical\u0000reviewer"))
                approvalPolicies = mapOf("v1" to listOf("medical-reviewer"))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(malformed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approver-role-malformed")
    }

    @Test
    fun `an over-long approver role is a startup failure`() {
        val tooLong = "r".repeat(MAX_APPROVER_ROLE_LENGTH + 1)
        val malformed =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf(tooLong))
                approvalPolicies = mapOf("v1" to listOf(tooLong))
            }

        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(malformed) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approver-role-malformed")
            .hasMessageContaining("$MAX_APPROVER_ROLE_LENGTH")
    }

    @Test
    fun `an approver role of exactly the length bound is accepted`() {
        val atBound = "r".repeat(MAX_APPROVER_ROLE_LENGTH)
        val accepted =
            properties().apply {
                approverRoleMappings = mapOf("reviewer" to listOf(atBound))
                approvalPolicies = mapOf("v1" to listOf(atBound))
            }

        assertThat(autoConfiguration.operatorApprovalPolicy(accepted).configuredPolicyVersions())
            .containsExactly("v1")
    }

    // ── Existing rules, still enforced ────────────────────────────────────

    @Test
    fun `no approval-policies entry remains a startup failure`() {
        assertThatThrownBy { autoConfiguration.operatorApprovalPolicy(properties()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-approval-policies-missing")
    }

    private fun authorityHolding(approverRole: String): OperatorAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "operator-1",
                issuer = "https://idp.example/",
                authenticatedAt = Instant.parse("2026-10-27T00:00:00Z"),
                capabilities = emptySet(),
                approverRoles = setOf(ApproverRole(approverRole)),
            ),
        )

    /** Guards against a silently vacuous suite: the policies above must reach a real policy object. */
    @Test
    fun `the positive control really produces a policy`() {
        val policy: OperatorApprovalPolicy =
            autoConfiguration.operatorApprovalPolicy(
                properties().apply {
                    approverRoleMappings = mapOf("reviewer" to listOf("medical-reviewer"))
                    approvalPolicies = mapOf("v1" to listOf("medical-reviewer"))
                },
            )

        assertThat(policy).isNotNull
    }
}
