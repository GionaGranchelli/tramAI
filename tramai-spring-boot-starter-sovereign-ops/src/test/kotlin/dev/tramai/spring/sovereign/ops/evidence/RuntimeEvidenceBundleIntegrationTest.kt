package dev.tramai.spring.sovereign.ops.evidence

import dev.tramai.engine.evidence.ProviderRouteDecisionEvidenceSource
import dev.tramai.engine.evidence.ProviderRouteDecisionKind
import dev.tramai.engine.evidence.ProviderRoutingRuntimeEvidenceExporter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import dev.tramai.security.evidence.RuntimeEvidenceBundleWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * End-to-end integration test for runtime evidence bundle wiring.
 *
 * Uses three real exporters to produce [RuntimeEvidenceRecord]s from
 * real source types and writes them into a bundle directory using
 * [RuntimeEvidenceBundleWriter].
 */
class RuntimeEvidenceBundleIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val policyExporter = PolicyDecisionRuntimeEvidenceExporter()
    private val approvalExporter = ApprovalDecisionRuntimeEvidenceExporter()
    private val routingExporter = ProviderRoutingRuntimeEvidenceExporter()
    private val bundleWriter = RuntimeEvidenceBundleWriter()

    private val fixedTimestamp = Instant.parse("2026-07-13T10:00:00Z")

    @Test
    fun `three real exporters produce complete runtime evidence section`() {
        // Create a real AuditEvent for policy
        val policyEvent = AuditEvent(
            schemaVersion = 1,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = "integration-run-001",
            eventId = "int-policy-001",
            sequenceNumber = 1L,
            workflowRunId = "wf-int-001",
            correlationId = "corr-int-001",
            actor = "policy-engine",
            enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
            decision = "ALLOW",
            policyVersion = "v1",
            workflowDigest = "digest-int-001",
            previousEventHash = null,
            eventHash = "hash-int-001",
            timestamp = fixedTimestamp,
            reasonCode = "policy_allowed",
            metadata = mapOf(
                "providerName" to "ollama",
                "modelName" to "mistral",
                "classification" to "low-risk",
            ),
        )

        // Create a real SovereignOpsAuditOutboxRecord for approval
        val approvalRecord = dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord(
            outboxId = "int-approval-001",
            aggregateType = "approval",
            aggregateIdDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            operation = "approval-approved.int-approval-001",
            eventKey = "approval-approved.int-approval-001",
            actor = "integration-tester",
            workflowRunId = "wf-int-001",
            correlationId = "corr-int-002",
            approvalStatus = "APPROVED",
            approvalVersion = 1L,
            reasonDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            reasonLength = 29,
            createdAt = fixedTimestamp.plusSeconds(5),
            status = dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.PENDING,
        )

        // Create a real ProviderRouteDecisionEvidenceSource for routing
        val routeSource = ProviderRouteDecisionEvidenceSource(
            eventId = "int-routing-001",
            workflowRunId = "wf-int-001",
            correlationId = "corr-int-003",
            actor = "provider-router",
            createdAt = fixedTimestamp.plusSeconds(10),
            decisionKind = ProviderRouteDecisionKind.SELECTED,
            requestedModelName = "mistral-7b",
            selectedProviderName = "ollama",
            selectedModelName = "mistral-7b",
            routeIndex = 0,
            attempt = 1,
        )

        // Export all three into RuntimeEvidenceRecords
        val policyRecords = policyExporter.export(listOf(policyEvent))
        val approvalRecords = approvalExporter.export(listOf(approvalRecord))
        val routingRecords = routingExporter.export(listOf(routeSource))

        assertEquals(1, policyRecords.size)
        assertEquals(1, approvalRecords.size)
        assertEquals(1, routingRecords.size)

        // Write them into the bundle
        val allRecords = policyRecords + approvalRecords + routingRecords
        val result = bundleWriter.write(tempDir, allRecords)

        // Assert all three JSONL files exist
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")))
        assertEquals(3, result.writtenFiles.size)
        assertEquals(3, result.countsByEventType.size)

        // Check each file's content for correct eventType and decision kind
        val policyContent = Files.readString(
            result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        )
        assertTrue(policyContent.contains("\"eventType\":\"policy.decision\""))
        assertTrue(policyContent.contains("\"kind\":\"ALLOW\""))
        // No raw prompt should appear (not even as a metadata key)
        assertFalse(policyContent.contains("prompt"))
        assertFalse(policyContent.contains("toolArg"))

        val approvalContent = Files.readString(
            result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")
        )
        assertTrue(approvalContent.contains("\"eventType\":\"approval.decision\""))
        assertTrue(approvalContent.contains("\"kind\":\"APPROVED\""))
        // No raw approval IDs, event keys, comments, or tokens
        assertFalse(approvalContent.contains("approvalToken"))
        assertFalse(approvalContent.contains("comments"))

        val routingContent = Files.readString(
            result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")
        )
        assertTrue(routingContent.contains("\"eventType\":\"provider.route\""))
        assertTrue(routingContent.contains("\"kind\":\"SELECTED\""))
        // Raw provider/model names must not appear
        assertFalse(routingContent.contains("ollama"))
        assertFalse(routingContent.contains("mistral-7b"))
        assertFalse(routingContent.contains("mistral"))
    }
}
