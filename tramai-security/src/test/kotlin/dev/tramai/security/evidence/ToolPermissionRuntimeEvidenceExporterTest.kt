package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ToolPermissionRuntimeEvidenceExporterTest {

    private val exporter = ToolPermissionRuntimeEvidenceExporter()
    private val digestRegex = Regex("^sha256:[0-9a-f]{64}$")

    private val fixedTimestamp = Instant.parse("2026-07-08T12:00:00Z")

    // ─── Base tool events for each enforcement point ────────────────────

    private val exposureAllowEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-exposure-allow-001",
        sequenceNumber = 1L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_TOOL_EXPOSURE",
        decision = "ALLOW",
        policyVersion = "v1",
        workflowDigest = "digest-abc",
        previousEventHash = null,
        eventHash = "hash-001",
        timestamp = fixedTimestamp,
        reasonCode = "tool_allowed",
        metadata = mapOf(
            "toolName" to "weather-tool",
            "riskLevel" to "LOW",
        ),
    )

    private val executionDenyEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-execution-deny-001",
        sequenceNumber = 2L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_TOOL_EXECUTION",
        decision = "DENY",
        policyVersion = "v2",
        workflowDigest = "digest-abc",
        previousEventHash = "hash-001",
        eventHash = "hash-002",
        timestamp = fixedTimestamp.plusSeconds(1),
        reasonCode = "tool_denied",
        metadata = mapOf(
            "toolName" to "payment-tool",
            "riskLevel" to "HIGH",
            "classification" to "RESTRICTED",
        ),
    )

    private val executionRequireApprovalEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-execution-approval-001",
        sequenceNumber = 3L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_TOOL_EXECUTION",
        decision = "REQUIRE_APPROVAL",
        policyVersion = "v3",
        workflowDigest = "digest-abc",
        previousEventHash = "hash-002",
        eventHash = "hash-003",
        timestamp = fixedTimestamp.plusSeconds(2),
        reasonCode = "tool_requires_approval",
        metadata = mapOf(
            "toolName" to "code-execution-tool",
            "riskLevel" to "CRITICAL",
        ),
    )

    private val reinjectionAllowEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-reinjection-allow-001",
        sequenceNumber = 4L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_TOOL_RESULT_REINJECTION",
        decision = "ALLOW",
        policyVersion = "v1",
        workflowDigest = "digest-abc",
        previousEventHash = "hash-003",
        eventHash = "hash-004",
        timestamp = fixedTimestamp.plusSeconds(3),
        reasonCode = "reinjection_allowed",
        metadata = mapOf(
            "toolName" to "database-query-tool",
            "riskLevel" to "MEDIUM",
        ),
    )

    // ─── 1. Exposure ALLOW exports as tool.permission ───────────────────

    @Test
    fun `exposure ALLOW exports as tool permission`() {
        val results = exporter.export(listOf(exposureAllowEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("runtime-evidence.v1", record.schemaVersion)
        assertEquals("tool.permission", record.eventType)
        assertEquals("ALLOW", record.decision.kind)
        assertEquals("tool_allowed", record.decision.reasonCode)
    }

    // ─── 2. Execution DENY exports correctly ────────────────────────────

    @Test
    fun `execution DENY exports correctly`() {
        val results = exporter.export(listOf(executionDenyEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("tool.permission", record.eventType)
        assertEquals("DENY", record.decision.kind)
        assertEquals("tool_denied", record.decision.reasonCode)
    }

    // ─── 3. Execution REQUIRE_APPROVAL exports correctly ─────────────────

    @Test
    fun `execution REQUIRE_APPROVAL exports correctly`() {
        val results = exporter.export(listOf(executionRequireApprovalEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("tool.permission", record.eventType)
        assertEquals("REQUIRE_APPROVAL", record.decision.kind)
        assertEquals("tool_requires_approval", record.decision.reasonCode)
    }

    // ─── 4. Result-reinjection decision exports correctly ───────────────

    @Test
    fun `result reinjection decision exports correctly`() {
        val results = exporter.export(listOf(reinjectionAllowEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("tool.permission", record.eventType)
        assertEquals("ALLOW", record.decision.kind)
        assertEquals("reinjection_allowed", record.decision.reasonCode)
    }

    // ─── 5. Non-tool enforcement events are ignored (empty result) ──────

    @Test
    fun `non-tool enforcement events are ignored`() {
        val nonToolEvent = exposureAllowEvent.copy(
            eventId = "evt-non-tool-001",
            enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
        )
        val results = exporter.export(listOf(nonToolEvent))
        assertTrue(results.isEmpty(), "Non-tool enforcement events should be skipped")
    }

    // ─── 6. Original audit event ID is preserved ────────────────────────

    @Test
    fun `original audit event ID is preserved`() {
        val record = exporter.export(listOf(exposureAllowEvent)).single()
        assertEquals("evt-exposure-allow-001", record.eventId)
        assertEquals("wf-run-001", record.workflowRunId)
        assertEquals("corr-001", record.correlationId)
        assertEquals(fixedTimestamp, record.createdAt)
    }

    // ─── 7. Source component is policy-engine ───────────────────────────

    @Test
    fun `source component is policy-engine`() {
        val record = exporter.export(listOf(exposureAllowEvent)).single()
        assertEquals("policy-engine", record.source.component)
    }

    // ─── 8. Policy version becomes source.module ────────────────────────

    @Test
    fun `policy version becomes source module`() {
        val record = exporter.export(listOf(executionDenyEvent)).single()
        assertEquals("v2", record.source.module)

        val record2 = exporter.export(listOf(exposureAllowEvent)).single()
        assertEquals("v1", record2.source.module)
    }

    @Test
    fun `null policy version results in null source module`() {
        val event = exposureAllowEvent.copy(policyVersion = null)
        val record = exporter.export(listOf(event)).single()
        assertEquals(null, record.source.module)
    }

    // ─── 9. Enforcement point is present in metadata ────────────────────

    @Test
    fun `enforcement point is enriched from top-level audit field`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-enrich-001",
            metadata = mapOf(
                "toolName" to "weather-tool",
                "riskLevel" to "LOW",
            ),
            // enforcementPoint is a top-level field, not in metadata
        )
        val record = exporter.export(listOf(event)).single()
        // Exporter must enrich enforcementPoint from the top-level audit field
        assertEquals("BEFORE_TOOL_EXPOSURE", record.metadata["enforcementPoint"])
        // enforcementPoint in metadata must match the event's top-level field
        assertEquals(event.enforcementPoint, record.metadata["enforcementPoint"])
    }

    // ─── 10. Tool name and risk level are preserved ─────────────────────

    @Test
    fun `tool name and risk level are preserved`() {
        val record = exporter.export(listOf(executionDenyEvent)).single()
        assertEquals("payment-tool", record.metadata["toolName"])
        assertEquals("HIGH", record.metadata["riskLevel"])
    }

    @Test
    fun `classification metadata is preserved`() {
        val record = exporter.export(listOf(executionDenyEvent)).single()
        assertEquals("RESTRICTED", record.metadata["classification"])
    }

    // ─── 11. Missing toolName fails closed (IllegalArgumentException) ───

    @Test
    fun `missing toolName throws IllegalArgumentException`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-no-tool-001",
            metadata = emptyMap(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            exporter.export(listOf(event))
        }
    }

    @Test
    fun `blank toolName throws IllegalArgumentException`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-blank-tool-001",
            metadata = mapOf("toolName" to "   "),
        )
        assertThrows(IllegalArgumentException::class.java) {
            exporter.export(listOf(event))
        }
    }

    // ─── 12. Raw arguments are absent from metadata ─────────────────────

    @Test
    fun `raw arguments are absent from metadata`() {
        val event = executionDenyEvent.copy(
            eventId = "evt-args-001",
            metadata = mapOf(
                "toolName" to "payment-tool",
                "riskLevel" to "HIGH",
                "toolArguments" to """{"amount": 100, "currency": "USD"}""",
                "rawArguments" to "sensitive-data",
            ),
        )
        val record = exporter.export(listOf(event)).single()
        assertFalse(record.metadata.containsKey("toolArguments"))
        assertFalse(record.metadata.containsKey("rawArguments"))
    }

    // ─── 13. Unknown metadata is excluded ───────────────────────────────

    @Test
    fun `unknown metadata is excluded`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-unknown-meta-001",
            metadata = mapOf(
                "toolName" to "weather-tool",
                "riskLevel" to "LOW",
                "prompt" to "what is the weather",
                "secretApiKey" to "sk-123456",
                "internalRouting" to "true",
                "classificationSource" to "user-input",
            ),
        )
        val record = exporter.export(listOf(event)).single()

        // Allowed keys are preserved
        assertEquals("weather-tool", record.metadata["toolName"])
        assertEquals("LOW", record.metadata["riskLevel"])
        assertEquals("user-input", record.metadata["classificationSource"])

        // Unknown keys are excluded
        assertFalse(record.metadata.containsKey("prompt"))
        assertFalse(record.metadata.containsKey("secretApiKey"))
        assertFalse(record.metadata.containsKey("internalRouting"))

        // Only allowed keys remain
        assertEquals(
            setOf("enforcementPoint", "toolName", "riskLevel", "classificationSource"),
            record.metadata.keys,
        )
        assertEquals("BEFORE_TOOL_EXPOSURE", record.metadata["enforcementPoint"])
    }

    // ─── 14. Payload digest is deterministic (same input = same digest) ─

    @Test
    fun `same audit event produces same digests`() {
        val results1 = exporter.export(listOf(exposureAllowEvent))
        val results2 = exporter.export(listOf(exposureAllowEvent))

        val record1 = results1[0]
        val record2 = results2[0]

        assertEquals(record1.digests.subjectDigest, record2.digests.subjectDigest)
        assertEquals(record1.digests.payloadDigest, record2.digests.payloadDigest)
    }

    @Test
    fun `digests match strict sha256 colon 64 hex format`() {
        val events = listOf(exposureAllowEvent, executionDenyEvent, executionRequireApprovalEvent, reinjectionAllowEvent)
        val results = exporter.export(events)

        assertEquals(4, results.size)
        for (record in results) {
            assertTrue(
                digestRegex.matches(record.digests.subjectDigest),
                "subjectDigest '${record.digests.subjectDigest}' must match $digestRegex",
            )
            assertTrue(
                digestRegex.matches(record.digests.payloadDigest),
                "payloadDigest '${record.digests.payloadDigest}' must match $digestRegex",
            )
        }
    }

    // ─── 15. Different enforcement points produce different payload digests ──

    @Test
    fun `different enforcement points produce different payload digests`() {
        val exposureRecord = exporter.export(listOf(exposureAllowEvent)).single()
        val reinjectionRecord = exporter.export(listOf(reinjectionAllowEvent)).single()

        assertEquals(
            exposureRecord.digests.subjectDigest,
            reinjectionRecord.digests.subjectDigest,
            "subjectDigest depends on auditStreamId only — same stream = same digest",
        )
        assertNotEquals(
            exposureRecord.digests.payloadDigest,
            reinjectionRecord.digests.payloadDigest,
            "payloadDigest includes enforcementPoint in canonical metadata — different enforcement points must produce different digests",
        )
    }

    // ─── 16. Empty input produces empty result ──────────────────────────

    @Test
    fun `empty events list returns empty list`() {
        val results = exporter.export(emptyList())
        assertTrue(results.isEmpty())
    }

    // ─── 17. Invalid decisions are filtered out ──────────────────────────

    @Test
    fun `invalid decision kind REDACT_RESULT is filtered out`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-invalid-decision-001",
            decision = "REDACT_RESULT",
        )
        val results = exporter.export(listOf(event))
        assertTrue(results.isEmpty(), "REDACT_RESULT is not a valid tool.permission decision")
    }

    @Test
    fun `invalid decision kind ALLOW_INTERNAL_ONLY is filtered out`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-invalid-decision-002",
            decision = "ALLOW_INTERNAL_ONLY",
        )
        val results = exporter.export(listOf(event))
        assertTrue(results.isEmpty(), "ALLOW_INTERNAL_ONLY is not a valid tool.permission decision")
    }

    // ─── Additional: Actor is preserved ─────────────────────────────────

    @Test
    fun `actor is preserved in exported record`() {
        val record = exporter.export(listOf(exposureAllowEvent)).single()
        assertEquals("policy-engine", record.actor)
    }

    // ─── Additional: Allowed metadata key set is complete ───────────────

    @Test
    fun `all allowed metadata keys are preserved when present`() {
        val event = exposureAllowEvent.copy(
            eventId = "evt-all-meta-001",
            metadata = mapOf(
                "toolName" to "full-tool",
                "enforcementPoint" to "BEFORE_TOOL_EXPOSURE",
                "riskLevel" to "LOW",
                "classification" to "PUBLIC",
                "classificationSource" to "system",
            ),
        )
        val record = exporter.export(listOf(event)).single()

        assertEquals("full-tool", record.metadata["toolName"])
        assertEquals("BEFORE_TOOL_EXPOSURE", record.metadata["enforcementPoint"])
        assertEquals("LOW", record.metadata["riskLevel"])
        assertEquals("PUBLIC", record.metadata["classification"])
        assertEquals("system", record.metadata["classificationSource"])
    }

    // ─── Additional: Export handles multiple events ─────────────────────

    @Test
    fun `export handles multiple tool enforcement events`() {
        val results = exporter.export(listOf(
            exposureAllowEvent,
            executionDenyEvent,
            executionRequireApprovalEvent,
            reinjectionAllowEvent,
        ))
        assertEquals(4, results.size)

        assertEquals("tool.permission", results[0].eventType)
        assertEquals("ALLOW", results[0].decision.kind)
        assertEquals("evt-exposure-allow-001", results[0].eventId)

        assertEquals("tool.permission", results[1].eventType)
        assertEquals("DENY", results[1].decision.kind)
        assertEquals("evt-execution-deny-001", results[1].eventId)

        assertEquals("tool.permission", results[2].eventType)
        assertEquals("REQUIRE_APPROVAL", results[2].decision.kind)
        assertEquals("evt-execution-approval-001", results[2].eventId)

        assertEquals("tool.permission", results[3].eventType)
        assertEquals("ALLOW", results[3].decision.kind)
        assertEquals("evt-reinjection-allow-001", results[3].eventId)
    }

    // ─── Additional: Mixed events filter correctly ──────────────────────

    @Test
    fun `mixed tool and non-tool events filter correctly`() {
        val nonToolEvent = exposureAllowEvent.copy(
            eventId = "evt-non-tool-002",
            enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
            metadata = mapOf("providerName" to "ollama"),
        )
        val results = exporter.export(listOf(nonToolEvent, exposureAllowEvent))
        assertEquals(1, results.size)
        assertEquals("evt-exposure-allow-001", results[0].eventId)
    }
}
