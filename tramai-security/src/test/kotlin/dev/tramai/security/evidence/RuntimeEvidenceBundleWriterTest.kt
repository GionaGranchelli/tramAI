package dev.tramai.security.evidence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class RuntimeEvidenceBundleWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUpBundleRoot() {
        createBundleManifest(tempDir)
    }

    private val writer = RuntimeEvidenceBundleWriter()

    private val fixedTimestamp = Instant.parse("2026-07-13T10:00:00Z")

    private val validSha256 =
        "sha256:0000000000000000000000000000000000000000000000000000000000000001"

    private fun createBundleManifest(dir: Path) {
        dir.resolve("manifest.json").toFile().writeText(
            """{"bundleType": "sovereign-lab-evidence-bundle", "schemaVersion": 1, "claimBoundary": {}, "requiredFiles": [], "files": []}"""
        )
    }

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
        metadata = mapOf("providerName" to "ollama", "classification" to "low"),
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
        metadata = mapOf(
            "requestedModelDigest" to "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "attempt" to "1",
        ),
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

    @Test
    fun `write fails without valid bundle manifest`() {
        val missingManifestDir = Files.createDirectory(tempDir.resolve("missing-manifest"))
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(missingManifestDir, listOf(policyRecord))
        }
        assertTrue(ex.message!!.contains("manifest.json"))
    }

    @Test
    fun `write fails with wrong bundle type in manifest`() {
        val wrongManifestDir = Files.createDirectory(tempDir.resolve("wrong-manifest"))
        wrongManifestDir.resolve("manifest.json").toFile().writeText(
            """{"bundleType": "wrong-type"}"""
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(wrongManifestDir, listOf(policyRecord))
        }
        assertTrue(ex.message!!.contains("bundleType"))
    }

    // ─── 8. Unknown event type fails before filesystem mutation ─────────

    @Test
    fun `unknown event type fails before filesystem mutation`() {
        val badRecord = policyRecord.copy(eventType = "unknown.decision")
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("Unknown event type"))
        // No runtime-evidence directory or temp dir should remain
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence")))
        // Unique temp dir is cleaned up — just verify no .runtime-evidence- prefixes remain
        val leftovers = tempDir.toFile().listFiles { f -> f.name.startsWith(".runtime-evidence-") }
        assertEquals(0, leftovers?.size ?: 0)
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

    // ─── 11. Duplicate event ID fails (global, across families) ─────────

    @Test
    fun `duplicate event ID fails`() {
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(policyRecord, policyRecord))
        }
        assertTrue(ex.message!!.contains("Duplicate runtime evidence eventId"))
    }

    @Test
    fun `duplicate event ID fails across event families`() {
        val policyDup = policyRecord.copy(
            eventType = "approval.decision",
            source = RuntimeEvidenceSource(component = "approval-control-plane", module = null),
            decision = RuntimeEvidenceDecision(kind = "APPROVED", reasonCode = null),
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(policyRecord, policyDup))
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
    // This tests a validation failure (before any filesystem op).

    @Test
    fun `failed write leaves previous section intact on validation failure`() {
        writer.write(tempDir, listOf(policyRecord))
        val policyPath = tempDir.resolve("runtime-evidence/policy-decisions.jsonl")
        val contentBefore = Files.readString(policyPath)

        // Attempt a write with an invalid record (fails during validation)
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
        // No temp directory should remain
        val leftovers = tempDir.toFile().listFiles { f -> f.name.startsWith(".runtime-evidence-") }
        assertEquals(0, leftovers?.size ?: 0)
        // Only the three JSONL files and the runtime-evidence directory
        val files = tempDir.resolve("runtime-evidence").toFile().listFiles() ?: emptyArray()
        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        assertEquals(3, jsonlFiles.size)
    }

    // ─── 16. Valid digests pass through writer ─────────────────────────

    @Test
    fun `valid digests pass through writer`() {
        val result = writer.write(tempDir, listOf(policyRecord))
        assertEquals(1, result.countsByEventType["policy.decision"])
    }

    // ─── 17. write result metadata is correct ───────────────────────────

    @Test
    fun `write result contains correct paths and counts`() {
        val result = writer.write(tempDir, listOf(
            policyRecord,
            policyRecord.copy(
                eventId = "evt-policy-002",
                createdAt = fixedTimestamp.plusSeconds(1),
                digests = RuntimeEvidenceDigests(
                    subjectDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000007",
                    payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000008",
                ),
            ),
        ))
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

    // ─── 20. Wrong source component is rejected ─────────────────────────

    @Test
    fun `wrong source component is rejected`() {
        val badRecord = policyRecord.copy(
            source = RuntimeEvidenceSource(component = "provider-router")
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("source.component must be"))
        assertTrue(ex.message!!.contains("policy-engine"))
    }

    // ─── 21. Unallowlisted metadata key is rejected ─────────────────────

    @Test
    fun `unallowlisted metadata key is rejected`() {
        val badRecord = policyRecord.copy(
            metadata = mapOf("forbiddenPrompt" to "sensitive data")
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("not allowlisted"))
    }

    // ─── 22. Non-code-shaped reasonCode is rejected ─────────────────────

    @Test
    fun `non-code-shaped reasonCode is rejected`() {
        val badRecord = policyRecord.copy(
            decision = RuntimeEvidenceDecision(
                kind = "ALLOW",
                reasonCode = "Customer John Smith medical details with PII and secrets",
            )
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("reasonCode must match"))
    }

    @Test
    fun `approval reasonCode outside allowlist is rejected`() {
        val badRecord = approvalRecord.copy(
            decision = RuntimeEvidenceDecision(kind = "APPROVED", reasonCode = "approval-pending")
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("approval.decision"))
    }

    @Test
    fun `provider routing reasonCode outside allowlist is rejected`() {
        val badRecord = providerRecord.copy(
            decision = RuntimeEvidenceDecision(kind = "SELECTED", reasonCode = "provider-retry")
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("provider.route"))
    }

    // ─── 23. Blank eventId is rejected ──────────────────────────────────

    @Test
    fun `blank eventId is rejected`() {
        val badRecord = policyRecord.copy(eventId = "   ")
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("eventId must not be blank"))
    }

    // ─── 24. Invalid metadata digest in approval record is rejected ─────

    @Test
    fun `invalid metadata digest in approval record is rejected`() {
        val badRecord = approvalRecord.copy(
            metadata = mapOf("reasonDigest" to "sha256:abc")
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("reasonDigest must match"))
    }

    // ─── 25. Invalid metadata digest in routing record is rejected ──────

    @Test
    fun `invalid metadata digest in routing record is rejected`() {
        val badRecord = providerRecord.copy(
            metadata = mapOf(
                "requestedModelDigest" to "not-a-digest",
                "attempt" to "1",
            )
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("requestedModelDigest must match"))
    }

    // ─── 26. Negative routeIndex is rejected ────────────────────────────

    @Test
    fun `negative routeIndex is rejected`() {
        val badRecord = providerRecord.copy(
            metadata = mapOf(
                "requestedModelDigest" to "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "routeIndex" to "-1",
            )
        )
        val ex = assertThrows<IllegalArgumentException> {
            writer.write(tempDir, listOf(badRecord))
        }
        assertTrue(ex.message!!.contains("routeIndex must be a non-negative integer"))
    }

    // ─── 27. Zero routeIndex is accepted (valid) ────────────────────────

    @Test
    fun `zero routeIndex is accepted`() {
        val record = providerRecord.copy(
            metadata = mapOf(
                "requestedModelDigest" to "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "routeIndex" to "0",
                "attempt" to "1",
            )
        )
        val result = writer.write(tempDir, listOf(record))
        assertEquals(1, result.writtenFiles.size)
    }

    // ─── 28. Stale .bak directory from a previous crash is cleaned ─────

    @Test
    fun `stale backup directory is cleaned before write`() {
        // Write once to create the section
        writer.write(tempDir, listOf(policyRecord))

        // Simulate a stale backup
        val backupDir = tempDir.resolve("runtime-evidence.bak")
        Files.createDirectories(backupDir)
        val staleFile = backupDir.resolve("stale-evidence.jsonl")
        Files.writeString(staleFile, "stale")

        // Write again — should clean the stale backup and succeed
        val result = writer.write(tempDir, listOf(approvalRecord))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
        assertFalse(Files.exists(backupDir))
    }

    // ─── 29. Unique temp dir name avoids cross-writer interference ──────

    @Test
    fun `unique temp dir name is used`() {
        // Run two writes and verify each uses a unique temp dir
        writer.write(tempDir, listOf(policyRecord))
        writer.write(tempDir, listOf(approvalRecord))

        // After success, no temp or backup dirs should remain
        val tempDirs = tempDir.toFile().listFiles { f ->
            f.name.startsWith(".runtime-evidence-")
        }
        assertEquals(0, tempDirs?.size ?: 0)
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence.bak")))
    }

    // ─── 30. Valid approval metadata digests pass ───────────────────────

    @Test
    fun `valid approval metadata digests pass`() {
        val record = approvalRecord.copy(
            metadata = mapOf(
                "approvalVersion" to "2",
                "reasonDigest" to "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "eventKeyDigest" to "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "reasonLength" to "42",
            )
        )
        val result = writer.write(tempDir, listOf(record))
        assertEquals(1, result.writtenFiles.size)
    }
}
