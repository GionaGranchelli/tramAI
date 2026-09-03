package dev.tramai.engine.benchmark

import dev.tramai.engine.evidence.ProviderRouteDecisionEvidenceSource
import dev.tramai.engine.evidence.ProviderRouteDecisionKind
import dev.tramai.engine.evidence.ProviderRoutingRuntimeEvidenceExporter
import dev.tramai.testing.benchmark.BenchmarkHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.time.Instant

/**
 * B09 — evidence export. Converts a 3-record provider-route batch (SELECTED,
 * FALLBACK, BLOCKED) into runtime-evidence.v1 records. Mirrors the
 * ProviderRoutingRuntimeEvidenceExporterTest multi-source fixture; sources
 * are built once, export is timed.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class EvidenceExportBenchmark {
    private val exporter = ProviderRoutingRuntimeEvidenceExporter()
    private val fixedTimestamp = Instant.parse("2026-07-09T10:00:00Z")

    private val selected =
        ProviderRouteDecisionEvidenceSource(
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

    private val fallback =
        ProviderRouteDecisionEvidenceSource(
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

    private val blocked =
        ProviderRouteDecisionEvidenceSource(
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

    private val batch = listOf(selected, fallback, blocked)

    @Test
    fun `B09 evidence export latency`() {
        val probe = exporter.export(batch)
        assertEquals(3, probe.size, "fixture batch must export three records")

        val (meanUs, p50Us, p95Us) =
            BenchmarkHarness.latency(
                operation = "B09-evidence-export",
                module = "tramai-engine",
                fixture =
                    "ProviderRoutingRuntimeEvidenceExporter.export(3-record batch: " +
                        "SELECTED + FALLBACK + BLOCKED, digests precomputed)",
            ) {
                val records = exporter.export(batch)
                assertEquals(3, records.size)
            }
        assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
    }
}
