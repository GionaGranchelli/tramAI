package dev.tramai.security.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class RuntimeEvidenceBundleWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private val writer = RuntimeEvidenceBundleWriter()

    private val fixedTimestamp = Instant.parse("2026-07-13T10:00:00Z")

    private val policyRecord = RuntimeEvidenceRecord(
        eventId = "evt-policy-001",
        eventType = "policy.decision",
        workflowRunId = "wf-001",
        correlationId = "corr-001",
        actor = "policy-engine",
        createdAt = fixedTimestamp,
        source = RuntimeEvidenceSource(component = "policy-engine", module = "v1"),
        decision = RuntimeEvidenceDecision(kind = "ALLOW", reasonCode = "policy_allowed"),
        digests = RuntimeEvidenceDigests(
            subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000001",
            payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000002",
        ),
        metadata = mapOf("providerName" to "ollama"),
    )

    private val approvalRecord = RuntimeEvidenceRecord(
        eventId = "evt-approval-001",
        eventType = "approval.decision",
        workflowRunId = "wf-001",
        correlationId = "corr-002",
        actor = "human-approver",
        createdAt = fixedTimestamp.plusSeconds(10),
        source = RuntimeEvidenceSource(component = "approval-control-plane", module = "approval"),
        decision = RuntimeEvidenceDecision(kind = "APPROVED", reasonCode = "approval-approved"),
        digests = RuntimeEvidenceDigests(
            subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000003",
            payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000004",
        ),
        metadata = mapOf("approvalVersion" to "1"),
    )

    private val providerRecord = RuntimeEvidenceRecord(
        eventId = "evt-routing-001",
        eventType = "provider.route",
        workflowRunId = "wf-001",
        correlationId = "corr-003",
        actor = "provider-router",
        createdAt = fixedTimestamp.plusSeconds(20),
        source = RuntimeEvidenceSource(component = "provider-router", module = "tramai-engine"),
        decision = RuntimeEvidenceDecision(kind = "SELECTED", reasonCode = "provider-selected"),
        digests = RuntimeEvidenceDigests(
            subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000005",
            payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000006",
        ),
        metadata = mapOf("requestedModelDigest" to "sha256:abc", "attempt" to "1"),
    )

    // ─── 1. Policy records write to policy-decisions.jsonl ──────────────

    @Test
    fun `policy records write to policy-decisions jsonl`() {
        val result = writer.write(tempDir, listOf(policyRecord))
        val file = result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue(content.contains("\"eventType\":\"policy.decision\""))
        assertTrue(content.contains("\"kind\":\"ALLOW\""))
        assertTrue(content.endsWith("\n"))
    }

    // ─── 2. Approval records write to approval-decisions.jsonl ──────────

    @Test
    fun `approval records write to approval-decisions jsonl`() {
        val result = writer.write(tempDir, listOf(approvalRecord))
        val file = result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue(content.contains("\"eventType\":\"approval.decision\""))
        assertTrue(content.contains("\"kind\":\"APPROVED\""))
    }

    // ─── 3. Provider records write to provider-routing.jsonl ────────────

    @Test
    fun `provider records write to provider-routing jsonl`() {
        val result = writer.write(tempDir, listOf(providerRecord))
        val file = result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")
        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertTrue(content.contains("\"eventType\":\"provider.route\""))
        assertTrue(content.contains("\"kind\":\"SELECTED\""))
    }

    // ─── 4. Mixed records produce all three files ───────────────────────

    @Test
    fun `mixed records produce all three files`() {
        val result = writer.write(tempDir, listOf(policyRecord, approvalRecord, providerRecord))
        assertEquals(3, result.writtenFiles.size)
        assertEquals(3, result.countsByEventType.size)
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")))
    }

    // ─── 5. Records are deterministically ordered ───────────────────────

    @Test
    fun `records are deterministically ordered by createdAt then eventId`() {
        val early = policyRecord.copy(eventId = "z-policy")
        val late = policyRecord.copy(
            eventId = "a-policy",
            createdAt = fixedTimestamp.plusSeconds(1),
        )
        val result = writer.write(tempDir, listOf(late, early))
        val content = Files.readString(
            result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        )
        val lines = content.trimEnd().lines()
        assertEquals(2, lines.size)
        // z-policy comes first (earlier createdAt)
        assertTrue(lines[0].contains("z-policy"))
        // a-policy comes second
        assertTrue(lines[1].contains("a-policy"))
    }

    // ─── 6. Empty event families do not produce files ───────────────────

    @Test
    fun `empty event families do not produce files`() {
        val result = writer.write(tempDir, listOf(policyRecord))
        assertFalse(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
        assertFalse(Files.exists(result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")))
    }

    // ─── 7. Empty total input fails ─────────────────────────────────────

    @Test
    fun `empty total input fails`() {
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, emptyList())
        }
        assertTrue(ex.message!!.contains("must not be empty"))
    }

    // ─── 8. Unknown event type fails before filesystem mutation ─────────

    @Test
    fun `unknown event type fails before filesystem mutation`() {
        val badRecord = policyRecord.copy(eventType = "unknown.decision")
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("Unknown event type"))
        // No runtime-evidence directory should exist
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence")))
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence.tmp")))
    }

    // ─── 9. Invalid decision kind fails ─────────────────────────────────

    @Test
    fun `invalid decision kind fails`() {
        val badRecord = policyRecord.copy(
            decision = RuntimeEvidenceDecision(kind = "INVALID_KIND", reasonCode = null)
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("Invalid decision.kind"))
    }

    // ─── 10. Invalid schema version fails ───────────────────────────────

    @Test
    fun `invalid schema version fails`() {
        val badRecord = policyRecord.copy(schemaVersion = "evidences.v2")
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("Unsupported schemaVersion"))
    }

    // ─── 11. Duplicate event ID fails ───────────────────────────────────

    @Test
    fun `duplicate event ID fails`() {
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(policyRecord, policyRecord))
        }
        assertTrue(ex.message!!.contains("Duplicate runtime evidence eventId"))
    }

    // ─── 12. Existing section is replaced rather than merged ────────────

    @Test
    fun `existing section is replaced rather than merged`() {
        // First write
        writer.write(tempDir, listOf(policyRecord))
        // Second write with only approval record
        val result = writer.write(tempDir, listOf(approvalRecord))
        // policy-decisions.jsonl should not exist anymore
        assertFalse(Files.exists(result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
    }

    // ─── 13. Failed write leaves previous section intact ────────────────

    @Test
    fun `failed write leaves previous section intact`() {
        writer.write(tempDir, listOf(policyRecord))
        val policyPath = tempDir.resolve("runtime-evidence/policy-decisions.jsonl")
        val contentBefore = Files.readString(policyPath)

        // Attempt a write with an invalid record
        try {
            writer.write(tempDir, listOf(approvalRecord.copy(eventType = "bad.type")))
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // Previous evidence section should still be intact
        assertTrue(Files.exists(policyPath))
        assertEquals(contentBefore, Files.readString(policyPath))
    }

    // ─── 14. JSONL files end with exactly one trailing newline ──────────

    @Test
    fun `jsonl files end with exactly one trailing newline`() {
        val result = writer.write(tempDir, listOf(policyRecord))
        val content = Files.readString(
            result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        )
        assertTrue(content.endsWith("\n"))
        // Single newline: last char is \n and the content before that doesn't end with \n
        val withoutLast = content.removeSuffix("\n")
        assertFalse(withoutLast.endsWith("\n"))
    }

    // ─── 15. Output contains no temporary files after success ──────────

    @Test
    fun `output contains no temporary files after success`() {
        writer.write(tempDir, listOf(policyRecord, approvalRecord, providerRecord))
        // No .tmp directory should remain
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence.tmp")))
        // Only the three JSONL files and the runtime-evidence directory
        val files = tempDir.resolve("runtime-evidence").toFile().listFiles() ?: emptyArray()
        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        assertEquals(3, jsonlFiles.size)
    }

    // ─── 16. Valid digests pass through writer ─────────────────────────

    @Test
    fun `valid digests pass through writer`() {
        // Digest format is validated by RuntimeEvidenceDigests.init at construction.
        // This test confirms that valid digests are accepted by the writer.
        val result = writer.write(tempDir, listOf(policyRecord))
        assertEquals(1, result.countsByEventType["policy.decision"])
    }

    // ─── 17. write result metadata is correct ───────────────────────────

    @Test
    fun `write result contains correct paths and counts`() {
        val result = writer.write(tempDir, listOf(policyRecord, policyRecord.copy(
            eventId = "evt-policy-002",
            createdAt = fixedTimestamp.plusSeconds(1),
            digests = RuntimeEvidenceDigests(
                subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000007",
                payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000008",
            ),
        )))
        assertEquals(tempDir.resolve("runtime-evidence"), result.runtimeEvidenceDirectory)
        assertEquals(listOf("policy-decisions.jsonl"), result.writtenFiles.map { it.toString() })
        assertEquals(mapOf("policy.decision" to 2), result.countsByEventType)
    }

    // ─── 18. Multiple records in same family produce one file ───────────

    @Test
    fun `multiple records in same event family produce one JSONL file`() {
        val record2 = policyRecord.copy(
            eventId = "evt-policy-002",
            createdAt = fixedTimestamp.plusSeconds(1),
            digests = RuntimeEvidenceDigests(
                subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000007",
                payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000008",
            ),
        )
        val result = writer.write(tempDir, listOf(policyRecord, record2))
        val content = Files.readString(
            result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        )
        val lines = content.trimEnd().lines()
        assertEquals(2, lines.size)
    }

    // ─── 19. Concurrent event types with same createdAt sort by eventId ─

    @Test
    fun `same family records with same createdAt sort by eventId`() {
        val aRecord = policyRecord.copy(
            eventId = "evt-a",
            digests = RuntimeEvidenceDigests(
                subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000007",
                payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000008",
            ),
        )
        val bRecord = policyRecord.copy(
            eventId = "evt-b",
            digests = RuntimeEvidenceDigests(
                subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000009",
                payloadDigest = "sha256:000000000000000000000000000000000000000000000000000000000000000a",
            ),
        )
        val result = writer.write(tempDir, listOf(bRecord, aRecord))
        val content = Files.readString(
            result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")
        )
        val lines = content.trimEnd().lines()
        assertTrue(lines[0].contains("evt-a"), "First line should be evt-a")
        assertTrue(lines[1].contains("evt-b"), "Second line should be evt-b")
    }
}
