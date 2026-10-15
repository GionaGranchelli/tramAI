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
 * implementation of the production [AuditStore] SPI. Only the adversarial stores below are
 * wrappers, and each exists to inject one specific broken behaviour that a correct store cannot
 * produce on demand: a racing append, a tampered event, a page read answered from another stream,
 * and a head that is not the event that was read.
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
                metadata = emptyMap(),
            ),
        )

    private suspend fun policyAllow() {
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), PolicyDecision.Allow)
    }

    private suspend fun policyDeny() {
        val deny = PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied")
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), deny)
    }

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
            assertThat(read.coverage.verifiedRange).isEqualTo(1L..3L)
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
            assertThat(read.coverage.verifiedRange).isNull()
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
            assertThat(read.coverage.verifiedRange).isNull()
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
            assertThat(read.coverage.verifiedRange).isNull()
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
    fun `a foreign-run event in a run-named stream refuses the read instead of being dropped`() =
        runBlocking<Unit> {
            policyAllow()
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", workflowRunId = "run-2")
            policyAllow()

            val refusal =
                assertFailsWith<AuditDecisionAttributionException> {
                    AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)
                }

            // Refused, not narrowed. This run's own two decisions are present in the stream and the
            // read still fails: a stream that mixes runs cannot be read as this run's evidence, and
            // returning the two facts it happens to own would present the stream as a clean read.
            assertThat(store.readStream(runId)).hasSize(3)
            assertThat(refusal.auditStreamId).isEqualTo(runId)
            assertThat(refusal.requestedWorkflowRunId).isEqualTo(runId)
            assertThat(refusal.message).contains("run-2").contains("position 2")
        }

    @Test
    fun `a read answered from another stream is refused, not narrowed`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            // A second, genuinely coherent chain lives in "run-2", and this store answers *every*
            // read from it — pages and head alike. Positions, length and the internal hashes all
            // agree, so nothing about the chain's own consistency gives it away.
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", streamId = "run-2")
            raw("BEFORE_PROVIDER_INVOCATION", "ALLOW", streamId = "run-2")
            val crossedStreams =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    override suspend fun readStream(auditStreamId: String) = store.readStream(auditStreamId)

                    override suspend fun latestEvent(auditStreamId: String) = store.latestEvent("run-2")

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ) = store.readStreamPage("run-2", afterSequenceNumber, limit)
                }

            val refusal =
                assertFailsWith<AuditDecisionStreamMismatchException> {
                    AuditBackedGovernanceDecisionFactSource(crossedStreams).decisionsFor(runId)
                }

            // Refused, not narrowed to an unknown-completeness read: those events belong to another
            // stream and must not enter the projection as this run's decisions. An unknown range is
            // not enough, because the records themselves would still be facts.
            assertThat(refusal.requestedAuditStreamId).isEqualTo(runId)
            assertThat(refusal.message).contains("run-2")
        }

    @Test
    fun `a verified stream with non-decision events does not manufacture gaps`() =
        runBlocking<Unit> {
            policyAllow()
            raw("BEFORE_TOOL_RESULT_REINJECTION", "REDACTED")
            policyDeny()

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            // The two decisions are a strict subset of a three-event stream that verified in full, and
            // the raw range survives that: the stream was read and anchored. What the read cannot say
            // is that position 2 was irrelevant — "REDACTED" at a tool boundary is not a producer this
            // repository defines — so the decision claim stays unproven rather than becoming COMPLETE.
            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 3L)
            assertThat(read.coverage.verifiedRange).isEqualTo(1L..3L)
            assertThat(read.coverage.unclassifiedPositions).containsExactly(2L)
            assertThat(read.coverage.unrecordedDecisionPositions).isEmpty()
        }

    @Test
    fun `a mixed stream keeps its verified range and accounts for every event`() =
        runBlocking<Unit> {
            policyAllow()
            // The enforcement point and decision token AuditEngineDlpRedactionAuditEmitter records: a
            // producer this repository defines, and not a decision family's.
            raw("DLP_TOOL_RESULT", "REDACTED")
            policyDeny()

            val read = AuditBackedGovernanceDecisionFactSource(store).decisionsFor(runId)

            // The redaction is a real event of another family, so it is neither a hole nor an unknown,
            // the raw range covers the stream that was read, and the account shows every position was
            // classified rather than dropped.
            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 3L)
            assertThat(read.coverage.verifiedRange).isEqualTo(1L..3L)
            assertThat(read.coverage.accountedPositions).isEqualTo(3L)
            assertThat(read.coverage.unrecordedDecisionPositions).isEmpty()
            assertThat(read.coverage.unclassifiedPositions).isEmpty()
        }

    @Test
    fun `a read that stops short of the head attests no range`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            policyAllow()
            // A coherent chain that stops one event short of the head the store reports. The pages are
            // internally consistent, so the count against the head is what shows the read was partial —
            // this is the case the contiguity condition exists for, and it is the case the mutation run
            // left standing, so the fixture is here to pin which guard actually refuses it.
            val shortRead =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    override suspend fun readStream(auditStreamId: String) = store.readStream(auditStreamId)

                    override suspend fun latestEvent(auditStreamId: String) = store.latestEvent(auditStreamId)

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ) = store
                        .readStreamPage(auditStreamId, afterSequenceNumber, limit)
                        .filterNot { it.sequenceNumber == 3L }
                }

            val read = AuditBackedGovernanceDecisionFactSource(shortRead).decisionsFor(runId)

            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 2L)
            assertThat(read.coverage.verifiedRange).isNull()
        }

    @Test
    fun `a bounded read never exceeds maxEventsPerRead`() =
        runBlocking<Unit> {
            repeat(4) { policyAllow() }

            // A page size that does not divide the cap: two events per page, cap three. Asking for a
            // full page while only one slot remains would read four events and could then attest a
            // range wider than the configured cap.
            val capped = AuditBackedGovernanceDecisionFactSource(store, pageSize = 2, maxEventsPerRead = 3)
            val read = capped.decisionsFor(runId)

            assertThat(read.records.map { it.sourceSequence }).containsExactly(1L, 2L, 3L)
            assertThat(read.coverage.verifiedRange).isNull()
        }

    @Test
    fun `a head that is not the event last read does not attest the range`() =
        runBlocking<Unit> {
            policyAllow()
            policyAllow()
            // Same stream, same positions, same length: the only disagreement is that the head the
            // store reports is not the event that was read, which position alone cannot detect.
            val mismatchedHead =
                object : AuditStore {
                    override suspend fun appendNext(
                        auditStreamId: String,
                        eventFactory: (latest: AuditEvent?) -> AuditEvent,
                    ) = store.appendNext(auditStreamId, eventFactory)

                    override suspend fun readStream(auditStreamId: String) = store.readStream(auditStreamId)

                    override suspend fun latestEvent(auditStreamId: String) =
                        store.latestEvent(auditStreamId)?.copy(eventHash = "not-the-event-that-was-read")

                    override suspend fun readStreamPage(
                        auditStreamId: String,
                        afterSequenceNumber: Long?,
                        limit: Int,
                    ) = store.readStreamPage(auditStreamId, afterSequenceNumber, limit)
                }

            val read = AuditBackedGovernanceDecisionFactSource(mismatchedHead).decisionsFor(runId)

            assertThat(read.records).hasSize(2)
            assertThat(read.coverage.verifiedRange).isNull()
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
            // The event recorded a decision outcome at a boundary this source does not recognise as a
            // decision family, so it produced no record. That is a hole in the record set and is
            // reported as one, while the raw stream it came from is still covered and attested.
            assertThat(read.coverage.verifiedRange).isEqualTo(1L..1L)
            assertThat(read.coverage.unrecordedDecisionPositions).containsExactly(1L)
            assertThat(read.coverage.unclassifiedPositions).isEmpty()
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
            assertThat(read.coverage.verifiedRange).isNull()
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
            assertThat(read.coverage.verifiedRange).isNull()
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
