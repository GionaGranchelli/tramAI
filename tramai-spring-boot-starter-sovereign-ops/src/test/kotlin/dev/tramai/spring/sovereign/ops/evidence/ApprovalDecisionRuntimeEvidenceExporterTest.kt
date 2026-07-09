package dev.tramai.spring.sovereign.ops.evidence

import com.fasterxml.jackson.databind.json.JsonMapper
import dev.tramai.security.evidence.RuntimeEvidenceJsonlWriter
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ApprovalDecisionRuntimeEvidenceExporterTest {

    private val exporter = ApprovalDecisionRuntimeEvidenceExporter()
    private val mapper = JsonMapper()
    private val digestRegex = Regex("^sha256:[0-9a-f]{64}$")

    private val fixedTimestamp = Instant.parse("2026-07-08T12:00:00Z")

    private val approvedRecord = SovereignOpsAuditOutboxRecord(
        outboxId = "outbox-approved-001",
        aggregateType = "approval",
        aggregateIdDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        operation = "approval-approved.approval-001",
        eventKey = "approval-approved.approval-001",
        actor = "medical-ops-reviewer",
        workflowRunId = "wf-approve-001",
        correlationId = "corr-approve-001",
        approvalStatus = "APPROVED",
        approvalVersion = 1L,
        reasonDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        reasonLength = 29,
        createdAt = fixedTimestamp,
        status = SovereignOpsAuditOutboxStatus.PENDING,
    )

    private val deniedRecord = SovereignOpsAuditOutboxRecord(
        outboxId = "outbox-denied-001",
        aggregateType = "approval",
        aggregateIdDigest = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        operation = "approval-denied.approval-002",
        eventKey = "approval-denied.approval-002",
        actor = "medical-ops-reviewer",
        workflowRunId = "wf-deny-001",
        correlationId = "corr-deny-001",
        approvalStatus = "DENIED",
        approvalVersion = 2L,
        reasonDigest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        reasonLength = 25,
        createdAt = fixedTimestamp.plusSeconds(10),
        status = SovereignOpsAuditOutboxStatus.EMITTED,
    )

    // ─── A: Approved ───────────────────────────────────────────────────

    @Test
    fun `APPROVED exports as approval decision`() {
        val results = exporter.export(listOf(approvedRecord))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("runtime-evidence.v1", record.schemaVersion)
        assertEquals("approval.decision", record.eventType)
        assertEquals("APPROVED", record.decision.kind)
        assertEquals("approval-approved", record.decision.reasonCode)
        assertEquals("approval-control-plane", record.source.component)
        assertEquals("approval", record.source.module)
        assertEquals("outbox-approved-001", record.eventId)
        assertEquals("wf-approve-001", record.workflowRunId)
        assertEquals("corr-approve-001", record.correlationId)
        assertEquals("medical-ops-reviewer", record.actor)
        assertEquals(fixedTimestamp, record.createdAt)
    }

    // ─── B: Denied ─────────────────────────────────────────────────────

    @Test
    fun `DENIED exports as approval decision`() {
        val results = exporter.export(listOf(deniedRecord))
        assertEquals(1, results.size)

        val record = results[0]
        assertEquals("approval.decision", record.eventType)
        assertEquals("DENIED", record.decision.kind)
        assertEquals("approval-denied", record.decision.reasonCode)
    }

    // ─── C: Raw comment not exported ───────────────────────────────────

    @Test
    fun `raw comment is not exported only digest and length are present`() {
        val record = exporter.export(listOf(approvedRecord)).single()

        // The record has reasonDigest (sha256) and reasonLength — no raw comment
        assertTrue(digestRegex.matches(record.metadata["reasonDigest"] ?: ""))
        assertEquals("29", record.metadata["reasonLength"])
        // Assert no raw comment text in JSONL output
        val jsonl = RuntimeEvidenceJsonlWriter.write(listOf(record))
        assertFalse(jsonl.contains("Medical necessity"))
    }

    // ─── D: Repeat decisions — exporter is input-faithful ──────────────

    @Test
    fun `duplicate outbox records are both exported`() {
        val results = exporter.export(listOf(approvedRecord, approvedRecord))
        assertEquals(2, results.size, "Exporter should transform faithfully, not de-duplicate")
        assertEquals(results[0].eventId, results[1].eventId)
    }

    // ─── E: Strict digest format ───────────────────────────────────────

    @Test
    fun `all digests match sha256 colon 64 hex format`() {
        val results = exporter.export(listOf(approvedRecord, deniedRecord))
        assertEquals(2, results.size)

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

    @Test
    fun `reasonDigest in metadata matches strict format`() {
        val record = exporter.export(listOf(approvedRecord)).single()
        assertTrue(
            digestRegex.matches(record.metadata["reasonDigest"] ?: ""),
            "reasonDigest '${record.metadata["reasonDigest"]}' must match $digestRegex",
        )
    }

    // ─── F: JSONL parses as valid JSON ─────────────────────────────────

    @Test
    fun `JSONL output parses each line as valid JSON`() {
        val records = exporter.export(listOf(approvedRecord, deniedRecord))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        val lines = jsonl.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size)

        for (line in lines) {
            val node = mapper.readTree(line)
            assertEquals("runtime-evidence.v1", node["schemaVersion"].asText())
            assertEquals("approval.decision", node["eventType"].asText())
        }

        // First line is APPROVED
        val first = mapper.readTree(lines[0])
        assertEquals("APPROVED", first["decision"]["kind"].asText())

        // Second line is DENIED
        val second = mapper.readTree(lines[1])
        assertEquals("DENIED", second["decision"]["kind"].asText())
    }

    @Test
    fun `empty JSONL output is empty string`() {
        assertEquals("", RuntimeEvidenceJsonlWriter.write(emptyList()))
    }

    // ─── G: Non-approval records are skipped ───────────────────────────

    @Test
    fun `non-approval outbox records are skipped`() {
        val nonApprovalRecords = listOf(
            approvedRecord.copy(
                aggregateType = "worker",
                operation = "heartbeat",
                eventKey = "heartbeat",
            ),
            approvedRecord.copy(
                aggregateType = "approval",
                operation = "some-other-operation",
                eventKey = "some-other-operation",
            ),
        )

        val results = exporter.export(nonApprovalRecords)
        assertTrue(results.isEmpty(), "Non-approval records should be skipped")
    }

    @Test
    fun `empty records list returns empty list`() {
        val results = exporter.export(emptyList())
        assertTrue(results.isEmpty())
    }

    // ─── H: Unsafe fields not present ──────────────────────────────────

    @Test
    fun `raw approval ID is not present in JSONL output`() {
        val records = exporter.export(listOf(approvedRecord, deniedRecord))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        // Raw approval IDs from eventKey should not appear
        assertFalse(jsonl.contains("approval-001"), "Raw approval ID should not appear")
        assertFalse(jsonl.contains("approval-002"), "Raw approval ID should not appear")
    }

    @Test
    fun `raw event key is not present in metadata only digest`() {
        val records = exporter.export(listOf(approvedRecord, deniedRecord))

        for (record in records) {
            // eventKeyDigest is a SHA-256, not raw eventKey
            assertTrue(
                digestRegex.matches(record.metadata["eventKeyDigest"] ?: ""),
                "eventKeyDigest should be sha256:hex format",
            )
            assertFalse(
                record.metadata.containsKey("eventKey"),
                "Raw eventKey must never appear in metadata",
            )
        }
    }

    @Test
    fun `raw decision comment not present in JSONL`() {
        val records = exporter.export(listOf(approvedRecord, deniedRecord))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        // Even if the original comment was something identifiable, it's not in the outbox
        assertFalse(jsonl.contains("approval-approved.approval"), "Raw operation string should not appear as text")
    }

    // ─── I: Metadata shape ─────────────────────────────────────────────

    @Test
    fun `metadata contains safe fields only`() {
        val record = exporter.export(listOf(approvedRecord)).single()

        assertEquals("1", record.metadata["approvalVersion"])
        assertEquals("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", record.metadata["reasonDigest"])
        assertEquals("29", record.metadata["reasonLength"])
        assertEquals("PENDING", record.metadata["outboxStatus"])
        assertTrue(record.metadata.containsKey("eventKeyDigest"))

        // Never raw
        assertFalse(record.metadata.containsKey("eventKey"))
        assertFalse(record.metadata.containsKey("operation"))
        assertFalse(record.metadata.containsKey("aggregateId"))
    }

    // ─── J: Digest consistency ─────────────────────────────────────────

    @Test
    fun `same outbox record produces same digests`() {
        val results1 = exporter.export(listOf(approvedRecord))
        val results2 = exporter.export(listOf(approvedRecord))

        assertEquals(
            results1[0].digests.subjectDigest,
            results2[0].digests.subjectDigest,
        )
        assertEquals(
            results1[0].digests.payloadDigest,
            results2[0].digests.payloadDigest,
        )
    }

    // ─── K: All required fields present ────────────────────────────────

    @Test
    fun `exported record has all required runtime-evidence v1 fields`() {
        val record = exporter.export(listOf(approvedRecord)).single()

        assertTrue(record.schemaVersion.isNotEmpty())
        assertTrue(record.eventId.isNotEmpty())
        assertTrue(record.eventType.isNotEmpty())
        assertTrue(record.source.component.isNotEmpty())
        assertTrue(record.decision.kind.isNotEmpty())
        assertTrue(digestRegex.matches(record.digests.subjectDigest))
        assertTrue(digestRegex.matches(record.digests.payloadDigest))
    }
}
