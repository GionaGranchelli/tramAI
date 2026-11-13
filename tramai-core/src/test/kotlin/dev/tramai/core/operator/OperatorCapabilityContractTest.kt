package dev.tramai.core.operator

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Instant
import kotlin.test.Test

/**
 * Contract tests for the 0.7.6b operator identity and capability vocabulary.
 *
 * These guard the two properties the whole epic depends on: an operator is identified by
 * subject **and** issuer, and operator authority is neither a wildcard nor a runtime capability.
 */
class OperatorCapabilityContractTest {
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun principal(
        subject: String = "alice",
        issuer: String = "https://idp.example/",
        capabilities: Set<OperatorCapability> = emptySet(),
    ): OperatorPrincipal = OperatorPrincipal(subject, issuer, now, capabilities)

    @Test
    fun `principals with the same subject from different issuers are different operators`() {
        val fromOne = principal(issuer = "https://idp-one.example/")
        val fromOther = principal(issuer = "https://idp-other.example/")

        assertThat(fromOne).isNotEqualTo(fromOther)
    }

    @Test
    fun `principals with the same identity but different capabilities are not equal`() {
        val reader = principal()
        val approver = principal(capabilities = setOf(OperatorCapability.DECIDE_APPROVAL))

        assertThat(reader).isNotEqualTo(approver)
    }

    @Test
    fun `an authenticated principal with no capabilities authorizes nothing`() {
        val authenticatedButUnprivileged = principal()

        assertThat(authenticatedButUnprivileged.capabilities).isEmpty()
        assertThat(authenticatedButUnprivileged.authorize(OperatorCapability.DECIDE_APPROVAL))
            .isEqualTo(OperatorAuthorizationResult.Denied(OperatorCapability.DECIDE_APPROVAL))
    }

    @Test
    fun `authorize returns Authorized only for a granted capability`() {
        val operator =
            principal(
                capabilities = setOf(OperatorCapability.INSPECT_GOVERNANCE, OperatorCapability.CANCEL_RUN),
            )

        assertThat(operator.authorize(OperatorCapability.CANCEL_RUN))
            .isEqualTo(OperatorAuthorizationResult.Authorized)
        assertThat(operator.authorize(OperatorCapability.DECIDE_APPROVAL))
            .isEqualTo(OperatorAuthorizationResult.Denied(OperatorCapability.DECIDE_APPROVAL))
    }

    @Test
    fun `a denial names the capability that was missing`() {
        val denied = principal().authorize(OperatorCapability.ADMINISTER_WORKLOAD)

        assertThat(denied).isInstanceOf(OperatorAuthorizationResult.Denied::class.java)
        assertThat((denied as OperatorAuthorizationResult.Denied).missing)
            .isEqualTo(OperatorCapability.ADMINISTER_WORKLOAD)
    }

    @Test
    fun `an unknown or wildcard capability name is rejected instead of resolved`() {
        // 'admin' is ApiKeyScope's super-scope. It must not exist here: accepting it would hand one
        // grant the meaning of every grant, which is the conflation 0.7.6b exists to prevent.
        assertThatThrownBy { OperatorCapability.fromWireName("admin") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("admin")
        assertThatThrownBy { OperatorCapability.fromWireName("*") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every capability round-trips through its wire name`() {
        OperatorCapability.entries.forEach { capability ->
            assertThat(OperatorCapability.fromWireName(capability.wireName)).isEqualTo(capability)
        }
    }

    @Test
    fun `no operator capability names a runtime execution resource`() {
        // The executable form of "operator capability != runtime capability": if a future member is
        // added for tools, MCP, models, providers, agents or network destinations, this fails.
        val runtimeResourceWords = listOf("tool", "mcp", "model", "provider", "agent", "network", "prompt", "embedding")

        OperatorCapability.entries.forEach { capability ->
            val spellings = listOf(capability.name.lowercase(), capability.wireName.lowercase())
            spellings.forEach { spelling ->
                runtimeResourceWords.forEach { word ->
                    assertThat(spelling)
                        .describedAs("operator capability '%s' must not describe runtime resource '%s'", spelling, word)
                        .doesNotContain(word)
                }
            }
        }
    }

    @Test
    fun `an operator subject that the approval path could not record is rejected`() {
        assertThatThrownBy { principal(subject = "alice smith") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recordable real-world subject formats are accepted`() {
        // ADFS object id and a Google service account: both recordable as an approval actor id.
        assertThat(principal(subject = "S-1-5-21-3623811015-3361044348-30300820-1013").subject)
            .isEqualTo("S-1-5-21-3623811015-3361044348-30300820-1013")
        assertThat(principal(subject = "svc-claims@project-1.iam.gserviceaccount.com").subject)
            .isEqualTo("svc-claims@project-1.iam.gserviceaccount.com")
    }

    @Test
    fun `an Auth0-style or URL-shaped subject is rejected, and that limit is documented`() {
        // Real IdP subjects that the approval path's actor-id rule cannot record. Fail-closed on
        // purpose: 0.7.6c must map these into a recordable form, not widen this rule.
        assertThatThrownBy { principal(subject = "auth0|64f1c2") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { principal(subject = "https://idp.example/alice") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mutating the caller's set after construction cannot change authority`() {
        val grants = mutableSetOf(OperatorCapability.INSPECT_GOVERNANCE)
        val operator = OperatorPrincipal("alice", "https://idp.example/", now, grants)

        grants.add(OperatorCapability.DECIDE_APPROVAL)

        assertThat(operator.capabilities).containsExactly(OperatorCapability.INSPECT_GOVERNANCE)
        assertThat(operator.authorize(OperatorCapability.DECIDE_APPROVAL))
            .isEqualTo(OperatorAuthorizationResult.Denied(OperatorCapability.DECIDE_APPROVAL))
    }

    @Test
    fun `the exposed capability set cannot be modified`() {
        // Built from a mutable set so the assertion discriminates: without the defensive snapshot the
        // exposed set would still be the caller's mutable one and this add would succeed.
        val operator =
            OperatorPrincipal(
                "alice",
                "https://idp.example/",
                now,
                mutableSetOf(OperatorCapability.CANCEL_RUN),
            )

        assertThatThrownBy {
            @Suppress("UNCHECKED_CAST")
            (operator.capabilities as MutableSet<OperatorCapability>).add(OperatorCapability.DECIDE_APPROVAL)
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `a blank or whitespace-bearing issuer is rejected`() {
        assertThatThrownBy { principal(issuer = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { principal(issuer = "https://idp example/") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
