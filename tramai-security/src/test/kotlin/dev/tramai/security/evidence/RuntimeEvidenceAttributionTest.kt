package dev.tramai.security.evidence

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 0.7.1d discriminators for governed attribution at the evidence boundary.
 *
 * These prove the atomicity rule (nothing, or everything, or fail) and the authoritative-overlay
 * rule that stops caller-supplied metadata from impersonating identity. The run component is
 * checked to stay canonical: it is the record's own `workflowRunId`, never a metadata key.
 */
class RuntimeEvidenceAttributionTest {
    private val complete =
        mapOf(
            "identity.workloadId" to "wl-1",
            "identity.configurationId" to "cfg-1",
            "identity.configurationVersion" to "v3",
            "identity.environmentId" to "env-1",
            "identity.deploymentId" to "dep-1",
        )

    @Test
    fun `legacy evidence with no identity keys stays valid`() {
        RuntimeEvidenceAttribution.validate(null, mapOf("toolName" to "search"))
        RuntimeEvidenceAttribution.validate("run-1", mapOf("toolName" to "search"))
        RuntimeEvidenceAttribution.validate(null, emptyMap())
    }

    @Test
    fun `complete attribution with the canonical run identity is accepted`() {
        RuntimeEvidenceAttribution.validate("run-1", complete)
    }

    @Test
    fun `every partial attribution is rejected rather than treated as legacy`() {
        val keys = RuntimeEvidenceAttribution.metadataKeys.sorted()
        for (size in 1 until keys.size) {
            val partial = keys.take(size).associateWith { "value" }
            assertFailsWith<IllegalStateException>("$size of ${keys.size} keys must fail closed") {
                RuntimeEvidenceAttribution.validate("run-1", partial)
            }
        }
    }

    @Test
    fun `complete attribution without the canonical run identity is rejected`() {
        assertFailsWith<IllegalStateException> {
            RuntimeEvidenceAttribution.validate(null, complete)
        }
        assertFailsWith<IllegalStateException> {
            RuntimeEvidenceAttribution.validate("   ", complete)
        }
    }

    @Test
    fun `framework identity overrides colliding caller metadata`() {
        val merged =
            RuntimeEvidenceAttribution.merge(
                mapOf("identity.workloadId" to "caller-supplied", "toolName" to "search"),
                mapOf("identity.workloadId" to "wl-authoritative"),
            )
        assertEquals("wl-authoritative", merged["identity.workloadId"])
        assertEquals("search", merged["toolName"])
    }

    @Test
    fun `merge preserves caller metadata and rejects non-identity attribution keys`() {
        val merged = RuntimeEvidenceAttribution.merge(mapOf("riskLevel" to "LOW"), complete)
        assertEquals("LOW", merged["riskLevel"])
        assertTrue(RuntimeEvidenceAttribution.metadataKeys.all { it in merged })

        assertFailsWith<IllegalArgumentException> {
            RuntimeEvidenceAttribution.merge(emptyMap(), mapOf("workflowRunId" to "run-2"))
        }
    }

    @Test
    fun `the run component is never carried as a metadata key`() {
        assertTrue(
            RuntimeEvidenceAttribution.metadataKeys.none { it.contains("runId", ignoreCase = true) },
            "runId must stay canonical on the record, not duplicated in metadata",
        )
    }

    @Test
    fun `attribution composes at the validator and leaves the writer vocabulary untouched`() {
        val families = RuntimeEvidenceBundleWriter.ALLOWED_METADATA_KEYS
        assertEquals(
            setOf("policy.decision", "approval.decision", "provider.route", "tool.permission"),
            families.keys.toSet(),
        )
        families.forEach { (family, familyKeys) ->
            assertTrue(
                RuntimeEvidenceAttribution.metadataKeys.none { it in familyKeys },
                "$family keeps its family-specific vocabulary; attribution is composed separately",
            )
        }
    }
}
