package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G2: a `tool.permission` record states which declared boundary enforced the decision and with what
 * strength, and no producer can promote itself to AUTHORITATIVE by asserting it.
 *
 * The chain under test is the repository's own: the event comes from the production [AuditEngine]
 * through the same `AuditEmission` shape [dev.tramai.examples.springaiboundary.BoundaryEvidenceRecorder]
 * uses, and the record comes from the production [ToolPermissionRuntimeEvidenceExporter] and is checked
 * by the production [RuntimeEvidenceContractValidator]. The real Spring AI callback that produces these
 * events end to end is covered by this module's own `GovernanceBoundaryTest` in
 * `examples/spring-ai-authority-boundary`; here the rules themselves are pinned.
 */
class ToolPermissionBoundaryProvenanceTest {
    @Test
    fun `a registered boundary's strength is resolved from the registration and reaches the record`() {
        val record = export(boundaryEvent(metadata = boundaryMetadata()))

        assertEquals(
            BOUNDARY_ID,
            record.metadata[RuntimeBoundaryProvenance.ID_KEY],
            "the declared boundary identifier must reach the record",
        )
        assertEquals(
            RuntimeBoundaryProvenance.AUTHORITATIVE,
            record.metadata[RuntimeBoundaryProvenance.STRENGTH_KEY],
            "the strength must be the one the registration grants, resolved by the framework",
        )
    }

    @Test
    fun `the record still names the decision producer and not the boundary`() {
        val record = export(boundaryEvent(metadata = boundaryMetadata()))

        assertEquals(
            "policy-engine",
            record.source.component,
            "source.component identifies the decision producer; the boundary travels as provenance",
        )
    }

    @Test
    fun `the exported record passes the production contract validator`() {
        val record = export(boundaryEvent(metadata = boundaryMetadata() + completeIdentity()))

        // Validates rather than merely constructing: the writer's own pre-write check is the contract.
        RuntimeEvidenceContractValidator.validate(listOf(record))
    }

    @Test
    fun `a caller-supplied enforcement strength is refused rather than accepted or ignored`() {
        val metadata =
            boundaryMetadata() +
                mapOf(RuntimeBoundaryProvenance.STRENGTH_KEY to RuntimeBoundaryProvenance.OBSERVED)

        val failure = assertFailsWith<IllegalStateException> { export(boundaryEvent(metadata = metadata)) }

        assertTrue(
            failure.message.orEmpty().contains("self-asserted claim"),
            "a caller-supplied strength must be refused as self-assertion, got: ${failure.message}",
        )
    }

    @Test
    fun `an unregistered boundary identifier is refused`() {
        val metadata = boundaryMetadata() + mapOf(RuntimeBoundaryProvenance.ID_KEY to "some-other-adapter")

        val failure = assertFailsWith<IllegalStateException> { export(boundaryEvent(metadata = metadata)) }

        assertTrue(
            failure.message.orEmpty().contains("unregistered enforcement boundary 'some-other-adapter'"),
            "an unregistered boundary must not acquire provenance, got: ${failure.message}",
        )
    }

    @Test
    fun `a boundary registered for another enforcement point is refused`() {
        val metadata = boundaryMetadata()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                export(boundaryEvent(metadata = metadata, enforcementPoint = "BEFORE_TOOL_EXPOSURE"))
            }

