package dev.tramai.spring.sovereign.ops.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.WorkflowCancellationControlPlane
import dev.tramai.spring.sovereign.ops.WorkflowCancellationResult
import dev.tramai.spring.sovereign.ops.WorkflowUnauthorizedReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class WorkflowCancellationControlPlaneControllerTest {
    private val controlPlane: WorkflowCancellationControlPlane = mock(WorkflowCancellationControlPlane::class.java)
    private val mapper: ObjectMapper = ObjectMapper()
    private lateinit var mockMvc: MockMvc

    private val fixtureAuthority =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "ops-lead",
                issuer = "https://idp.example.com",
                authenticatedAt = Instant.parse("2026-03-01T12:00:00Z"),
                capabilities = setOf(OperatorCapability.CANCEL_WORKFLOW),
                approverRoles = setOf(ApproverRole("ops-admin")),
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

    @BeforeEach
    fun setUp() {
        val controller = WorkflowCancellationControlPlaneController(controlPlane)
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(operatorAuthorityResolver())
                .build()
    }

    @Test
    fun `cancel succeeds and returns 200 with WorkflowCancellationResponse`() {
        val command =
            WorkflowCancellationCommand(
                workflowName = "deploy-pipeline",
                runId = "run-101",
                expectedRevision = 3L,
                reason = "Cluster drain initiated",
                correlationId = "corr-101",
            )
        val result =
            WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = 4L,
                status = "cancelled",
                cancelledBy = fixtureAuthority.subject,
                cancelledAtEpochMillis = 1709294400000L,
                alreadyCancelled = false,
            )

        runBlocking {
            `when`(controlPlane.cancel(command, fixtureAuthority)).thenReturn(result)
        }

        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 3L,
                reason = "Cluster drain initiated",
                correlationId = "corr-101",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-101/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
            .andExpect(jsonPath("$.workflowName").value("deploy-pipeline"))
            .andExpect(jsonPath("$.runId").value("run-101"))
            .andExpect(jsonPath("$.revision").value(4))
            .andExpect(jsonPath("$.cancelledBy").value("ops-lead"))
            .andExpect(jsonPath("$.alreadyCancelled").value(false))
    }

    @Test
    fun `repeated cancellation returns 200 with alreadyCancelled true`() {
        val command =
            WorkflowCancellationCommand(
                workflowName = "deploy-pipeline",
                runId = "run-102",
                expectedRevision = 4L,
                reason = "Repeat cancel",
            )
        val result =
            WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = 4L,
                status = "cancelled",
                cancelledBy = "previous-actor",
                cancelledAtEpochMillis = 1709294400000L,
                alreadyCancelled = true,
            )

        runBlocking {
            `when`(controlPlane.cancel(command, fixtureAuthority)).thenReturn(result)
        }

        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 4L,
                reason = "Repeat cancel",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-102/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("cancelled"))
            .andExpect(jsonPath("$.alreadyCancelled").value(true))
            .andExpect(jsonPath("$.cancelledBy").value("previous-actor"))
    }

    @Test
    fun `stale expectedRevision returns 409 Conflict with Stale details`() {
        val command =
            WorkflowCancellationCommand(
                workflowName = "deploy-pipeline",
                runId = "run-103",
                expectedRevision = 1L,
                reason = "Stale cancel",
            )
        val result =
            WorkflowCancellationResult.Stale(
                workflowName = command.workflowName,
                runId = command.runId,
                expectedRevision = 1L,
                currentRevision = 5L,
            )

        runBlocking {
            `when`(controlPlane.cancel(command, fixtureAuthority)).thenReturn(result)
        }

        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 1L,
                reason = "Stale cancel",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-103/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("STALE"))
            .andExpect(jsonPath("$.expectedRevision").value(1))
            .andExpect(jsonPath("$.currentRevision").value(5))
            .andExpect(jsonPath("$.message").value("tramai-workflow-version-conflict"))
    }

    @Test
    fun `target not found returns 404 Not Found`() {
        val command =
            WorkflowCancellationCommand(
                workflowName = "unknown-pipeline",
                runId = "missing-run",
                expectedRevision = 0L,
                reason = "Cancel missing",
            )
        val result =
            WorkflowCancellationResult.NotFound(
                workflowName = command.workflowName,
                runId = command.runId,
            )

        runBlocking {
            `when`(controlPlane.cancel(command, fixtureAuthority)).thenReturn(result)
        }

        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 0L,
                reason = "Cancel missing",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/unknown-pipeline/runs/missing-run/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("tramai-workflow-not-found"))
    }

    @Test
    fun `unauthorized refusal returns 403 Forbidden with reason wireName`() {
        val command =
            WorkflowCancellationCommand(
                workflowName = "deploy-pipeline",
                runId = "run-104",
                expectedRevision = 2L,
                reason = "Unauthorized cancel attempt",
            )
        val result =
            WorkflowCancellationResult.Unauthorized(
                workflowName = command.workflowName,
                runId = command.runId,
                reason = WorkflowUnauthorizedReason.ROLE_NOT_PERMITTED,
            )

        runBlocking {
            `when`(controlPlane.cancel(command, fixtureAuthority)).thenReturn(result)
        }

        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 2L,
                reason = "Unauthorized cancel attempt",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-104/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.status").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").value("role-not-permitted"))
    }

    @Test
    fun `missing expectedRevision returns 400 Bad Request`() {
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = null,
                reason = "Valid reason",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-105/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("tramai-cancellation-invalid-expected-revision"))
    }

    @Test
    fun `negative expectedRevision returns 400 Bad Request`() {
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = -1L,
                reason = "Valid reason",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-106/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("tramai-cancellation-invalid-expected-revision"))
    }

    @Test
    fun `blank reason returns 400 Bad Request`() {
        val requestBody =
            CancelWorkflowRunRequest(
                expectedRevision = 0L,
                reason = "   ",
            )

        mockMvc
            .perform(
                post("/tramai/sovereign/workflows/deploy-pipeline/runs/run-107/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(requestBody)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("tramai-cancellation-blank-reason"))
    }
}
