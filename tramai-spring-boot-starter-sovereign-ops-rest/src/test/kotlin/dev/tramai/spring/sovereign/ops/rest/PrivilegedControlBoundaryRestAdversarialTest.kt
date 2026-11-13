package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.rest.security.OperatorAuthTestApplication
import dev.tramai.spring.sovereign.ops.rest.security.OperatorTestKeys
import dev.tramai.spring.sovereign.ops.rest.security.SharedTestKeys
import dev.tramai.spring.sovereign.ops.rest.security.SideEffectRecorder
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Base64

/**
 * 0.7.6g2 Adversarial qualification suite for REST control plane boundary.
 *
 * Covers:
 * - G2-01: Missing / invalid JWT rejected with 401; zero downstream mutation.
 * - G2-04: Header / body authority forgery ignored; authority taken only from verified JWT.
 * - G2-05: Untrusted issuer token rejected with 401.
 * - G2-06: Host application cannot run REST control plane without operator security or policy.
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
        "tramai.sovereign.ops.security.role-mappings.approval-reviewer=decide-approval",
        "tramai.sovereign.ops.security.approver-role-mappings.approval-reviewer=medical-reviewer",
        "tramai.sovereign.ops.security.approval-policies.v1=medical-reviewer",
    ],
)
class PrivilegedControlBoundaryRestAdversarialTest {
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

    // ── G2-01: Missing / Invalid JWT Rejected with 401 ──

    @Test
    fun `G2-01 negative - request without Authorization header is rejected with 401 and never reaches handler`() {
        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/app-1/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedVersion":0,"comment":"unauthenticated-attempt"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `G2-01 negative - malformed token is rejected with 401`() {
        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/app-1/approve")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedVersion":0,"comment":"malformed-token-attempt"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    // ── G2-05: Untrusted Issuer Token Rejected with 401 ──

    @Test
    fun `G2-05 negative - token signed with untrusted foreign key is rejected with 401`() {
        val foreignToken =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    subject = "malicious-actor",
                    signedWith = keys.foreignPair,
                ),
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/app-1/approve")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $foreignToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedVersion":0,"comment":"foreign-key-attempt"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    @Test
    fun `G2-05 negative - token with wrong unconfigured issuer is rejected with 401`() {
        val untrustedIssuerToken =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    issuer = "https://hostile-idp.attacker.example/",
                    signedWith = keys.trustedPair,
                ),
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/app-1/approve")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $untrustedIssuerToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedVersion":0,"comment":"wrong-issuer-attempt"}"""),
            ).andExpect(status().isUnauthorized)

        assertThat(sideEffects.invocations()).isZero()
    }

    // ── G2-04: Authority Forgery in Headers and Body Ignored ──

    private class RecordingDecisionControlPlane : ApprovalDecisionControlPlane {
        var capturedCommand: ApprovalDecisionCommand? = null
        var capturedAuthority: OperatorAuthority? = null

        override suspend fun approve(
            command: ApprovalDecisionCommand,
            authority: OperatorAuthority,
        ): ApprovalDecisionResult {
            capturedCommand = command
            capturedAuthority = authority
            return ApprovalDecisionResult.Approved(
                approvalId = command.approvalId,
                decidedBy = authority.subject,
                decidedAt = Instant.parse("2026-06-25T10:01:00Z"),
                version = 1L,
            )
        }

        override suspend fun deny(
            command: ApprovalDecisionCommand,
            authority: OperatorAuthority,
        ): ApprovalDecisionResult = throw UnsupportedOperationException()
    }

    @Test
    fun `G2-04 - forged headers and body claims are ignored, authority is extracted strictly from verified JWT`() {
        val recordingDecisionControlPlane = RecordingDecisionControlPlane()
        val resumeControlPlane = mock(ApprovalResumeControlPlane::class.java)
        val approvalStore = mock(ApprovalStore::class.java)
        val approvalContinuationStore = mock(ApprovalContinuationStore::class.java)

        val verifiedAuthority =
            OperatorAuthority(
                OperatorPrincipal(
                    subject = "legitimate-operator-42",
                    issuer = "https://idp.example/",
                    authenticatedAt = Instant.parse("2026-06-25T10:00:00Z"),
                    capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                    approverRoles = setOf(ApproverRole("medical-reviewer")),
                ),
            )

        val testMockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    ApprovalControlPlaneController(
                        decisionControlPlane = recordingDecisionControlPlane,
                        resumeControlPlane = resumeControlPlane,
                        approvalStore = approvalStore,
                        approvalContinuationStore = approvalContinuationStore,
                    ),
                ).setCustomArgumentResolvers(
                    object : org.springframework.web.method.support.HandlerMethodArgumentResolver {
                        override fun supportsParameter(parameter: org.springframework.core.MethodParameter) =
                            parameter.parameterType == OperatorAuthority::class.java

                        override fun resolveArgument(
                            parameter: org.springframework.core.MethodParameter,
                            mavContainer: org.springframework.web.method.support.ModelAndViewContainer?,
                            webRequest: org.springframework.web.context.request.NativeWebRequest,
                            binderFactory: org.springframework.web.bind.support.WebDataBinderFactory?,
                        ): Any = verifiedAuthority
                    },
                ).build()

        testMockMvc
            .perform(
                post("/tramai/sovereign/approvals/app-forgery-1/approve")
                    .header("X-Actor", "attacker-impersonator")
                    .header("X-Operator-Role", "super-admin")
                    .header("X-Capabilities", "SUPERUSER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "expectedVersion": 0,
                            "comment": "forgery test",
                            "correlationId": "corr-1",
                            "actor": "spoofed-actor",
                            "role": "spoofed-role"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.actorId").value("legitimate-operator-42"))

        // The captured authority passed to the domain control plane is strictly the verified authority
        val capturedAuthority = requireNotNull(recordingDecisionControlPlane.capturedAuthority)
        assertThat(capturedAuthority.subject).isEqualTo("legitimate-operator-42")
        assertThat(capturedAuthority.issuer).isEqualTo("https://idp.example/")
        assertThat(capturedAuthority.approverRoles).containsExactly(ApproverRole("medical-reviewer"))
        assertThat(capturedAuthority.holds(OperatorCapability.DECIDE_APPROVAL)).isTrue()
        assertThat(capturedAuthority.principal.capabilities).containsExactly(OperatorCapability.DECIDE_APPROVAL)

        val capturedCommand = requireNotNull(recordingDecisionControlPlane.capturedCommand)
        assertThat(capturedCommand.approvalId.value).isEqualTo("app-forgery-1")
        assertThat(capturedCommand.expectedVersion).isEqualTo(0L)
    }

    // ── G2-06: Fail-Closed Application Configuration Guards ──

    @Test
    fun `G2-06 negative - rest control plane enabled with security disabled fails startup`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(TestStubPlanesConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=false",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("tramai-rest-control-plane-requires-operator-security")
            }
    }

    @Test
    fun `G2-06 negative - rest control plane enabled with security enabled but missing policy fails startup`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(TestMissingDecisionControlPlaneConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("tramai-rest-control-plane-requires-approval-policy")
            }
    }

    @Test
    fun `G2-06 positive control - rest control plane with security enabled and policy starts successfully`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(TestStubPlanesConfig::class.java, ValidPolicyConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ApprovalControlPlaneController::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    open class TestMissingDecisionControlPlaneConfig {
        @Bean
        open fun resumeControlPlane(): ApprovalResumeControlPlane = mock(ApprovalResumeControlPlane::class.java)

        @Bean
        open fun approvalStore(): ApprovalStore = mock(ApprovalStore::class.java)

        @Bean
        open fun approvalContinuationStore(): ApprovalContinuationStore = mock(ApprovalContinuationStore::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    open class TestStubPlanesConfig {
        @Bean
        open fun decisionControlPlane(): ApprovalDecisionControlPlane = mock(ApprovalDecisionControlPlane::class.java)

        @Bean
        open fun resumeControlPlane(): ApprovalResumeControlPlane = mock(ApprovalResumeControlPlane::class.java)

        @Bean
        open fun approvalStore(): ApprovalStore = mock(ApprovalStore::class.java)

        @Bean
        open fun approvalContinuationStore(): ApprovalContinuationStore = mock(ApprovalContinuationStore::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    open class ValidPolicyConfig {
        @Bean
        open fun operatorApprovalPolicy(): OperatorApprovalPolicy =
            OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer"))))
    }
}
