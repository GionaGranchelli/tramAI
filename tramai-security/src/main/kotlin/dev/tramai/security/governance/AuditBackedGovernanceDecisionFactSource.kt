package dev.tramai.security.governance

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.VerificationResult

/**
 * How a workflow run is associated with the audit stream that carries its decisions.
 *
 * The store has no run index: `AuditStore` can read a stream, not find one by run. A run-scoped
 * read therefore needs the association supplied, and it must be supplied honestly — inventing one,
 * or assuming the default, is exactly how a foreign run's records end up in a projection.
 */
internal interface RunStreamAssociation {
    fun streamIdFor(workflowRunId: String): String?

    /**
     * The default association, and the limit that goes with it: this is what
     * [dev.tramai.security.audit.DefaultAuditStreamIdResolver] produces **when the context carries
     * a run id**. It is a resolution rule, not a property of the store. A deployment that
     * customises the resolver, or writes several runs into one correlated stream, must supply its
     * own association rather than rely on this one.
     */
    object DirectlyNamedByRunId : RunStreamAssociation {
        override fun streamIdFor(workflowRunId: String): String? = workflowRunId
    }
}

/**
 * 0.7.4c2 — the production decision-fact source, reading persisted audit decisions.
 *
 * The 0.7.4c1 projection may report only what its source can establish. This source therefore does
 * three things and refuses the rest: it reads the stream through the existing [AuditStore] SPI, it
 * attests the range it actually read, and it maps only events it can recognise as decisions.
 *
 * What it establishes:
 * - **source order** — `sourceSequence` is the store's own `sequenceNumber`, never a line number,
 *   an ordering index, a timestamp or an event-id sort.
 * - **attested range** — [DecisionFactRead.assertedRange] is populated only when the read captured
 *   positions `1..head` contiguously, verified the hash chain over exactly those events, and
 *   re-read the head afterwards to show no append raced the read. Any other shape reports `null`,
 *   which the projection reads as unknown completeness, not as complete.
 * - **attribution** — only events whose own `workflowRunId` is the requested run become facts.
 * - **integrity** — a broken hash chain throws rather than producing a projection: unverified
 *   records are not a partial answer, they are an unanswerable question.
 *
 * What it deliberately does not do: infer execution, completion or success from an `ALLOW`; carry
 * telemetry as a decision; read a record's metadata into the projection; or claim a registered
 * enforcement strength for a boundary an event merely names. A recorded decision stays an
 * authorization record — [GovernanceFactClassification] assigns its authority, not this source.
 */