        assertTrue(
            failure.message.orEmpty().contains("registered for BEFORE_TOOL_EXECUTION"),
            "a registration must not be applied to an enforcement point it does not cover, got: ${failure.message}",
        )
    }

    @Test
    fun `partial provenance is refused by the validator`() {
        // A record carrying only the identifier is corruption, not legacy evidence.
        val partial = mapOf("toolName" to TOOL_NAME, RuntimeBoundaryProvenance.ID_KEY to BOUNDARY_ID)

        val failure =
            assertFailsWith<IllegalStateException> {
                RuntimeBoundaryProvenance.validate(ENFORCEMENT_POINT, partial)
            }

        assertTrue(
            failure.message.orEmpty().contains("partial enforcement-boundary provenance"),
            "a partial provenance pair must be refused, got: ${failure.message}",
        )
    }

    @Test
    fun `a strength the registration does not grant is refused`() {
        val record =
            recordWith(
                mapOf(
                    "toolName" to TOOL_NAME,
                    "enforcementPoint" to ENFORCEMENT_POINT,
                    RuntimeBoundaryProvenance.ID_KEY to BOUNDARY_ID,
                    RuntimeBoundaryProvenance.STRENGTH_KEY to RuntimeBoundaryProvenance.OBSERVED,
                ),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                RuntimeEvidenceContractValidator.validate(listOf(record))
            }

        assertTrue(
            failure.message.orEmpty().contains("cannot claim"),
            "a boundary may not claim a strength its registration does not grant, got: ${failure.message}",
        )
    }

    @Test
    fun `an unknown enforcement strength is refused`() {
        val metadata =
            mapOf(
                "toolName" to TOOL_NAME,
                "enforcementPoint" to ENFORCEMENT_POINT,
                RuntimeBoundaryProvenance.ID_KEY to BOUNDARY_ID,
                RuntimeBoundaryProvenance.STRENGTH_KEY to "TOTALLY_AUTHORITATIVE",
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                RuntimeBoundaryProvenance.validate(ENFORCEMENT_POINT, metadata)
            }

        assertTrue(
            failure.message.orEmpty().contains("unknown enforcement strength"),
            "the vocabulary is closed, got: ${failure.message}",
        )
    }

    @Test
    fun `legacy evidence without boundary provenance stays valid`() {
        val legacy = mapOf("toolName" to TOOL_NAME, "enforcementPoint" to ENFORCEMENT_POINT)

        // Zero provenance keys is the pre-G2 shape and must stay valid, not be refused.
        RuntimeBoundaryProvenance.validate(ENFORCEMENT_POINT, legacy)
    }

    @Test
    fun `the exported record's digest binds the provenance it advertises`() {
        val event = boundaryEvent(metadata = boundaryMetadata())
        val record = export(event)

        // The advertised digest must be computed over the metadata the record carries. If provenance were
        // overlaid after digesting, the record would advertise a digest that does not bind its own
        // boundary provenance — the same defect the identity overlay is ordered to avoid.
        assertEquals(
            event.computeToolPermissionPayloadDigest(record.metadata),
            record.digests.payloadDigest,
            "the payload digest must be computed over the metadata the record advertises, provenance included",
        )
    }

    @Test
    fun `provenance participates in the canonical payload digest`() {
        val withoutProvenance = boundaryEvent(metadata = familyMetadata())
        val withProvenance = boundaryEvent(metadata = boundaryMetadata())

        assertNotEquals(
            withoutProvenance.computeToolPermissionPayloadDigest(familyMetadata()),
            withProvenance.computeToolPermissionPayloadDigest(boundaryMetadata()),
            "a provenance-bearing record must not share a payload digest with the same record without provenance",
        )
    }

    @Test
    fun `the digest binds the enforcement-strength value`() {
        val event = boundaryEvent(metadata = boundaryMetadata())
        val authoritative = boundaryMetadata() + mapOf(RuntimeBoundaryProvenance.STRENGTH_KEY to "AUTHORITATIVE")
        val observed = boundaryMetadata() + mapOf(RuntimeBoundaryProvenance.STRENGTH_KEY to "OBSERVED")

        assertNotEquals(
            event.computeToolPermissionPayloadDigest(authoritative),
            event.computeToolPermissionPayloadDigest(observed),
            "changing only the enforcement-strength value must change the digest of a provenance-bearing record",
        )
    }

    @Test
    fun `identity and provenance coexist in the same record`() {
        val record = export(boundaryEvent(metadata = boundaryMetadata() + completeIdentity()))

        RuntimeEvidenceAttribution.metadataKeys.forEach { key ->
            assertTrue(record.metadata.containsKey(key), "identity key $key must survive alongside provenance")
        }
        assertEquals(BOUNDARY_ID, record.metadata[RuntimeBoundaryProvenance.ID_KEY])
        assertEquals(RUN_ID, record.workflowRunId)
    }

    private fun export(event: AuditEvent): RuntimeEvidenceRecord = exporter.export(listOf(event)).single()

    private val exporter = ToolPermissionRuntimeEvidenceExporter()

    /** A hand-built record, used only where the point is the validator's rule rather than the export path. */
    private fun recordWith(metadata: Map<String, String>): RuntimeEvidenceRecord =
        RuntimeEvidenceRecord(
            eventId = "evt-g2",
            eventType = "tool.permission",
            workflowRunId = RUN_ID,
            correlationId = "corr-g2",
            actor = "actor-g2",
            createdAt = Instant.parse("2026-10-13T00:00:00Z"),
            source = RuntimeEvidenceSource(component = "policy-engine", module = POLICY_VERSION),
            decision = RuntimeEvidenceDecision(kind = "DENY", reasonCode = "tool.permission.denied"),
            digests =
                RuntimeEvidenceDigests(
                    subjectDigest = "sha256:" + "a".repeat(64),
                    payloadDigest = "sha256:" + "b".repeat(64),
                ),
            metadata = metadata,
        )

    private fun familyMetadata() = mapOf("toolName" to TOOL_NAME, "enforcementPoint" to ENFORCEMENT_POINT)

    private fun boundaryMetadata() = familyMetadata() + mapOf(RuntimeBoundaryProvenance.ID_KEY to BOUNDARY_ID)

    private fun completeIdentity(): Map<String, String> =
        mapOf(
            "identity.workloadId" to "workload-g2",
            "identity.configurationId" to "configuration-g2",
            "identity.configurationVersion" to "v1",
            "identity.environmentId" to "environment-g2",
            "identity.deploymentId" to "deployment-g2",
        )

    private fun boundaryEvent(
        metadata: Map<String, String>,
        enforcementPoint: String = ENFORCEMENT_POINT,
    ): AuditEvent {
        val store = CapturingAuditStore()
        runBlocking {
            AuditEngine(store).emit(
                AuditEmission(
                    auditStreamId = RUN_ID,
                    workflowRunId = RUN_ID,
                    correlationId = "corr-g2",
                    actor = "actor-g2",
                    enforcementPoint = enforcementPoint,
                    decision = "DENY",
                    policyVersion = POLICY_VERSION,
                    workflowDigest = null,
                    reasonCode = "tool.permission.denied",
                    metadata = metadata,
                ),
            )
        }
        return store.last ?: error("the production audit engine recorded no event")
    }

    private class CapturingAuditStore(
        private val delegate: AuditStore = InMemoryAuditStore(),
    ) : AuditStore {
        var last: AuditEvent? = null
            private set

        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (latest: AuditEvent?) -> AuditEvent,
        ): AuditEvent = delegate.appendNext(auditStreamId, eventFactory).also { last = it }

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> = delegate.readStream(auditStreamId)

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? = delegate.latestEvent(auditStreamId)
    }

    private companion object {
        const val BOUNDARY_ID = "spring-ai-tool-callback"

        const val ENFORCEMENT_POINT = "BEFORE_TOOL_EXECUTION"

        const val RUN_ID = "run-g2-provenance"

        const val TOOL_NAME = "ledger.append"

        const val POLICY_VERSION = "policy-g2-v1"
    }
}
