package dev.tramai.spring.sovereign.ops.rest.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.Base64

/**
 * Hostile-substitution proof for the operator trust boundary (0.7.6e contract §1.2 and §3).
 *
 * The contract requires that the collaborators deciding *what a verified caller is allowed to be* cannot
 * be replaced by host configuration. This test declares a **`@Primary` permissive bean of every one of
 * those types** — a capability mapping that grants everything to every role, an approver-role mapping that
 * turns every role into `medical-reviewer`, and a principal factory and resolver that mint all of that —
 * and asserts nothing changes.
 *
 * Why `@Primary` rather than a plain bean: `@ConditionalOnMissingBean` would not have been enough. Two
 * candidate beans of one type still resolve, and `@Primary` picks the hostile one. The boundary therefore
 * constructs the whole chain inline from configuration, so there is no injection point to substitute.
 *
 * Both halves of the proof are here:
 * - **structural** — the boundary publishes no bean of any trusted type, so the only beans of those types
 *   are the hostile ones;
 * - **behavioural** — a token whose role is unmapped by configuration still receives exactly the
 *   configured capabilities and is still refused by the capability gate, which a substituted mapping or
 *   factory could not produce.
 *
 * Discrimination recorded when this was written: with the previous `@ConditionalOnMissingBean` beans in
 * place, the `@Primary` hostile mapping won type-based injection and the `403` case below returned `200`.
 * A host that registers its own `WebMvcConfigurer` or argument resolver runs arbitrary in-process code and
 * is outside what a Spring boundary can defend; that limit is stated on
 * [OperatorAuthenticationAutoConfiguration.operatorPrincipalWebMvcConfigurer].
 */
@SpringBootTest(classes = [OperatorAuthTestApplication::class, HostileTrustedCollaboratorsConfig::class])
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.security.enabled=true",
        "tramai.sovereign.ops.security.issuer=https://idp.example/",
        "tramai.sovereign.ops.security.audience=tramai-control-plane",
        "tramai.sovereign.ops.security.jwk-set-uri=https://idp.example/jwks",
        "tramai.sovereign.ops.security.role-claim=roles",
        // Configuration deliberately grants ONE capability to ONE role, and no approver role to anybody
        // else. The hostile beans below claim the opposite.
        "tramai.sovereign.ops.security.role-mappings.governance-reader=inspect-governance",
        "tramai.sovereign.ops.security.approver-role-mappings.approval-reviewer=medical-reviewer",
        "tramai.sovereign.ops.security.approval-policies.0.4.0-preview=medical-reviewer",
    ],
)
class OperatorTrustBoundarySubstitutionTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun operatorSecurityProperties(registry: DynamicPropertyRegistry) {
            val publicKey = Base64.getEncoder().encodeToString(SharedTestKeys.keys.trustedPair.public.encoded)
            registry.add("tramai.sovereign.ops.security.public-key") { publicKey }
        }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var keys: OperatorTestKeys

    @Test
    fun `the boundary publishes no bean of the trusted types`() {
        assertThat(context.getBeanNamesForType(OperatorCapabilityMapping::class.java))
            .describedAs("only the host's hostile mapping may exist; the boundary constructs its own")
            .containsExactly("hostileCapabilityMapping")
        assertThat(context.getBeanNamesForType(OperatorApproverRoleMapping::class.java))
            .containsExactly("hostileApproverRoleMapping")
        assertThat(context.getBeanNamesForType(OperatorPrincipalFactory::class.java))
            .containsExactly("hostilePrincipalFactory")
        assertThat(context.getBeanNamesForType(OperatorPrincipalResolver::class.java))
            .containsExactly("hostilePrincipalResolver")
    }

    @Test
    fun `a hostile capability mapping cannot widen the capabilities a token receives`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(roles = listOf("governance-reader")))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities[0]").value("inspect-governance"))
            .andExpect(jsonPath("$.capabilities[1]").doesNotExist())
    }

    @Test
    fun `a role the hostile mapping would grant everything still receives nothing`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(roles = listOf("*")))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities").isEmpty)
    }

    @Test
    fun `a hostile principal factory cannot turn an unprivileged token into a decision-maker`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(roles = listOf("governance-reader")))

        // 403, not 200: the capability gate still sees configuration's grants, not the hostile factory's.
        mockMvc
            .perform(post("/tramai/sovereign/test/gated").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isForbidden)
    }
}

/**
 * The hostile wiring. Every bean here is a plausible-looking permissive substitute a consumer could add
 * "to make the tests pass", and every one of them is `@Primary` so type-based injection would prefer it.
 */
@Configuration(proxyBeanMethods = false)
open class HostileTrustedCollaboratorsConfig {
    private fun permissiveProperties() =
        OperatorSecurityProperties().apply {
            enabled = true
            issuer = "https://idp.example/"
            audience = "tramai-control-plane"
            jwkSetUri = "https://idp.example/jwks"
            roleMappings = mapOf("*" to listOf("decide-approval", "inspect-governance"))
            approverRoleMappings = mapOf("*" to listOf("medical-reviewer"))
            approvalPolicies = mapOf("0.4.0-preview" to listOf("medical-reviewer"))
        }

    /**
     * Grants both capabilities to a wildcard role *and* to the role the configuration maps narrowly, so a
     * substitution is a visible **widening**: the narrow role would also be able to decide approvals.
     */
    @Bean
    @Primary
    open fun hostileCapabilityMapping(): OperatorCapabilityMapping =
        OperatorCapabilityMapping(
            "roles",
            mapOf(
                "*" to listOf("decide-approval", "inspect-governance"),
                "governance-reader" to listOf("decide-approval", "inspect-governance"),
            ),
        )

    @Bean
    @Primary
    open fun hostileApproverRoleMapping(): OperatorApproverRoleMapping =
        OperatorApproverRoleMapping(
            "roles",
            mapOf(
                "*" to listOf("medical-reviewer"),
                "governance-reader" to listOf("medical-reviewer"),
            ),
        )

    @Bean
    @Primary
    open fun hostilePrincipalFactory(): OperatorPrincipalFactory =
        OperatorPrincipalFactory(
            permissiveProperties(),
            hostileCapabilityMapping(),
            hostileApproverRoleMapping(),
            Clock.systemUTC(),
        )

    @Bean
    @Primary
    open fun hostilePrincipalResolver(factory: OperatorPrincipalFactory) = OperatorPrincipalResolver(factory)
}
