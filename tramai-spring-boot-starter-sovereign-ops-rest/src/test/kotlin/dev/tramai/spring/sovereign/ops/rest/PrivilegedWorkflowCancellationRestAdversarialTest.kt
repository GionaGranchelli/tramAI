@file:Suppress("MaxLineLength")

package dev.tramai.spring.sovereign.ops.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.WorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.WorkflowCancellationResult
import dev.tramai.spring.sovereign.ops.WorkflowUnauthorizedReason
import dev.tramai.spring.sovereign.ops.rest.security.OperatorAuthTestApplication
import dev.tramai.spring.sovereign.ops.rest.security.OperatorTestKeys
import dev.tramai.spring.sovereign.ops.rest.security.SharedTestKeys
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    classes = [
        OperatorAuthTestApplication::class,
        PrivilegedWorkflowCancellationRestAdversarialTest.CancellationControlPlaneConfig::class,
    ],
)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.security.enabled=true",
        "tramai.sovereign.ops.security.issuer=https://idp.example/",
        "tramai.sovereign.ops.security.audience=tramai-control-plane",
        "tramai.sovereign.ops.security.jwk-set-uri=https://idp.example/jwks",
        "tramai.sovereign.ops.security.role-claim=roles",
        "tramai.sovereign.ops.security.role-mappings.workflow-canceller=cancel-workflow",
        "tramai.sovereign.ops.security.role-mappings.other-role=decide-approval",
        "tramai.sovereign.ops.security.approver-role-mappings.workflow-canceller=deploy-admin",
        "tramai.sovereign.ops.security.approver-role-mappings.other-role=finance-reviewer",
        "tramai.sovereign.ops.security.approval-policies.v1=finance-reviewer",
        "tramai.sovereign.ops.security.workflow-cancellation-policies.deploy-pipeline=deploy-admin",
        "tramai.sovereign.ops.rest-control-plane-enabled=true",
    ],
)
class PrivilegedWorkflowCancellationRestAdversarialTest {
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
    private lateinit var recordingControlPlane: RecordingWorkflowCancellationControlPlane

    private val mapper = ObjectMapper()

    @BeforeEach
    fun resetRecorder() {
        recordingControlPlane.reset()
    }

    @Test
    fun `request without Authorization header is rejected with 401 and never reaches control plane`() {
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Unauthenticated attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isUnauthorized)

