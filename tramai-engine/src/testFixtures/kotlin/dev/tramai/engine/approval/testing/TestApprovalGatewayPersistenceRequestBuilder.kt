package dev.tramai.engine.approval.testing

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
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import java.time.Clock
import java.time.Instant

/**
 * Builder for constructing internally consistent [ApprovalGatewayPersistenceRequest]
 * objects in tests and examples.
 *
 * Usage:
 * ```kotlin
 * TestApprovalGatewayPersistenceRequestBuilder(clock)
 *     .subject(ApprovalSubject("claim-1"))
 *     .workflowRunId(WorkflowRunId("run-1"))
 *     .toolName("claim-triage-model")
 *     .operationReference(
 *         serviceInterface = "com.example.MyWorkflow",
 *         methodName = "handle",
 *         jvmMethodDescriptor = "(Lcom/example/Input;)Ldev/tramai/core/workflow/SovereignWorkflowResult;",
 *     )
 *     .sensitiveArgumentsJson("""{"claimId":"claim-1"}""")
 *     .build()
 * ```
 *
 * This is **test/example support only**. Not production API.
 */
class TestApprovalGatewayPersistenceRequestBuilder(
    private val clock: Clock,
    private val defaults: TestApprovalGatewayRequestDefaults = TestApprovalGatewayRequestDefaults(),
) {
    private var subject: ApprovalSubject? = null
    private var recommendation: ApprovalRecommendation? = null
    private var requiredRole: ApproverRole? = null
    private var workflowRunId: WorkflowRunId? = null
    private var toolName: String = defaults.toolName
    private var requestedBy: String = defaults.requestedBy
    private var policyVersion: String = defaults.policyVersion
    private var workflowDigest: Sha256Digest = defaults.workflowDigest
    private var resumeDefinitionDigest: Sha256Digest = defaults.resumeDefinitionDigest
    private var approvalTokenDigest: Sha256Digest = defaults.approvalTokenDigest
    private var sensitiveArgumentsJson: String? = null
    private var operationReference: ResumeOperationReference? = null
    private var customResumeToken: ResumeToken? = null
    private var customApprovalId: String? = null
    private var customCorrelationId: String? = null
    private var customToolCallId: String? = null

    fun subject(subject: ApprovalSubject) = apply { this.subject = subject }
    fun recommendation(recommendation: ApprovalRecommendation) = apply { this.recommendation = recommendation }
    fun requiredRole(requiredRole: ApproverRole) = apply { this.requiredRole = requiredRole }
    fun workflowRunId(workflowRunId: WorkflowRunId) = apply { this.workflowRunId = workflowRunId }
    fun toolName(toolName: String) = apply { this.toolName = toolName }
    fun requestedBy(requestedBy: String) = apply { this.requestedBy = requestedBy }
    fun policyVersion(policyVersion: String) = apply { this.policyVersion = policyVersion }
    fun workflowDigest(workflowDigest: Sha256Digest) = apply { this.workflowDigest = workflowDigest }
    fun resumeDefinitionDigest(resumeDefinitionDigest: Sha256Digest) = apply { this.resumeDefinitionDigest = resumeDefinitionDigest }
    fun approvalTokenDigest(approvalTokenDigest: Sha256Digest) = apply { this.approvalTokenDigest = approvalTokenDigest }
    fun sensitiveArgumentsJson(json: String) = apply { this.sensitiveArgumentsJson = json }
    fun resumeToken(resumeToken: ResumeToken) = apply { this.customResumeToken = resumeToken }
    fun approvalId(approvalId: String) = apply { this.customApprovalId = approvalId }
    fun correlationId(correlationId: String) = apply { this.customCorrelationId = correlationId }
    fun toolCallId(toolCallId: String) = apply { this.customToolCallId = toolCallId }

    /**
     * Convenience helper for building a JSON string from key-value pairs.
     * Values are JSON-escaped automatically.
     */
    fun sensitiveArguments(vararg pairs: Pair<String, String>) = apply {
        this.sensitiveArgumentsJson = buildJsonObject(*pairs)
    }

    fun operationReference(
        serviceInterface: String,
        methodName: String,
        jvmMethodDescriptor: String,
        resumeDefinitionDigest: Sha256Digest = this.resumeDefinitionDigest,
    ) = apply {
        this.operationReference = ResumeOperationReference(
            serviceInterface = serviceInterface,
            methodName = methodName,
            jvmMethodDescriptor = jvmMethodDescriptor,
            resumeDefinitionDigest = resumeDefinitionDigest,
        )
    }

    fun build(): ApprovalGatewayPersistenceRequest {
        val subj = requireNotNull(subject) { "subject is required" }
        val rec = requireNotNull(recommendation) { "recommendation is required" }
        val role = requireNotNull(requiredRole) { "requiredRole is required" }
        val runId = requireNotNull(workflowRunId) { "workflowRunId is required" }

        val now: Instant = clock.instant()
        val claimId = subj.value
        val approvalId = customApprovalId ?: "test-approval-$claimId-${now.toEpochMilli()}"
        val correlationId = customCorrelationId ?: "test-corr-$claimId"
        val toolCallId = customToolCallId ?: "test-tc-$claimId"
        val resumeToken = customResumeToken ?: ResumeToken("test-resume-token-$claimId")
        val expiresAt = now.plus(defaults.ttl)

        val argsJson = sensitiveArgumentsJson
            ?: """{"claimId":"$claimId","type":"${rec.type}"}"""
        val sensitiveArguments = SensitiveToolArguments.of(argsJson)
        val computedArgumentsDigest = Sha256ToolArgumentsDigester().digest(sensitiveArguments)

        val opRef = operationReference ?: ResumeOperationReference(
            serviceInterface = "dev.tramai.test.TestWorkflow",
            methodName = "execute",
            jvmMethodDescriptor = "()V",
            resumeDefinitionDigest = resumeDefinitionDigest,
        )

        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = toolCallId,
                        name = toolName,
                        argumentsJson = argsJson,
                    ),
                ),
            ),
        )

        val computedDigest = ReplayEnvelopeDigestHelper.compute(opRef, messages)

        return ApprovalGatewayPersistenceRequest(
            approvalRequest = ApprovalRequest(
                approvalId = approvalId,
                binding = ApprovalBinding(
                    workflowRunId = runId.value,
                    toolName = toolName,
                    argumentsDigest = computedArgumentsDigest,
                    policyVersion = policyVersion,
                    workflowDigest = workflowDigest,
                    approvalTokenDigest = approvalTokenDigest,
                ),
                status = ApprovalStatus.PENDING,
                requestedBy = requestedBy,
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
                workflowRunId = runId.value,
                correlationId = correlationId,
                toolCallId = toolCallId,
                toolName = toolName,
                argumentsDigest = computedArgumentsDigest,
                policyVersion = policyVersion,
                workflowDigest = workflowDigest,
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
                    workflowRunId = runId.value,
                    correlationId = correlationId,
                    workflowDigest = workflowDigest,
                    policyVersion = policyVersion,
                    actorId = requestedBy,
                ),
                securityContext = ExecutionSecurityContext(),
                operationReference = opRef,
                replayEnvelopeDigest = computedDigest,
                toolReference = ResumeToolReference(
                    toolName = toolName,
                    declarationDigest = resumeDefinitionDigest,
                ),
            ),
            replayEnvelope = SensitiveReplayEnvelope.of(messages),
            resumeToken = resumeToken,
        )
    }

    private fun buildJsonObject(vararg pairs: Pair<String, String>): String =
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
}
