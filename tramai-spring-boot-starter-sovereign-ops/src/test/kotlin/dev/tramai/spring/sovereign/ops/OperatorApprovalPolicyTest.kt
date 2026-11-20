package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Semantics of the per-approval approver-role requirement (0.7.6e, contract §2 L3).
 *
 * The rule these tests defend: a decision is permitted only when the operator's **verified** approver
 * roles intersect the roles the approval's `policyVersion` accepts, and an unknown requirement is a
 * refusal rather than a default allow.
 */
class OperatorApprovalPolicyTest {
    private val now: Instant = Instant.parse("2026-10-25T09:00:00Z")

    private fun authority(
        subject: String = "operator-1",
        issuer: String = "https://issuer.example",
        capabilities: Set<OperatorCapability> = setOf(OperatorCapability.DECIDE_APPROVAL),
        approverRoles: Set<ApproverRole> = setOf(ApproverRole("medical-reviewer")),
    ) = OperatorAuthority(
        OperatorPrincipal(subject, issuer, now, capabilities, approverRoles),
    )

    private fun policy(vararg entries: Pair<String, Set<ApproverRole>>) = OperatorApprovalPolicy(entries.toMap())

    @Test
    fun `a verified approver role the policy accepts permits the decision`() {
        val policy = policy("2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")))

        val refusal = policy.evaluate("2024-06-medical-v3", authority())

        assertThat(refusal).isNull()
    }

    @Test
    fun `a verified approver role the policy does not accept is refused`() {
        val policy = policy("2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")))
        val financeOnly = authority(approverRoles = setOf(ApproverRole("finance-reviewer")))

        val refusal = policy.evaluate("2024-06-medical-v3", financeOnly)

        assertThat(refusal).isEqualTo(ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED)
    }

    @Test
    fun `an unconfigured policy version is unavailable rather than allow-any-approver`() {
        val policy = policy("2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")))

        val refusal = policy.evaluate("2025-01-unconfigured", authority())

        assertThat(refusal).isEqualTo(ApprovalUnauthorizedReason.APPROVAL_POLICY_UNAVAILABLE)
    }

    @Test
    fun `approver roles union across verified claims so one matching role is enough`() {
        val policy = policy("2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")))
        val bothRoles =
            authority(
                approverRoles = setOf(ApproverRole("finance-reviewer"), ApproverRole("medical-reviewer")),
            )

        assertThat(policy.evaluate("2024-06-medical-v3", bothRoles)).isNull()
    }

    @Test
    fun `an operator with no approver role at all is refused`() {
        val policy = policy("2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")))

        val refusal = policy.evaluate("2024-06-medical-v3", authority(approverRoles = emptySet()))

        assertThat(refusal).isEqualTo(ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED)
    }

    @Test
    fun `an empty policy map is a configuration error, not a permissive default`() {
        assertThatThrownBy { OperatorApprovalPolicy(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-operator-approval-policy-empty")
    }

    @Test
    fun `a policy entry with no accepted role is a configuration error`() {
        assertThatThrownBy { OperatorApprovalPolicy(mapOf("2024-06-medical-v3" to emptySet())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-operator-approval-policy-empty-roles")
    }

    @Test
    fun `a blank policy version is a configuration error`() {
        assertThatThrownBy { OperatorApprovalPolicy(mapOf(" " to setOf(ApproverRole("medical-reviewer")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-operator-approval-policy-blank-version")
    }

    @Test
    fun `the configured policy versions are readable for startup diagnostics`() {
        val policy =
            policy(
                "2024-06-medical-v3" to setOf(ApproverRole("medical-reviewer")),
                "2024-09-payout-v1" to setOf(ApproverRole("finance-reviewer")),
            )

        assertThat(policy.configuredPolicyVersions())
            .containsExactlyInAnyOrder("2024-06-medical-v3", "2024-09-payout-v1")
    }

    @Test
    fun `the authority keeps the issuer alongside the subject`() {
        val authority = authority(subject = "shared-subject", issuer = "https://issuer-a.example")
        val otherIssuer = authority(subject = "shared-subject", issuer = "https://issuer-b.example")

        assertThat(authority.subject).isEqualTo(otherIssuer.subject)
        assertThat(authority.issuer).isNotEqualTo(otherIssuer.issuer)
    }

    @Test
    fun `the authority snapshots approver roles instead of aliasing the caller collection`() {
        val mutableRoles = mutableSetOf(ApproverRole("medical-reviewer"))
        val authority =
            OperatorAuthority(OperatorPrincipal("operator-1", "https://issuer.example", now, emptySet(), mutableRoles))

        mutableRoles.add(ApproverRole("finance-reviewer"))

        assertThat(authority.approverRoles).containsExactly(ApproverRole("medical-reviewer"))
    }

    @Test
    fun `capability decisions are typed so a refusal names the missing capability`() {
        val withoutCapability = authority(capabilities = emptySet())

        assertThat(withoutCapability.holds(OperatorCapability.DECIDE_APPROVAL)).isFalse()
        assertThat(withoutCapability.authorize(OperatorCapability.DECIDE_APPROVAL))
            .isInstanceOf(dev.tramai.core.operator.OperatorAuthorizationResult.Denied::class.java)
    }

    @Test
    fun `toString does not leak capabilities or roles`() {
        val rendered = authority().toString()

        assertThat(rendered).contains("operator-1").contains("issuer.example")
        assertThat(rendered).doesNotContain("medical-reviewer").doesNotContain("decide-approval")
    }
}
