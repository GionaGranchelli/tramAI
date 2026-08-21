package dev.tramai.spring.sovereign.ops.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ApprovalControlPlaneController::class)
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.rest-control-plane-enabled=true",
        "tramai.sovereign.ops.rest.base-path=/tramai/sovereign/approvals",
    ],
)
class ApprovalControlPlaneControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @org.junit.jupiter.api.BeforeEach
    fun setupMockMvc() {
        org.mockito.MockitoAnnotations.openMocks(this)
        // The controller carries @ConditionalOnProperty + @ConditionalOnBean, which
        // are evaluated before @MockBean definitions are registered — the bean never
        // appears in a @WebMvcTest slice. Test the controller standalone with plain
        // mocks instead; assertions are identical.
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(
                ApprovalControlPlaneController(
                    decisionControlPlane = decisionControlPlane,
                    resumeControlPlane = resumeControlPlane,
                    approvalStore = approvalStore,
                    approvalContinuationStore = approvalContinuationStore,
                ),
            )
            .build()
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @org.mockito.Mock
    private lateinit var decisionControlPlane: ApprovalDecisionControlPlane

    @org.mockito.Mock
    private lateinit var resumeControlPlane: ApprovalResumeControlPlane

    @org.mockito.Mock
    private lateinit var approvalStore: ApprovalStore

    @org.mockito.Mock
    private lateinit var approvalContinuationStore: ApprovalContinuationStore

    private val now: Instant = Instant.parse("2026-06-26T08:00:00Z")

    @Test
    fun `approve endpoint returns 200 for Approved`() { runBlocking {
        val expectedCommand = approveCommand(
            approvalId = "approval-1",
            comment = "Approved",
            expectedVersion = 0L,
            correlationId = "claim-1",
        )
        doReturn(
            ApprovalDecisionResult.Approved(
                approvalId = ApprovalId("approval-1"),
                decidedBy = "medical-ops-reviewer",
                decidedAt = now,
                version = 1L,
            ),
        ).`when`(decisionControlPlane).approve(expectedCommand)

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                            comment = "Approved",
                            expectedVersion = 0L,
                            correlationId = "claim-1",
                        ),
                    ),
                ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvalId").value("approval-1"))
            .andExpect(jsonPath("$.actorId").value("medical-ops-reviewer"))
            .andExpect(jsonPath("$.version").value(1))

        verify(decisionControlPlane).approve(expectedCommand)
    }
    }

    @Test
    fun `deny endpoint returns 200 for Denied`() { runBlocking {
        doReturn(
            ApprovalDecisionResult.Denied(
                approvalId = ApprovalId("approval-1"),
                decidedBy = "medical-ops-reviewer",
                decidedAt = now,
                version = 1L,
            ),
        ).`when`(decisionControlPlane).deny(
            approveCommand(approvalId = "approval-1", comment = "Denied"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                            comment = "Denied",
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DENIED"))
            .andExpect(jsonPath("$.approvalId").value("approval-1"))
    }
    }

    @Test
    fun `resume endpoint returns 200 for Resumed`() { runBlocking {
        doReturn(
            ApprovalResumeResult.Resumed(
                approvalId = ApprovalId("approval-1"),
                resumedBy = "medical-ops-reviewer",
                result = "CLAIM_PAYOUT_COMPLETED",
            ),
        ).`when`(resumeControlPlane).resume(
            resumeCommand("approval-1", expectedApprovalVersion = 1L, expectedContinuationVersion = 0L),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResumeRequest(
                            resumeToken = "resume-token-1",
                            resumedBy = "medical-ops-reviewer",
                            expectedApprovalVersion = 1L,
                            expectedContinuationVersion = 0L,
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESUMED"))
            .andExpect(jsonPath("$.approvalId").value("approval-1"))
            .andExpect(jsonPath("$.actorId").value("medical-ops-reviewer"))
    }
    }

    @Test
    fun `missing approval returns 404 for getApproval`() { runBlocking {
        doReturn(null).`when`(approvalStore).get("approval-404")

        mockMvc.perform(get("/tramai/sovereign/approvals/approval-404"))
            .andExpect(status().isNotFound)
            .andExpect(content().string(""))
    }
    }

    @Test
    fun `conflict maps to 409 for approve`() { runBlocking {
        doReturn(
            ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId("approval-1"),
                reason = "approval-version-conflict",
            ),
        ).`when`(decisionControlPlane).approve(
            approveCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("approval-version-conflict"))
    }
    }

    @Test
    fun `deny not found maps to 404`() { runBlocking {
        doReturn(
            ApprovalDecisionResult.NotFound(ApprovalId("approval-404")),
        ).`when`(decisionControlPlane).deny(
            approveCommand("approval-404"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-404/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(content().string(""))
    }
    }

    @Test
    fun `resume not found maps to 404`() { runBlocking {
        doReturn(
            ApprovalResumeResult.NotFound(ApprovalId("approval-404")),
        ).`when`(resumeControlPlane).resume(
            resumeCommand("approval-404"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-404/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResumeRequest(
                            resumeToken = "resume-token-1",
                            resumedBy = "medical-ops-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isNotFound)
            .andExpect(content().string(""))
    }
    }

    @Test
    fun `resume not approved maps to 409`() { runBlocking {
        doReturn(
            ApprovalResumeResult.NotApproved(
                approvalId = ApprovalId("approval-1"),
                status = ApprovalStatus.PENDING,
            ),
        ).`when`(resumeControlPlane).resume(
            resumeCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResumeRequest(
                            resumeToken = "resume-token-1",
                            resumedBy = "medical-ops-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("NOT_APPROVED"))
            .andExpect(jsonPath("$.message").value("approval-status-PENDING"))
    }
    }

    @Test
    fun `AlreadyCompleted returns 200`() { runBlocking {
        doReturn(
            ApprovalResumeResult.AlreadyCompleted(ApprovalId("approval-1")),
        ).`when`(resumeControlPlane).resume(
            resumeCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResumeRequest(
                            resumeToken = "resume-token-1",
                            resumedBy = "medical-ops-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ALREADY_COMPLETED"))
    }
    }

    @Test
    fun `AlreadyApproved returns 200`() { runBlocking {
        doReturn(
            ApprovalDecisionResult.AlreadyApproved(
                approvalId = ApprovalId("approval-1"),
                decidedBy = "existing-reviewer",
                decidedAt = now.minusSeconds(60),
            ),
        ).`when`(decisionControlPlane).approve(
            approveCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ALREADY_APPROVED"))
            .andExpect(jsonPath("$.actorId").value("existing-reviewer"))
    }
    }

    @Test
    fun `get approval returns 200 with approval and continuation status`() { runBlocking {
        doReturn(approvalRequest("approval-1")).`when`(approvalStore).get("approval-1")
        doReturn(approvalContinuation("approval-1")).`when`(approvalContinuationStore).get("approval-1")

        mockMvc.perform(get("/tramai/sovereign/approvals/approval-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvalId").value("approval-1"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.requestedBy").value("triage-system"))
            .andExpect(jsonPath("$.continuationStatus").value("PENDING"))
    }
    }

    @Test
    fun `resume Failed returns 500 with safe message not internal exception`() { runBlocking {
        doReturn(
            ApprovalResumeResult.Failed(
                approvalId = ApprovalId("approval-1"),
                reason = "java.lang.RuntimeException: something sensitive",
            ),
        ).`when`(resumeControlPlane).resume(
            resumeCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ResumeRequest(
                            resumeToken = "resume-token-1",
                            resumedBy = "medical-ops-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.message").value("approval-resume-failed"))
    }
    }

    @Test
    fun `Expired maps to 409`() { runBlocking {
        doReturn(
            ApprovalDecisionResult.Expired(
                approvalId = ApprovalId("approval-1"),
                expiredAt = now,
            ),
        ).`when`(decisionControlPlane).approve(
            approveCommand("approval-1"),
        )

        mockMvc.perform(
            post("/tramai/sovereign/approvals/approval-1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ApproveDenyRequest(
                            actorId = "medical-ops-reviewer",
                            actorRole = "medical-reviewer",
                        ),
                    ),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("EXPIRED"))
    }
    }

    // -- test helpers --

    private fun approvalRequest(approvalId: String): ApprovalRequest = ApprovalRequest(
        approvalId = approvalId,
        binding = ApprovalBinding(
            workflowRunId = "wf-$approvalId",
            toolName = "claim-payout",
            argumentsDigest = digest("1"),
            policyVersion = "1.0",
            workflowDigest = digest("2"),
            approvalTokenDigest = digest("3"),
        ),
        status = ApprovalStatus.PENDING,
        requestedBy = "triage-system",
        requestedAt = now.minusSeconds(300),
        expiresAt = now.plusSeconds(3600),
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        consumedBy = null,
        consumedAt = null,
        version = 0L,
    )

    private fun approvalContinuation(approvalId: String): ApprovalContinuation = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = "wf-$approvalId",
        correlationId = "claim-$approvalId",
        toolCallId = "tool-call-1",
        toolName = "claim-payout",
        argumentsDigest = digest("4"),
        policyVersion = "1.0",
        workflowDigest = digest("5"),
        status = ApprovalContinuationStatus.PENDING,
        createdAt = now.minusSeconds(300),
        approvalExpiresAt = now.plusSeconds(3600),
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        version = 0L,
    )

    private fun digest(seed: String): Sha256Digest =
        Sha256Digest.of("sha256:${seed.repeat(64).take(64)}")

    private fun approveCommand(
        approvalId: String,
        comment: String? = null,
        expectedVersion: Long? = null,
        correlationId: String? = null,
    ): ApprovalDecisionCommand = ApprovalDecisionCommand(
        approvalId = ApprovalId(approvalId),
        actorId = "medical-ops-reviewer",
        actorRole = ApproverRole("medical-reviewer"),
        comment = comment,
        expectedVersion = expectedVersion,
        correlationId = correlationId,
    )

    private fun resumeCommand(
        approvalId: String,
        expectedApprovalVersion: Long? = null,
        expectedContinuationVersion: Long? = null,
    ): ApprovalResumeCommand = ApprovalResumeCommand(
        approvalId = ApprovalId(approvalId),
        resumeToken = ResumeToken("resume-token-1"),
        resumedBy = "medical-ops-reviewer",
        expectedApprovalVersion = expectedApprovalVersion,
        expectedContinuationVersion = expectedContinuationVersion,
    )
}
