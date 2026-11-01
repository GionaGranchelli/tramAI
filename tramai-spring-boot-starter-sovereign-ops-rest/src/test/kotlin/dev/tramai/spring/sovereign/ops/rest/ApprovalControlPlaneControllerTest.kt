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
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.OperatorAuthority
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
import java.time.Instant

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
        mockMvc =
            org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(
                    ApprovalControlPlaneController(
                        decisionControlPlane = decisionControlPlane,
                        resumeControlPlane = resumeControlPlane,
                        approvalStore = approvalStore,
                        approvalContinuationStore = approvalContinuationStore,
                    ),
                ).setCustomArgumentResolvers(operatorAuthorityResolver())
                .build()
    }

    /**
     * The authority the controller must receive. In production it comes from
     * `OperatorPrincipalResolver.issueCurrentAuthority()` after JWT verification; here it is a fixture, and
     * the only thing the test asserts about it is that the controller passes it through untouched.
     */
    private val fixtureAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "medical-ops-reviewer",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-06-26T08:00:00Z"),
                capabilities = setOf(OperatorCapability.DECIDE_APPROVAL),
                approverRoles = setOf(ApproverRole("medical-reviewer")),
            ),
        )

    private fun operatorAuthorityResolver() =
        object : org.springframework.web.method.support.HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: org.springframework.core.MethodParameter) =
                parameter.parameterType == OperatorAuthority::class.java

            override fun resolveArgument(
                parameter: org.springframework.core.MethodParameter,
                mavContainer: org.springframework.web.method.support.ModelAndViewContainer?,
                webRequest: org.springframework.web.context.request.NativeWebRequest,
                binderFactory: org.springframework.web.bind.support.WebDataBinderFactory?,
            ): Any = fixtureAuthority
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
    fun `approve endpoint returns 200 for Approved`() {
        runBlocking {
            val expectedCommand =
                approveCommand(
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
            ).`when`(decisionControlPlane).approve(expectedCommand, fixtureAuthority)

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
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

            verify(decisionControlPlane).approve(expectedCommand, fixtureAuthority)
        }
    }

    @Test
    fun `a refusal-recording failure returns no decision`() {
        runBlocking {
            val expectedCommand =
                approveCommand(
                    approvalId = "approval-1",
                    comment = "Approved",
                    expectedVersion = 0L,
                    correlationId = "claim-1",
                )
            org.mockito.Mockito
                .doThrow(IllegalStateException("tramai-sovereign-ops-outbox-dispatchable-append-failed"))
                .`when`(decisionControlPlane)
                .approve(expectedCommand, fixtureAuthority)

            // The container turns this into a 5xx; what matters here is that no decision -- approved,
            // denied or refused -- is rendered. A refusal that could not be recorded must never look
            // like an answer.
            org.assertj.core.api.Assertions
                .assertThatThrownBy {
                    mockMvc.perform(
                        post("/tramai/sovereign/approvals/approval-1/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                objectMapper.writeValueAsString(
                                    ApproveDenyRequest(
                                        comment = "Approved",
                                        expectedVersion = 0L,
                                        correlationId = "claim-1",
                                    ),
                                ),
                            ),
                    )
                }.hasRootCauseMessage("tramai-sovereign-ops-outbox-dispatchable-append-failed")

            verify(decisionControlPlane).approve(expectedCommand, fixtureAuthority)
        }
    }

    @Test
    fun `deny endpoint returns 200 for Denied`() {
        runBlocking {
            doReturn(
                ApprovalDecisionResult.Denied(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "medical-ops-reviewer",
                    decidedAt = now,
                    version = 1L,
                ),
            ).`when`(decisionControlPlane).deny(
                approveCommand(approvalId = "approval-1", comment = "Denied"),
                fixtureAuthority,
            )

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/deny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    expectedVersion = 0L,
                                    comment = "Denied",
                                ),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.approvalId").value("approval-1"))
        }
    }

    @Test
    fun `resume endpoint returns 200 for Resumed`() {
        runBlocking {
            doReturn(
                ApprovalResumeResult.Resumed(
                    approvalId = ApprovalId("approval-1"),
                    resumedBy = "medical-ops-reviewer",
                    result = "CLAIM_PAYOUT_COMPLETED",
                ),
            ).`when`(resumeControlPlane).resume(
                resumeCommand("approval-1", expectedApprovalVersion = 1L, expectedContinuationVersion = 0L),
            )

            mockMvc
                .perform(
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
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.approvalId").value("approval-1"))
                .andExpect(jsonPath("$.actorId").value("medical-ops-reviewer"))
        }
    }

    @Test
    fun `missing approval returns 404 for getApproval`() {
        runBlocking {
            doReturn(null).`when`(approvalStore).get("approval-404")

            mockMvc
                .perform(get("/tramai/sovereign/approvals/approval-404"))
                .andExpect(status().isNotFound)
                .andExpect(content().string(""))
        }
    }

    @Test
    fun `conflict maps to 409 for approve`() {
        runBlocking {
            doReturn(
                ApprovalDecisionResult.Conflict(
                    approvalId = ApprovalId("approval-1"),
                    reason = "approval-version-conflict",
                ),
            ).`when`(decisionControlPlane).approve(
                approveCommand("approval-1"),
                fixtureAuthority,
            )

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    expectedVersion = 0L,
                                ),
                            ),
                        ),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("approval-version-conflict"))
        }
    }

    @Test
    fun `deny not found maps to 404`() {
        runBlocking {
            doReturn(
                ApprovalDecisionResult.NotFound(ApprovalId("approval-404")),
            ).`when`(decisionControlPlane).deny(
                approveCommand("approval-404"),
                fixtureAuthority,
            )

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-404/deny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    expectedVersion = 0L,
                                ),
                            ),
                        ),
                ).andExpect(status().isNotFound)
                .andExpect(content().string(""))
        }
    }

    @Test
    fun `resume not found maps to 404`() {
        runBlocking {
            doReturn(
                ApprovalResumeResult.NotFound(ApprovalId("approval-404")),
            ).`when`(resumeControlPlane).resume(
                resumeCommand("approval-404"),
            )

            mockMvc
                .perform(
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
                ).andExpect(status().isNotFound)
                .andExpect(content().string(""))
        }
    }

    @Test
    fun `resume not approved maps to 409`() {
        runBlocking {
            doReturn(
                ApprovalResumeResult.NotApproved(
                    approvalId = ApprovalId("approval-1"),
                    status = ApprovalStatus.PENDING,
                ),
            ).`when`(resumeControlPlane).resume(
                resumeCommand("approval-1"),
            )

            mockMvc
                .perform(
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
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.status").value("NOT_APPROVED"))
                .andExpect(jsonPath("$.message").value("approval-status-PENDING"))
        }
    }

    @Test
    fun `AlreadyCompleted returns 200`() {
        runBlocking {
            doReturn(
                ApprovalResumeResult.AlreadyCompleted(ApprovalId("approval-1")),
            ).`when`(resumeControlPlane).resume(
                resumeCommand("approval-1"),
            )

            mockMvc
                .perform(
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
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ALREADY_COMPLETED"))
        }
    }

    @Test
    fun `AlreadyApproved returns 200`() {
        runBlocking {
            doReturn(
                ApprovalDecisionResult.AlreadyApproved(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "existing-reviewer",
                    decidedAt = now.minusSeconds(60),
                ),
            ).`when`(decisionControlPlane).approve(
                approveCommand("approval-1"),
                fixtureAuthority,
            )

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    expectedVersion = 0L,
                                ),
                            ),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ALREADY_APPROVED"))
                .andExpect(jsonPath("$.actorId").value("existing-reviewer"))
        }
    }

    @Test
    fun `get approval returns 200 with approval and continuation status`() {
        runBlocking {
            doReturn(approvalRequest("approval-1")).`when`(approvalStore).get("approval-1")
            doReturn(approvalContinuation("approval-1")).`when`(approvalContinuationStore).get("approval-1")

            mockMvc
                .perform(get("/tramai/sovereign/approvals/approval-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.approvalId").value("approval-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.requestedBy").value("triage-system"))
                .andExpect(jsonPath("$.continuationStatus").value("PENDING"))
        }
    }

    @Test
    fun `resume Failed returns 500 with safe message not internal exception`() {
        runBlocking {
            doReturn(
                ApprovalResumeResult.Failed(
                    approvalId = ApprovalId("approval-1"),
                    reason = "java.lang.RuntimeException: something sensitive",
                ),
            ).`when`(resumeControlPlane).resume(
                resumeCommand("approval-1"),
            )

            mockMvc
                .perform(
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
                ).andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("approval-resume-failed"))
        }
    }

    @Test
    fun `Expired maps to 409`() {
        runBlocking {
            doReturn(
                ApprovalDecisionResult.Expired(
                    approvalId = ApprovalId("approval-1"),
                    expiredAt = now,
                ),
            ).`when`(decisionControlPlane).approve(
                approveCommand("approval-1"),
                fixtureAuthority,
            )

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    expectedVersion = 0L,
                                ),
                            ),
                        ),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
        }
    }

    // -- test helpers --

    private fun approvalRequest(approvalId: String): ApprovalRequest =
        ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
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

    private fun approvalContinuation(approvalId: String): ApprovalContinuation =
        ApprovalContinuation(
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

    @Test
    fun `a decision without an expected version is rejected before the control plane`() {
        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/approval-1/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"actorId":"medical-ops-reviewer","actorRole":"medical-reviewer","comment":"no version"}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_PRECONDITION"))
            .andExpect(jsonPath("$.message").value("tramai-approval-expected-version-required"))

        // F2: the request must not become a control-plane command at all.
        org.mockito.Mockito.verifyNoInteractions(decisionControlPlane)
    }

    @Test
    fun `an explicit null expected version is rejected rather than substituted`() {
        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/approval-1/deny")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"actorId":"medical-ops-reviewer","actorRole":"medical-reviewer","expectedVersion":null}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_PRECONDITION"))
            .andExpect(jsonPath("$.message").value("tramai-approval-expected-version-required"))

        org.mockito.Mockito.verifyNoInteractions(decisionControlPlane)
    }

    @Test
    fun `a negative expected version is rejected before the control plane`() {
        mockMvc
            .perform(
                post("/tramai/sovereign/approvals/approval-1/approve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"actorId":"medical-ops-reviewer","actorRole":"medical-reviewer","expectedVersion":-1}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("tramai-approval-invalid-expected-version"))

        org.mockito.Mockito.verifyNoInteractions(decisionControlPlane)
    }

    @Test
    fun `a stale outcome is reported as 409 with both versions and no success status`() {
        runBlocking {
            val expectedCommand = approveCommand("approval-1", comment = "stale", expectedVersion = 0L)
            doReturn(
                ApprovalDecisionResult.Stale(
                    approvalId = ApprovalId("approval-1"),
                    expectedVersion = 0L,
                    currentVersion = 1L,
                ),
            ).`when`(decisionControlPlane).approve(expectedCommand, fixtureAuthority)

            mockMvc
                .perform(
                    post("/tramai/sovereign/approvals/approval-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                ApproveDenyRequest(
                                    comment = "stale",
                                    expectedVersion = 0L,
                                ),
                            ),
                        ),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.version").doesNotExist())

            verify(decisionControlPlane).approve(expectedCommand, fixtureAuthority)
        }
    }

    private fun digest(seed: String): Sha256Digest = Sha256Digest.of("sha256:${seed.repeat(64).take(64)}")

    private fun approveCommand(
        approvalId: String,
        comment: String? = null,
        expectedVersion: Long = 0L,
        correlationId: String? = null,
    ): ApprovalDecisionCommand =
        ApprovalDecisionCommand(
            approvalId = ApprovalId(approvalId),
            expectedVersion = expectedVersion,
            comment = comment,
            correlationId = correlationId,
        )

    private fun resumeCommand(
        approvalId: String,
        expectedApprovalVersion: Long? = null,
        expectedContinuationVersion: Long? = null,
    ): ApprovalResumeCommand =
        ApprovalResumeCommand(
            approvalId = ApprovalId(approvalId),
            resumeToken = ResumeToken("resume-token-1"),
            resumedBy = "medical-ops-reviewer",
            expectedApprovalVersion = expectedApprovalVersion,
            expectedContinuationVersion = expectedContinuationVersion,
        )
}
