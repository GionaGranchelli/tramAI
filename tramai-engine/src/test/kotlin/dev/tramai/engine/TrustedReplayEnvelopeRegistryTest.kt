package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for PR #28 types: ReplayEnvelopeFactory, ResumeOperationRegistry,
 * JvmMethodDescriptorHelper, ResumeDefinitionDigestHelper.
 */
class TrustedReplayEnvelopeRegistryTest {

    // ── ReplayEnvelopeFactory tests ──────────────────────────────────

    @Test
    fun `prepareForSuspension redacts selected slot`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(role = MessageRole.USER, content = "hello"),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = toolCallId, name = toolName, argumentsJson = """{"input":"secret"}"""),
                ),
            ),
        )

        val prepared = ReplayEnvelopeFactory.prepareForSuspension(
            operationReference = opRef,
            messages = messages,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
        )

        val payload = prepared.envelope.revealForResume()
        val assistantMsg = payload.messages.last { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
        val toolCall = assistantMsg.toolCalls!![0]
        assertThat(toolCall.argumentsJson).isEqualTo(REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)
        assertThat(toolCall.id).isEqualTo(toolCallId)
        assertThat(toolCall.name).isEqualTo(toolName)
    }

    @Test
    fun `prepareForSuspension digest differs from raw digest`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = toolCallId, name = toolName, argumentsJson = """{"input":"secret"}"""),
                ),
            ),
        )

        val rawDigest = ReplayEnvelopeDigestHelper.compute(opRef, messages)
        val prepared = ReplayEnvelopeFactory.prepareForSuspension(
            operationReference = opRef,
            messages = messages,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
        )

        assertThat(prepared.digest).isNotEqualTo(rawDigest)
    }

    @Test
    fun `rehydration restores selected slot after claim`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(role = MessageRole.USER, content = "hello"),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = toolCallId, name = toolName, argumentsJson = """{"input":"secret"}"""),
                ),
            ),
        )

        val prepared = ReplayEnvelopeFactory.prepareForSuspension(
            operationReference = opRef,
            messages = messages,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
        )

        val payload = prepared.envelope.revealForResume()
        val metadata = SuspendedInvocationMetadata(
            approvalId = "approval-1",
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
            correlationId = "corr-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                workflowDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                policyVersion = "1.0",
                actorId = "admin",
            ),
            securityContext = ExecutionSecurityContext(),
            operationReference = opRef,
            replayEnvelopeDigest = prepared.digest,
            toolReference = ResumeToolReference(toolName, Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001")),
        )

        val rehydrated = ReplayEnvelopeFactory.rehydrateAfterClaim(
            payload = payload,
            metadata = metadata,
            claimedArgumentsJson = """{"input":"restored"}""",
        )

        val rehydratedMsg = rehydrated.messages.last { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
        val rehydratedCall = rehydratedMsg.toolCalls!![0]
        assertThat(rehydratedCall.argumentsJson).isEqualTo("""{"input":"restored"}""")
        assertThat(rehydratedCall.id).isEqualTo(toolCallId)
        assertThat(rehydratedCall.name).isEqualTo(toolName)
    }

    @Test
    fun `historical duplicate toolCallId across batches fails`() {
        val duplicateId = "call-duplicate"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = duplicateId, name = toolName, argumentsJson = """{"input":"historical"}"""),
                ),
            ),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = duplicateId, name = toolName, argumentsJson = """{"input":"current"}"""),
                ),
            ),
        )

        assertThatThrownBy {
            ReplayEnvelopeFactory.prepareForSuspension(
                operationReference = opRef,
                messages = messages,
                toolCallId = duplicateId,
                toolName = toolName,
                toolCallIndex = 0,
            )
        }.hasMessageContaining("replay-envelope-duplicate-matching-calls")
    }

    @Test
    fun `historical duplicate identity fails rehydration`() {
        val duplicateId = "call-duplicate"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = duplicateId, name = toolName, argumentsJson = """{"input":"historical"}"""),
                ),
            ),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = duplicateId, name = toolName, argumentsJson = REDACTED_APPROVAL_CONTINUATION_ARGUMENTS),
                ),
            ),
        )

        val payload = ReplayPayload(messages = messages)
        val metadata = SuspendedInvocationMetadata(
            approvalId = "approval-1",
            toolCallId = duplicateId,
            toolName = toolName,
            toolCallIndex = 0,
            correlationId = "corr-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                workflowDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                policyVersion = "1.0",
                actorId = "admin",
            ),
            securityContext = ExecutionSecurityContext(),
            operationReference = opRef,
            replayEnvelopeDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
            toolReference = ResumeToolReference(toolName, Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001")),
        )

        assertThatThrownBy {
            ReplayEnvelopeFactory.rehydrateAfterClaim(
                payload = payload,
                metadata = metadata,
                claimedArgumentsJson = """{"input":"restored"}""",
            )
        }.hasMessageContaining("replay-envelope-duplicate-matching-calls")
    }

    @Test
    fun `missing assistant batch fails creation`() {
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        assertThatThrownBy {
            ReplayEnvelopeFactory.prepareForSuspension(
                operationReference = opRef,
                messages = listOf(Message(role = MessageRole.USER, content = "hello")),
                toolCallId = "call-1",
                toolName = "lookup",
                toolCallIndex = 0,
            )
        }.hasMessageContaining("replay-envelope-assistant-batch-not-found")
    }

    @Test
    fun `corrupt index fails rehydration`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = toolCallId, name = toolName, argumentsJson = REDACTED_APPROVAL_CONTINUATION_ARGUMENTS),
                ),
            ),
        )
        val payload = ReplayPayload(messages = messages)
        val metadata = SuspendedInvocationMetadata(
            approvalId = "approval-1",
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 5,
            correlationId = "corr-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                workflowDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                policyVersion = "1.0",
                actorId = "admin",
            ),
            securityContext = ExecutionSecurityContext(),
            operationReference = opRef,
            replayEnvelopeDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
            toolReference = ResumeToolReference(toolName, Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001")),
        )

        assertThatThrownBy {
            ReplayEnvelopeFactory.rehydrateAfterClaim(
                payload = payload,
                metadata = metadata,
                claimedArgumentsJson = "{}",
            )
        }.hasMessageContaining("replay-envelope-tool-call-index-out-of-bounds")
    }

    @Test
    fun `corrupt ID fails rehydration`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = ResumeOperationReference(
            serviceInterface = "test.Service",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )
        val messages = listOf(
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = "wrong-id", name = toolName, argumentsJson = REDACTED_APPROVAL_CONTINUATION_ARGUMENTS),
                ),
            ),
        )
        val payload = ReplayPayload(messages = messages)
        val metadata = SuspendedInvocationMetadata(
            approvalId = "approval-1",
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
            correlationId = "corr-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                workflowDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                policyVersion = "1.0",
                actorId = "admin",
            ),
            securityContext = ExecutionSecurityContext(),
            operationReference = opRef,
            replayEnvelopeDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
            toolReference = ResumeToolReference(toolName, Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001")),
        )

        assertThatThrownBy {
            ReplayEnvelopeFactory.rehydrateAfterClaim(
                payload = payload,
                metadata = metadata,
                claimedArgumentsJson = "{}",
            )
        }.hasMessageContaining("replay-envelope-tool-call-id-mismatch")
    }

    // ── ResumeOperationRegistry tests ────────────────────────────────

    @Test
    fun `registry missing key fails`() {
        val registry = ResumeOperationRegistry()
        val reference = ResumeOperationReference(
            serviceInterface = "test.Missing",
            methodName = "execute",
            jvmMethodDescriptor = "()V",
            resumeDefinitionDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
        )

        assertThatThrownBy {
            registry.resolve(reference)
        }.hasMessageContaining("resume-operation-not-registered")
    }

    // ── JvmMethodDescriptorHelper tests ──────────────────────────────

    class NestedInput

    @Test
    fun `descriptor nested class uses binary name`() {
        val method = TestNestedService::class.java.methods.find { it.name == "nestedParam" }
            ?: throw AssertionError("Method not found")
        val descriptor = JvmMethodDescriptorHelper.compute(method)
        assertThat(descriptor).contains("TrustedReplayEnvelopeRegistryTest\$NestedInput")
        assertThat(descriptor).doesNotContain("TrustedReplayEnvelopeRegistryTest.NestedInput")
    }

    @Test
    fun `descriptor primitive arrays`() {
        val method = TestNestedService::class.java.methods.find { it.name == "arrayParams" }
            ?: throw AssertionError("Method not found")
        val descriptor = JvmMethodDescriptorHelper.compute(method)
        assertThat(descriptor).contains("([I")
        assertThat(descriptor).contains("[Ljava/lang/String;")
    }

    private interface TestNestedService {
        fun nestedParam(input: NestedInput): String
        fun arrayParams(ints: IntArray, strings: Array<String>): Unit
    }
}
