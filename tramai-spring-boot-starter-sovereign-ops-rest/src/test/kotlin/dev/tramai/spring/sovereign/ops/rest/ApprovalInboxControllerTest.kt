package dev.tramai.spring.sovereign.ops.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxPage
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQuery
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxWorkItem
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ApprovalInboxController::class)
@TestPropertySource(
    properties = [
        "tramai.sovereign.ops.rest-control-plane-enabled=true",
        "tramai.sovereign.ops.rest.base-path=/tramai/sovereign/approvals",
    ],
)
class ApprovalInboxControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @org.mockito.Mock
    private lateinit var queryService: ApprovalInboxQueryService

    @org.junit.jupiter.api.BeforeEach
    fun setupMockMvc() {
        org.mockito.MockitoAnnotations.openMocks(this)
        // ApprovalInboxController carries @ConditionalOnProperty + @ConditionalOnBean,
        // which are evaluated before @MockBean definitions are registered — the bean
        // never appears in a @WebMvcTest slice. Test the controller standalone with
        // plain mocks instead; assertions are identical.
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(ApprovalInboxController(queryService))
            .build()
    }

    private val now = Instant.parse("2026-06-26T10:00:00Z")

    @Test
    fun `GET approvals returns inbox items`() { runBlocking {
        doReturn(inboxPage()).`when`(queryService).search(ApprovalInboxQuery())

        mockMvc.perform(get("/tramai/sovereign/approvals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].approvalId").value("approval-1"))
            .andExpect(jsonPath("$.items[0].workflowRunId").value("wf-1"))
            .andExpect(jsonPath("$.items[0].toolName").value("claim-payout"))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.items[0].requiredRole").value("medical-reviewer"))
            .andExpect(jsonPath("$.items[0].riskLevel").value("HIGH"))
            .andExpect(jsonPath("$.items[0].subjectType").value("claim"))
            .andExpect(jsonPath("$.items[0].subjectId").value("claim-1"))
            .andExpect(jsonPath("$.items[0].recommendationType").value("claim-payout"))
            .andExpect(jsonPath("$.items[0].continuationStatus").value("PENDING"))
            .andExpect(jsonPath("$.items[0].version").value(0))
    }
    }

    @Test
    fun `GET approvals response does not contain sensitive fields`() { runBlocking {
        doReturn(inboxPage()).`when`(queryService).search(ApprovalInboxQuery())

        mockMvc.perform(get("/tramai/sovereign/approvals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].resumeToken").doesNotExist())
            .andExpect(jsonPath("$.items[0].approvalTokenDigest").doesNotExist())
            .andExpect(jsonPath("$.items[0].argumentsDigest").doesNotExist())
            .andExpect(jsonPath("$.items[0].replayEnvelope").doesNotExist())
            .andExpect(jsonPath("$.items[0].decisionComment").doesNotExist())
    }
    }

    @Test
    fun `GET work-item returns safe projection`() { runBlocking {
        doReturn(inboxItem()).`when`(queryService).getWorkItem(ApprovalId("approval-1"))

        mockMvc.perform(get("/tramai/sovereign/approvals/approval-1/work-item"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvalId").value("approval-1"))
            .andExpect(jsonPath("$.toolName").value("claim-payout"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.continuationStatus").value("PENDING"))
            .andExpect(jsonPath("$.version").value(0))
    }
    }

    @Test
    fun `GET work-item returns 404 for missing approval`() { runBlocking {
        doReturn(null).`when`(queryService).getWorkItem(ApprovalId("approval-missing"))

        mockMvc.perform(get("/tramai/sovereign/approvals/approval-missing/work-item"))
            .andExpect(status().isNotFound)
    }
    }

    @Test
    fun `GET approvals rejects invalid limit with 400`() { runBlocking {
        mockMvc.perform(get("/tramai/sovereign/approvals?limit=0"))
            .andExpect(status().isBadRequest)
    }
    }

    @Test
    fun `GET approvals rejects limit over 100 with 400`() { runBlocking {
        mockMvc.perform(get("/tramai/sovereign/approvals?limit=101"))
            .andExpect(status().isBadRequest)
    }
    }

    @Test
    fun `GET approvals with requiredRole filter returns matching approvals`() { runBlocking {
        doReturn(inboxPage()).`when`(queryService).search(
            ApprovalInboxQuery(requiredRole = ApproverRole("medical-reviewer")),
        )

        mockMvc.perform(get("/tramai/sovereign/approvals?requiredRole=medical-reviewer"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].requiredRole").value("medical-reviewer"))
    }
    }

    @Test
    fun `GET approvals with requiredRole filter returns 400 for invalid role`() { runBlocking {
        mockMvc.perform(get("/tramai/sovereign/approvals?requiredRole="))
            .andExpect(status().isBadRequest)
    }
    }

    private fun inboxPage(): ApprovalInboxPage = ApprovalInboxPage(
        items = listOf(inboxItem()),
        nextCursor = null,
    )

    private fun inboxItem(): ApprovalInboxWorkItem = ApprovalInboxWorkItem(
        approvalId = ApprovalId("approval-1"),
        workflowRunId = "wf-1",
        toolName = "claim-payout",
        status = ApprovalStatus.PENDING,
        requestedBy = "claim-triage-workflow",
        requestedAt = now.minusSeconds(300),
        expiresAt = now.plusSeconds(300),
        requiredRole = ApproverRole("medical-reviewer"),
        riskLevel = "HIGH",
        subjectType = "claim",
        subjectId = "claim-1",
        recommendationType = "claim-payout",
        continuationStatus = ApprovalContinuationStatus.PENDING,
        version = 0,
    )
}
