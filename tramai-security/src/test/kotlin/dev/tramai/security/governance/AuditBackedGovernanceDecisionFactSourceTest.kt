package dev.tramai.security.governance

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * 0.7.4c2 — discriminated behaviour of the audit-backed decision fact source.
 *
 * The store, the append path and the emitter are the real ones: decisions are persisted with
 * [AuditEngine] (the same writer every emitter uses) through [InMemoryAuditStore], which is a real
 * implementation of the production [AuditStore] SPI. Only the two adversarial stores below are
 * wrappers, and each exists to inject one specific broken behaviour — a racing append and a
 * tampered event — that a correct store cannot produce on demand.
 */
class AuditBackedGovernanceDecisionFactSourceTest {
    private val runId = "run-1"
    private val correlationId = "corr-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
    private val store = InMemoryAuditStore()
    private val engine = AuditEngine(store, clock = clock)
    private val emitter = AuditEnginePolicyDecisionAuditEmitter(engine)

    private fun context(
        enforcementPoint: EnforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
        toolName: String? = null,
    ) = PolicyContext(
        enforcementPoint = enforcementPoint,
        workflowId = "wf-1",
        workflowRunId = runId,
        correlationId = correlationId,
        actorId = "system.anonymous",
        policyVersion = "v1",
        toolName = toolName,
    )

    /** A raw emission through the real append path, for shapes the emitters never produce. */
    private suspend fun raw(
        enforcementPoint: String,
        decision: String,
        reasonCode: String? = null,
        metadata: Map<String, String> = emptyMap(),
        workflowRunId: String? = runId,
        streamId: String = runId,
    ): AuditEvent =
        engine.emit(
            AuditEmission(
                auditStreamId = streamId,
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                actor = null,
                enforcementPoint = enforcementPoint,
                decision = decision,
                policyVersion = "v1",
                workflowDigest = null,
                reasonCode = reasonCode,
                metadata = metadata,
            ),
        )

