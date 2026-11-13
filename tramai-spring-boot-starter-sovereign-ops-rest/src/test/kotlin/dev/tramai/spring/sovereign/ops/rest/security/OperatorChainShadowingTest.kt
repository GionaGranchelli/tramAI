package dev.tramai.spring.sovereign.ops.rest.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64

/**
 * What a host application's own security chain can and cannot do to the operator boundary (0.7.6c).
 *
 * The operator chain is `@Order(1)`, so a host chain with a **higher** precedence that matches the same
 * paths wins the routing. This class proves the consequence rather than claiming the chain cannot be
 * shadowed: a shadowing chain can break operator authentication (legitimate callers get 401), but it cannot
 * **grant** access, because the argument resolver only ever builds a principal from a verified
 * `JwtAuthenticationToken`. Shadowing is therefore an availability defect a host can cause, never an
 * authorization bypass.
 *
 * The integration contract that follows: a host must not register a chain with higher precedence than the
 * operator chain over the operator paths. The starter cannot enforce that ordering against an arbitrary
 * `@Order`, and trying to would mean owning the host's whole security configuration.
 */
@SpringBootTest(
    classes = [OperatorAuthTestApplication::class, OperatorChainShadowingTest.ShadowingChainConfig::class],
    properties = [
        "tramai.sovereign.ops.security.enabled=true",
        "tramai.sovereign.ops.security.issuer=https://idp.example/",
        "tramai.sovereign.ops.security.audience=tramai-control-plane",
        "tramai.sovereign.ops.security.role-claim=roles",
        "tramai.sovereign.ops.security.role-mappings.approval-reviewer=decide-approval",
        "tramai.sovereign.ops.security.approver-role-mappings.approval-reviewer=medical-reviewer",
        "tramai.sovereign.ops.security.approval-policies.v1=medical-reviewer",
        "tramai.sovereign.ops.security.role-mappings.governance-reader=inspect-governance",
    ],
)
@AutoConfigureMockMvc
class OperatorChainShadowingTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var keys: OperatorTestKeys

    @Autowired
    private lateinit var sideEffects: SideEffectRecorder

    @BeforeEach
    fun resetRecorder() {
        sideEffects.reset()
    }

    @AfterEach
    fun clearRecorder() {
        sideEffects.reset()
    }

    @Test
    fun `a permitAll host chain with higher precedence shadows the operator chain`() {
        // Documented limitation, asserted so it cannot drift into a claim that the operator chain is
        // unshadowable: the host chain runs first and permits the request.
        mockMvc
            .perform(get("/tramai/sovereign/test/whoami"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a shadowed operator route still cannot mint a principal for a forged token`() {
        val forged = keys.token(OperatorTestKeys.TokenSpec(signedWith = keys.foreignPair))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a shadowed operator route rejects a valid token rather than bypassing authentication`() {
        // The availability cost of shadowing: the host chain consumed the request before the resource
        // server could authenticate it, so a legitimate operator is refused. Fail-closed, never fail-open.
        val valid = keys.token(OperatorTestKeys.TokenSpec())

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $valid"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a non-JWT authentication established by the host chain never becomes an operator`() {
        // The host chain authenticates this request with HTTP Basic. The resolver requires a verified
        // JwtAuthenticationToken, so a host-established identity cannot be promoted to an operator.
        val basic = Base64.getEncoder().encodeToString("host-user:host-password".toByteArray())
        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Basic $basic"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    /** A host-style chain with precedence over the operator chain, matching the same paths. */
    @TestConfiguration(proxyBeanMethods = false)
    class ShadowingChainConfig {
        @Bean
        fun hostUserDetails(): UserDetailsService =
            InMemoryUserDetailsManager(
                User
                    .withUsername("host-user")
                    .password("{noop}host-password")
                    .roles("HOST")
                    .build(),
            )

        @Bean
        @Order(0)
        fun hostShadowingFilterChain(http: HttpSecurity): SecurityFilterChain =
            http
                .securityMatcher("/tramai/sovereign/**")
                .csrf { csrf -> csrf.disable() }
                .httpBasic { basic -> basic.realmName("host") }
                .authorizeHttpRequests { authorize -> authorize.anyRequest().permitAll() }
                .build()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun operatorSecurityProperties(registry: DynamicPropertyRegistry) {
            val publicKey = Base64.getEncoder().encodeToString(SharedTestKeys.keys.trustedPair.public.encoded)
            registry.add("tramai.sovereign.ops.security.public-key") { publicKey }
        }
    }
}
