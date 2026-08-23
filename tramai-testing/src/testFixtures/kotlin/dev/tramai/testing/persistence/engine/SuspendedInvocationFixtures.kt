package dev.tramai.testing.persistence.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.ClassificationSource

/**
 * Shared fixture builders for the [SuspendedInvocationStoreTck].
 *
 * Every record built here satisfies the *intersection* of the three stores'
 * create-time validations, so a single fixture set runs against all of them:
 * the selected tool call lives in the latest assistant message with tool
 * calls, carries the engine's redaction sentinel in its arguments, and the
 * metadata digest is the canonical digest of the envelope messages.
 */
object SuspendedInvocationFixtures {

    const val TOOL_NAME = "sensitive_lookup"
    const val TOOL_CALL_ID = "tool-call-1"
    const val REDACTED_ARGUMENTS = "__redacted_approval_continuation_args__"

    val OPERATION_DIGEST = Sha256Digest.of(
        "sha256:1111111111111111111111111111111111111111111111111111111111111111",
    )

    val WORKFLOW_DIGEST = Sha256Digest.of(
        "sha256:2222222222222222222222222222222222222222222222222222222222222222",
    )

    val OPERATION_REFERENCE = ResumeOperationReference(
        serviceInterface = "dev.tramai.testing.SuspendedTestService",
        methodName = "resume",
        jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
        resumeDefinitionDigest = OPERATION_DIGEST,
    )

    val TOOL_REFERENCE = ResumeToolReference(
        toolName = TOOL_NAME,
        declarationDigest = OPERATION_DIGEST,
    )

    val TOKEN_BUDGET = TokenBudgetSnapshot(
        totalInputTokens = 1_024L,
        totalOutputTokens = 512L,
        totalInputCost = 0.01,
        totalOutputCost = 0.02,
        warnIfExceeded = true,
    )

    val TOOL_SECURITY = ToolSecurityMetadata.legacyPermissive()

    /** The canonical messages used by every fixture: user + assistant-with-tool-call. */
    fun messages(
        toolCallId: String = TOOL_CALL_ID,
        toolName: String = TOOL_NAME,
        toolCallIndex: Int = 0,
        toolCalls: List<ToolCall>? = null,
    ): List<Message> = listOf(
        Message(
            role = MessageRole.USER,
            content = "review-sensitive-prompt workflow=workflow-run-1 correlation=correlation-1",
        ),
        Message(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = toolCalls ?: listOf(
                ToolCall(
                    id = toolCallId,
                    name = toolName,
                    argumentsJson = if (toolCallIndex == 0) REDACTED_ARGUMENTS else "{}",
                ),
            ),
        ),
    )

    /** Envelope wrapping [messages], with a defensive copy like the engine's. */
    fun envelope(messages: List<Message> = messages()): SensitiveReplayEnvelope =
        SensitiveReplayEnvelope.of(messages)

    /** Computes the canonical digest the engine would attach to [messages]. */
    fun digest(messages: List<Message> = messages()): Sha256Digest =
        ReplayEnvelopeDigestHelper.compute(OPERATION_REFERENCE, messages)

    /**
     * A valid metadata+envelope pair for [approvalId].
     *
     * [digestOverride] and [messageOverride] let tests tamper with exactly one
     * binding at a time while keeping the rest canonical.
     */
    fun record(
        approvalId: String = "si-1",
        toolCallId: String = TOOL_CALL_ID,
        toolName: String = TOOL_NAME,
        toolCallIndex: Int = 0,
        correlationId: String = "correlation-1",
        conversationId: String? = "conversation-1",
        historySize: Int = 1,
        digestOverride: Sha256Digest? = null,
        messageOverride: List<Message>? = null,
    ): Pair<SuspendedInvocationMetadata, SensitiveReplayEnvelope> {
        val messages = messageOverride ?: messages(toolCallId, toolName, toolCallIndex)
        val metadata = metadata(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = toolCallIndex,
            correlationId = correlationId,
            conversationId = conversationId,
            historySize = historySize,
            replayEnvelopeDigest = digestOverride ?: digest(messages),
        )
        return metadata to envelope(messages)
    }

    fun metadata(
        approvalId: String = "si-1",
        toolCallId: String = TOOL_CALL_ID,
        toolName: String = TOOL_NAME,
        toolCallIndex: Int = 0,
        correlationId: String = "correlation-1",
        conversationId: String? = "conversation-1",
        historySize: Int = 1,
        replayEnvelopeDigest: Sha256Digest = digest(),
    ): SuspendedInvocationMetadata = SuspendedInvocationMetadata(
        approvalId = approvalId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolCallIndex = toolCallIndex,
        correlationId = correlationId,
        identity = EngineExecutionIdentity(
            workflowRunId = "workflow-run-1",
            correlationId = correlationId,
            workflowDigest = WORKFLOW_DIGEST,
            policyVersion = "policy-v1",
            actorId = "actor-1",
        ),
        securityContext = ExecutionSecurityContext(
            dataClassification = DataClassification.CONFIDENTIAL,
            classificationSource = ClassificationSource.RULE_BASED,
        ),
        operationReference = OPERATION_REFERENCE,
        replayEnvelopeDigest = replayEnvelopeDigest,
        conversationId = conversationId,
        historySize = historySize,
        tokenBudgetSnapshot = TOKEN_BUDGET,
        toolReference = TOOL_REFERENCE,
        toolSecurity = TOOL_SECURITY,
    )
}
