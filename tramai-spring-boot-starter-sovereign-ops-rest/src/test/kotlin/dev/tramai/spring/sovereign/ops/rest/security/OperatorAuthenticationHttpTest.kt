package dev.tramai.spring.sovereign.ops.rest.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Base64

/**
 * HTTP behaviour of the operator authentication boundary (0.7.6c), driven through the real Spring
 * filter chain and MockMvc — not by calling the conversion code directly.
 *
 * `401` means authentication failed; `403` means authentication succeeded and the required capability
 * was denied. The `403` here is produced by a test-only capability check, deliberately: implementing
 * the epic's real authorization boundary (0.7.6e) to obtain the status code would be exactly the
 * confusion this epic exists to remove.
 */
@SpringBootTest(classes = [OperatorAuthTestApplication::class])
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.security.enabled=true",
        "tramai.sovereign.ops.security.issuer=https://idp.example/",
        "tramai.sovereign.ops.security.audience=tramai-control-plane",
        "tramai.sovereign.ops.security.jwk-set-uri=https://idp.example/jwks",
        "tramai.sovereign.ops.security.role-claim=roles",
        "tramai.sovereign.ops.security.role-mappings.governance-reader=inspect-governance",
        "tramai.sovereign.ops.security.role-mappings.approval-reviewer=decide-approval",
        "tramai.sovereign.ops.security.approver-role-mappings.approval-reviewer=medical-reviewer",
        "tramai.sovereign.ops.security.approval-policies.v1=medical-reviewer",
    ],
)
class OperatorAuthenticationHttpTest {
    companion object {
        /**
         * The operator boundary builds its decoder from configuration, not from a bean, so the suite pins
         * the locally generated trusted key as the `public-key` property.
         */
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
    fun `a request without a token is rejected with 401 and never reaches the handler`() {
        mockMvc
            .perform(get("/tramai/sovereign/test/whoami"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a verified token yields the immutable operator principal`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(subject = "alice"))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subject").value("alice"))
            .andExpect(jsonPath("$.issuer").value(OperatorTestKeys.ISSUER))
            .andExpect(jsonPath("$.capabilities[0]").value("decide-approval"))
    }

    @Test
    fun `a verified token without a subject is rejected with 401 and never reaches the handler`() {
        // Regression guard: this used to raise IllegalStateException from the factory, which Spring
        // surfaced as 500. An unusable credential is an authentication failure, not a server fault.
        val token = keys.token(OperatorTestKeys.TokenSpec(subject = null))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a verified token without an expiry claim is rejected with 401`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(expiresAt = null))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a permissive host decoder named exactly like the operator decoder is still ignored`() {
        // Regression guard for the same-name override: the operator decoder used to be a bean guarded by
        // @ConditionalOnMissingBean(name = "operatorJwtDecoder"), so a host declaring that name replaced
        // it. It is now constructed inside the chain from configuration, so there is no bean to claim. The
        // context still holds a permissive decoder under exactly that name.
        val forged = keys.token(OperatorTestKeys.TokenSpec(signedWith = keys.foreignPair))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a permissive host decoder cannot weaken the operator chain`() {
        // The context holds a second, deliberately permissive JwtDecoder bean that trusts the foreign
        // key and applies no validators. If the chain inherited it, this forged token would be accepted.
        val forged = keys.token(OperatorTestKeys.TokenSpec(signedWith = keys.foreignPair))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a token signed by a foreign key is rejected with 401`() {
        val forged = keys.token(OperatorTestKeys.TokenSpec(signedWith = keys.foreignPair))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $forged"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a token with the wrong audience is rejected with 401`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(audience = "some-other-api"))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `a token with the wrong issuer is rejected with 401`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(issuer = "https://evil.example/"))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `an expired token is rejected with 401`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(expiresAt = Instant.now().minusSeconds(3600)))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `an unrecordable subject is rejected with 401 instead of being rewritten`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(subject = "auth0|64f1c2"))

        mockMvc
            .perform(get("/tramai/sovereign/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `an authenticated operator without the required capability gets 403 and no state change`() {
        val readerOnly = keys.token(OperatorTestKeys.TokenSpec(subject = "bob", roles = listOf("governance-reader")))

        mockMvc
            .perform(post("/tramai/sovereign/test/gated").header(HttpHeaders.AUTHORIZATION, "Bearer $readerOnly"))
            .andExpect(status().isForbidden)

        assertThat(sideEffects.mutations()).isZero()
    }

    @Test
    fun `an authenticated operator holding the capability may execute the operation`() {
        val reviewer = keys.token(OperatorTestKeys.TokenSpec(subject = "carol"))

        mockMvc
            .perform(post("/tramai/sovereign/test/gated").header(HttpHeaders.AUTHORIZATION, "Bearer $reviewer"))
            .andExpect(status().isOk)

        assertThat(sideEffects.mutations()).isEqualTo(1)
    }

    @Test
    fun `claims that the server configuration does not map grant no capability`() {
        val token = keys.token(OperatorTestKeys.TokenSpec(subject = "mallory", roles = listOf("admin", "*")))

        mockMvc
            .perform(post("/tramai/sovereign/test/gated").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isForbidden)

        assertThat(sideEffects.mutations()).isZero()
    }

    @Test
    fun `routes outside the protected paths stay outside the operator chain`() {
        mockMvc
            .perform(get("/public/ping"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pong").value(true))
    }
}