internal class AuditBackedGovernanceDecisionFactSource(
    private val auditStore: AuditStore,
    private val streamAssociation: RunStreamAssociation = RunStreamAssociation.DirectlyNamedByRunId,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxEventsPerRead: Int = DEFAULT_MAX_EVENTS,
) : GovernanceDecisionFactSource {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxEventsPerRead >= pageSize) { "maxEventsPerRead must be at least pageSize" }
    }

    override suspend fun decisionsFor(workflowRunId: String): DecisionFactRead {
        require(workflowRunId.isNotBlank()) { "a decision read must name the run it is about" }
        // Head before: the event this read is about, not merely its position. An absent association
        // or an absent head is an empty read, not a complete one — nothing was observed, and nothing
        // is asserted.
        val streamId = streamAssociation.streamIdFor(workflowRunId)
        val headBefore = streamId?.let { auditStore.latestEvent(it) }
        if (streamId == null || headBefore == null) {
            return DecisionFactRead(records = emptyList(), assertedRange = null)
        }

        val events = readFromStart(streamId, headBefore.sequenceNumber)
        val verification = AuditChainVerifier.verify(events)
        if (!verification.isValid) {
            throw AuditDecisionIntegrityException(streamId, verification)
        }
        // Attribution fails closed rather than filtering. A run-named stream carrying another run's
        // event is not this run's stream, so dropping the event would leave a shorter, confident read
        // — possibly over an attested range — where the honest answer is that the stream cannot be
        // read as this run's evidence at all. Multi-run streams are therefore not supported by this
        // source; supporting them would be a separate mode with its own attribution and completeness
        // semantics, not a silent filter here.
        events.firstOrNull { it.workflowRunId != workflowRunId }?.let { foreign ->
            throw AuditDecisionAttributionException(streamId, workflowRunId, foreign)
        }

        val records = events.mapNotNull { recordFor(it, workflowRunId) }
        return DecisionFactRead(
            records = records,
            assertedRange = attestedRange(streamId, headBefore, auditStore.latestEvent(streamId), events),
        )
    }

    /**
     * The range this read can attest, or `null` when it can attest none.
     *
     * Positions alone are not enough: [AuditChainVerifier] proves only that the returned list is
     * internally consistent, so a source answering with a different — but equally coherent — chain
     * of the same length would otherwise earn an attested range. The range therefore requires the
     * events to carry the requested stream id, the last event's position **and** hash to be the
     * observed head's, the head to be unchanged either side of the read, and the positions to begin
     * at 1 and be contiguous up to that head.
     */
    private fun attestedRange(
        streamId: String,
        headBefore: AuditEvent,
        headAfter: AuditEvent?,
        events: List<AuditEvent>,
    ): LongRange? {
        val last = events.lastOrNull()
        val headUnchanged =
            headAfter != null &&
                headAfter.sequenceNumber == headBefore.sequenceNumber &&
                headAfter.eventHash == headBefore.eventHash
        val anchored =
            last != null &&
                events.first().sequenceNumber == FIRST_SOURCE_POSITION &&
                events.all { it.auditStreamId == streamId } &&
                last.sequenceNumber == headBefore.sequenceNumber &&
                last.eventHash == headBefore.eventHash &&
                events.size.toLong() == headBefore.sequenceNumber &&
                headUnchanged
        return if (anchored) FIRST_SOURCE_POSITION..headBefore.sequenceNumber else null
    }

    /** An audit stream is evidence, not telemetry. Nothing read here becomes an observation. */
    override suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation> = emptyList()

    /**
     * Reads positions 1..[head] contiguously, or as much of that prefix as the bound allows.
     *
     * A bounded read returns the prefix it did verify, with no attested range; it never returns a
     * range it did not read.
     */
    private suspend fun readFromStart(
        streamId: String,
        head: Long,
    ): List<AuditEvent> {
        // A new list per page rather than a mutable accumulator: the loop is bounded by
        // maxEventsPerRead, so the copies are bounded too, and nothing here is process-global state.
        var events: List<AuditEvent> = emptyList()
        var cursor: Long? = null
        while (events.size.toLong() < head && events.size < maxEventsPerRead) {
            val page =
                auditStore.readStreamPage(
                    auditStreamId = streamId,
                    afterSequenceNumber = cursor,
                    limit = pageSize,
                )
            if (page.isEmpty()) break
            events = events + page
            cursor = page.last().sequenceNumber
        }
        return events
    }

    /**
     * The decision record an audit event carries, or `null` when it carries none this source can
     * recognise.
     *
     * Recognition is an allowlist in both directions, which is stricter than the existing evidence
     * exporters on purpose: their policy family is defined by exclusion, so an event with an
     * unrecognised enforcement point and an `ALLOW` token passes those filters. Here an
     * unrecognised enforcement point is not a decision record at all, because classifying it as a
     * policy decision would be manufacturing a fact the boundary vocabulary does not contain.
     */
    private fun recordFor(
        event: AuditEvent,
        requestedRun: String,
    ): RecordedDecision? =
        decisionKindOf(event)?.let { kind ->
            RecordedDecision(
                workflowRunId = requestedRun,
                kind = kind,
                decision = event.decision,
                // The store guarantees no reason-code format, so the value is treated as untrusted input
                // and dropped unless it is a bounded safe token. Dropping it never invents one.
                reasonCode = event.reasonCode?.takeIf(SAFE_REASON_CODE::matches),
                // The boundary an event names, at the strength registration can prove for it here:
                // none. Audit data carries a declared label, not a registered strength.
                boundary = DeclaredBoundary(id = event.enforcementPoint, registeredStrength = null),
                sourceSequence = event.sequenceNumber,
            )
        }

    /**
     * The family an event belongs to, or `null` when this source cannot recognise a decision.
     *
     * Run attribution is not checked here: it is enforced over the whole read, failing closed, so a
     * record reaching this point is already known to belong to the requested run.
     */
    private fun decisionKindOf(event: AuditEvent): DecisionKind? =
        when {
            event.decision !in DECISION_TOKENS -> {
                null
            }

            // A tool enforcement event without the tool it concerns is not a tool permission, and
            // must not fall back to a policy decision instead.
            event.enforcementPoint in TOOL_ENFORCEMENT_POINTS -> {
                DecisionKind.TOOL_PERMISSION.takeIf { !event.metadata["toolName"].isNullOrBlank() }
            }

            event.enforcementPoint in POLICY_ENFORCEMENT_POINTS -> {
                DecisionKind.POLICY_DECISION
            }

            else -> {
                null
            }
        }
}

private const val DEFAULT_PAGE_SIZE = 256

private const val DEFAULT_MAX_EVENTS = 8192

private const val FIRST_SOURCE_POSITION = 1L

/** The decision tokens the emitters produce, as the exporters already define them. */
private val DECISION_TOKENS = setOf("ALLOW", "DENY", "REQUIRE_APPROVAL")

/** The enforcement points whose decisions belong to the tool permission family. */
private val TOOL_ENFORCEMENT_POINTS =
    setOf(
        "BEFORE_TOOL_EXPOSURE",
        "BEFORE_TOOL_EXECUTION",
        "BEFORE_TOOL_RESULT_REINJECTION",
    )

/**
 * The remaining enforcement points are the recognised non-tool vocabulary, taken from the engine's
 * own enum rather than from a string convention.
 */
private val POLICY_ENFORCEMENT_POINTS =
    EnforcementPoint.entries
        .map { it.name }
        .toSet()
        .minus(TOOL_ENFORCEMENT_POINTS)

/**
 * A bounded safe token for a persisted reason code. The audit store does not enforce this shape, so
 * a value that does not match is untrusted and is dropped rather than projected.
 */
private val SAFE_REASON_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

/** A stream whose chain does not verify cannot produce a trustworthy projection. */
internal class AuditDecisionIntegrityException(
    val auditStreamId: String,
    result: VerificationResult,
) : IllegalStateException(
        "audit stream '$auditStreamId' failed hash-chain verification: " +
            result.errors
                .firstOrNull()
                ?.message
                .orEmpty(),
    )

/**
 * A stream carrying another run's event cannot be read as this run's evidence, so the read is
 * refused rather than silently narrowed to the events this run happens to own.
 */
internal class AuditDecisionAttributionException(
    val auditStreamId: String,
    val requestedWorkflowRunId: String,
    offending: AuditEvent,
) : IllegalStateException(
        "audit stream '$auditStreamId' cannot be read as run '$requestedWorkflowRunId': " +
            "event at position ${offending.sequenceNumber} belongs to run '${offending.workflowRunId}'",
    )
