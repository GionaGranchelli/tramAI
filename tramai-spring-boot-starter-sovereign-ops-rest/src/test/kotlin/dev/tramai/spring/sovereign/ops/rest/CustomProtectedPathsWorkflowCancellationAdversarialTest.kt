package dev.tramai.spring.sovereign.ops.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.spring.sovereign.ops.rest.PrivilegedWorkflowCancellationRestAdversarialTest.CancellationControlPlaneConfig
import dev.tramai.spring.sovereign.ops.rest.PrivilegedWorkflowCancellationRestAdversarialTest.RecordingWorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.rest.security.OperatorAuthTestApplication
import dev.tramai.spring.sovereign.ops.rest.security.SharedTestKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64

@SpringBootTest(classes = [OperatorAuthTestApplication::class, CancellationControlPlaneConfig::class])
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.security.enabled=true",
        "tramai.sovereign.ops.security.issuer=https://idp.example/",
        "tramai.sovereign.ops.security.audience=tramai-control-plane",
        "tramai.sovereign.ops.security.jwk-set-uri=https://idp.example/jwks",
        "tramai.sovereign.ops.security.role-claim=roles",
        "tramai.sovereign.ops.security.role-mappings.workflow-canceller=cancel-workflow",
        "tramai.sovereign.ops.security.approver-role-mappings.workflow-canceller=deploy-admin",
        "tramai.sovereign.ops.security.approval-policies.v1=deploy-admin",
        "tramai.sovereign.ops.security.workflow-cancellation-policies.deploy-pipeline=deploy-admin",
        "tramai.sovereign.ops.rest-control-plane-enabled=true",
        "tramai.sovereign.ops.security.protected-paths[0]=/tramai/sovereign/approvals/**",
        "tramai.sovereign.ops.security.protected-paths[1]=/tramai/sovereign/workflows/probe-wf-*/runs/probe-run-*/cancel",
    ],
)
@Suppress("MaxLineLength")
class CustomProtectedPathsWorkflowCancellationAdversarialTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun operatorSecurityProperties(registry: DynamicPropertyRegistry) {
            val key = Base64.getEncoder().encodeToString(SharedTestKeys.keys.trustedPair.public.encoded)
            registry.add("tramai.sovereign.ops.security.public-key") { key }
        }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var recordingControlPlane: RecordingWorkflowCancellationControlPlane

    @Test
    fun `unauthenticated cancellation request to arbitrary workflow is rejected with 401`() {
        val body = CancelWorkflowRunRequest(expectedRevision = 1L, reason = "Unauthenticated probe")

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/payments/runs/run-123/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ObjectMapper().writeValueAsString(body)),
            ).andExpect(status().isUnauthorized)

        assertThat(recordingControlPlane.callCount.get()).isZero()
    }
}
