package dev.tramai.engine.evidence

import com.fasterxml.jackson.databind.json.JsonMapper
import dev.tramai.security.evidence.EvidenceDigest
import dev.tramai.security.evidence.RuntimeEvidenceJsonlWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProviderRoutingRuntimeEvidenceExporterTest {

    private val exporter = ProviderRoutingRuntimeEvidenceExporter()
    private val mapper = JsonMapper()
    private val digestRegex = Regex("^sha256:[0-9a-f]{64}$")

    private val fixedTimestamp = Instant.parse("2026-07-09T10:00:00Z")
    private val primaryDigest = EvidenceDigest.sha256("primary-provider")
    private val fallbackDigest = EvidenceDigest.sha256("fallback-provider")
    private val modelDigest = EvidenceDigest.sha256("gpt-model-name")

    private val selectedSource = ProviderRouteDecisionEvidenceSource(
        eventId = "route-ev-001",
        workflowRunId = "wf-route-001",
        correlationId = "corr-route-001",
        actor = "tramai-engine",
        createdAt = fixedTimestamp,
        decisionKind = ProviderRouteDecisionKind.SELECTED,
        requestedModelName = "gpt-model-name",
        selectedProviderName = "primary-provider",
        selectedModelName = "gpt-model-name",
        routeIndex = 0,
        attempt = 1,
    )

    private val fallbackSource = ProviderRouteDecisionEvidenceSource(
        eventId = "route-ev-002",
        workflowRunId = "wf-route-001",
        correlationId = "corr-route-001",
        actor = "tramai-engine",
        createdAt = fixedTimestamp.plusSeconds(5),
        decisionKind = ProviderRouteDecisionKind.FALLBACK,
        requestedModelName = "gpt-model-name",
        selectedProviderName = "fallback-provider",
        selectedModelName = "gpt-model-name",
        previousProviderName = "primary-provider",
        previousModelName = "gpt-model-name",
        fallbackReason = "circuit-breaker-open",
        routeIndex = 1,
        attempt = 2,
    )

    private val blockedSource = ProviderRouteDecisionEvidenceSource(
        eventId = "route-ev-003",
        workflowRunId = "wf-route-002",
        correlationId = "corr-route-002",
        actor = "tramai-engine",
        createdAt = fixedTimestamp.plusSeconds(10),
        decisionKind = ProviderRouteDecisionKind.BLOCKED,
        requestedModelName = "restricted-model",
        selectedProviderName = null,
        selectedModelName = null,
        fallbackReason = "model-registry-blocked",
    )

    // ─── A: SELECTED ───────────────────────────────────────────────────

    @Test
    fun `SELECTED route exports as provider route`() {
        val record = exporter.export(listOf(selectedSource)).single()

        assertEquals("runtime-evidence.v1", record.schemaVersion)
        assertEquals("provider.route", record.eventType)
        assertEquals("SELECTED", record.decision.kind)
        assertEquals("provider-selected", record.decision.reasonCode)
        assertEquals("provider-router", record.source.component)
        assertEquals("tramai-engine", record.source.module)
        assertEquals("route-ev-001", record.eventId)
        assertEquals("wf-route-001", record.workflowRunId)
        assertEquals("corr-route-001", record.correlationId)
        assertEquals("tramai-engine", record.actor)
        assertEquals(fixedTimestamp, record.createdAt)
    }

    // ─── B: FALLBACK ───────────────────────────────────────────────────

    @Test
    fun `FALLBACK route exports with fallback reason`() {
        val record = exporter.export(listOf(fallbackSource)).single()

        assertEquals("provider.route", record.eventType)
        assertEquals("FALLBACK", record.decision.kind)
        assertEquals("provider-fallback", record.decision.reasonCode)
        assertEquals("circuit-breaker-open", record.metadata["fallbackReason"])
        assertEquals("1", record.metadata["routeIndex"])
        assertEquals("2", record.metadata["attempt"])
    }

    // ─── C: BLOCKED ────────────────────────────────────────────────────

    @Test
    fun `BLOCKED route exports without selected provider`() {
        val record = exporter.export(listOf(blockedSource)).single()

        assertEquals("provider.route", record.eventType)
        assertEquals("BLOCKED", record.decision.kind)
        assertEquals("provider-blocked", record.decision.reasonCode)
        assertEquals("model-registry-blocked", record.metadata["fallbackReason"])
        assertFalse(record.metadata.containsKey("selectedProviderDigest"))
        assertFalse(record.metadata.containsKey("selectedModelDigest"))
    }

    // ─── D: Raw provider/model names not exported ──────────────────────

    @Test
    fun `raw provider name is not exported`() {
        val records = exporter.export(listOf(selectedSource, fallbackSource))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        assertFalse(jsonl.contains("primary-provider"))
        assertFalse(jsonl.contains("fallback-provider"))
        assertFalse(jsonl.contains("gpt-model-name"))
        assertFalse(jsonl.contains("restricted-model"))
    }

    @Test
    fun `metadata contains digest fields not raw names`() {
        val record = exporter.export(listOf(selectedSource)).single()

        // Digests are present
        assertEquals(modelDigest, record.metadata["requestedModelDigest"])
        assertEquals(primaryDigest, record.metadata["selectedProviderDigest"])
        assertEquals(modelDigest, record.metadata["selectedModelDigest"])

        // Raw names are never present
        assertFalse(record.metadata.containsKey("selectedProviderName"))
        assertFalse(record.metadata.containsKey("selectedModelName"))
        assertFalse(record.metadata.containsKey("requestedModelName"))
    }

    // ─── E: Strict digest format ───────────────────────────────────────

    @Test
    fun `all digests match sha256 colon 64 hex format`() {
        val records = exporter.export(listOf(selectedSource, fallbackSource, blockedSource))
        assertEquals(3, records.size)

        for (record in records) {
            assertTrue(digestRegex.matches(record.digests.subjectDigest))
            assertTrue(digestRegex.matches(record.digests.payloadDigest))
            for ((_, value) in record.metadata) {
                if (value.startsWith("sha256:")) {
                    assertTrue(digestRegex.matches(value), "Digest '$value' must match $digestRegex")
                }
            }
        }
    }

    // ─── F: Fallback reason allowlist ──────────────────────────────────

    @Test
    fun `allowed fallback reasons pass through`() {
        for (code in ALLOWED_ROUTING_REASON_CODES) {
            val source = fallbackSource.copy(fallbackReason = code)
            val record = exporter.export(listOf(source)).single()
            assertEquals(code, record.metadata["fallbackReason"], "Code '$code' should pass through")
        }
    }

    @Test
    fun `unknown fallback reason is normalized`() {
        val source = fallbackSource.copy(fallbackReason = "arbitrary-custom-reason")
        val record = exporter.export(listOf(source)).single()
        assertEquals("provider-fallback", record.metadata["fallbackReason"])
    }

    @Test
    fun `unknown block reason is normalized`() {
        val source = blockedSource.copy(fallbackReason = "unknown-internal-reason")
        val record = exporter.export(listOf(source)).single()
        assertEquals("provider-blocked", record.metadata["fallbackReason"])
    }

    @Test
    fun `SELECTED does not emit fallbackReason`() {
        val source = selectedSource.copy(fallbackReason = "should-not-appear")
        val record = exporter.export(listOf(source)).single()
        assertFalse(record.metadata.containsKey("fallbackReason"))
    }

    // ─── G: JSONL parses as valid JSON ─────────────────────────────────

    @Test
    fun `JSONL output parses each line as valid JSON`() {
        val records = exporter.export(listOf(selectedSource, fallbackSource))
        val jsonl = RuntimeEvidenceJsonlWriter.write(records)

        val lines = jsonl.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(2, lines.size)

        for (line in lines) {
            val node = mapper.readTree(line)
            assertEquals("runtime-evidence.v1", node["schemaVersion"].asText())
            assertEquals("provider.route", node["eventType"].asText())
        }

        // First line is SELECTED
        val first = mapper.readTree(lines[0])
        assertEquals("SELECTED", first["decision"]["kind"].asText())

        // Second line is FALLBACK
        val second = mapper.readTree(lines[1])
        assertEquals("FALLBACK", second["decision"]["kind"].asText())
    }

    @Test
    fun `empty JSONL output is empty string`() {
        assertEquals("", RuntimeEvidenceJsonlWriter.write(emptyList()))
    }

    // ─── H: Canonical digest determinism ───────────────────────────────

    @Test
    fun `same source produces same digests`() {
        val results1 = exporter.export(listOf(selectedSource))
        val results2 = exporter.export(listOf(selectedSource))

        assertEquals(results1[0].digests.subjectDigest, results2[0].digests.subjectDigest)
        assertEquals(results1[0].digests.payloadDigest, results2[0].digests.payloadDigest)
    }

    @Test
    fun `different decision kinds produce different payload digests`() {
        val selected = exporter.export(listOf(selectedSource)).single()
        val fallback = exporter.export(listOf(fallbackSource)).single()
        val blocked = exporter.export(listOf(blockedSource)).single()

        assertTrue(selected.digests.payloadDigest != fallback.digests.payloadDigest)
        assertTrue(selected.digests.payloadDigest != blocked.digests.payloadDigest)
        assertTrue(fallback.digests.payloadDigest != blocked.digests.payloadDigest)
    }

    // ─── I: Fallback metadata shape ───────────────────────────────────

    @Test
    fun `FALLBACK record carries previous provider and model digests`() {
        val record = exporter.export(listOf(fallbackSource)).single()

        assertTrue(digestRegex.matches(record.metadata["previousProviderDigest"] ?: ""))
        assertTrue(digestRegex.matches(record.metadata["previousModelDigest"] ?: ""))
        assertEquals(primaryDigest, record.metadata["previousProviderDigest"])
        assertEquals(modelDigest, record.metadata["previousModelDigest"])
    }

    // ─── J: Required fields present ────────────────────────────────────

    @Test
    fun `exported record has all required runtime-evidence v1 fields`() {
        val record = exporter.export(listOf(selectedSource)).single()

        assertNotNull(record.schemaVersion)
        assertNotNull(record.eventId)
        assertNotNull(record.eventType)
        assertNotNull(record.source.component)
        assertNotNull(record.decision.kind)
        assertTrue(digestRegex.matches(record.digests.subjectDigest))
        assertTrue(digestRegex.matches(record.digests.payloadDigest))
    }

    // ─── K: Subject digest stability ───────────────────────────────────

    @Test
    fun `same route targets produce same subject digest`() {
        val source1 = selectedSource
        val source2 = selectedSource.copy(
            eventId = "different-route-id",
            createdAt = fixedTimestamp.plusSeconds(100),
        )
        assertEquals(
            exporter.export(listOf(source1)).single().digests.subjectDigest,
            exporter.export(listOf(source2)).single().digests.subjectDigest,
        )
    }

    @Test
    fun `different route targets produce different subject digests`() {
        val source1 = selectedSource
        val source2 = selectedSource.copy(selectedProviderName = "other-provider")
        assertTrue(
            exporter.export(listOf(source1)).single().digests.subjectDigest !=
                exporter.export(listOf(source2)).single().digests.subjectDigest,
        )
    }
}