    private suspend fun policyAllow() = emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), PolicyDecision.Allow)

    // ---------------------------------------------------------------- range attestation

    @Test
    fun `a contiguous verified read attests the exact source range it read`() =
        runBlocking<Unit> {
            emitter.emit(
                EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                context(),
                PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"),
            )
            emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), PolicyDecision.Allow)
            emitter.emit(
                EnforcementPoint.BEFORE_TOOL_EXECUTION,
                context(toolName = "pricing_lookup"),
                PolicyDecision.Allow,
            )

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 2L, 3L)
            assertThat(read.records.map { it.kind })
                .containsExactly(
                    DecisionKind.POLICY_DECISION,
                    DecisionKind.POLICY_DECISION,
                    DecisionKind.TOOL_PERMISSION,
                )
            assertThat(read.records.map { it.decision }).containsExactly("DENY", "ALLOW", "ALLOW")
            assertThat(read.assertedRange).isEqualTo(1L..3L)
        }

    @Test
    fun `a read that a concurrent append raced reports no attested range`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            val racing =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    private var raced = false

                    override suspend fun readStream(auditStreamId: String) = store.readStream(auditStreamId)

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ): List<AuditEvent> {
                        if (!raced) {
                            raced = true
                            raw("BEFORE_RESPONSE_RETURN", "ALLOW")
                        }
                        return store.readStreamPage(auditStreamId, afterSequenceNumber, limit)
                    }

                    override suspend fun latestEvent(auditStreamId: String) = store.latestEvent(auditStreamId)
                }

            val read = AuditBackedGovernanceDecisionFactSource(racing).decisionsFor(runId)

            assertThat(read.records).isNotEmpty()
            assertThat(read.assertedRange).isNull()
        }

    @Test
    fun `an append that lands after the pages were read is not miscounted as complete`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            // This is the window the head re-read exists for: the pages were read and cover 1..2,
            // so a size-only check would attest them, but the stream has already moved on to 3.
            val lateAppend =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    override suspend fun readStream(auditStreamId: String) = store.readStream(auditStreamId)

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ) = store.readStreamPage(auditStreamId, afterSequenceNumber, limit)

                    private var headReads = 0

                    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
                        headReads++
                        if (headReads == 2) {
                            raw("BEFORE_RESPONSE_RETURN", "ALLOW")
                        }
                        return store.latestEvent(auditStreamId)
                    }
                }

            val read = AuditBackedGovernanceDecisionFactSource(lateAppend).decisionsFor(runId)

            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 2L)
            assertThat(read.assertedRange).isNull()
        }

    @Test
    fun `a bounded read returns the prefix it verified and claims no range`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            policyAllow()

            val read =
                AuditBackedGovernanceDecisionFactSource(store, pageSize = 2, maxEventsPerRead = 2)
                    .decisionsFor(runId)

            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 2L)
            assertThat(read.assertedRange).isNull()
        }

    @Test
    fun `a broken hash chain refuses the read instead of projecting unverified records`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            val tampered =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    override suspend fun readStream(auditStreamId: String) = tamper(store.readStream(auditStreamId))

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ) = tamper(store.readStreamPage(auditStreamId, afterSequenceNumber, limit))

                    override suspend fun latestEvent(auditStreamId: String) = store.latestEvent(auditStreamId)

                    /** Rewrites a decision without rewriting the hash that covered it. */
                    private fun tamper(events: List<AuditEvent>) =
                        events.map { if (it.sequenceNumber == 1L) it.copy(decision = "DENY") else it }
                }

            val refusal =
                assertFailsWith<AuditDecisionIntegrityException> {
                    AuditBackedGovernanceDecisionFactSource(tampered).decisionsFor(runId)
                }

            assertThat(refusal.auditStreamId).isEqualTo(runId)
            assertThat(refusal.message).contains("failed hash-chain verification")
        }

    // ---------------------------------------------------------------- attribution and family

    @Test
    fun `a record attributed to another run is not projected`() =
        runBlocking<Unit> {
            policyAllow()
            raw("BEFORE_PROVIDER_INVOCATION", "DENY", workflowRunId = "run-2")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records.map { it.workflowRunId }).containsExactly(runId)
            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L)
        }

    @Test
    fun `an unrecognised enforcement point is not reclassified as a policy decision`() =
        runBlocking<Unit> {
            raw("UNKNOWN_BOUNDARY", "ALLOW")
            val events = store.readStream(runId)

            // The existing exporter's policy family is defined by exclusion, so it exports this
            // event; the governance decision source must not, because the boundary vocabulary it
            // recognises does not contain that enforcement point.
            assertThat(PolicyDecisionRuntimeEvidenceExporter().export(events)).hasSize(1)

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records).isEmpty()
            assertThat(read.assertedRange).isEqualTo(1L..1L)
        }

    @Test
    fun `a tool enforcement event without a tool name becomes no record at all`() =
        runBlocking<Unit> {
            raw("BEFORE_TOOL_EXECUTION", "ALLOW")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records).isEmpty()
        }

    @Test
    fun `an unrecognised decision token is not a decision`() =
        runBlocking<Unit> {
            raw("BEFORE_PROVIDER_INVOCATION", "MAYBE")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records).isEmpty()
        }

    @Test
    fun `a boundary is reported at the strength audit data can prove, which is none`() =
        runBlocking<Unit> {
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", workflowRunId = runId)

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(
                read.records
                    .single()
                    .boundary
                    ?.id,
            ).isEqualTo("BEFORE_PROVIDER_INVOCATION")
            assertThat(
                read.records
                    .single()
                    .boundary
                    ?.registeredStrength,
            ).isNull()
        }

    // ---------------------------------------------------------------- untrusted persisted input

    @Test
    fun `an unsafe reason code is dropped rather than projected`() =
        runBlocking<Unit> {
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", reasonCode = "key: sk-live/secret value")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records.single().reasonCode).isNull()
        }

    @Test
    fun `a safe reason code is preserved`() =
        runBlocking<Unit> {
            raw("BEFORE_PROVIDER_INVOCATION", "DENY", reasonCode = "policy_denied")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records.single().reasonCode).isEqualTo("policy_denied")
        }

    // ---------------------------------------------------------------- empty and unattributable reads

    @Test
    fun `an empty stream is an empty read and not an attested one`() =
        runBlocking<Unit> {
            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records).isEmpty()
            assertThat(read.assertedRange).isNull()
        }

    @Test
    fun `a run with no defensible stream association is an empty read`() =
        runBlocking<Unit> {
            policyAllow()
            val noAssociation =
                object : RunStreamAssociation {
                    override fun streamIdFor(workflowRunId: String): String? = null
                }

            val read = AuditBackedGovernanceDecisionFactSource(store, noAssociation).decisionsFor(runId)

            assertThat(read.records).isEmpty()
            assertThat(read.assertedRange).isNull()
        }

    @Test
    fun `a read of one run does not read another run's stream`() =
        runBlocking<Unit> {
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", workflowRunId = "run-2", streamId = "run-2")

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            assertThat(read.records).isEmpty()
        }

    @Test
    fun `a decision read must name the run it is about`() {
        runBlocking<Unit> {
            assertFailsWith<IllegalArgumentException> {
                AuditBackedGovernanceDecisionFactSource(store).decisionsFor("   ")
            }
        }
    }

    @Test
    fun `an audit stream is not a telemetry source`() =
        runBlocking<Unit> {
            policyAllow()

            val observations = AuditBackedGovernanceDecisionFactSource(store).telemetryFor(runId)

            assertThat(observations).isEmpty()
        }
}
