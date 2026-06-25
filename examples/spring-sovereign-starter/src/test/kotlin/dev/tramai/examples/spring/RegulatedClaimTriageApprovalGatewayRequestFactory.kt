package dev.tramai.examples.spring

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import java.time.Clock
import java.time.Duration

class RegulatedClaimTriageApprovalGatewayRequestFactory(
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGatewayRequestFactory {

    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val now = clock.instant()
        val claimId = subject.value
        val workflowRun = requireNotNull(workflowRunId) { "regulated-claim-triage-workflow-run-id-required" }
        val approvalId = "approval-gateway-$claimId"
        val correlationId = "corr-$claimId"
        val resumeToken = ResumeToken("resume-token-$claimId")
        val toolCallId = "tool-call-$claimId"
        val toolName = "claim-triage-model"
        val expiresAt = now.plus(Duration.ofMinutes(5))
        val sensitiveArgumentsJson = safeJsonObject(
            "claimId" to claimId,
            "recommendationType" to recommendation.summary,
            "summary" to recommendation.summary,
        )

        val sensitiveArguments = SensitiveToolArguments.of(sensitiveArgumentsJson)
        val computedArgumentsDigest = Sha256ToolArgumentsDigester().digest(sensitiveArguments)

        val operationReference = ResumeOperationReference(
            serviceInterface = "dev.tramai.examples.spring.ClaimTriageWorkflow",
            methodName = "triage",
            jvmMethodDescriptor = "(Ldev/tramai/examples/spring/ClaimTriageInput;Ldev/tramai/examples/spring/RequestedRoute;)Ldev/tramai/examples/spring/ClaimTriageResult;",
            resumeDefinitionDigest = ZERO_DIGEST,
        )

        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = toolCallId,
                        name = toolName,
                        argumentsJson = sensitiveArgumentsJson,
                    ),
                ),
            ),
        )

        val computedDigest = ReplayEnvelopeDigestHelper.compute(operationReference, messages)

        return ApprovalGatewayPersistenceRequest(
            approvalRequest = ApprovalRequest(
                approvalId = approvalId,
                binding = ApprovalBinding(
                    workflowRunId = workflowRun.value,
                    toolName = toolName,
                    argumentsDigest = computedArgumentsDigest,
                    policyVersion = "1.0",
                    workflowDigest = WORKFLOW_DIGEST,
                    approvalTokenDigest = APPROVAL_TOKEN_DIGEST,
                ),
                status = ApprovalStatus.PENDING,
                requestedBy = "triage-system",
                requestedAt = now,
                expiresAt = expiresAt,
                decidedBy = null,
                decidedAt = null,
                decisionComment = null,
                consumedBy = null,
                consumedAt = null,
                version = 0L,
            ),
            continuation = ApprovalContinuation(
                approvalId = approvalId,
                workflowRunId = workflowRun.value,
                correlationId = correlationId,
                toolCallId = toolCallId,
                toolName = toolName,
                argumentsDigest = computedArgumentsDigest,
                policyVersion = "1.0",
                workflowDigest = WORKFLOW_DIGEST,
                status = ApprovalContinuationStatus.PENDING,
                createdAt = now,
                approvalExpiresAt = expiresAt,
                claimedBy = null,
                claimedAt = null,
                completedAt = null,
                version = 0L,
            ),
            sensitiveArguments = sensitiveArguments,
            suspendedInvocationMetadata = SuspendedInvocationMetadata(
                approvalId = approvalId,
                toolCallId = toolCallId,
                toolName = toolName,
                toolCallIndex = 0,
                correlationId = correlationId,
                identity = EngineExecutionIdentity(
                    workflowRunId = workflowRun.value,
                    correlationId = correlationId,
                    workflowDigest = WORKFLOW_DIGEST,
                    policyVersion = "1.0",
                    actorId = "triage-system",
                ),
                securityContext = ExecutionSecurityContext(),
                operationReference = operationReference,
                replayEnvelopeDigest = computedDigest,
                toolReference = ResumeToolReference(
                    toolName = toolName,
                    declarationDigest = ZERO_DIGEST,
                ),
            ),
            replayEnvelope = SensitiveReplayEnvelope.of(messages),
            resumeToken = resumeToken,
        )
    }

    private fun safeJsonObject(vararg pairs: Pair<String, String>): String =
        buildString {
            append('{')
            pairs.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append('"')
                append(jsonEscape(key))
                append("\":\"")
                append(jsonEscape(value))
                append('"')
            }
            append('}')
        }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        private val ZERO_DIGEST = Sha256Digest.of("sha256:${"0".repeat(64)}")
        private val WORKFLOW_DIGEST = Sha256Digest.of("sha256:${"1".repeat(64)}")
        private val APPROVAL_TOKEN_DIGEST = Sha256Digest.of("sha256:${"3".repeat(64)}")
    }
}