        assertThat(recordingControlPlane.callCount.get()).isZero()
    }

    @Test
    fun `token signed with untrusted foreign key is rejected with 401`() {
        val forgedToken =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    roles = listOf("workflow-canceller"),
                    signedWith = keys.foreignPair,
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Forged key attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $forgedToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isUnauthorized)

        assertThat(recordingControlPlane.callCount.get()).isZero()
    }

    @Test
    fun `expired token is rejected with 401`() {
        val expiredToken =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    roles = listOf("workflow-canceller"),
                    expiresAt =
                        java.time.Instant
                            .now()
                            .minusSeconds(300),
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Expired token attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $expiredToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isUnauthorized)

        assertThat(recordingControlPlane.callCount.get()).isZero()
    }

    @Test
    fun `token missing CANCEL_WORKFLOW capability is rejected with 403 existence-blind`() {
        val token =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    roles = listOf("other-role"),
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Missing capability attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.status").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("missing-capability"))

        assertThat(recordingControlPlane.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `token with CANCEL_WORKFLOW on unconfigured target returns 403 workflow-policy-unavailable`() {
        val token =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    roles = listOf("workflow-canceller"),
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Unconfigured workflow attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/unconfigured-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.status").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("workflow-policy-unavailable"))

        assertThat(recordingControlPlane.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `valid authorized token succeeds with 200 and ignores spoofed headers`() {
        val token =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    subject = "legit-operator",
                    roles = listOf("workflow-canceller"),
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Authorized cancellation",
                correlationId = "corr-xyz",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .header("X-Actor", "spoofed-admin")
                    .header("X-Operator-Role", "root")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
            .andExpect(jsonPath("$.workflowName").value("deploy-pipeline"))
            .andExpect(jsonPath("$.runId").value("run-1"))
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.cancelledBy").value("legit-operator"))
            .andExpect(jsonPath("$.alreadyCancelled").value(false))

        assertThat(recordingControlPlane.callCount.get()).isEqualTo(1)
        val capturedAuthority = recordingControlPlane.lastAuthority!!
        // Proves spoofed headers were completely ignored and authority comes from JWT
        assertThat(capturedAuthority.subject).isEqualTo("legit-operator")
        assertThat(OperatorCapability.CANCEL_WORKFLOW in capturedAuthority.principal.capabilities).isTrue()
    }

    @Test
    fun `stale expectedRevision returns 409 Conflict`() {
        val token =
            keys.token(
                OperatorTestKeys.TokenSpec(
                    subject = "legit-operator",
                    roles = listOf("workflow-canceller"),
                ),
            )
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 99L, // Stale!
                reason = "Stale cancellation attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-1/cancel")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("STALE"))
            .andExpect(jsonPath("$.expectedRevision").value(99))
            .andExpect(jsonPath("$.currentRevision").value(1))
            .andExpect(jsonPath("$.message").value("tramai-workflow-version-conflict"))
    }

    @Configuration(proxyBeanMethods = false)
    open class CancellationControlPlaneConfig {
        @Bean
        open fun recordingWorkflowCancellationControlPlane(): RecordingWorkflowCancellationControlPlane =
            RecordingWorkflowCancellationControlPlane()

        @Bean
        open fun decisionControlPlane(): dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane =
            mock(dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane::class.java)

        @Bean
        open fun resumeControlPlane(): dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane =
            mock(dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane::class.java)

        @Bean
        open fun approvalStore(): dev.tramai.core.approval.ApprovalStore = mock(dev.tramai.core.approval.ApprovalStore::class.java)

        @Bean
        open fun approvalContinuationStore(): dev.tramai.core.approval.ApprovalContinuationStore =
            mock(dev.tramai.core.approval.ApprovalContinuationStore::class.java)
    }

    open class RecordingWorkflowCancellationControlPlane : WorkflowCancellationControlPlane {
        val callCount = AtomicInteger(0)
        var lastCommand: WorkflowCancellationCommand? = null
        var lastAuthority: OperatorAuthority? = null

        fun reset() {
            callCount.set(0)
            lastCommand = null
            lastAuthority = null
        }

        @Suppress("ReturnCount")
        override suspend fun cancel(
            command: WorkflowCancellationCommand,
            authority: OperatorAuthority,
        ): WorkflowCancellationResult {
            callCount.incrementAndGet()
            lastCommand = command
            lastAuthority = authority

            // L2: Capability gate
            if (OperatorCapability.CANCEL_WORKFLOW !in authority.principal.capabilities) {
                return WorkflowCancellationResult.Unauthorized(
                    workflowName = command.workflowName,
                    runId = command.runId,
                    reason = WorkflowUnauthorizedReason.MISSING_CAPABILITY,
                )
            }

            // L3: Target policy
            if (command.workflowName != "deploy-pipeline") {
                return WorkflowCancellationResult.Unauthorized(
                    workflowName = command.workflowName,
                    runId = command.runId,
                    reason = WorkflowUnauthorizedReason.WORKFLOW_POLICY_UNAVAILABLE,
                )
            }

            // Stale check simulation
            if (command.expectedRevision != 1L) {
                return WorkflowCancellationResult.Stale(
                    workflowName = command.workflowName,
                    runId = command.runId,
                    expectedRevision = command.expectedRevision,
                    currentRevision = 1L,
                )
            }

            return WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = command.expectedRevision + 1,
                status = "cancelled",
                cancelledBy = authority.subject,
                cancelledAtEpochMillis = System.currentTimeMillis(),
                alreadyCancelled = false,
            )
        }
    }
}
