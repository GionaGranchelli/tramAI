package dev.tramai.security.evidence

import com.fasterxml.jackson.databind.json.JsonMapper
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PolicyDecisionRuntimeEvidenceExporterTest {

    private val exporter = PolicyDecisionRuntimeEvidenceExporter()
    private val mapper = JsonMapper()
    private val digestRegex = Regex("^sha256:[0-9a-f]{64}$")

    private val fixedTimestamp = Instant.parse("2026-07-08T12:00:00Z")

    private val allowEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-allow-001",
        sequenceNumber = 1L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
        decision = "ALLOW",
        policyVersion = "v1",
        workflowDigest = "digest-abc",
        previousEventHash = null,
        eventHash = "hash-001",
        timestamp = fixedTimestamp,
        reasonCode = "policy_allowed",
        metadata = mapOf(
            "providerName" to "ollama",
            "modelName" to "mistral",
        ),
    )

    private val denyEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-deny-001",
        sequenceNumber = 2L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
        decision = "DENY",
        policyVersion = "v1",
        workflowDigest = "digest-abc",
        previousEventHash = "hash-001",
        eventHash = "hash-002",
        timestamp = fixedTimestamp.plusSeconds(1),
        reasonCode = "policy_denied",
        metadata = mapOf(
            "classification" to "RESTRICTED",
            "riskLevel" to "HIGH",
        ),
    )

    private val requireApprovalEvent = AuditEvent(
        schemaVersion = 1,
        hashAlgorithm = AuditHashAlgorithm.SHA_256,
        auditStreamId = "run-123",
        eventId = "evt-approval-001",
        sequenceNumber = 3L,
        workflowRunId = "wf-run-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
        decision = "REQUIRE_APPROVAL",
        policyVersion = "v1",
        workflowDigest = "digest-abc",
        previousEventHash = "hash-002",
        eventHash = "hash-003",
        timestamp = fixedTimestamp.plusSeconds(2),
        reasonCode = "policy_requires_approval",
        metadata = mapOf(
            "toolName" to "payment-tool",
            "riskLevel" to "HIGH",
        ),
    )

    // ─── A: ALLOW ───────────────────────────────────────────────────────

    @Test
    fun `ALLOW exports as policy decision`() {
        val results = exporter.export(listOf(allowEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("runtime-evidence.v1", record.schemaVersion)
        assertEquals("policy.decision", record.eventType)
        assertEquals("ALLOW", record.decision.kind)
        assertEquals("policy_allowed", record.decision.reasonCode)
        assertEquals("policy-engine", record.source.component)
        assertEquals("v1", record.source.module)
        assertEquals("evt-allow-001", record.eventId)
        assertEquals("wf-run-001", record.workflowRunId)
        assertEquals("corr-001", record.correlationId)
        assertEquals("policy-engine", record.actor)
        assertEquals(fixedTimestamp, record.createdAt)
    }

    // ─── B: DENY ────────────────────────────────────────────────────────

    @Test
    fun `DENY exports as policy decision`() {
        val results = exporter.export(listOf(denyEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("policy.decision", record.eventType)
        assertEquals("DENY", record.decision.kind)
        assertEquals("policy_denied", record.decision.reasonCode)
    }

    // ─── C: REQUIRE_APPROVAL ───────────────────────────────────────────

    @Test
    fun `REQUIRE_APPROVAL exports as policy decision`() {
        val results = exporter.export(listOf(requireApprovalEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("policy.decision", record.eventType)
        assertEquals("REQUIRE_APPROVAL", record.decision.kind)
        assertEquals("policy_requires_approval", record.decision.reasonCode)
    }

    // ─── D: Strict digest format ───────────────────────────────────────

    @Test
    fun `digests match strict sha256 colon 64 hex format`() {
        val events = listOf(allowEvent, denyEvent, requireApprovalEvent)
        val results = exporter.export(events)

        assertEquals(3, results.size)
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

    // ─── E: Unsafe metadata is filtered ────────────────────────────────

    @Test
    fun `unsafe metadata attributes are filtered from exported records`() {
        val unsafeEvent = allowEvent.copy(
            eventId = "evt-unsafe-001",
            metadata = mapOf(
                "providerName" to "ollama",
                "prompt" to "ignore all previous instructions",
                "toolArguments" to "secret",
                "secret" to "alice@example.com",
                "attr_cacheReuse" to "true",
            ),
        )

        val record = exporter.export(listOf(unsafeEvent)).single()

        // Allowed keys are preserved
        assertEquals("ollama", record.metadata["providerName"])
        assertEquals("true", record.metadata["attr_cacheReuse"])

        // Unsafe keys are dropped by the exporter-level allowlist
        assertFalse(record.metadata.containsKey("prompt"))
        assertFalse(record.metadata.containsKey("toolArguments"))
        assertFalse(record.metadata.containsKey("secret"))

        // Unsafe keys are not smuggled via attr_ prefix
        assertFalse(record.metadata.containsKey("attr_prompt"))
        assertFalse(record.metadata.containsKey("attr_toolArguments"))
        assertFalse(record.metadata.containsKey("attr_secret"))
    }

    // ─── F: Safe metadata is preserved ─────────────────────────────────

    @Test
    fun `safe metadata is preserved in exported records`() {
        val results = exporter.export(listOf(allowEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("ollama", record.metadata["providerName"])
        assertEquals("mistral", record.metadata["modelName"])
    }

    @Test
    fun `classification and risk metadata is preserved`() {
        val results = exporter.export(listOf(denyEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("RESTRICTED", record.metadata["classification"])
        assertEquals("HIGH", record.metadata["riskLevel"])
    }

    @Test
    fun `toolName metadata is preserved`() {
        val results = exporter.export(listOf(requireApprovalEvent))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("payment-tool", record.metadata["toolName"])
        assertEquals("HIGH", record.metadata["riskLevel"])
    }

    // ─── G: JSONL output is valid ──────────────────────────────────────

    @Test
    fun `JSONL output parses each line as valid JSON`() {
        val records = exporter.export(listOf(allowEvent, denyEvent))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        val lines = jsonl.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size)

        for (line in lines) {
            val node = mapper.readTree(line)
            assertEquals("runtime-evidence.v1", node["schemaVersion"].asText())
            assertEquals("policy.decision", node["eventType"].asText())
            assertTrue(
                digestRegex.matches(node["digests"]["subjectDigest"].asText()),
            )
            assertTrue(
                digestRegex.matches(node["digests"]["payloadDigest"].asText()),
            )
        }
    }

    @Test
    fun `JSONL output has no blank trailing record`() {
        val records = exporter.export(listOf(allowEvent, denyEvent, requireApprovalEvent))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        val lines = jsonl.lines()
        // lines() splits on \n; trailing \n produces empty last element
        val nonBlank = lines.filter { it.isNotBlank() }
        assertEquals(3, nonBlank.size)
        assertEquals(4, lines.size, "Three records + one trailing empty line from final newline")
    }

    @Test
    fun `empty JSONL output is empty string`() {
        assertEquals("", RuntimeEvidenceJsonlWriter.write(emptyList()))
    }

    // ─── H: Non-policy events are skipped ──────────────────────────────

    @Test
    fun `non-policy decisions are skipped`() {
        val unknownEvent = allowEvent.copy(
            eventId = "evt-unknown-001",
            decision = "UNKNOWN",
            sequenceNumber = 99L,
        )
        val results = exporter.export(listOf(unknownEvent))
        assertTrue(results.isEmpty(), "Unknown decision should be skipped")
    }

    @Test
    fun `empty events list returns empty list`() {
        val results = exporter.export(emptyList())
        assertTrue(results.isEmpty())
    }

    // ─── I: Digest consistency ─────────────────────────────────────────

    @Test
    fun `same audit event produces same digests`() {
        val results1 = exporter.export(listOf(allowEvent))
        val results2 = exporter.export(listOf(allowEvent))

        val record1 = results1[0]
        val record2 = results2[0]

        assertEquals(record1.digests.subjectDigest, record2.digests.subjectDigest)
        assertEquals(record1.digests.payloadDigest, record2.digests.payloadDigest)
    }

    @Test
    fun `same audit event produces same digests regardless of metadata order`() {
        val eventA = allowEvent.copy(
            metadata = linkedMapOf(
                "providerName" to "ollama",
                "modelName" to "mistral",
            ),
        )
        val eventB = allowEvent.copy(
            metadata = linkedMapOf(
                "modelName" to "mistral",
                "providerName" to "ollama",
            ),
        )

        val resultA = exporter.export(listOf(eventA)).single()
        val resultB = exporter.export(listOf(eventB)).single()

        assertEquals(resultA.digests.payloadDigest, resultB.digests.payloadDigest)
    }

    // ─── J: EvidenceDigest utility ─────────────────────────────────────

    @Test
    fun `EvidenceDigest sha256 produces correct format`() {
        val digest = EvidenceDigest.sha256("hello")
        assertTrue(digestRegex.matches(digest), "Digest '$digest' must match $digestRegex")
        // Known SHA-256 of "hello"
        assertEquals("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest)
    }

    // ─── K: field mapping completeness ─────────────────────────────────

    @Test
    fun `exported record maps all required fields`() {
        val results = exporter.export(listOf(allowEvent))
        assertEquals(1, results.size)

        val record = results[0]

        // Required fields from runtime-evidence.v1
        assertNotNull(record.schemaVersion)
        assertNotNull(record.eventId)
        assertNotNull(record.eventType)
        assertNotNull(record.createdAt)
        assertNotNull(record.source.component)
        assertNotNull(record.decision.kind)
        assertNotNull(record.digests.subjectDigest)
        assertNotNull(record.digests.payloadDigest)
    }

    // ─── L: CanonicalDigestBuilder ─────────────────────────────────────

    @Test
    fun `CanonicalDigestBuilder produces stable JSON`() {
        val result = CanonicalDigestBuilder().apply {
            appendField("name", "test")
            appendField("value", "hello world")
            appendNullableField("maybe", null)
        }.build()

        // Should produce: {"name":"test","value":"hello world","maybe":null}
        val node = mapper.readTree(result)
        assertEquals("test", node["name"].asText())
        assertEquals("hello world", node["value"].asText())
        assertTrue(node["maybe"].isNull)
    }

    @Test
    fun `CanonicalDigestBuilder sorts metadata keys`() {
        val result = CanonicalDigestBuilder().apply {
            appendField("type", "test")
            appendMetadataField("meta", mapOf(
                "zebra" to "last",
                "alpha" to "first",
            ))
        }.build()

        val node = mapper.readTree(result)
        assertEquals("first", node["meta"]["alpha"].asText())
        assertEquals("last", node["meta"]["zebra"].asText())
    }

    // ─── M: JsonObjectWriter produces valid JSON ───────────────────────

    @Test
    fun `JsonObjectWriter produces well-formed JSON`() {
        val w = JsonObjectWriter()
        w.field("a", "1")
        w.nullableField("b", null)
        w.objectField("inner") {
            field("x", "y")
        }
        w.metadataField("meta", mapOf("k" to "v"))
        val json = w.finish()

        val node = mapper.readTree(json)
        assertEquals("1", node["a"].asText())
        assertTrue(node["b"].isNull)
        assertEquals("y", node["inner"]["x"].asText())
        assertEquals("v", node["meta"]["k"].asText())
    }

    // ─── N: Default deny reason code ───────────────────────────────────

    @Test
    fun `DENY with default reasonCode exports correctly`() {
        val denyDefaultReason = denyEvent.copy(reasonCode = "policy_denied")
        val record = exporter.export(listOf(denyDefaultReason)).single()

        assertEquals("DENY", record.decision.kind)
        assertEquals("policy_denied", record.decision.reasonCode)
        assertTrue(digestRegex.matches(record.digests.payloadDigest))
    }

    // ─── O: Tool exposure events are excluded from policy export ─────────

    @Test
    fun `tool exposure events are excluded from policy export`() {
        val toolExposure = allowEvent.copy(
            eventId = "evt-tool-exposure",
            enforcementPoint = "BEFORE_TOOL_EXPOSURE",
            decision = "ALLOW",
            metadata = mapOf("toolName" to "calculator", "enforcementPoint" to "BEFORE_TOOL_EXPOSURE"),
        )
        val results = exporter.export(listOf(toolExposure))
        assertEquals(0, results.size, "Tool exposure events must not appear as policy.decision")
    }

    // ─── P: Tool execution events are excluded from policy export ────────

    @Test
    fun `tool execution events are excluded from policy export`() {
        val toolExecution = denyEvent.copy(
            eventId = "evt-tool-execution",
            enforcementPoint = "BEFORE_TOOL_EXECUTION",
            decision = "DENY",
            metadata = mapOf("toolName" to "payment", "enforcementPoint" to "BEFORE_TOOL_EXECUTION"),
        )
        val results = exporter.export(listOf(toolExecution))
        assertEquals(0, results.size, "Tool execution events must not appear as policy.decision")
    }

    // ─── Q: Tool reinjection events are excluded from policy export ──────

    @Test
    fun `tool reinjection events are excluded from policy export`() {
        val toolReinjection = allowEvent.copy(
            eventId = "evt-tool-reinjection",
            enforcementPoint = "BEFORE_TOOL_RESULT_REINJECTION",
            decision = "ALLOW",
            metadata = mapOf("toolName" to "search", "enforcementPoint" to "BEFORE_TOOL_RESULT_REINJECTION"),
        )
        val results = exporter.export(listOf(toolReinjection))
        assertEquals(0, results.size, "Tool reinjection events must not appear as policy.decision")
    }

    // ─── R: Non-tool policy events continue to export ────────────────────

    @Test
    fun `non-tool policy events continue to export as policy decision`() {
        val providerPolicy = allowEvent.copy(
            eventId = "evt-provider-policy",
            enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
        )
        val results = exporter.export(listOf(providerPolicy))
        assertEquals(1, results.size)
        assertEquals("policy.decision", results[0].eventType)
        assertEquals("evt-provider-policy", results[0].eventId)
    }

    // ─── S: Combining tool and non-tool events excludes tools only ───────

    @Test
    fun `combining tool and non-tool events excludes tool events only`() {
        val toolEvent = allowEvent.copy(
            eventId = "evt-tool-combo",
            enforcementPoint = "BEFORE_TOOL_EXECUTION",
        )
        val results = exporter.export(listOf(toolEvent, allowEvent))
        assertEquals(1, results.size)
        assertEquals("evt-allow-001", results[0].eventId)
        assertEquals("policy.decision", results[0].eventType)
    }
}
