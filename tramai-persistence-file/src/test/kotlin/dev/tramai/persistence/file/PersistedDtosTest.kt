package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.CompatibilityMode
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PersistedDtosTest {

    // ── Helpers ────────────────────────────────────────────────────

    private fun sha256(value: String): Sha256Digest =
        Sha256Digest.of("sha256:${"0".repeat(64 - value.length)}$value".take(70))

    private val testDigest: Sha256Digest get() = Sha256Digest.of(
        "sha256:0000000000000000000000000000000000000000000000000000000000000001",
    )

    // ── ApprovalRequest DTO ────────────────────────────────────────

    @Test
    fun `PersistedApprovalRequestV1 toJson fromJson round-trip`() {
        val original = PersistedApprovalRequestV1(schemaVersion = 1,
            approvalId = "req-001",
            binding = PersistedApprovalBindingV1(schemaVersion = 1,
                workflowRunId = "wf-run-1",
                toolName = "file-reader",
                argumentsDigest = "sha256:aaaa",
                policyVersion = "v1.0",
                workflowDigest = "sha256:bbbb",
                approvalTokenDigest = "sha256:cccc",
            ),
            status = "PENDING",
            requestedBy = "user-alice",
            requestedAt = "2025-06-01T10:00:00Z",
            expiresAt = "2025-06-01T10:15:00Z",
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        val json = original.toJson()
        val restored = PersistedApprovalRequestV1.fromJson(json)

        assertEquals(original.approvalId, restored.approvalId)
        assertEquals(original.binding.workflowRunId, restored.binding.workflowRunId)
        assertEquals(original.status, restored.status)
        assertEquals(original.requestedBy, restored.requestedBy)
        assertEquals(original.requestedAt, restored.requestedAt)
        assertEquals(original.expiresAt, restored.expiresAt)
        assertEquals(original.decidedBy, restored.decidedBy)
        assertEquals(original.decidedAt, restored.decidedAt)
        assertEquals(original.decisionComment, restored.decisionComment)
        assertEquals(original.consumedBy, restored.consumedBy)
        assertEquals(original.consumedAt, restored.consumedAt)
        assertEquals(original.version, restored.version)
    }

    @Test
    fun `PersistedApprovalRequestV1 round-trip with all fields populated`() {
        val original = PersistedApprovalRequestV1(schemaVersion = 1,
            approvalId = "req-002",
            binding = PersistedApprovalBindingV1(schemaVersion = 1,
                workflowRunId = "wf-run-2",
                toolName = "db-query",
                argumentsDigest = "sha256:dddd",
                policyVersion = "v2.0",
                workflowDigest = "sha256:eeee",
                approvalTokenDigest = "sha256:ffff",
            ),
            status = "APPROVED",
            requestedBy = "user-bob",
            requestedAt = "2025-06-01T11:00:00Z",
            expiresAt = "2025-06-01T11:15:00Z",
            decidedBy = "approver-carol",
            decidedAt = "2025-06-01T11:05:00Z",
            decisionComment = "Looks good",
            consumedBy = "executor-dave",
            consumedAt = "2025-06-01T11:10:00Z",
            version = 2L,
        )

        val json = original.toJson()
        val restored = PersistedApprovalRequestV1.fromJson(json)

        assertEquals(original.approvalId, restored.approvalId)
        assertEquals(original.binding.toolName, restored.binding.toolName)
        assertEquals(original.status, restored.status)
        assertEquals(original.decidedBy, restored.decidedBy)
        assertEquals(original.decisionComment, restored.decisionComment)
        assertEquals(original.consumedBy, restored.consumedBy)
        assertEquals(original.version, restored.version)
    }

    @Test
    fun `PersistedApprovalRequestV1 toDomain round-trip`() {
        val binding = ApprovalBinding(
            workflowRunId = "wf-1",
            toolName = "reader",
            argumentsDigest = testDigest,
            policyVersion = "v1",
            workflowDigest = testDigest,
            approvalTokenDigest = testDigest,
        )
        val request = ApprovalRequest(
            approvalId = "req-003",
            binding = binding,
            status = ApprovalStatus.PENDING,
            requestedBy = "alice",
            requestedAt = Instant.parse("2025-06-01T12:00:00Z"),
            expiresAt = Instant.parse("2025-06-01T12:15:00Z"),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        val persisted = request.toPersistedV1()
        val restored = persisted.toDomain()

        assertEquals(request.approvalId, restored.approvalId)
        assertEquals(request.binding.workflowRunId, restored.binding.workflowRunId)
        assertEquals(request.binding.toolName, restored.binding.toolName)
        assertEquals(request.status, restored.status)
        assertEquals(request.requestedBy, restored.requestedBy)
        assertEquals(request.requestedAt, restored.requestedAt)
        assertEquals(request.expiresAt, restored.expiresAt)
        assertEquals(request.version, restored.version)
    }

    // ── ApprovalContinuation DTO ───────────────────────────────────

    @Test
    fun `PersistedApprovalContinuationRecordV1 toJson fromJson round-trip`() {
        val continuation = PersistedApprovalContinuationV1(schemaVersion = 1,
            approvalId = "cont-001",
            workflowRunId = "wf-run-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "api-caller",
            argumentsDigest = "sha256:aaaa",
            policyVersion = "v1.0",
            workflowDigest = "sha256:bbbb",
            status = "PENDING",
            createdAt = "2025-06-01T10:00:00Z",
            approvalExpiresAt = "2025-06-01T10:15:00Z",
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            recoveryResolvedBy = null,
            recoveryResolvedAt = null,
            recoveryReasonCode = null,
            version = 0L,
        )
        val original = PersistedApprovalContinuationRecordV1(schemaVersion = 1,
            continuation = continuation,
            arguments = "plain-text-args",
        )

        val json = original.toJson()
        val restored = PersistedApprovalContinuationRecordV1.fromJson(json)

        assertEquals(original.continuation.approvalId, restored.continuation.approvalId)
        assertEquals(original.continuation.workflowRunId, restored.continuation.workflowRunId)
        assertEquals(original.continuation.status, restored.continuation.status)
        assertEquals(original.continuation.version, restored.continuation.version)
        assertEquals(original.arguments, restored.arguments)
    }

    @Test
    fun `PersistedApprovalContinuationRecordV1 toJson fromJson with null arguments`() {
        val continuation = PersistedApprovalContinuationV1(schemaVersion = 1,
            approvalId = "cont-002",
            workflowRunId = "wf-run-2",
            correlationId = "corr-2",
            toolCallId = "tc-2",
            toolName = "db-writer",
            argumentsDigest = "sha256:cccc",
            policyVersion = "v2.0",
            workflowDigest = "sha256:dddd",
            status = "CLAIMED",
            createdAt = "2025-06-01T11:00:00Z",
            approvalExpiresAt = "2025-06-01T11:15:00Z",
            claimedBy = "executor-eve",
            claimedAt = "2025-06-01T11:05:00Z",
            completedAt = null,
            recoveryResolvedBy = null,
            recoveryResolvedAt = null,
            recoveryReasonCode = null,
            version = 1L,
        )
        val original = PersistedApprovalContinuationRecordV1(schemaVersion = 1,
            continuation = continuation,
            arguments = null,
        )

        val json = original.toJson()
        val restored = PersistedApprovalContinuationRecordV1.fromJson(json)

        assertEquals(original.continuation.approvalId, restored.continuation.approvalId)
        assertEquals(original.continuation.status, restored.continuation.status)
        assertEquals(original.arguments, restored.arguments)
    }

    @Test
    fun `PersistedApprovalContinuationRecordV1 toDomain round-trip`() {
        val continuation = ApprovalContinuation(
            approvalId = "cont-003",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "api",
            argumentsDigest = testDigest,
            policyVersion = "v1",
            workflowDigest = testDigest,
            status = ApprovalContinuationStatus.PENDING,
            createdAt = Instant.parse("2025-06-01T12:00:00Z"),
            approvalExpiresAt = Instant.parse("2025-06-01T12:15:00Z"),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

        val persisted = continuation.toPersistedV1()
        val restored = persisted.toDomain()

        assertEquals(continuation.approvalId, restored.approvalId)
        assertEquals(continuation.workflowRunId, restored.workflowRunId)
        assertEquals(continuation.toolName, restored.toolName)
        assertEquals(continuation.status, restored.status)
        assertEquals(continuation.version, restored.version)
    }

    // ── AuditEvent DTO ─────────────────────────────────────────────

    @Test
    fun `PersistedAuditEventV1 toJson fromJson round-trip`() {
        val original = PersistedAuditEventV1(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256.wireName,
            auditStreamId = "stream-1",
            eventId = "evt-001",
            sequenceNumber = 1L,
            workflowRunId = "wf-run-1",
            correlationId = "corr-1",
            actor = "alice",
            enforcementPoint = "approval-gate",
            decision = "APPROVED",
            policyVersion = "v1.0",
            workflowDigest = "sha256:aaaa",
            previousEventHash = null,
            eventHash = "hash123",
            timestamp = "2025-06-01T10:00:00Z",
            reasonCode = null,
            metadata = mapOf("key1" to "val1", "key2" to "val2"),
        )

        val json = original.toJson()
        val restored = PersistedAuditEventV1.fromJson(json)

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.hashAlgorithm, restored.hashAlgorithm)
        assertEquals(original.auditStreamId, restored.auditStreamId)
        assertEquals(original.eventId, restored.eventId)
        assertEquals(original.sequenceNumber, restored.sequenceNumber)
        assertEquals(original.workflowRunId, restored.workflowRunId)
        assertEquals(original.enforcementPoint, restored.enforcementPoint)
        assertEquals(original.decision, restored.decision)
        assertEquals(original.eventHash, restored.eventHash)
        assertEquals(original.metadata, restored.metadata)
    }

    @Test
    fun `PersistedAuditEventV1 toJson fromJson with null fields and empty metadata`() {
        val original = PersistedAuditEventV1(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256.wireName,
            auditStreamId = "stream-2",
            eventId = "evt-002",
            sequenceNumber = 2L,
            workflowRunId = null,
            correlationId = null,
            actor = null,
            enforcementPoint = "policy-check",
            decision = "DENIED",
            policyVersion = null,
            workflowDigest = null,
            previousEventHash = "prev-hash",
            eventHash = "hash456",
            timestamp = "2025-06-01T11:00:00Z",
            reasonCode = null,
            metadata = emptyMap(),
        )

        val json = original.toJson()
        val restored = PersistedAuditEventV1.fromJson(json)

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.workflowRunId, restored.workflowRunId)
        assertEquals(original.correlationId, restored.correlationId)
        assertEquals(original.actor, restored.actor)
        assertEquals(original.policyVersion, restored.policyVersion)
        assertEquals(original.metadata, restored.metadata)
    }

    @Test
    fun `PersistedAuditEventV1 toDomain round-trip`() {
        val event = AuditEvent(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = "stream-1",
            eventId = "evt-001",
            sequenceNumber = 1L,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            actor = "alice",
            enforcementPoint = "gate",
            decision = "APPROVED",
            policyVersion = "v1",
            workflowDigest = "sha256:aaaa",
            previousEventHash = null,
            eventHash = "",  // will be computed
            timestamp = Instant.parse("2025-06-01T12:00:00Z"),
            reasonCode = null,
            metadata = mapOf("env" to "prod"),
        )
        // Compute the actual event hash
        val computedHash = event.calculateHash()
        val eventWithHash = event.copy(eventHash = computedHash)

        val persisted = eventWithHash.toPersistedV1()
        val restored = persisted.toDomain()

        assertEquals(eventWithHash.schemaVersion, restored.schemaVersion)
        assertEquals(eventWithHash.auditStreamId, restored.auditStreamId)
        assertEquals(eventWithHash.eventId, restored.eventId)
        assertEquals(eventWithHash.sequenceNumber, restored.sequenceNumber)
        assertEquals(eventWithHash.enforcementPoint, restored.enforcementPoint)
        assertEquals(eventWithHash.decision, restored.decision)
        assertEquals(eventWithHash.eventHash, restored.eventHash)
        assertEquals(eventWithHash.metadata, restored.metadata)
        assertEquals(eventWithHash.timestamp, restored.timestamp)
    }

    @Test
    fun `Suspended invocation DTOs round trip domain and json`() {
        val messages = listOf(
            Message(
                role = MessageRole.USER,
                content = "",
                contentParts = listOf(
                    ContentPart.TextPart("describe"),
                    ContentPart.ImagePart("image/png", byteArrayOf(1, 2, 3, 4)),
                    ContentPart.ImageUrlContent("https://example.com/x.png", "image/png"),
                ),
            ),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "call-1",
                        name = "safeTool",
                        argumentsJson = "__redacted_approval_continuation_args__",
                    ),
                ),
            ),
        )
        val metadata = SuspendedInvocationMetadata(
            approvalId = "approval-1",
            toolCallId = "call-1",
            toolName = "safeTool",
            toolCallIndex = 0,
            correlationId = "corr-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                workflowDigest = testDigest,
                policyVersion = "policy-v1",
                actorId = "actor-1",
            ),
            securityContext = ExecutionSecurityContext(
                dataClassification = DataClassification.CONFIDENTIAL,
                classificationSource = ClassificationSource.RULE_BASED,
            ),
            operationReference = ResumeOperationReference(
                serviceInterface = "dev.example.Service",
                methodName = "run",
                jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                resumeDefinitionDigest = Sha256Digest.of(
                    "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                ),
            ),
            replayEnvelopeDigest = Sha256Digest.of(
                "sha256:2222222222222222222222222222222222222222222222222222222222222222",
            ),
            conversationId = "conversation-1",
            historySize = 7,
            tokenBudgetSnapshot = TokenBudgetSnapshot(
                totalInputTokens = 10,
                totalOutputTokens = 20,
                totalInputCost = 0.1,
                totalOutputCost = 0.2,
                warnIfExceeded = true,
            ),
            toolReference = ResumeToolReference(
                toolName = "safeTool",
                declarationDigest = Sha256Digest.of(
                    "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                ),
            ),
            toolSecurity = ToolSecurityMetadata(
                permission = "files.read",
                risk = RiskLevel.MEDIUM,
                approval = ApprovalMode.HUMAN_REQUIRED,
                managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
                audit = AuditDetail.FULL,
                compatibilityMode = CompatibilityMode.STRICT,
            ),
        )
        val envelope = SensitiveReplayEnvelope.of(messages)

        val persistedRecord = PersistedSuspendedInvocationRecordV1(
            schemaVersion = 1,
            metadata = metadata.toPersistedV1(),
            replayEnvelope = envelope.toPersistedV1(),
        )

        val restoredRecord = PersistedSuspendedInvocationRecordV1.fromJson(persistedRecord.toJson())
        val restoredMetadata = restoredRecord.metadata.toDomain()
        val restoredMessages = restoredRecord.replayEnvelope.toDomain().revealForResume().messages

        assertEquals(metadata, restoredMetadata)
        assertEquals(messages[0].role, restoredMessages[0].role)
        assertEquals(messages[0].contentParts?.size, restoredMessages[0].contentParts?.size)
        val restoredImage = restoredMessages[0].contentParts?.get(1) as ContentPart.ImagePart
        assertContentEquals(byteArrayOf(1, 2, 3, 4), restoredImage.data)
        assertEquals("safeTool", restoredMessages[1].toolCalls?.single()?.name)
        assertEquals("__redacted_approval_continuation_args__", restoredMessages[1].toolCalls?.single()?.argumentsJson)
    }
}
